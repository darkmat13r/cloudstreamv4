package com.lagradost.cloudstream4.media.download

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.takeFrom
import kotlin.coroutines.cancellation.CancellationException

/**
 * Turns a host name into the addresses a connection would use, as raw bytes: 4 for IPv4, 16 for IPv6.
 * Throws when the name does not resolve.
 */
interface HostResolver {
    suspend fun resolve(host: String): List<ByteArray>
}

/**
 * The platform resolver: `InetAddress.getAllByName` on Android and the JVM, `getaddrinfo` on iOS,
 * both off the calling thread.
 */
expect object SystemHostResolver : HostResolver

/** What [UrlGuard.check] decided. */
internal sealed interface UrlCheck {
    /** The URL may be fetched. [url] is ktor's reading of it, the one the connection will use. */
    class Allowed(val url: Url) : UrlCheck

    /** Wrong scheme, userinfo, an unparseable host, or an address that is not public: `AddressRefused`. */
    data object Refused : UrlCheck

    /** The name did not resolve, or resolved to nothing: `NetworkLost`. */
    data object ResolveFailed : UrlCheck
}

/**
 * Decides whether a download may connect to a URL, before it does.
 *
 * It refuses, without resolving anything: any scheme but http and https; userinfo; control characters,
 * whitespace or a backslash in the authority; a host the guard and ktor read differently; an empty host or
 * an empty label; `localhost`, `*.localhost`, `*.local`; every spelling of a literal IPv4 address that
 * `inet_aton` accepts (`127.1`, `0x7f.0.0.1`, `2130706433`, `017700000001`), and any host whose last
 * label looks numeric but does not parse as one; and a literal IPv6 address in a non-public class. A
 * name is resolved through [resolver] and refused when **any** address is not public. The classes are the
 * ones in `SandboxPolicy.isPublicAddress`, restated over bytes: loopback, unspecified, private ranges,
 * link-local, CGNAT `100.64/10`, benchmark `198.18/15`, `192.0.0.0/24`, multicast and `240/4` for IPv4;
 * `::`, `::1`, IPv4-mapped and IPv4-compatible addresses judged as the IPv4 inside, `fc00::/7`, `fe80::/10`,
 * `fec0::/10`, `ff00::/8`, `2002::/16` and `64:ff9b::/32` for IPv6.
 *
 * [allowPrivateAddresses] skips the host-name and address checks (and so the resolution), never the
 * scheme, userinfo or syntax checks.
 *
 * Known residue: DNS rebinding. A name that resolved to a public address here may resolve to a private
 * one when the connection is made. The real fix is a `Dns` in the transport (OkHttp), which sees the
 * address it connects to.
 *
 * [check] never throws on input; a cancelled coroutine still gets its [CancellationException].
 */
internal class UrlGuard(
    private val resolver: HostResolver,
    private val allowPrivateAddresses: Boolean,
) {
    suspend fun check(url: String): UrlCheck {
        val parsed = parseHttpUrl(url) ?: return UrlCheck.Refused
        val host = normalizeHost(parsed.hostText)
        if (host.isEmpty()) return UrlCheck.Refused
        if (allowPrivateAddresses) return UrlCheck.Allowed(parsed.url)
        if (':' !in host && host.split('.').any { it.isEmpty() }) return UrlCheck.Refused
        if (isPrivateHostName(host)) return UrlCheck.Refused

        when (val literal = parseIpLiteral(host)) {
            is IpLiteral.Address ->
                return if (isPublicAddress(literal.bytes)) UrlCheck.Allowed(parsed.url) else UrlCheck.Refused
            IpLiteral.Invalid -> return UrlCheck.Refused
            IpLiteral.NotAnAddress -> Unit
        }

        val addresses = try {
            resolver.resolve(host)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return UrlCheck.ResolveFailed
        }
        if (addresses.isEmpty()) return UrlCheck.ResolveFailed
        return if (addresses.all(::isPublicAddress)) UrlCheck.Allowed(parsed.url) else UrlCheck.Refused
    }
}

/**
 * [location] resolved against [base] the way a browser does, or `null` when it cannot be read.
 * The result is not trusted: run it through [UrlGuard.check].
 */
internal fun resolveRedirect(base: Url, location: String): Url? {
    if (location.length > MAX_URL_CHARS || location.any { it.isControlOrSpace() }) return null
    return runCatching {
        // ktor keeps the base query for a relative reference; a browser does not.
        val builder = URLBuilder(base)
        builder.parameters.clear()
        builder.fragment = ""
        builder.takeFrom(location).build()
    }.getOrNull()
}

internal const val MAX_URL_CHARS = 16 * 1024

private class ParsedUrl(val url: Url, val hostText: String)

private fun Char.isControlOrSpace(): Boolean = code <= 0x20 || code in 0x7f..0xa0

/**
 * Own reading of the authority, compared with ktor's. Two parsers that disagree about the host are
 * exactly where a bypass lives, so any disagreement is a refusal.
 */
private fun parseHttpUrl(raw: String): ParsedUrl? {
    if (raw.isEmpty() || raw.length > MAX_URL_CHARS) return null
    if (raw.any { it.isControlOrSpace() }) return null
    val schemeEnd = raw.indexOf("://")
    if (schemeEnd < 0) return null
    val scheme = raw.substring(0, schemeEnd).lowercase()
    if (scheme != "http" && scheme != "https") return null
    val afterScheme = raw.substring(schemeEnd + 3)
    val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
    if (authority.isEmpty() || '@' in authority || '\\' in authority) return null

    val ownHost: String
    if (authority.startsWith("[")) {
        val close = authority.indexOf(']')
        if (close < 0) return null
        ownHost = authority.substring(1, close)
        if (':' !in ownHost) return null
        if (!isValidPort(authority.substring(close + 1))) return null
    } else {
        val colon = authority.lastIndexOf(':')
        if (colon < 0) {
            ownHost = authority
        } else {
            ownHost = authority.substring(0, colon)
            if (!isValidPort(authority.substring(colon))) return null
        }
        if (':' in ownHost) return null
    }

    val url = runCatching { URLBuilder(raw).build() }.getOrNull() ?: return null
    if (url.user != null || url.password != null) return null
    val protocol = url.protocol.name.lowercase()
    if (protocol != "http" && protocol != "https") return null
    if (normalizeHost(url.host) != normalizeHost(ownHost)) return null
    return ParsedUrl(url, ownHost)
}

/** `""` is no port at all, otherwise `:` and 0 to 5 digits, 1..65535 when there are any. */
private fun isValidPort(text: String): Boolean {
    if (text.isEmpty()) return true
    if (text[0] != ':') return false
    val digits = text.substring(1)
    if (digits.isEmpty()) return true
    if (digits.length > 5 || !digits.all { it in '0'..'9' }) return false
    return digits.toInt() in 1..65535
}

private fun normalizeHost(host: String): String = host.trim('[', ']').trimEnd('.').lowercase()

private fun isPrivateHostName(host: String): Boolean =
    host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")

private sealed interface IpLiteral {
    class Address(val bytes: ByteArray) : IpLiteral

    /** Looks like an address and is not a valid one. */
    data object Invalid : IpLiteral

    /** An ordinary name. */
    data object NotAnAddress : IpLiteral
}

private fun parseIpLiteral(host: String): IpLiteral {
    if (':' in host) return parseIpv6(host)?.let { IpLiteral.Address(it) } ?: IpLiteral.Invalid
    val lastLabel = host.substringAfterLast('.')
    val looksNumeric = lastLabel.isNotEmpty() && (
        lastLabel.all { it in '0'..'9' } ||
            ((lastLabel.startsWith("0x") || lastLabel.startsWith("0X")) && lastLabel.drop(2).all(::isHexDigit))
        )
    if (!looksNumeric) return IpLiteral.NotAnAddress
    return parseIpv4Lenient(host)?.let { IpLiteral.Address(it) } ?: IpLiteral.Invalid
}

private fun isHexDigit(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

private const val NUMBER_LIMIT = 0xFFFFFFFFL

/** One `inet_aton` number: decimal, `0x` hex or leading-zero octal. `null` on a bad digit or beyond 32 bits. */
private fun parseIpv4Number(text: String): Long? {
    if (text.isEmpty()) return null
    var radix = 10
    var digits = text
    if (text.length >= 2 && text[0] == '0' && (text[1] == 'x' || text[1] == 'X')) {
        radix = 16
        digits = text.substring(2)
    } else if (text.length >= 2 && text[0] == '0') {
        radix = 8
        digits = text.substring(1)
    }
    if (digits.isEmpty()) return if (radix == 16) 0L else null
    var value = 0L
    for (c in digits) {
        val d = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> return null
        }
        if (d >= radix) return null
        value = value * radix + d
        if (value > NUMBER_LIMIT) return null
    }
    return value
}

/** `a`, `a.b`, `a.b.c` or `a.b.c.d`, the last part filling the remaining bytes. `null` when invalid. */
private fun parseIpv4Lenient(host: String): ByteArray? {
    val parts = host.split('.')
    if (parts.size !in 1..4) return null
    val numbers = parts.map { parseIpv4Number(it) ?: return null }
    for (i in 0 until numbers.size - 1) if (numbers[i] > 255L) return null
    val last = numbers.last()
    val remainingBytes = 5 - numbers.size
    val limit = if (remainingBytes >= 4) NUMBER_LIMIT else (1L shl (8 * remainingBytes)) - 1
    if (last > limit) return null
    val out = ByteArray(4)
    for (i in 0 until numbers.size - 1) out[i] = numbers[i].toInt().toByte()
    for (i in 0 until remainingBytes) out[3 - i] = ((last shr (8 * i)) and 0xFF).toInt().toByte()
    return out
}

/** Strict dotted decimal, 4 parts of 1..3 digits each 0..255, as embedded in an IPv6 tail. */
private fun parseStrictIpv4(text: String): ByteArray? {
    val parts = text.split('.')
    if (parts.size != 4) return null
    val out = ByteArray(4)
    for ((i, part) in parts.withIndex()) {
        if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
        val v = part.toInt()
        if (v > 255) return null
        out[i] = v.toByte()
    }
    return out
}

/** A literal IPv6 address without brackets or zone, `null` when it is not one. */
private fun parseIpv6(text: String): ByteArray? {
    if (text.isEmpty() || '%' in text) return null
    val gap = text.indexOf("::")
    if (gap >= 0 && text.indexOf("::", gap + 1) >= 0) return null
    val left = if (gap >= 0) text.substring(0, gap) else text
    val right = if (gap >= 0) text.substring(gap + 2) else ""
    val leftGroups = parseGroups(left, allowV4Tail = gap < 0) ?: return null
    val rightGroups = if (gap >= 0) parseGroups(right, allowV4Tail = true) ?: return null else emptyList()
    val total = leftGroups.size + rightGroups.size
    if (gap < 0 && total != 8) return null
    if (gap >= 0 && total > 7) return null
    val groups = IntArray(8)
    leftGroups.forEachIndexed { i, g -> groups[i] = g }
    rightGroups.forEachIndexed { i, g -> groups[8 - rightGroups.size + i] = g }
    val out = ByteArray(16)
    for (i in 0 until 8) {
        out[2 * i] = (groups[i] ushr 8).toByte()
        out[2 * i + 1] = (groups[i] and 0xFF).toByte()
    }
    return out
}

/** Colon-separated groups as 16-bit values; a dotted-quad last group counts as two. `null` when malformed. */
private fun parseGroups(text: String, allowV4Tail: Boolean): List<Int>? {
    if (text.isEmpty()) return emptyList()
    val pieces = text.split(':')
    val out = ArrayList<Int>(8)
    for ((i, piece) in pieces.withIndex()) {
        if (piece.contains('.')) {
            if (!allowV4Tail || i != pieces.size - 1) return null
            val v4 = parseStrictIpv4(piece) ?: return null
            out += ((v4[0].toInt() and 0xFF) shl 8) or (v4[1].toInt() and 0xFF)
            out += ((v4[2].toInt() and 0xFF) shl 8) or (v4[3].toInt() and 0xFF)
        } else {
            if (piece.isEmpty() || piece.length > 4 || !piece.all(::isHexDigit)) return null
            out += piece.toInt(16)
        }
    }
    return out
}

/**
 * Whether a connection may go to [address] (4 or 16 bytes). Any other length is not public.
 * `SandboxPolicy.isPublicAddress`, restated over bytes so this module does not depend on it.
 */
internal fun isPublicAddress(address: ByteArray): Boolean = when (address.size) {
    4 -> isPublicIpv4(address)
    16 -> isPublicIpv6(address)
    else -> false
}

private fun ByteArray.u(i: Int): Int = this[i].toInt() and 0xFF

private fun isPublicIpv4(b: ByteArray): Boolean {
    val first = b.u(0)
    val second = b.u(1)
    return when {
        first == 0 -> false // 0.0.0.0/8, includes the unspecified address
        first == 10 -> false
        first == 127 -> false
        first == 100 && second in 64..127 -> false // carrier-grade NAT
        first == 169 && second == 254 -> false
        first == 172 && second in 16..31 -> false
        first == 192 && second == 168 -> false
        first == 192 && second == 0 && b.u(2) == 0 -> false // IETF protocol assignments
        first == 198 && second in 18..19 -> false // benchmarking
        first >= 224 -> false // multicast and reserved, including broadcast
        else -> true
    }
}

private fun isPublicIpv6(b: ByteArray): Boolean {
    val first = b.u(0)
    val second = b.u(1)
    val leadingZeros = (0 until 10).all { b.u(it) == 0 }
    if (leadingZeros && b.u(10) == 0xFF && b.u(11) == 0xFF) return isPublicIpv4(b.copyOfRange(12, 16)) // IPv4-mapped
    if ((0 until 12).all { b.u(it) == 0 }) return false // ::, ::1 and the deprecated IPv4-compatible form
    return when {
        first == 0xFF -> false // multicast
        first == 0xFE && (second and 0xC0) == 0x80 -> false // link-local fe80::/10
        first == 0xFE && (second and 0xC0) == 0xC0 -> false // site-local fec0::/10
        (first and 0xFE) == 0xFC -> false // unique local fc00::/7
        first == 0x20 && second == 0x02 -> false // 6to4, an IPv4 address inside
        first == 0x00 && second == 0x64 && b.u(2) == 0xFF && b.u(3) == 0x9B -> false // NAT64
        else -> true
    }
}

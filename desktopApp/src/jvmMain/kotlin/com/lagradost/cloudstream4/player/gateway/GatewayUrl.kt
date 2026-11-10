package com.lagradost.cloudstream4.player.gateway

import com.lagradost.cloudstream4.feature.extensions.sandbox.SandboxPolicy
import java.net.URI
import java.security.MessageDigest

/**
 * What a url is for, carried in its gateway form (`KMP-113`). It decides whether `Range` goes upstream: a playlist is
 * always fetched whole, because it is rewritten, and a partial body is never rewritten.
 */
enum class GatewayRole(val code: String) {
    /** A playlist: the uri after `EXT-X-STREAM-INF`, and `URI` on `EXT-X-MEDIA` and `EXT-X-I-FRAME-STREAM-INF`. */
    Playlist("p"),

    /** A segment, a key, a map, a part or a data file: fetched as asked, ranges included. */
    Media("s"),

    /** The stream's first url, whose role nobody knows yet. Fetched as a playlist would be. */
    Unknown("u");

    internal companion object {
        fun of(code: String): GatewayRole? = entries.firstOrNull { it.code == code }
    }
}

/** An upstream url and its role, read back from a gateway url. */
data class GatewayTarget(val upstream: String, val role: GatewayRole)

/**
 * The gateway's url scheme: `http://127.0.0.1:<port>/<token>/<role>/<scheme>/<authority>/<path>[?<query>]`, as
 * `docs/plans/media-gateway.md` §1 sets out.
 *
 * Encoding keeps the path and query byte for byte, so a signed url's signature survives. Decoding splits the raw
 * request target on `/` before anything is decoded, so an encoded slash cannot move a boundary; removes dot segments
 * (RFC 3986 §5.2.4, with `%2e` read as `.`) and refuses any that climb above the root; and refuses user info, a zone
 * id, an empty host, control characters and anything past [SandboxPolicy.MAX_URL_CHARS].
 */
class GatewayUrl(private val port: Int, private val token: String) {

    init {
        require(TOKEN.matches(token)) { "token" }
    }

    private val prefix = "http://127.0.0.1:$port/$token/"

    /** [url], absolute http(s), as the gateway url for [role]; null for anything this scheme refuses. */
    fun encode(url: String, role: GatewayRole): String? {
        if (url.length > SandboxPolicy.MAX_URL_CHARS || hasControl(url)) return null
        val parsed = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase()?.takeIf { it in SCHEMES } ?: return null
        if (parsed.rawUserInfo != null) return null
        val authority = authorityOf(parsed, scheme) ?: return null
        val path = removeDotSegments(parsed.rawPath.orEmpty().ifEmpty { "/" }) ?: return null
        val query = parsed.rawQuery?.let { "?$it" }.orEmpty()
        val encoded = "$prefix${role.code}/$scheme/$authority$path$query"
        return encoded.takeIf { it.length <= SandboxPolicy.MAX_URL_CHARS }
    }

    /**
     * The upstream url a request target names, or null: a missing or wrong token, an unknown role or scheme, a bad
     * authority, a dot segment above the root, a control character, or too long.
     */
    fun decode(rawTarget: String): GatewayTarget? {
        if (rawTarget.length > SandboxPolicy.MAX_URL_CHARS || hasControl(rawTarget)) return null
        if (!rawTarget.startsWith("/")) return null
        val parts = rawTarget.split('/', limit = 6)
        if (parts.size < 5) return null
        val (_, tokenPart, rolePart, schemePart, authorityPart) = parts
        if (!sameToken(tokenPart)) return null
        val role = GatewayRole.of(rolePart) ?: return null
        if (schemePart !in SCHEMES) return null
        if (!AUTHORITY.matches(authorityPart) || '%' in authorityPart) return null
        val rest = parts.getOrNull(5)?.let { "/$it" } ?: "/"
        val rawPath = rest.substringBefore('?')
        val query = rest.substringAfter('?', missingDelimiterValue = "").let { if ('?' in rest) "?$it" else "" }
        val path = removeDotSegments(rawPath) ?: return null
        val upstream = "$schemePart://$authorityPart$path$query"
        // The same rules as encoding, read back: what goes upstream is a url this scheme would itself have made.
        val again = runCatching { URI(upstream) }.getOrNull() ?: return null
        if (again.rawUserInfo != null || authorityOf(again, schemePart) != authorityPart) return null
        return GatewayTarget(upstream, role)
    }

    /** True for a value that names this gateway or holds its token, which must never go upstream (§4). */
    fun namesGateway(value: String): Boolean =
        token in value || "127.0.0.1:$port" in value || "[::1]:$port" in value || "localhost:$port" in value

    private fun sameToken(candidate: String): Boolean =
        MessageDigest.isEqual(candidate.toByteArray(), token.toByteArray())

    companion object {
        private val SCHEMES = setOf("http", "https")

        /** 128 random bits, base64url without padding. */
        val TOKEN = Regex("[A-Za-z0-9_-]{22}")

        /**
         * A host (letters, digits, dots and hyphens, already lowercase), an IPv4 literal, or an IPv6 literal in
         * brackets, then an optional port. No zone id: `%` is refused separately, which covers `%25`.
         */
        private val AUTHORITY = Regex("(?:[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?|\\[[0-9a-f:.]{2,45}])(?::[0-9]{1,5})?")

        private fun authorityOf(uri: URI, scheme: String): String? {
            val host = uri.host?.lowercase() ?: return null
            if (host.isEmpty() || '%' in host) return null
            val bracketed = if (':' in host && !host.startsWith("[")) "[$host]" else host
            val defaultPort = if (scheme == "https") 443 else 80
            val port = uri.port
            if (port == 0 || port > 65_535) return null
            val authority = if (port == -1 || port == defaultPort) bracketed else "$bracketed:$port"
            return authority.takeIf { AUTHORITY.matches(it) }
        }

        /** A control character, raw or percent-encoded below `%20` or as `%7F`. */
        private fun hasControl(target: String): Boolean =
            target.any { it.isISOControl() } || ENCODED_CONTROL.containsMatchIn(target)

        private val ENCODED_CONTROL = Regex("%(?:[01][0-9A-Fa-f]|7[Ff])")

        /**
         * RFC 3986 §5.2.4 on a raw path, with `%2e` read as `.` only to recognise a dot segment. Null when a `..`
         * would climb above the root: refused, never clamped.
         */
        internal fun removeDotSegments(rawPath: String): String? {
            if (!rawPath.startsWith("/")) return null
            val input = rawPath.substring(1).split('/')
            val output = ArrayDeque<String>()
            input.forEachIndexed { index, segment ->
                val last = index == input.lastIndex
                when (segment.replace("%2e", ".", ignoreCase = true)) {
                    "." -> if (last) output.addLast("")
                    ".." -> {
                        if (output.isEmpty()) return null
                        output.removeLast()
                        if (last) output.addLast("")
                    }
                    else -> output.addLast(segment)
                }
            }
            return "/" + output.joinToString("/")
        }
    }
}

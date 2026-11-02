package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.io.File
import java.net.InetAddress
import java.net.URI

/** Decisions about untrusted names and addresses that the app makes outside any sandbox. */
object SandboxPolicy {

    private val SAFE_EXTENSION_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    /**
     * The private directory for the extension called [name], or null when [name] is not safe.
     *
     * Refused rather than cleaned, because cleaning can merge two names into one shared directory.
     * The name comes from a file in a scanned directory: `...jar` has the name `..`, which would make
     * the extensions root its writable directory, and with it the `.store` of scanned jars.
     * The canonical check also catches a symlink planted at the target.
     */
    fun dataDirectory(sandboxes: File, name: String): File? {
        if (!name.matches(SAFE_EXTENSION_NAME) || name.contains("..")) return null
        val directory = File(sandboxes, name)
        val root = runCatching { sandboxes.canonicalFile }.getOrNull() ?: return null
        return directory.takeIf { runCatching { it.canonicalFile.parentFile }.getOrNull() == root }
    }

    /**
     * Whether the app may connect to [address] on a plugin's behalf.
     *
     * JDK checks where they are right, and bytes where they are not: `isSiteLocalAddress` knows only
     * the deprecated `fec0::/10` on IPv6, and nothing knows carrier-grade NAT. An IPv4-mapped IPv6
     * address needs no case of its own, because `InetAddress` already returns it as IPv4.
     */
    fun isPublicAddress(address: InetAddress): Boolean {
        if (
            address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
            address.isAnyLocalAddress || address.isMulticastAddress
        ) {
            return false
        }
        val bytes = address.address
        val first = bytes[0].toInt() and BYTE_MASK
        val second = bytes[1].toInt() and BYTE_MASK
        return when (bytes.size) {
            IPV4_BYTES -> first != 0 && !(first == CGNAT_FIRST && second in CGNAT_SECOND)
            else -> first and ULA_MASK != ULA_PREFIX
        }
    }

    /**
     * Whether libVLC may be given [url]: http(s), to a host whose every address is public. Blocking: it
     * resolves the host. libVLC cannot be checked at connection time, so this is its only gate.
     *
     * It checks the exact string that will be played, never a truncated copy: checking only the first
     * 2048 characters of `http://public:000…80@127.0.0.1/` read the host as `public`, and VLC connected
     * to loopback. Userinfo is refused outright because it is where URL parsers disagree, and no stream
     * URL needs it.
     */
    fun isPublicHttpUrl(url: String): Boolean {
        if (url.length > MAX_URL_CHARS || url.any { it.isISOControl() }) return false
        val parsed = runCatching { URI(url) }.getOrNull() ?: return false
        if (parsed.rawUserInfo != null || parsed.scheme?.lowercase() !in HTTP_SCHEMES) return false
        val host = parsed.host?.lowercase()?.trim('[', ']')?.trimEnd('.') ?: return false
        if (isPrivateHostName(host)) return false
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return false
        return addresses.isNotEmpty() && addresses.all(::isPublicAddress)
    }

    /**
     * [isPublicHttpUrl], and no `#`. libVLC splits `:input-slave` on `#` and opens every part, so
     * `https://public/a#file:///x` passes as a URL with a fragment and then opens a local file
     * (measured on VLC 3.0.23).
     */
    fun isPublicAudioUrl(url: String): Boolean = '#' !in url && isPublicHttpUrl(url)

    /** Signed stream URLs run long: a googlevideo one is well past the 2048 used for other fields. */
    const val MAX_URL_CHARS = 16 * 1024

    private const val BYTE_MASK = 0xFF
    private const val IPV4_BYTES = 4
    private const val CGNAT_FIRST = 100
    private val CGNAT_SECOND = 64..127
    private const val ULA_MASK = 0xFE
    private const val ULA_PREFIX = 0xFC
}

package com.lagradost.cloudstream4.player.gateway

/**
 * A parsed url: exactly the `java.net.URI` getters the gateway reads, and nothing else (`BUG-114`).
 *
 * @property host `URI.getHost()`: null for a registry-based authority (one that is not a valid host name, IPv4 or
 *   bracketed IPv6 address), as `java.net.URI` reads it.
 * @property port `URI.getPort()`: -1 when there is none.
 */
internal class ParsedUri(
    val scheme: String?,
    val rawUserInfo: String?,
    val host: String?,
    val port: Int,
    val rawAuthority: String?,
    val rawPath: String?,
    val rawQuery: String?,
)

/**
 * The url mechanics, per platform; the policy that uses them is common (`BUG-114`).
 *
 * - JVM and Android: `java.net.URI` and `java.net.IDN`, as `KMP-113` called them on the Mac.
 * - iOS: [PortableUri], with a host that is not ASCII refused.
 *
 * `GoldenCorpusTest` runs the same rows on both, and `PortableUriParityTest` holds [PortableUri] to `java.net.URI` on the
 * JVM, so the two agree except where a row names the difference.
 */
internal expect object UriOps {
    /** `URI(text)`, or null where it throws. */
    fun parse(text: String): ParsedUri?

    /** `URI(base).resolve(URI(reference))`, or null where either throws. */
    fun resolve(base: String, reference: String): ParsedUri?

    /** [host] in ASCII (`IDN.toASCII` on the JVM), or null where it does not convert. */
    fun asciiHost(host: String): String?
}

/** The longest url the gateway handles. Mirrors `SandboxPolicy.MAX_URL_CHARS`; `GatewayLimitsTest` holds them equal. */
internal const val MAX_URL_CHARS: Int = 16 * 1024

package com.lagradost.cloudstream4.player.gateway

/**
 * The iPad's url mechanics: [PortableUri], `java.net.URI`'s parser and resolver ported (`BUG-114`).
 *
 * One deliberate difference from the Mac: a host that is not ASCII is not converted ([asciiHost] is null), so it stays
 * as written, is not a host `java.net.URI`'s rules accept, and is refused. The Mac converts it with `IDN.toASCII`.
 * `GoldenCorpusTest` names the difference as a row of its own. It matches `BUG-111`'s rule for the iPad's client, which
 * refuses a host that is not ASCII.
 */
internal actual object UriOps {
    actual fun parse(text: String): ParsedUri? = PortableUri.parse(text)

    actual fun resolve(base: String, reference: String): ParsedUri? = PortableUri.resolve(base, reference)

    actual fun asciiHost(host: String): String? = host.takeIf { h -> h.all { it.code < 0x80 } }
}

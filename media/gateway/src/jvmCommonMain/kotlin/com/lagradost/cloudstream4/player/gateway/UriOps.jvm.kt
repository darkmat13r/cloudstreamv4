package com.lagradost.cloudstream4.player.gateway

import java.net.IDN
import java.net.URI

/**
 * `java.net.URI` and `java.net.IDN`, called as `KMP-113`'s `GatewayUrl` and `HlsRewriter` called them on the Mac, so
 * the Mac's behaviour is the same code's (`BUG-114`).
 */
internal actual object UriOps {
    actual fun parse(text: String): ParsedUri? = runCatching { URI(text) }.getOrNull()?.toParsed()

    actual fun resolve(base: String, reference: String): ParsedUri? =
        runCatching { URI(base).resolve(URI(reference)) }.getOrNull()?.toParsed()

    actual fun asciiHost(host: String): String? = runCatching { IDN.toASCII(host) }.getOrNull()

    private fun URI.toParsed() = ParsedUri(scheme, rawUserInfo, host, port, rawAuthority, rawPath, rawQuery)
}

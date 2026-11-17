package com.lagradost.cloudstream4.player.gateway

import io.ktor.http.Url
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `BUG-114` review: on the iPad the gateway's upstream fetch is Ktor Darwin, which builds an `NSURL` from Ktor's `Url`.
 * So the host the gateway's rules judged must be the host `NSURL` will connect to, or the rules judged a different url
 * from the one fetched. Every hostile row goes through [UriOps] and through `NSURL(string:)`, and through the gateway's
 * own encode and decode to the url that goes upstream: wherever the gateway accepts a url, `NSURL` and Ktor's `Url`
 * must name the same scheme and host. Where the gateway refuses, nothing is fetched, whatever `NSURL` makes of it.
 */
class NsUrlDifferentialTest {
    private val token = "AAAAAAAAAAAAAAAAAAAAAA"
    private val urls = GatewayUrl(8123, token)

    private val hostile = listOf(
        // backslashes
        "https://evil.example\\@good.example/a", "https://good.example\\evil.example/a", "https:\\\\evil.example/a",
        "https://good.example/a\\..\\..\\b",
        // user info with "@"
        "https://user@evil.example/a", "https://a@b@c.example/a", "https://good.example@evil.example/a",
        "https://good.example:443@evil.example/a", "https://[::1]@good.example/a",
        // tab, CR and LF inside
        "https://ev\til.example/a", "https://evil.example/a\r\nb", "https://evil\n.example/a", "https://evil.example/\t",
        // a percent-encoded host
        "https://%65vil.example/a", "https://evil%2eexample/a", "https://evil.example%2f@good.example/a",
        // %2e%2e dot segments
        "https://good.example/a/%2e%2e/%2e%2e/b", "https://good.example/%2e%2e/x", "https://good.example/a/%2E%2E/b",
        // an IPv6 zone
        "https://[fe80::1%25en0]/a", "https://[fe80::1%en0]/a", "https://[fe80::1%25]/a",
        // several "#" and "?"
        "https://evil.example/a?b#c#d?e", "https://evil.example#@good.example/a", "https://evil.example?@good.example/",
        "https://good.example/a??b", "https://good.example/a#?b",
        // leading and trailing space
        " https://evil.example/a", "https://evil.example/a ", "https:// evil.example/a",
        // "//" scheme-relative and odd slashes
        "//evil.example/a", "///evil.example/a", "https:///evil.example/a", "https:evil.example/a",
        // an empty authority
        "https:///a", "https://:443/a", "https://@/a",
        // hosts that look like something else
        "https://evil.example./a", "https://EVIL.Example/a", "https://127.0.0.1/a", "https://2130706433/a",
        "https://0x7f000001/a", "https://[::ffff:127.0.0.1]/a", "https://host_name.example/a", "https://a..b/a",
        "https://exämple.com/a", "https://evil。example/a", "https://ｅvil.example/a",
        "https://evil.example:99999/a", "https://evil.example:/a", "https://evil.example:0/a",
    )

    /** `NSURL`'s host as the gateway writes one: lowercase, an IPv6 literal bracketed. */
    private fun nsHost(text: String): Pair<String?, String?>? {
        val url = NSURL.URLWithString(text) ?: return null
        val host = url.host?.lowercase()?.let { if (':' in it && !it.startsWith("[")) "[$it]" else it }
        return url.scheme?.lowercase() to host
    }

    private fun ktorHost(text: String): Pair<String, String>? = runCatching { Url(text) }.getOrNull()?.let {
        it.protocol.name.lowercase() to it.host.lowercase().let { h -> if (':' in h && !h.startsWith("[")) "[$h]" else h }
    }

    @Test
    fun whereTheParserFindsAHostNsUrlFindsTheSameOne() {
        for (text in hostile) {
            val ours = UriOps.parse(text) ?: continue
            val host = ours.host?.lowercase() ?: continue
            if ('%' in host) {
                // NSURL decodes a host's escapes and the parser keeps them (`[fe80::1%25en0]` is `fe80::1%en0` to NSURL),
                // so the two cannot be compared. The gateway refuses any host holding a '%', so nothing is fetched.
                GatewayRole.entries.forEach { role -> assertNull(urls.encode(text, role), "the gateway takes $text") }
                continue
            }
            val ns = assertNotNull(nsHost(text), "NSURL refuses what the parser takes: $text")
            assertEquals(ours.scheme?.lowercase() to host, ns, text)
        }
    }

    @Test
    fun everyUrlTheGatewaySendsUpstreamNamesTheHostItsRulesJudged() {
        var accepted = 0
        for (text in hostile) for (role in GatewayRole.entries) {
            val encoded = urls.encode(text, role) ?: continue
            val target = assertNotNull(urls.decode(encoded.substringAfter("127.0.0.1:8123")), "decode($encoded)")
            accepted++
            val judged = UriOps.parse(target.upstream)!!
            val expected = judged.scheme!!.lowercase() to judged.host!!.lowercase()
            assertEquals(expected, nsHost(target.upstream), "NSURL: $text -> ${target.upstream}")
            assertEquals(expected, ktorHost(target.upstream), "Ktor Url: $text -> ${target.upstream}")
            assertEquals(expected, ktorHost(target.upstream)?.let { nsHost(Url(target.upstream).toString()) }, "Ktor then NSURL: $text")
        }
        assertTrue(accepted > 0, "no hostile row was accepted, so nothing was compared")
    }

    @Test
    fun everyUrlARewrittenPlaylistSendsUpstreamNamesTheHostItsRulesJudged() {
        val rewriter = HlsRewriter(urls::encode, strict = true)
        var accepted = 0
        for (text in hostile + hostile.map { it.substringAfter("://", it) }) {
            val result = rewriter.rewrite("#EXTM3U\n$text\n".encodeToByteArray(), "https://cdn.example/v/master.m3u8")
            val line = (result as? Rewrite.Done)?.text?.lines()?.getOrNull(1) ?: continue
            val target = assertNotNull(urls.decode(line.substringAfter("127.0.0.1:8123")), "decode($line)")
            accepted++
            val judged = UriOps.parse(target.upstream)!!
            val expected = judged.scheme!!.lowercase() to judged.host!!.lowercase()
            assertEquals(expected, nsHost(target.upstream), "NSURL: $text -> ${target.upstream}")
            assertEquals(expected, ktorHost(target.upstream), "Ktor Url: $text -> ${target.upstream}")
        }
        assertTrue(accepted > 0, "no hostile row was rewritten, so nothing was compared")
    }
}

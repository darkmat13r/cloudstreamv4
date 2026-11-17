package com.lagradost.cloudstream4.player.gateway

import com.lagradost.cloudstream4.player.gateway.oracle.GatewayRole as OldRole
import com.lagradost.cloudstream4.player.gateway.oracle.GatewayUrl as OldGatewayUrl
import com.lagradost.cloudstream4.player.gateway.oracle.HlsRewriter as OldHlsRewriter
import com.lagradost.cloudstream4.player.gateway.oracle.Rewrite as OldRewrite
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `BUG-114`: the moved `GatewayUrl` and `HlsRewriter`, with `strict` off as the Mac runs them, give exactly what
 * `KMP-113`'s originals ([oracle][com.lagradost.cloudstream4.player.gateway.oracle], verbatim) gave, on every input.
 *
 * Generated hostile urls, references and playlists ([Fuzz]), seeded so a failure repeats. This is the proof that moving
 * them out of `:desktopApp` changed nothing the Mac does; the moved `GatewayUrlTest` and `HlsRewriterTest` are the rest.
 */
class MacIdenticalParityTest {
    private val token = "AAAAAAAAAAAAAAAAAAAAAA"
    private val new = GatewayUrl(8123, token)
    private val old = OldGatewayUrl(8123, token)

    private fun GatewayRole.old() = OldRole.valueOf(name)

    @Test
    fun encodingAndDecodingAgreeOnEveryUrl() {
        val fuzz = Fuzz(114)
        repeat(60_000) {
            val url = fuzz.url()
            for (role in GatewayRole.entries) {
                val encoded = new.encode(url, role)
                assertEquals(old.encode(url, role.old()), encoded, "encode($url, $role)")
                val target = encoded?.substringAfter("127.0.0.1:8123") ?: "/$token/${role.code}/" + url.substringAfter("://")
                val n = new.decode(target)
                val o = old.decode(target)
                assertEquals(o?.upstream, n?.upstream, "decode($target)")
                assertEquals(o?.role?.name, n?.role?.name, "decode($target)")
            }
            assertEquals(old.namesGateway(url), new.namesGateway(url), url)
        }
    }

    @Test
    fun decodingAgreesOnRawTargets() {
        val fuzz = Fuzz(1141)
        repeat(60_000) {
            val target = "/" + listOf(token, "x$token", "").random() + "/" + listOf("u", "p", "s", "z", "").random() + "/" +
                listOf("https", "http", "ftp", "HTTPS").random() + "/" + fuzz.junk(5) + fuzz.url().substringAfter("://")
            val n = new.decode(target)
            val o = old.decode(target)
            assertEquals(o?.upstream, n?.upstream, target)
            assertEquals(o?.role?.name, n?.role?.name, target)
        }
    }

    @Test
    fun lenientAgreesOnEveryReference() {
        val fuzz = Fuzz(1142)
        repeat(100_000) {
            val reference = fuzz.reference()
            assertEquals(OldHlsRewriter.lenient(reference), HlsRewriter.lenient(reference), reference)
        }
    }

    @Test
    fun rewritingAgreesOnEveryPlaylist() {
        val fuzz = Fuzz(1143)
        val newRewriter = HlsRewriter(new::encode)
        val oldRewriter = OldHlsRewriter({ url, role -> old.encode(url, role) })
        repeat(20_000) {
            val body = fuzz.playlist().encodeToByteArray()
            val base = fuzz.base()
            assertEquals(oldRewriter.rewrite(body, base).text(), newRewriter.rewrite(body, base).text(), "base=$base\n${body.decodeToString()}")
        }
    }

    @Test
    fun rewritingAgreesUnderTightLimits() {
        val fuzz = Fuzz(1144)
        val newRewriter = HlsRewriter(new::encode, HlsRewriter.Limits(maxBytes = 300, maxLines = 6, maxLineChars = 80, maxAttributes = 2, maxOutputChars = 400))
        val oldRewriter = OldHlsRewriter({ url, role -> old.encode(url, role) }, OldHlsRewriter.Limits(maxBytes = 300, maxLines = 6, maxLineChars = 80, maxAttributes = 2, maxOutputChars = 400))
        repeat(20_000) {
            val body = fuzz.playlist().encodeToByteArray()
            val base = fuzz.base()
            assertEquals(oldRewriter.rewrite(body, base).text(), newRewriter.rewrite(body, base).text(), "base=$base\n${body.decodeToString()}")
        }
    }

    private fun Rewrite.text() = when (this) {
        is Rewrite.Done -> "done:$text"
        is Rewrite.Failed -> "failed:${kind.name}"
    }

    private fun OldRewrite.text() = when (this) {
        is OldRewrite.Done -> "done:$text"
        is OldRewrite.Failed -> "failed:${kind.name}"
    }
}

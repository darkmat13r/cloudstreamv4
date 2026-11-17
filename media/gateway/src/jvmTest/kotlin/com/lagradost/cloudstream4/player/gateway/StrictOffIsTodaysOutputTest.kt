package com.lagradost.cloudstream4.player.gateway

import com.lagradost.cloudstream4.player.gateway.oracle.GatewayUrl as OldGatewayUrl
import com.lagradost.cloudstream4.player.gateway.oracle.HlsRewriter as OldHlsRewriter
import com.lagradost.cloudstream4.player.gateway.oracle.Rewrite as OldRewrite
import java.net.IDN
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `BUG-114` review: every check `strict` adds is gated. With it off, as the Mac runs, each line it would refuse is
 * rewritten exactly as `KMP-113`'s original rewriter ([oracle][com.lagradost.cloudstream4.player.gateway.oracle]) rewrote
 * it: one line per strict check, the original as the expected value.
 */
class StrictOffIsTodaysOutputTest {
    private val token = "AAAAAAAAAAAAAAAAAAAAAA"
    private val base = "https://cdn.example/v/master.m3u8"

    private val oneLinePerStrictCheck = listOf(
        "http://other.example/x.ts",
        "data:,x",
        "rtsp://h.example/x",
        "../../../a.ts",
        "#EXT-X-MAP:URI=init.mp4",
        "#EXT-X-DEFINE:NAME=QaQ,VALUE=QbQ",
        "#EXT-X-VENDOR-THING:URI=Qx.tsQ",
        "#EXT-X-VENDOR-AD:https://ads.example/a.ts",
        "#ext-x-key:METHOD=AES-128,URI=QkQ",
        "#EXT-X-MEDIA:TYPE=AUDIO,URI=Qa.m3u8Q,URI=Qb.m3u8Q",
        "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=Qskd://keyQ",
        "#EXT-X-KEY:METHOD=AES-128,URI=Qkey.bin",
        "{\$x}/seg.ts",
        "#EXT-X-KEY:METHOD=AES-128,URI=Q{\$v}/k.binQ",
    ).map { it.replace('Q', '"') }

    @Test
    fun withStrictOffEachRefusedLineIsRewrittenAsTheOriginalRewroteIt() {
        val new = HlsRewriter(GatewayUrl(8123, token)::encode, strict = false)
        val oldUrls = OldGatewayUrl(8123, token)
        val old = OldHlsRewriter({ url, role -> oldUrls.encode(url, role) })
        for (line in oneLinePerStrictCheck) {
            val body = "#EXTM3U\n$line\n".encodeToByteArray()
            val expected = when (val r = old.rewrite(body, base)) { is OldRewrite.Done -> r.text; is OldRewrite.Failed -> "failed:" + r.kind.name }
            val actual = when (val r = new.rewrite(body, base)) { is Rewrite.Done -> r.text; is Rewrite.Failed -> "failed:" + r.kind.name }
            assertEquals(expected, actual, line)
            // And each one is a line strict refuses, so this list covers the checks it claims to.
            val strict = HlsRewriter(GatewayUrl(8123, token)::encode, strict = true).rewrite(body, base)
            assertEquals(RewriteFailure.Unrewritable, (strict as? Rewrite.Failed)?.kind, "strict on: $line")
        }
    }

    /** The JVM's url mechanics convert a host that is not ASCII, as the Mac always has (the corpus's named divergence). */
    @Test
    fun theJvmConvertsAHostThatIsNotAscii() =
        assertEquals(IDN.toASCII("ex\u00e4mple.com"), UriOps.asciiHost("ex\u00e4mple.com"))
}

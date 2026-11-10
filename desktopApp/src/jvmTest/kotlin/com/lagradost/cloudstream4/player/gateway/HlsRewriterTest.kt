package com.lagradost.cloudstream4.player.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `KMP-113` §3: the HLS rewriter, against hostile input. Made-up hosts only. Encoding here is a readable stand-in
 * (`G[role]<url>`), so each case shows exactly which url was rewritten and in which role.
 */
class HlsRewriterTest {
    private val rewriter = HlsRewriter({ url, role -> "G[${role.code}]$url" })
    private val base = "https://cdn.example/live/master.m3u8?sig=1"

    private fun rewrite(text: String, limits: HlsRewriter.Limits = HlsRewriter.Limits()): Rewrite =
        HlsRewriter({ url, role -> "G[${role.code}]$url" }, limits).rewrite(text.toByteArray(), base)

    private fun lines(text: String) = (assertIs<Rewrite.Done>(rewriter.rewrite(text.toByteArray(), base))).text.lines()

    @Test
    fun `a master playlist's variants are playlists, and a media playlist's lines are media`() {
        val out = lines("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow/index.m3u8\n#EXTINF:4,\nseg1.ts\n")
        assertEquals("G[p]https://cdn.example/live/low/index.m3u8", out[2])
        assertEquals("G[s]https://cdn.example/live/seg1.ts", out[4])
    }

    @Test
    fun `relative, root-relative and protocol-relative urls resolve against the final url`() {
        val out = lines("#EXTM3U\n#EXTINF:4,\na.ts\n#EXTINF:4,\n/b.ts\n#EXTINF:4,\n//other.example/c.ts\n#EXTINF:4,\n../d.ts\n")
        assertEquals("G[s]https://cdn.example/live/a.ts", out[2])
        assertEquals("G[s]https://cdn.example/b.ts", out[4])
        assertEquals("G[s]https://other.example/c.ts", out[6])
        assertEquals("G[s]https://cdn.example/d.ts", out[8])
    }

    @Test
    fun `dot segments are removed here too, and one above the root leaves the line as written`() {
        val out = lines("#EXTM3U\n#EXTINF:4,\nhttps://cdn.example/a/./b/../c.ts\n#EXTINF:4,\nhttps://cdn.example/../x.ts\n")
        assertEquals("G[s]https://cdn.example/a/c.ts", out[2])
        // An absolute reference comes back from resolve as written; its ".." above the root is refused, not clamped,
        // so the line stays for the kernel to stop.
        assertEquals("https://cdn.example/../x.ts", out[4])
    }

    @Test
    fun `every url-bearing tag is rewritten, each in its role`() {
        val tags = listOf(
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"en\",URI=\"audio/en.m3u8\"" to "p",
            "#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=1,URI=\"iframe.m3u8\"" to "p",
            "#EXT-X-RENDITION-REPORT:URI=\"other.m3u8\",LAST-MSN=1" to "p",
            "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x1" to "s",
            "#EXT-X-SESSION-KEY:METHOD=AES-128,URI=\"key.bin\"" to "s",
            "#EXT-X-MAP:URI=\"init.mp4\",BYTERANGE=\"720@0\"" to "s",
            "#EXT-X-PART:DURATION=1,URI=\"part.mp4\"" to "s",
            "#EXT-X-PRELOAD-HINT:TYPE=PART,URI=\"hint.mp4\"" to "s",
            "#EXT-X-SESSION-DATA:DATA-ID=\"x\",URI=\"data.json\"" to "s",
        )
        tags.forEach { (tag, role) ->
            val out = lines("#EXTM3U\n$tag\n")[1]
            assertTrue("\"G[$role]https://cdn.example/live/" in out, "$tag -> $out")
            assertTrue(out.startsWith(tag.substringBefore(':') + ":"), out)
        }
        val steering = lines("#EXTM3U\n#EXT-X-CONTENT-STEERING:SERVER-URI=\"steer.json\"\n")[1]
        assertEquals("#EXT-X-CONTENT-STEERING:SERVER-URI=\"G[s]https://cdn.example/live/steer.json\"", steering)
        val asset = lines("#EXTM3U\n#EXT-X-DATERANGE:ID=\"i\",X-ASSET-URI=\"ad.m3u8\",X-ASSET-LIST=\"ads.json\"\n")[1]
        assertTrue("X-ASSET-URI=\"G[p]" in asset && "X-ASSET-LIST=\"G[s]" in asset, asset)
    }

    @Test
    fun `a comma inside a quoted value is part of the value`() {
        val out = lines("#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,NAME=\"a,b\",URI=\"x.m3u8\"\n")[1]
        assertEquals("#EXT-X-MEDIA:TYPE=AUDIO,NAME=\"a,b\",URI=\"G[p]https://cdn.example/live/x.m3u8\"", out)
    }

    @Test
    fun `unknown tags, other cases, lists that do not parse, and a URI named twice pass through unchanged`() {
        val unchanged = listOf(
            "#EXT-X-SOMETHING-NEW:URI=\"https://lan.example/x\"",
            "#ext-x-key:METHOD=AES-128,URI=\"key.bin\"",
            "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin",
            "#EXT-X-KEY:METHOD=AES-128,,URI=\"key.bin\"",
            "#EXT-X-KEY:METHOD=AES-128,URI=\"a.bin\",URI=\"b.bin\"",
            "#EXT-X-KEY:METHOD=AES-128,URI=key.bin",
            "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",",
        )
        unchanged.forEach { tag -> assertEquals(tag, lines("#EXTM3U\n$tag\n")[1], tag) }
    }

    @Test
    fun `a url that is not http(s) is left as written`() {
        val out = lines("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://key-id\"\n#EXT-X-KEY:METHOD=AES-128,URI=\"data:text/plain;base64,AA==\"\n#EXTINF:4,\nfile:///etc/hosts\n")
        assertEquals("#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://key-id\"", out[1])
        assertEquals("#EXT-X-KEY:METHOD=AES-128,URI=\"data:text/plain;base64,AA==\"", out[2])
        assertEquals("file:///etc/hosts", out[4])
    }

    @Test
    fun `a byte-order mark and CRLF line ends are read, and a refused url is left as written`() {
        val text = "﻿#EXTM3U\r\n#EXTINF:4,\r\nseg.ts\r\n#EXTINF:4,\r\nhttps://viewer:pw@cdn.example/x.ts\r\n"
        val refusing = HlsRewriter({ url, role -> if ('@' in url) null else "G[${role.code}]$url" })
        val out = assertIs<Rewrite.Done>(refusing.rewrite(text.toByteArray(), base)).text.lines()
        assertEquals("#EXTM3U", out[0])
        assertEquals("G[s]https://cdn.example/live/seg.ts", out[2])
        assertEquals("https://viewer:pw@cdn.example/x.ts", out[4])
    }

    @Test
    fun `each limit is a failure at one past it, and none at it`() {
        val small = HlsRewriter.Limits(maxBytes = 100, maxLines = 5, maxLineChars = 40, maxAttributes = 3, maxOutputChars = 200)
        val atBytes = "#EXTM3U\n" + "#" + "x".repeat(30) + "\n" + "#" + "y".repeat(30) + "\n" + "#" + "z".repeat(27)
        assertEquals(100, atBytes.length)
        assertIs<Rewrite.Done>(rewrite(atBytes, small))
        assertEquals(Rewrite.Failed(RewriteFailure.TooLarge), rewrite("$atBytes#", small))
        assertIs<Rewrite.Done>(rewrite("#EXTM3U\n#a\n#b\n#c\n#d", small))
        assertEquals(Rewrite.Failed(RewriteFailure.TooManyLines), rewrite("#EXTM3U\n#a\n#b\n#c\n#d\n#e", small))
        assertIs<Rewrite.Done>(rewrite("#EXTM3U\n#" + "x".repeat(39), small))
        assertEquals(Rewrite.Failed(RewriteFailure.LineTooLong), rewrite("#EXTM3U\n#" + "x".repeat(40), small))
        assertIs<Rewrite.Done>(rewrite("#EXTM3U\n#EXT-X-KEY:A=1,B=2,URI=\"k\"", small.copy(maxLineChars = 1000)))
        assertEquals(
            Rewrite.Failed(RewriteFailure.TooManyAttributes),
            rewrite("#EXTM3U\n#EXT-X-KEY:A=1,B=2,C=3,URI=\"k\"", small.copy(maxLineChars = 1000)),
        )
    }

    @Test
    fun `the rewritten output is capped too, per line and in total`() {
        val grows = HlsRewriter.Limits(maxLineChars = 60, maxOutputChars = 10_000)
        assertEquals(Rewrite.Failed(RewriteFailure.OutputTooLarge), rewrite("#EXTM3U\n#EXTINF:4,\n" + "a".repeat(30) + ".ts", grows))
        val total = HlsRewriter.Limits(maxOutputChars = 100)
        assertEquals(Rewrite.Failed(RewriteFailure.OutputTooLarge), rewrite("#EXTM3U\n" + "#EXTINF:4,\nseg.ts\n".repeat(5), total))
    }
}

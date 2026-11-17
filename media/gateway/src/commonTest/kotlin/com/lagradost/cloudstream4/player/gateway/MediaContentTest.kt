package com.lagradost.cloudstream4.player.gateway

import kotlin.test.Test
import kotlin.test.assertEquals

/** `BUG-114`: what a response is, by its first bytes and never by its label. */
class MediaContentTest {
    private fun of(text: String) = MediaContent.of(text.encodeToByteArray())

    private fun ts(size: Int) = ByteArray(size) { (it % 251).toByte() }.also { b -> for (i in b.indices step 188) b[i] = 0x47 }

    @Test
    fun hlsIsKnownByItsFirstLine() {
        assertEquals(MediaContent.Hls, of("#EXTM3U\n#EXTINF:4,\na.ts"))
        assertEquals(MediaContent.Hls, of("\n  #EXTM3U\n"))
        assertEquals(MediaContent.Hls, MediaContent.of(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "#EXTM3U".encodeToByteArray()))
    }

    @Test
    fun manifestsAreKnownAnywhereInTheSniffWindow() {
        assertEquals(MediaContent.Manifest, of("<?xml version=\"1.0\"?><MPD></MPD>"))
        assertEquals(MediaContent.Manifest, of("<mpd>"))
        assertEquals(MediaContent.Manifest, of("<SmoothStreamingMedia/>"))
        assertEquals(MediaContent.Manifest, of("<manifest xmlns=\"http://ns.adobe.com/f4m/1.0\">"))
        // Behind bytes that look like a container's: a player probing content would still find it.
        assertEquals(MediaContent.Manifest, MediaContent.of(ts(1000) + "<MPD>".encodeToByteArray()))
    }

    @Test
    fun otherPlaylistFormatsAreKnown() {
        listOf(
            "[playlist]\nFile1=x", "#EXTINF:-1,x\nhttp://a", "#EXT-X-TARGETDURATION:4", "<asx version=\"3\">", "<smil>",
            "<?xml version=\"1.0\"?><playlist>", "RTSPtext\nrtsp://x", "[Reference]\nRef1=x",
        ).forEach { assertEquals(MediaContent.OtherPlaylist, of(it), it) }
    }

    /** libVLC's m3u reader takes input with any line that starts with a url (`ContainsURL`), so the gateway refuses it. */
    @Test
    fun aLineStartingWithAUrlMakesItAPlaylistWhateverElseItLooksLike() {
        val disguised = ts(2000).also { b ->
            val line = "\nhttps://192.168.1.1/x\n".encodeToByteArray()
            line.copyInto(b, 20)
        }
        assertEquals(MediaContent.OtherPlaylist, MediaContent.of(disguised))
        listOf("http://a/b", "x\nftp://h/", "RTSP://h/", "mms://h", "ftpes://h", "abcdef://h").forEach {
            assertEquals(MediaContent.OtherPlaylist, of(it), it)
        }
        // Not at a line's start, or not a scheme: left alone.
        assertEquals(MediaContent.Opaque, of("see http://a/b"))
        assertEquals(MediaContent.Opaque, of("1http://a"))
    }

    private fun utf16(text: String, little: Boolean): ByteArray =
        (if (little) byteArrayOf(0xFF.toByte(), 0xFE.toByte()) else byteArrayOf(0xFE.toByte(), 0xFF.toByte())) +
            text.flatMap { c -> if (little) listOf((c.code and 0xFF).toByte(), (c.code shr 8).toByte()) else listOf((c.code shr 8).toByte(), (c.code and 0xFF).toByte()) }

    /** libVLC's HDS reader decodes a UTF-16 body (with its byte-order mark) before it looks for `<manifest`. */
    @Test
    fun aUtf16BodyIsReadAsUtf16() {
        val f4m = "<?xml version=\"1.0\"?><manifest xmlns=\"http://ns.adobe.com/f4m/1.0\"><baseURL>http://h/</baseURL></manifest>"
        assertEquals(MediaContent.Manifest, MediaContent.of(utf16(f4m, little = true)))
        assertEquals(MediaContent.Manifest, MediaContent.of(utf16(f4m, little = false)))
        assertEquals(MediaContent.OtherPlaylist, MediaContent.of(utf16("<asx version=\"3\"><entry><ref href=\"http://h/\"/></entry></asx>", little = true)))
        assertEquals(MediaContent.OtherPlaylist, MediaContent.of(utf16("x\nhttps://h/a\n", little = true)))
        // Never HLS: the rewriter reads UTF-8, so a UTF-16 playlist is refused, not rewritten.
        assertEquals(MediaContent.OtherPlaylist, MediaContent.of(utf16("#EXTM3U\n#EXTINF:4,\na.ts\n", little = true)))
    }

    @Test
    fun utf16IsReadAfterAUtf8MarkAndWithNoMarkAtAll() {
        val f4m = "<manifest xmlns=\"http://ns.adobe.com/f4m/1.0\"><baseURL>http://h/</baseURL></manifest>"
        val afterUtf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf16(f4m, little = true)
        assertEquals(MediaContent.Manifest, MediaContent.of(afterUtf8Bom))
        // No mark: libVLC's readers stop at the first zero byte, so none reads it; the gateway looks anyway.
        assertEquals(MediaContent.Manifest, MediaContent.of(utf16(f4m, little = true).copyOfRange(2, 2 + 2 * f4m.length)))
        assertEquals(MediaContent.Manifest, MediaContent.of(utf16(f4m, little = false).copyOfRange(2, 2 + 2 * f4m.length)))
    }

    /** The window is cut at 4 KiB, at whatever byte: an odd cut is no error, and a mark inside it is still found. */
    @Test
    fun anOddCutOfTheWindowHidesNothingAndThrowsNothing() {
        val marker = "<manifest xmlns=\"http://ns.adobe.com/f4m/1.0\">"
        val text = " ".repeat(2040 - marker.length) + marker // ends at char 2040, byte 4082 of a little-endian body
        val body = utf16(text, little = true) + ByteArray(15) { 0x20 } // 4097 bytes in all: past the window, odd
        assertEquals(MediaContent.Manifest, MediaContent.of(body))
        for (size in listOf(0, 1, 2, 3, 4095, 4097)) MediaContent.of(utf16(" ".repeat(3000), little = true).copyOf(size))
        assertEquals(MediaContent.Opaque, MediaContent.of(utf16(" ".repeat(3000), little = true).copyOf(4097)))
    }

    @Test
    fun aContainersDemuxerIsKnownFromItsMagic() {
        assertEquals("ts", MediaContent.demuxOf(ts(2000)))
        assertEquals("mp4", MediaContent.demuxOf(byteArrayOf(0, 0, 0, 24) + "ftypisom".encodeToByteArray()))
        assertEquals("mkv", MediaContent.demuxOf(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 1, 2)))
        // As demuxFor says for the same kind: HLS is libVLC's adaptive demuxer, a playlist or a manifest none.
        assertEquals("adaptive", MediaContent.demuxOf("#EXTM3U\n".encodeToByteArray()))
        assertEquals(null, MediaContent.demuxOf("<MPD>".encodeToByteArray()))
        assertEquals(null, MediaContent.demuxOf(ts(2000).also { b -> "\nhttps://h/x\n".encodeToByteArray().copyInto(b, 10) }))
    }

    @Test
    fun containersAreKnownByTheirMagic() {
        assertEquals(MediaContent.MpegTs, MediaContent.of(ts(2000)))
        assertEquals(MediaContent.Mp4, MediaContent.of(byteArrayOf(0, 0, 0, 24) + "ftypisom".encodeToByteArray()))
        assertEquals(MediaContent.Mp4, MediaContent.of(byteArrayOf(0, 0, 0, 24) + "moof".encodeToByteArray()))
        assertEquals(MediaContent.Matroska, MediaContent.of(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 1, 2)))
    }

    @Test
    fun anythingElseIsOpaque() {
        assertEquals(MediaContent.Opaque, of("just text"))
        assertEquals(MediaContent.Opaque, MediaContent.of(ByteArray(16) { 7 }))
        // A sync byte at 0 but not at 188 is not MPEG-TS.
        assertEquals(MediaContent.Opaque, MediaContent.of(ByteArray(400).also { it[0] = 0x47 }))
        assertEquals(MediaContent.Opaque, MediaContent.of(ByteArray(0)))
    }
}

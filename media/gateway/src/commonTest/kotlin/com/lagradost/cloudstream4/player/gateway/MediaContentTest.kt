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

    @Test
    fun containersAreKnownByTheirMagic() {
        assertEquals(MediaContent.Container, MediaContent.of(ts(2000)))
        assertEquals(MediaContent.Container, MediaContent.of(byteArrayOf(0, 0, 0, 24) + "ftypisom".encodeToByteArray()))
        assertEquals(MediaContent.Container, MediaContent.of(byteArrayOf(0, 0, 0, 24) + "moof".encodeToByteArray()))
        assertEquals(MediaContent.Container, MediaContent.of(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 1, 2)))
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

package com.lagradost.cloudstream4.player.gateway

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `KMP-130`: a downloaded file is played only when the gateway would have served it as a first url: a container, and an
 * MP4 whose boxes pass, with the demuxer its bytes name, never its extension.
 */
class LocalMediaCheckTest {
    private fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray = ByteArray(0)) = u32(8L + payload.size) + type.encodeToByteArray() + payload
    private fun pattern(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private val ftyp = box("ftyp", "isom".encodeToByteArray() + ByteArray(16))
    private val mvhd = box("mvhd", ByteArray(100))
    private val moov = box("moov", mvhd + box("trak", box("tkhd", ByteArray(84))))
    private val rdrf = box("rdrf", u32(0) + "url ".encodeToByteArray() + u32(22) + "http://victim.test/x.ts".encodeToByteArray())
    private val refMoov = box("moov", mvhd + box("rmra", box("rmda", rdrf)))

    private fun ts(packets: Int) = ByteArray(packets * 188) { if (it % 188 == 0) 0x47 else 0x11 }
    private val mkv = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + pattern(8_000)

    /** What [LocalMediaCheck.demuxOf] says of [file], and how many bytes it read past the sniff window. */
    private fun judge(file: ByteArray, maxMoov: Long = LocalMediaCheck.MAX_MOOV_BYTES): Pair<String?, Long> {
        var beyond = 0L
        val demux = now {
            LocalMediaCheck.demuxOf(file.size.toLong(), { offset, length ->
                if (offset + length > MediaContent.SNIFF_BYTES) beyond += length
                file.copyOfRange(offset.toInt(), offset.toInt() + length)
            }, maxMoovBytes = maxMoov)
        }
        return demux to beyond
    }

    private fun <T> now(block: suspend () -> T): T {
        var out: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
        return out!!.getOrThrow()
    }

    @Test
    fun eachContainerGetsTheDemuxerItsBytesName() {
        assertEquals("ts", judge(ts(40)).first)
        assertEquals("mkv", judge(mkv).first)
        assertEquals("mp4", judge(ftyp + moov + box("mdat", pattern(10_000))).first)
    }

    @Test
    fun anMp4WithItsMoovAtTheTailIsWalkedToIt() {
        val (demux, beyond) = judge(ftyp + box("mdat", pattern(20_000)) + moov)
        assertEquals("mp4", demux)
        assertTrue(beyond > 0, "the tail's moov was read")
    }

    @Test
    fun aReferenceMovieIsRefusedEvenWithAValidStart() {
        assertNull(judge(ftyp + refMoov + box("mdat", pattern(1_000))).first)
        assertNull(judge(ftyp + box("mdat", pattern(20_000)) + refMoov).first, "at the tail too")
    }

    @Test
    fun anMp4LargerThanTheGatewaysBoundIsRefused() {
        assertNull(judge(ftyp + moov + box("mdat", pattern(1_000)), maxMoov = 64).first)
    }

    @Test
    fun aPlaylistAManifestOrUnknownBytesIsNeverPlayed() {
        assertNull(judge("#EXTM3U\n#EXTINF:10,\nhttps://cdn.test/1.ts\n".encodeToByteArray()).first, "HLS names segments")
        assertNull(judge("https://cdn.test/a.ts\n".encodeToByteArray() + ts(4)).first, "a url line before a TS")
        assertNull(judge("<?xml version=\"1.0\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"/>".encodeToByteArray()).first)
        assertNull(judge("[playlist]\nFile1=https://cdn.test/a.mp4\n".encodeToByteArray()).first)
        assertNull(judge(pattern(5_000)).first, "opaque")
    }

    @Test
    fun anEmptyFileOrAFailedReadIsRefused() {
        assertNull(judge(ByteArray(0)).first)
        val failed = now { LocalMediaCheck.demuxOf(10_000L, { _, _ -> throw IllegalStateException("gone") }) }
        assertNull(failed)
    }
}

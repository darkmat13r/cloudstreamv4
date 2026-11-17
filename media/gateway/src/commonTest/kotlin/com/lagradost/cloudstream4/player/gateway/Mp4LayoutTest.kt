package com.lagradost.cloudstream4.player.gateway

import com.lagradost.cloudstream4.player.gateway.Mp4Layout.Refusal
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `BUG-119`: an MP4 first url's boxes, walked wherever its `moov` is. libVLC turns a `moov/rmra` reference movie (and a
 * compressed `cmov` one) into subitems; the walk refuses one, and fails closed on any layout it cannot read to the end.
 */
class Mp4LayoutTest {
    // ---- Files -----------------------------------------------------------------------------------------------

    private fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray = ByteArray(0)) = u32(8L + payload.size) + type.encodeToByteArray() + payload
    private fun box64(type: String, payload: ByteArray, size: Long = 16L + payload.size) =
        u32(1) + type.encodeToByteArray() + u32(size ushr 32) + u32(size and 0xFFFF_FFFFL) + payload
    private fun sizeZero(type: String, payload: ByteArray = ByteArray(0)) = u32(0) + type.encodeToByteArray() + payload
    private fun pattern(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private val ftyp = box("ftyp", "isom".encodeToByteArray() + ByteArray(16))
    private val mvhd = box("mvhd", ByteArray(100))
    private val trak = box("trak", box("tkhd", ByteArray(84)))
    private val moov = box("moov", mvhd + trak)
    private val rdrf = box("rdrf", u32(0) + "url ".encodeToByteArray() + u32(22) + "http://victim.test/x.ts".encodeToByteArray())
    private val rmra = box("rmra", box("rmda", rdrf))
    private val refMoov = box("moov", mvhd + rmra)
    private fun mdat(size: Int) = box("mdat", pattern(size))

    /** [walk]s [file], and the bytes it read. */
    private class Walked(val verdict: Mp4Layout.Verdict, val bytesRead: Long)

    private fun walk(file: ByteArray, maxMoov: Long = 1L shl 20, maxBoxes: Int = 64): Walked {
        var read = 0L
        val verdict = now {
            Mp4Layout.walk(file.size.toLong(), maxMoov, maxBoxes) { offset, length ->
                read += length
                file.copyOfRange(offset.toInt(), offset.toInt() + length)
            }
        }
        return Walked(verdict, read)
    }

    private fun refused(file: ByteArray, maxMoov: Long = 1L shl 20, maxBoxes: Int = 64): Refusal =
        assertIs<Mp4Layout.Refuse>(walk(file, maxMoov, maxBoxes).verdict, "should be refused").why

    private fun passes(file: ByteArray, maxMoov: Long = 1L shl 20, maxBoxes: Int = 64): Mp4Layout.Pinned =
        when (val v = walk(file, maxMoov, maxBoxes).verdict) {
            is Mp4Layout.Pass -> v.pinned
            is Mp4Layout.Refuse -> fail("refused as ${v.why}")
        }

    /** The walk never suspends here, so it is run to its end at once. */
    private fun <T> now(block: suspend () -> T): T {
        var out: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
        return out!!.getOrThrow()
    }

    // ---- Where the moov is -----------------------------------------------------------------------------------

    @Test
    fun aMoovAtTheFrontOrTheTailPasses() {
        passes(ftyp + moov + mdat(5000))
        val tail = ftyp + mdat(200_000) + moov
        passes(tail)
        // The media between is skipped by its size, not read.
        assertTrue(walk(tail).bytesRead < 1000, "read ${walk(tail).bytesRead}")
    }

    /** The case a check of the probed first bytes alone would miss: the reference movie sits at the tail. */
    @Test
    fun aReferenceMovieIsRefusedWhereverItsMoovIs() {
        assertEquals(Refusal.ReferenceMovie, refused(ftyp + refMoov + mdat(5000)))
        assertEquals(Refusal.ReferenceMovie, refused(ftyp + mdat(200_000) + refMoov))
        // A pure reference file: no mvhd, only the rmra.
        assertEquals(Refusal.ReferenceMovie, refused(ftyp + box("moov", rmra)))
    }

    @Test
    fun noMoovIsRefused() {
        assertEquals(Refusal.NoMoov, refused(ftyp + mdat(5000)))
        assertEquals(Refusal.NoMoov, refused(ftyp))
        // Fewer than a header's bytes at the end, with no moov yet.
        assertEquals(Refusal.NoMoov, refused(ftyp + mdat(10) + ByteArray(5)))
        // After the moov they end the walk, as they end libVLC's.
        passes(ftyp + moov + ByteArray(5))
    }

    @Test
    fun aSecondMoovIsRefused() {
        assertEquals(Refusal.SecondMoov, refused(ftyp + moov + mdat(100) + refMoov))
    }

    // ---- The bound -------------------------------------------------------------------------------------------

    @Test
    fun aMoovPastTheBoundIsRefusedAndOneAtItPasses() {
        val big = box("moov", mvhd + box("free", ByteArray(4000)))
        passes(ftyp + mdat(100) + big, maxMoov = big.size.toLong())
        assertEquals(Refusal.MoovTooLarge, refused(ftyp + mdat(100) + big, maxMoov = big.size - 1L))
    }

    @Test
    fun tooManyTopLevelBoxesAreRefused() {
        val many = ftyp + (1..70).fold(ByteArray(0)) { acc, _ -> acc + box("free", ByteArray(4)) } + moov
        assertEquals(Refusal.TooManyBoxes, refused(many))
        passes(many, maxBoxes = 80)
    }

    // ---- Size 0 and 64-bit sizes -----------------------------------------------------------------------------

    /** Size 0 runs to the end of the file: before the moov, nothing after it could be read, so the moov is never found. */
    @Test
    fun aSizeZeroBoxBeforeTheMoovIsRefused() {
        assertEquals(Refusal.SizeZeroBeforeMoov, refused(ftyp + sizeZero("mdat", pattern(5000)) + refMoov))
        assertEquals(Refusal.SizeZeroBeforeMoov, refused(ftyp + sizeZero("free")))
    }

    /** After the moov, a size-0 mdat is an ordinary file's media to its end, and passes. */
    @Test
    fun aMoovThenASizeZeroMdatPasses() {
        passes(ftyp + moov + sizeZero("mdat", pattern(5000)))
    }

    /** A size-0 moov is judged as running to the end of the file: against the bound, and for what it holds. */
    @Test
    fun aSizeZeroMoovIsJudgedToTheEndOfTheFile() {
        val zeroMoov = sizeZero("moov", mvhd + trak)
        passes(ftyp + mdat(100) + zeroMoov)
        assertEquals(Refusal.MoovTooLarge, refused(ftyp + mdat(100) + zeroMoov, maxMoov = zeroMoov.size - 1L))
        assertEquals(Refusal.ReferenceMovie, refused(ftyp + mdat(100) + sizeZero("moov", mvhd + rmra)))
    }

    @Test
    fun aSixtyFourBitSizeIsReadAndOnePastTheFileIsRefused() {
        passes(ftyp + box64("mdat", pattern(3000)) + moov)
        assertEquals(Refusal.ReferenceMovie, refused(ftyp + box64("mdat", pattern(3000)) + refMoov))
        assertEquals(Refusal.PastEnd, refused(ftyp + box64("mdat", pattern(3000), size = 1L shl 40) + moov))
        assertEquals(Refusal.PastEnd, refused(ftyp + box64("mdat", pattern(3000), size = -1L) + moov))
        assertEquals(Refusal.BadBox, refused(ftyp + box64("mdat", pattern(3000), size = 15) + moov))
    }

    @Test
    fun aBoxThatRunsPastTheFileOrIsShorterThanItsHeaderIsRefused() {
        assertEquals(Refusal.PastEnd, refused(ftyp + u32(9000) + "mdat".encodeToByteArray() + pattern(100) + moov))
        assertEquals(Refusal.BadBox, refused(ftyp + u32(4) + "mdat".encodeToByteArray() + moov))
        // After the moov too: a truncated box cannot be told from a hidden one.
        assertEquals(Refusal.PastEnd, refused(ftyp + moov + u32(9000) + "free".encodeToByteArray()))
    }

    // ---- Compressed and hidden movies ------------------------------------------------------------------------

    @Test
    fun aCompressedMovieIsRefused() {
        assertEquals(Refusal.CompressedMovie, refused(ftyp + box("moov", box("cmov", box("dcom", "zlib".encodeToByteArray()))) + mdat(100)))
    }

    /** libVLC reads a top-level free box whose first child is cmov or mvhd as a hidden moov (`MP4_ReadBoxSkip`). */
    @Test
    fun aMoovHiddenInAFreeBoxIsRefusedBeforeOrAfterTheMoov() {
        val hidden = box("free", box("cmov", ByteArray(20)))
        assertEquals(Refusal.MoovInFree, refused(ftyp + hidden + moov))
        assertEquals(Refusal.MoovInFree, refused(ftyp + moov + mdat(100) + hidden))
        assertEquals(Refusal.MoovInFree, refused(ftyp + moov + box("free", mvhd)))
        assertEquals(Refusal.MoovInFree, refused(ftyp + moov + box64("free", box("cmov", ByteArray(20)))))
        passes(ftyp + box("free", ByteArray(64)) + moov + box("free", ByteArray(64)))
    }

    // ---- Fragmented files ------------------------------------------------------------------------------------

    /** libVLC stops at the first moof or mdat after a fragmented file's moov, and so does the walk: not every fragment. */
    @Test
    fun aFragmentedFileIsWalkedOnlyToItsFirstFragment() {
        val fragmented = box("moov", mvhd + box("mvex", box("trex", ByteArray(24))))
        val fragments = (1..200).fold(ByteArray(0)) { acc, _ -> acc + box("moof", ByteArray(16)) + mdat(32) }
        passes(ftyp + fragmented + fragments)
        // The same fragments with no mvex: read to the end, past the box bound.
        assertEquals(Refusal.TooManyBoxes, refused(ftyp + moov + fragments))
    }

    // ---- What is pinned --------------------------------------------------------------------------------------

    @Test
    fun theBytesJudgedArePinnedAndOthersAreNot() {
        val file = ftyp + mdat(10_000) + moov
        val pinned = passes(file)
        assertEquals(file.size.toLong(), pinned.total)
        assertTrue(pinned.matches(0, file))
        val moovAt = file.size - moov.size
        val swapped = file.copyOf().also { it[moovAt + 20] = (it[moovAt + 20] + 1).toByte() }
        assertFalse(pinned.matches(0, swapped), "a changed moov byte")
        assertFalse(pinned.matches(moovAt + 16L, swapped, moovAt + 16, 8), "a changed moov byte, read from inside")
        val header = file.copyOf().also { it[ftyp.size + 2] = 0x7F }
        assertFalse(pinned.matches(0, header), "a changed box size")
        val media = file.copyOf().also { it[ftyp.size + 500] = (it[ftyp.size + 500] + 1).toByte() }
        assertTrue(pinned.matches(0, media), "media bytes are not pinned")
    }
}

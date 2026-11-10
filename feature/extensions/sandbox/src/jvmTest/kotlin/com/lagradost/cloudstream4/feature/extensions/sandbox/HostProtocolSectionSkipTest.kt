package com.lagradost.cloudstream4.feature.extensions.sandbox

import com.lagradost.cloudstream4.feature.extensions.sandbox.TitleSectionFrames.person
import com.lagradost.cloudstream4.feature.extensions.sandbox.TitleSectionFrames.reply
import com.lagradost.cloudstream4.feature.extensions.sandbox.TitleSectionFrames.section
import com.lagradost.cloudstream4.feature.extensions.sandbox.TitleSectionFrames.suggestion
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.FilterInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `KMP-105` and `KMP-108`: a section this app does not read, a second of one it has read, and one past its own
 * cap (art, ids, cast or recommendations) are stepped over in place and never copied out. A counting stream under the
 * reader tells the bytes handed out by `read` from those passed by `skip`, so the test sees which path each section
 * took without measuring memory.
 */
class HostProtocolSectionSkipTest {

    /** Counts what passes through: bytes [read] by any `read`, and bytes [skipped] by `skip`. */
    private class Counting(input: InputStream) : FilterInputStream(input) {
        var read = 0L
        var skipped = 0L

        override fun read(): Int = super.read().also { if (it >= 0) read++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) read += it }

        override fun skip(n: Long): Long = super.skip(n).also { skipped += it }
    }

    private class Reading(val details: RemoteDetails, val dropped: List<String>, val read: Long, val skipped: Long)

    /** [frame]'s payload read by the app's own reader, through a [Counting] stream. */
    private fun readCounting(frame: ByteArray): Reading {
        val payload = HostProtocol.readFrame(ByteArrayInputStream(frame))!!
        val counting = Counting(ByteArrayInputStream(payload))
        val dropped = ArrayList<String>()
        val details = DataInputStream(counting).readDetails { dropped += it }
        assertEquals(payload.size.toLong(), counting.read + counting.skipped, "every byte of the payload passed once")
        return Reading(details, dropped, counting.read, counting.skipped)
    }

    /** A section body of [size] bytes, every one of them padding. */
    private fun padding(size: Int) = ByteArray(size) { 0x5A }

    @Test
    fun `a large unknown section and a second cast section are skipped, never read, and the first cast is kept`() {
        val unknownSize = 4 * 1024 * 1024
        val repeatSize = 1024 * 1024
        val frame = reply {
            section(0x7E) { write(padding(unknownSize)) }
            section(HostProtocol.CAST_V1) { writeInt(1); person("Ines Marlowe") }
            section(HostProtocol.CAST_V1) { writeInt(1); person("A second section"); write(padding(repeatSize)) }
        }
        val reading = readCounting(frame)

        assertEquals(listOf("Ines Marlowe"), reading.details.cast.map { it.name })
        assertEquals(listOf("a section this app does not read"), reading.dropped, "said once")
        // The count, the name's length and bytes, the kind byte, three absent optionals, then the padding.
        val repeatBody = Int.SIZE_BYTES + Int.SIZE_BYTES + "A second section".length + 1 + 3 + repeatSize
        assertEquals((unknownSize + repeatBody).toLong(), reading.skipped, "exactly the two bodies were skipped")
        assertTrue(reading.read < 4 * 1024, "only the page, the facts and the first cast were read: ${reading.read} bytes")
    }

    @Test
    fun `a known section seen for the first time is read, not skipped`() {
        val frame = reply {
            section(HostProtocol.CAST_V1) { writeInt(1); person("Ines Marlowe") }
            section(HostProtocol.RECOMMENDATIONS_V1) { writeInt(0) }
        }
        val reading = readCounting(frame)

        assertEquals(listOf("Ines Marlowe"), reading.details.cast.map { it.name })
        assertEquals(emptyList(), reading.dropped)
        assertEquals(0L, reading.skipped)
    }

    /** A cast of one person, "A", whose role pads the section's body to exactly [size] bytes. */
    private fun castOfSize(size: Int) = reply {
        // The count, the name's length and byte, the kind, the role's flag and length, then the voice and photo flags.
        val fixed = Int.SIZE_BYTES + Int.SIZE_BYTES + 1 + 1 + 1 + Int.SIZE_BYTES + 1 + 1
        section(HostProtocol.CAST_V1) { writeInt(1); person("A", role = "r".repeat(size - fixed)) }
    }

    @Test
    fun `a cast section of exactly 64 KiB is read, and one a byte past it is skipped before a byte is copied`() {
        val atCap = readCounting(castOfSize(HostProtocol.MAX_SECTION_BYTES))
        assertEquals(listOf("A"), atCap.details.cast.map { it.name })
        assertEquals(0L, atCap.skipped)

        val past = readCounting(castOfSize(HostProtocol.MAX_SECTION_BYTES + 1))
        assertEquals(emptyList(), past.details.cast)
        assertEquals(listOf("cast: a section past its bytes"), past.dropped)
        assertEquals(HostProtocol.MAX_SECTION_BYTES + 1L, past.skipped, "the whole body, skipped")
        assertTrue(past.read < 4 * 1024, "none of it read: ${past.read} bytes")
    }

    @Test
    fun `a recommendations section of a megabyte is skipped unread, said once, and the cast beside it is kept`() {
        val padSize = 1024 * 1024
        val frame = reply {
            section(HostProtocol.CAST_V1) { writeInt(1); person("Ines Marlowe") }
            section(HostProtocol.RECOMMENDATIONS_V1) {
                writeInt(1)
                suggestion("Saltwind", "https://site.example/s")
                write(padding(padSize))
            }
        }
        val reading = readCounting(frame)

        assertEquals(listOf("Ines Marlowe"), reading.details.cast.map { it.name })
        assertEquals(emptyList(), reading.details.recommendations)
        assertEquals(listOf("recommendations: a section past its bytes"), reading.dropped)
        assertTrue(reading.skipped > padSize, "the section was skipped: ${reading.skipped} bytes")
        assertTrue(reading.read < 4 * 1024, "none of it read: ${reading.read} bytes")
    }

    /** An ids section of [size] bytes: three ids of 16 digits, the most each may be, then [size] - 63 bytes of padding. */
    private fun idsOfSize(size: Int) = reply {
        section(HostProtocol.IDS_V1) {
            repeat(3) { writeOptional(MAX_ID) }
            write(padding(size - HostProtocol.MAX_IDS_SECTION_BYTES))
        }
    }

    @Test
    fun `an ids section of exactly 63 bytes is read, and one a byte past it is skipped before a byte is copied`() {
        assertEquals(63, HostProtocol.MAX_IDS_SECTION_BYTES, "3 x (a flag, a length and 16 bytes)")
        val atCap = readCounting(idsOfSize(HostProtocol.MAX_IDS_SECTION_BYTES))
        assertEquals(RemoteIds(MAX_ID, MAX_ID, MAX_ID), atCap.details.ids)
        assertEquals(emptyList(), atCap.dropped)
        assertEquals(0L, atCap.skipped)

        val past = readCounting(idsOfSize(HostProtocol.MAX_IDS_SECTION_BYTES + 1))
        assertEquals(RemoteIds.NONE, past.details.ids)
        assertEquals(listOf("ids: a section past its bytes"), past.dropped)
        assertEquals(HostProtocol.MAX_IDS_SECTION_BYTES + 1L, past.skipped, "the whole body, skipped")
    }

    /** An art section of [size] bytes: a backdrop of 2048 bytes, the most it may be, then [size] - 2053 of padding. */
    private fun artOfSize(size: Int) = reply {
        section(HostProtocol.ART_V1) {
            writeOptional(MAX_BACKDROP)
            write(padding(size - HostProtocol.MAX_ART_SECTION_BYTES))
        }
    }

    @Test
    fun `an art section of exactly 2053 bytes is read, and one a byte past it is skipped before a byte is copied`() {
        assertEquals(2053, HostProtocol.MAX_ART_SECTION_BYTES, "a flag, a length and 2048 bytes")
        val atCap = readCounting(artOfSize(HostProtocol.MAX_ART_SECTION_BYTES))
        assertEquals(MAX_BACKDROP, atCap.details.backdropUrl)
        assertEquals(emptyList(), atCap.dropped)
        assertEquals(0L, atCap.skipped)

        val past = readCounting(artOfSize(HostProtocol.MAX_ART_SECTION_BYTES + 1))
        assertNull(past.details.backdropUrl)
        assertEquals(listOf("art: a section past its bytes"), past.dropped)
        assertEquals(HostProtocol.MAX_ART_SECTION_BYTES + 1L, past.skipped, "the whole body, skipped")
    }

    @Test
    fun `a megabyte of ids or of art is skipped, never read, and the page beside it is kept`() {
        val big = 1024 * 1024
        val frame = reply {
            section(HostProtocol.ART_V1) { writeOptional(MAX_BACKDROP); write(padding(big)) }
            section(HostProtocol.IDS_V1) {
                writeOptional("5")
                writeOptional(null)
                writeOptional(null)
                write(padding(big))
            }
            section(HostProtocol.CAST_V1) { writeInt(1); person("Ines Marlowe") }
        }
        val reading = readCounting(frame)

        assertEquals(listOf("Ines Marlowe"), reading.details.cast.map { it.name })
        assertNull(reading.details.backdropUrl)
        assertEquals(RemoteIds.NONE, reading.details.ids)
        assertEquals(listOf("art: a section past its bytes", "ids: a section past its bytes"), reading.dropped)
        assertTrue(reading.skipped > 2L * big, "both sections were skipped: ${reading.skipped} bytes")
        assertTrue(reading.read < 4 * 1024, "none of them read: ${reading.read} bytes")
    }

    private companion object {
        val MAX_ID = "1".repeat(HostProtocol.MAX_ID_BYTES)
        const val BACKDROP_PREFIX = "https://img.example/"
        val MAX_BACKDROP = BACKDROP_PREFIX + "a".repeat(HostProtocol.MAX_IMAGE_URL_BYTES - BACKDROP_PREFIX.length)
    }
}

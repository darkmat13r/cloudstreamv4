package com.lagradost.cloudstream4.player

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The media host runs hostile media, so each of these is what a compromised one could send. */
class MediaStreamReaderTest {

    private class Recording(private val capacity: (Int, Int) -> Int = { w, h -> w * h * 4 }) : FrameSink {
        val allocations = mutableListOf<Pair<Int, Int>>()
        val published = mutableListOf<ByteArray>()

        override fun allocate(width: Int, height: Int): FrameTarget {
            allocations += width to height
            val buffer = ByteBuffer.allocate(capacity(width, height))
            return object : FrameTarget {
                override fun next() = buffer
                override fun publish() {
                    published += buffer.array().copyOf(buffer.position())
                }
            }
        }
    }

    private class Shapes : FrameSink {
        val shown = mutableListOf<Pair<Int, Int>>()

        override fun allocate(width: Int, height: Int): FrameTarget = object : FrameTarget {
            override fun next() = ByteBuffer.allocate(width * height * 4)
            override fun publish() = Unit
            override fun showAs(width: Int, height: Int) {
                shown += width to height
            }
        }
    }

    private fun DataOutputStream.picture(width: Int, height: Int) {
        writeByte(MediaProtocol.MESSAGE_PICTURE.toInt())
        writeInt(width)
        writeInt(height)
    }

    @Test
    fun `a picture size reaches the target of the frames before it`() {
        val sink = Shapes()
        read(sink) {
            format(1280, 738)
            picture(1280, 720)
        }
        assertEquals(listOf(1280 to 720), sink.shown)
    }

    @Test
    fun `a picture size before any format ends the host`() {
        assertFailsWith<IOException> { read(Shapes()) { picture(1280, 720) } }
    }

    @Test
    fun `a picture size outside the frame bounds ends the host`() {
        val refused = listOf(
            0 to 720,
            1280 to 0,
            -1 to 720,
            1280 to -1,
            MediaProtocol.MAX_FRAME_WIDTH + 1 to 720,
            1280 to MediaProtocol.MAX_FRAME_HEIGHT + 1,
            Int.MAX_VALUE to Int.MAX_VALUE,
        )
        refused.forEach { (width, height) ->
            val sink = Shapes()
            assertFailsWith<IOException>("$width x $height") {
                read(sink) {
                    format(2, 1)
                    picture(width, height)
                }
            }
            assertTrue(sink.shown.isEmpty())
        }
    }

    private fun stream(block: DataOutputStream.() -> Unit) =
        ByteArrayInputStream(ByteArrayOutputStream().also { DataOutputStream(it).block() }.toByteArray())

    private fun DataOutputStream.format(width: Int, height: Int) {
        writeByte(MediaProtocol.MESSAGE_FORMAT.toInt())
        writeInt(width)
        writeInt(height)
    }

    private fun read(sink: FrameSink = Recording(), block: DataOutputStream.() -> Unit): List<PlayerStatus> {
        val statuses = mutableListOf<PlayerStatus>()
        MediaStreamReader(stream(block), sink) { statuses += it }.readAll()
        return statuses
    }

    @Test
    fun `a frame reaches its target whole, and a status arrives as sent`() {
        val sink = Recording()
        val pixels = ByteArray(2 * 1 * 4) { it.toByte() }
        val statuses = read(sink) {
            format(2, 1)
            writeByte(MediaProtocol.MESSAGE_FRAME.toInt())
            write(pixels)
            writeByte(MediaProtocol.MESSAGE_STATUS.toInt())
            writeInt(MediaStreamReader.FLAG_PLAYING)
            writeLong(5)
            writeLong(9)
        }
        assertContentEquals(pixels, sink.published.single())
        assertEquals(PlayerStatus(playing = true, buffering = false, positionMillis = 5, lengthMillis = 9), statuses.single())
    }

    @Test
    fun `a host cannot choose how much memory the app allocates`() {
        val sizes = listOf(
            MediaProtocol.MAX_FRAME_WIDTH + 1 to 1,
            1 to MediaProtocol.MAX_FRAME_HEIGHT + 1,
            0 to 1,
            -1 to -1,
            Int.MAX_VALUE to Int.MAX_VALUE,
        )
        sizes.forEach { (width, height) ->
            val sink = Recording()
            assertFailsWith<IOException>("$width x $height") { read(sink) { format(width, height) } }
            assertTrue(sink.allocations.isEmpty(), "$width x $height allocated")
        }
    }

    @Test
    fun `the largest allowed frame does not overflow its size`() {
        val sink = Recording { _, _ -> 1 }
        // Refused as bigger than its target, rather than wrapping to a small number.
        assertFailsWith<IOException> {
            read(sink) {
                format(MediaProtocol.MAX_FRAME_WIDTH, MediaProtocol.MAX_FRAME_HEIGHT)
                writeByte(MediaProtocol.MESSAGE_FRAME.toInt())
            }
        }
    }

    @Test
    fun `changing size on every frame ends the host`() {
        val sink = Recording()
        assertFailsWith<IOException> {
            read(sink) { repeat(MediaProtocol.MAX_FORMAT_CHANGES + 1) { format(2, 2) } }
        }
        assertEquals(MediaProtocol.MAX_FORMAT_CHANGES, sink.allocations.size)
    }

    @Test
    fun `a frame before any format is refused`() {
        assertFailsWith<IOException> { read { writeByte(MediaProtocol.MESSAGE_FRAME.toInt()) } }
    }

    @Test
    fun `a frame cut short is not published`() {
        val sink = Recording()
        assertFailsWith<EOFException> {
            read(sink) {
                format(2, 2)
                writeByte(MediaProtocol.MESSAGE_FRAME.toInt())
                write(ByteArray(3))
            }
        }
        assertTrue(sink.published.isEmpty())
    }

    @Test
    fun `an unknown message ends the host`() {
        assertFailsWith<IOException> { read { writeByte(99) } }
    }

    @Test
    fun `a negative time is refused`() {
        assertFailsWith<IOException> {
            read {
                writeByte(MediaProtocol.MESSAGE_STATUS.toInt())
                writeInt(0)
                writeLong(-1)
                writeLong(0)
            }
        }
    }

    @Test
    fun `text over the limit is refused without being read`() {
        val size = MediaProtocol.MAX_TEXT_BYTES + 1
        val input = java.io.DataInputStream(stream { writeInt(size) })
        with(MediaProtocol) { assertFailsWith<IOException> { input.readText() } }
    }

    @Test
    fun `cleaning up a host directory does not follow a link out of it`() {
        val root = Files.createTempDirectory("media-host-test")
        val victim = Files.createDirectory(root.resolve("victim"))
        val precious = Files.writeString(victim.resolve("precious.txt"), "keep")
        val host = Files.createDirectory(root.resolve("host"))
        Files.writeString(host.resolve("own.txt"), "mine")
        Files.createSymbolicLink(host.resolve("link"), victim)
        Files.createSymbolicLink(host.resolve("file-link"), precious)

        deleteHostDirectory(host)

        assertFalse(Files.exists(host), "the host's own directory should be gone")
        assertEquals("keep", Files.readString(precious))
        deleteHostDirectory(root)
    }
}

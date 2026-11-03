package com.lagradost.cloudstream4.player

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * What the app and its media host say to each other, over the host's stdin and stdout.
 *
 * Fixed-layout and hand-written, like `HostProtocol`, and for the same reason: libVLC parses hostile
 * media in the host, so everything arriving from it is written by code that may no longer be ours.
 *
 * The frames take a pipe rather than shared memory on purpose. A host that shared a mapped file could
 * truncate it under the app, which faults the reader. Through a pipe it can only send bytes, and
 * [MediaStreamReader] decides how many it takes and where they go.
 */
internal object MediaProtocol {
    const val OP_PLAY: Byte = 1
    const val OP_TOGGLE_PAUSE: Byte = 2
    const val OP_SEEK_BY: Byte = 3
    const val OP_SEEK_TO: Byte = 4

    const val MESSAGE_STATUS: Byte = 1
    const val MESSAGE_FORMAT: Byte = 2
    const val MESSAGE_FRAME: Byte = 3
    const val MESSAGE_PICTURE: Byte = 4

    const val BYTES_PER_PIXEL = 4

    /**
     * The largest frame the app will allocate for. The host applies it too, but only this side counts.
     *
     * 1080p and not 4K, because these frames cross a pipe. See `KMP-16` for the measurement.
     */
    const val MAX_FRAME_WIDTH = 1920
    const val MAX_FRAME_HEIGHT = 1080

    /**
     * How many times one stream may change frame size. Each change allocates, so a host that changed
     * it on every frame would otherwise set the pace of this process's allocations.
     */
    const val MAX_FORMAT_CHANGES = 32

    const val MAX_TEXT_BYTES = 16 * 1024

    fun DataOutputStream.writeText(value: String?) {
        if (value == null) {
            writeInt(-1)
            return
        }
        val bytes = value.encodeToByteArray()
        require(bytes.size <= MAX_TEXT_BYTES) { "text too long" }
        writeInt(bytes.size)
        write(bytes)
    }

    fun DataInputStream.readText(): String? {
        val size = readInt()
        if (size == -1) return null
        if (size !in 0..MAX_TEXT_BYTES) throw IOException("text of $size bytes")
        return ByteArray(size).also(::readFully).decodeToString()
    }
}

/** A frame size the host asked for, once the app has agreed to it. Null from a sink refuses it. */
internal fun interface FrameSink {
    fun allocate(width: Int, height: Int): FrameTarget?
}

/** Where one size's frames go: fill [next], then [publish] it. [showAs] is the shape to draw them in, when it is known. */
internal interface FrameTarget {
    fun next(): ByteBuffer
    fun publish()
    fun showAs(width: Int, height: Int) = Unit
}

/**
 * Reads everything the media host sends, and treats all of it as hostile.
 *
 * Any violation throws, which ends the host: a peer that has stopped speaking the protocol cannot be
 * resynchronised. It is the only code that touches app memory on the host's say-so, so what it allows
 * is exact. A frame is never bigger than the agreed size, and a target never takes more than it holds.
 */
internal class MediaStreamReader(
    input: InputStream,
    private val sink: FrameSink,
    private val onStatus: (PlayerStatus) -> Unit,
) {
    private val data = DataInputStream(input)
    private val chunk = ByteArray(CHUNK_BYTES)
    private var target: FrameTarget? = null
    private var frameBytes = 0
    private var formatChanges = 0

    /** Until the stream ends, which is how a host exits. */
    fun readAll() {
        while (true) {
            val type = data.read()
            if (type < 0) return
            when (type.toByte()) {
                MediaProtocol.MESSAGE_STATUS -> onStatus(readStatus())
                MediaProtocol.MESSAGE_FORMAT -> readFormat()
                MediaProtocol.MESSAGE_FRAME -> readFrame()
                MediaProtocol.MESSAGE_PICTURE -> readPicture()
                else -> throw IOException("unknown message $type")
            }
        }
    }

    private fun readStatus(): PlayerStatus {
        val flags = data.readInt()
        val position = data.readLong()
        val length = data.readLong()
        if (position < 0 || length < 0) throw IOException("negative time")
        return PlayerStatus(
            playing = flags and FLAG_PLAYING != 0,
            buffering = flags and FLAG_BUFFERING != 0,
            positionMillis = position,
            lengthMillis = length,
            failed = flags and FLAG_FAILED != 0,
            finished = flags and FLAG_FINISHED != 0,
        )
    }

    private fun readFormat() {
        val width = data.readInt()
        val height = data.readInt()
        if (width !in 1..MediaProtocol.MAX_FRAME_WIDTH || height !in 1..MediaProtocol.MAX_FRAME_HEIGHT) {
            throw IOException("frame of $width x $height")
        }
        if (++formatChanges > MediaProtocol.MAX_FORMAT_CHANGES) throw IOException("too many frame sizes")
        target = sink.allocate(width, height) ?: throw IOException("no memory for $width x $height")
        frameBytes = width * height * MediaProtocol.BYTES_PER_PIXEL
    }

    /** Only a shape to draw in: nothing is allocated for it, and it is held to the same bounds as a frame. */
    private fun readPicture() {
        val width = data.readInt()
        val height = data.readInt()
        if (width !in 1..MediaProtocol.MAX_FRAME_WIDTH || height !in 1..MediaProtocol.MAX_FRAME_HEIGHT) {
            throw IOException("picture of $width x $height")
        }
        val current = target ?: throw IOException("picture before format")
        current.showAs(width, height)
    }

    private fun readFrame() {
        val current = target ?: throw IOException("frame before format")
        val buffer = current.next()
        buffer.clear()
        if (buffer.remaining() < frameBytes) throw IOException("target smaller than its frame")
        var left = frameBytes
        while (left > 0) {
            val read = data.read(chunk, 0, minOf(left, chunk.size))
            if (read < 0) throw EOFException("stream ended inside a frame")
            buffer.put(chunk, 0, read)
            left -= read
        }
        current.publish()
    }

    companion object {
        const val FLAG_PLAYING = 1
        const val FLAG_BUFFERING = 2
        const val FLAG_FAILED = 4
        const val FLAG_FINISHED = 8
        private const val CHUNK_BYTES = 256 * 1024
    }
}

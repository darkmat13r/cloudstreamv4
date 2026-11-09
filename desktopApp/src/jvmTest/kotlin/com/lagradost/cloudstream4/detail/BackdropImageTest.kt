package com.lagradost.cloudstream4.detail

import coil3.decode.Decoder
import coil3.decode.SkiaImageDecoder
import com.lagradost.cloudstream4.search.posterHttpClient
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/** The backdrop's loader (`KMP-100`): https on every hop, and no decode past its bounds. */
class BackdropImageTest {

    /** One thread answering every request with [body] as an image, counting the requests. */
    private class Server(private val body: ByteArray) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val port: Int = socket.localPort
        val requests = AtomicInteger()

        init {
            thread(isDaemon = true) {
                runCatching {
                    while (true) {
                        socket.accept().use { client ->
                            val reader = client.getInputStream().bufferedReader()
                            if (reader.readLine() == null) return@use
                            while (!reader.readLine().isNullOrEmpty()) Unit
                            requests.incrementAndGet()
                            val head = "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${body.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            client.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
                        }
                    }
                }
            }
        }

        val url get() = "http://127.0.0.1:$port/b.png"

        override fun close() = socket.close()
    }

    /** A real PNG of [width] by [height], drawn by Skia. */
    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap().apply { allocN32Pixels(width, height) }
        bitmap.erase(0xFF336699.toInt())
        return checkNotNull(Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)).bytes
    }

    /**
     * A file of a few dozen bytes whose header claims [width] by [height]: the signature, the header chunk, one tiny
     * data chunk and the end. Decoding it in full would cost `width * height * 4` bytes; reading its size costs none.
     */
    private fun claimingPng(width: Int, height: Int): ByteArray {
        fun chunk(out: DataOutputStream, type: String, data: ByteArray) {
            out.writeInt(data.size)
            val crc = CRC32()
            crc.update(type.toByteArray())
            crc.update(data)
            out.write(type.toByteArray())
            out.write(data)
            out.writeInt(crc.value.toInt())
        }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            val header = ByteArrayOutputStream().also { h ->
                DataOutputStream(h).use { it.writeInt(width); it.writeInt(height); it.write(byteArrayOf(8, 2, 0, 0, 0)) }
            }.toByteArray()
            chunk(out, "IHDR", header)
            val deflater = Deflater().apply { setInput(ByteArray(64)); finish() }
            val compressed = ByteArray(128).let { it.copyOf(deflater.deflate(it)) }
            chunk(out, "IDAT", compressed)
            chunk(out, "IEND", ByteArray(0))
        }
        return bytes.toByteArray()
    }

    /** How many images reached Coil's Skia decoder, past the bounds. */
    private val decoded = AtomicInteger()

    private val watchedSkia = Decoder.Factory { result, options, loader ->
        decoded.incrementAndGet()
        SkiaImageDecoder.Factory().create(result, options, loader)
    }

    /** The backdrop's loader over plain http to this machine, as a test can serve: the decoder is what is checked. */
    private fun localLoader(maxBytes: Long = BACKDROP_MAX_BYTES) =
        backdropImageLoader(posterHttpClient { true }, BoundedDecoder.Factory(maxBytes = maxBytes, then = watchedSkia))

    @Test
    fun `an image inside its bounds loads, no larger than it is drawn`() = runBlocking<Unit> {
        Server(png(1600, 800)).use { server ->
            val image = assertNotNull(loadBackdrop(server.url, 400, 200, localLoader()))
            assertEquals(1, decoded.get())
            // Asked for 400 by 200: Coil may keep up to twice that before scaling on draw, never the file's 1600.
            assertTrue(image.width in 400..800 && image.height in 200..400, "${image.width} x ${image.height}")
        }
    }

    @Test
    fun `a small file claiming a huge image is refused before it is decoded`() = runBlocking<Unit> {
        val bomb = claimingPng(20_000, 20_000)
        assertTrue(bomb.size < 200, "${bomb.size} bytes claim 20000 x 20000")
        Server(bomb).use { server ->
            assertNull(loadBackdrop(server.url, 400, 200, localLoader()))
            assertEquals(1, server.requests.get())
        }
        // One pixel past the budget is refused too. Neither reached the decoder: the header was enough.
        Server(claimingPng(4097, 4096)).use { server -> assertNull(loadBackdrop(server.url, 400, 200, localLoader())) }
        assertEquals(0, decoded.get(), "no decode was attempted")
        // At the budget the header passes, and the decoder is asked (this file's pixels are not real, so it then fails).
        Server(claimingPng(4096, 4096)).use { server -> loadBackdrop(server.url, 400, 200, localLoader()) }
        assertEquals(1, decoded.get())
    }

    @Test
    fun `a file past its bytes is refused`() = runBlocking<Unit> {
        val file = png(64, 64)
        Server(file).use { server ->
            assertNull(loadBackdrop(server.url, 64, 64, localLoader(maxBytes = file.size - 1L)))
            assertEquals(0, decoded.get())
            assertNotNull(loadBackdrop(server.url, 64, 64, localLoader(maxBytes = file.size.toLong())))
            assertEquals(1, decoded.get())
        }
    }

    @Test
    fun `the backdrop's client sends nothing that is not https, and follows no redirect off it`() {
        Server(png(8, 8)).use { server ->
            val client = backdropHttpClient { true }
            assertFailsWith<IOException> {
                client.newCall(Request.Builder().url(server.url).build()).execute().close()
            }
            assertFalse(client.followSslRedirects, "https to http is not followed")
            // The connection is made, so the hop's scheme can be seen; no request is written on it.
            assertEquals(0, server.requests.get())
        }
    }
}

package com.lagradost.cloudstream4.detail

import coil3.decode.Decoder
import coil3.decode.SkiaImageDecoder
import coil3.disk.DiskCache
import com.lagradost.cloudstream4.search.posterHttpClient
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okio.Path.Companion.toOkioPath
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * A title page's cast photos and recommendation posters (`KMP-104`): the backdrop's loader with tighter caps, a 1024 by
 * 1024 decode and 2 MB a file, and a page's worth measured.
 */
class ThumbnailImageTest {

    /** One thread answering every request with [body] as an image, counting the requests. */
    private class Server(private val body: ByteArray) : AutoCloseable {
        private val socket = ServerSocket(0, 64, InetAddress.getLoopbackAddress())
        val requests = AtomicInteger()

        init {
            thread(isDaemon = true) {
                runCatching {
                    while (true) {
                        socket.accept().use { client ->
                            val reader = client.getInputStream().bufferedReader()
                            reader.readLine() ?: return@use
                            while (!reader.readLine().isNullOrEmpty()) Unit
                            requests.incrementAndGet()
                            val head = "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                            client.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
                        }
                    }
                }
            }
        }

        /** A distinct path each time, so neither cache answers in place of the server. */
        fun url(name: String) = "http://127.0.0.1:${socket.localPort}/$name.png"

        override fun close() = socket.close()
    }

    /** A real PNG of [width] by [height], drawn by Skia. One colour, so even 1024 by 1024 is a small file. */
    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap().apply { allocN32Pixels(width, height) }
        bitmap.erase(0xFF336699.toInt())
        return checkNotNull(Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)).bytes
    }

    /** A few dozen bytes whose header claims [width] by [height]: decoding it would cost `width * height * 4`. */
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
            chunk(out, "IDAT", ByteArray(128).let { it.copyOf(deflater.deflate(it)) })
            chunk(out, "IEND", ByteArray(0))
        }
        return bytes.toByteArray()
    }

    private val decoded = AtomicInteger()

    private val watchedSkia = Decoder.Factory { result, options, loader ->
        decoded.incrementAndGet()
        SkiaImageDecoder.Factory().create(result, options, loader)
    }

    private val cacheDirectory = createTempDirectory("thumbnail-cache")

    private val cache: DiskCache by lazy {
        DiskCache.Builder().directory(cacheDirectory.toOkioPath()).maxSizeBytes(64L * 1024 * 1024).build()
    }

    @AfterTest
    fun removeCache() {
        cacheDirectory.toFile().deleteRecursively()
    }

    /** The app's thumbnail loader, built by its own function, over plain http to this machine, as a test can serve. */
    private fun localLoader() = thumbnailImageLoader(
        boundedImageClient(posterHttpClient { true }, maxBytes = THUMBNAIL_MAX_BYTES, requireHttps = false),
        then = watchedSkia,
        diskCache = { cache },
    )

    @Test
    fun `a headshot loads no larger than it is drawn`() = runBlocking<Unit> {
        Server(png(800, 800)).use { server ->
            val image = assertNotNull(loadBackdrop(server.url("face"), 40, 40, localLoader()))
            assertEquals(1, decoded.get())
            assertTrue(image.width in 40..80 && image.height in 40..80, "${image.width} x ${image.height}")
        }
    }

    @Test
    fun `1024 by 1024 decodes, and a file claiming one pixel more never reaches the decoder`() = runBlocking<Unit> {
        Server(claimingPng(1024, 1025)).use { server ->
            assertNull(loadBackdrop(server.url("tall"), 40, 40, localLoader()))
            assertEquals(1, server.requests.get(), "it was fetched")
            assertEquals(0, decoded.get(), "and refused before Skia saw it")
        }
        Server(claimingPng(20_000, 20_000)).use { server ->
            assertNull(loadBackdrop(server.url("huge"), 40, 40, localLoader()))
            assertEquals(0, decoded.get())
        }
        Server(png(1024, 1024)).use { server ->
            assertNotNull(loadBackdrop(server.url("edge"), 40, 40, localLoader()))
            assertEquals(1, decoded.get(), "exactly the cap passes")
        }
    }

    @Test
    fun `a file past 2 MB is refused, and one inside it is not`() = runBlocking<Unit> {
        val over = png(1, 1) + ByteArray((THUMBNAIL_MAX_BYTES - png(1, 1).size + 1).toInt())
        assertEquals(THUMBNAIL_MAX_BYTES + 1, over.size.toLong())
        Server(over).use { server ->
            assertNull(loadBackdrop(server.url("over"), 40, 40, localLoader()))
            assertEquals(0, decoded.get())
        }
        val inside = png(1, 1) + ByteArray((THUMBNAIL_MAX_BYTES - png(1, 1).size).toInt())
        Server(inside).use { server ->
            assertNotNull(loadBackdrop(server.url("inside"), 40, 40, localLoader()))
        }
    }

    @Test
    fun `the thumbnail's client sends nothing that is not https, and follows no redirect off it`() {
        Server(png(8, 8)).use { server ->
            val client = thumbnailHttpClient(allow = { true })
            assertFailsWith<IOException> { client.newCall(Request.Builder().url(server.url("x")).build()).execute().close() }
            assertFalse(client.followSslRedirects)
            assertEquals(0, server.requests.get())
        }
    }

    /**
     * `KMP-103`'s condition 5: a whole page, 24 headshots drawn 40 by 40 and 20 posters drawn 190 by 285 (five a line in
     * the 1280 window), every one from a 1024 by 1024 file, the largest each may be. Measured: the bitmaps kept, and the
     * peak of the JVM heap while they load. Skia's pixels are native memory, so the bitmaps are counted from their own
     * sizes; the heap peak is what the fetches and Coil's work cost on the JVM side.
     */
    @Test
    fun `a page of 24 headshots and 20 posters from the largest files keeps bitmaps of about their drawn size`() = runBlocking<Unit> {
        Server(png(1024, 1024)).use { server ->
            val loader = localLoader()
            val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
            System.gc()
            val before = pools.sumOf { it.usage.used }
            pools.forEach { it.resetPeakUsage() }
            val images = (List(24) { Triple("face$it", 40, 40) } + List(20) { Triple("poster$it", 190, 285) })
                .map { (name, w, h) -> async(Dispatchers.IO) { loadBackdrop(server.url(name), w, h, loader) } }
                .awaitAll()
            val peak = pools.sumOf { it.peakUsage.used }
            assertTrue(images.all { it != null }, "every one inside the caps loads")
            assertEquals(44, decoded.get())
            val bitmapBytes = images.sumOf { it!!.width.toLong() * it.height * 4 }
            val heapGrowth = peak - before
            println("KMP-104 page: bitmaps kept ${bitmapBytes / 1024} KiB; JVM heap peak ${heapGrowth / (1024 * 1024)} MiB over ${before / (1024 * 1024)} MiB")
            // INEXACT lets Coil keep up to twice the drawn size a side, never the file's 1024: four times the drawn area.
            val drawn = 24L * 40 * 40 * 4 + 20L * 190 * 285 * 4
            assertTrue(bitmapBytes <= drawn * 4, "bitmaps kept: $bitmapBytes bytes, against ${drawn * 4}")
            assertTrue(bitmapBytes < 44L * THUMBNAIL_MAX_PIXELS * 4 / 8, "far under 44 full decodes: $bitmapBytes bytes")
            // Each image may cost one decode of at most THUMBNAIL_MAX_PIXELS in flight; the heap bound is generous, and the
            // number printed above is the measurement.
            assertTrue(heapGrowth < 512L * 1024 * 1024, "heap peak grew by $heapGrowth bytes")
        }
    }
}

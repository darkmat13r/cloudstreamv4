package com.lagradost.cloudstream4.detail

import coil3.disk.DiskCache
import com.lagradost.cloudstream4.feature.extensions.sandbox.RemoteDetails
import com.lagradost.cloudstream4.feature.extensions.sandbox.RemoteEpisode
import com.lagradost.cloudstream4.search.posterHttpClient
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.system.measureTimeMillis
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * `KMP-116` on the Mac: an episode's still, as the sandbox's reader bound it, onto the page, and fetched only by the
 * loader the page draws it with, which refuses a private address before connecting to it (`BUG-105`).
 */
class EpisodeStillTest {

    private val cacheDirectory = createTempDirectory("episode-still-cache")

    private fun cache(): DiskCache = DiskCache.Builder().directory(cacheDirectory.toOkioPath()).build()

    @AfterTest
    fun removeCache() {
        cacheDirectory.toFile().deleteRecursively()
    }

    /** Counts the connections offered to it; answers each with [body] as an image, or closes it when there is none. */
    private class Server(private val body: ByteArray? = null) : AutoCloseable {
        private val socket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val accepted = AtomicInteger()

        init {
            thread(isDaemon = true) {
                runCatching {
                    while (true) {
                        socket.accept().use { client ->
                            accepted.incrementAndGet()
                            val image = body ?: return@use
                            val reader = client.getInputStream().bufferedReader()
                            reader.readLine() ?: return@use
                            while (!reader.readLine().isNullOrEmpty()) Unit
                            val head = "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${image.size}\r\nConnection: close\r\n\r\n"
                            client.getOutputStream().apply { write(head.toByteArray()); write(image); flush() }
                        }
                    }
                }
            }
        }

        override fun close() = socket.close()
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap().apply { allocN32Pixels(96, 56) }
        bitmap.erase(0xFF336699.toInt())
        return checkNotNull(Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)).bytes
    }

    @Test
    fun `each episode's still reaches the page on that episode, and one with none has none`() {
        val details = RemoteDetails(
            "Title", "https://site.example/t", "TvSeries", null, null, null, null,
            listOf(
                RemoteEpisode("One", "e1", 1, 1, stillUrl = "https://img.example/e1.jpg"),
                RemoteEpisode("Two", "e2", 1, 2),
            ),
        )
        val page = details.toTitleDetail("Provider")
        assertEquals(listOf("https://img.example/e1.jpg", null), page.episodes.map { it.stillUrl })
    }

    /**
     * The page draws a still with the thumbnail loader the app runs. A still at this Mac's loopback, in every way an
     * address can be written, is refused before a connection is offered: the counting server sees none.
     */
    @Test
    fun `a still at a private address is never connected to by the loader the page draws it with`() = runBlocking {
        Server().use { server ->
            val loader = thumbnailImageLoader(diskCache = ::cache)
            listOf("127.0.0.1", "[::ffff:127.0.0.1]", "[::ffff:7f00:1]", "2130706433", "0x7f000001", "localhost").forEach { host ->
                val url = "https://$host:${server.port}/still.jpg"
                assertNull(loadBackdrop(url, 96, 56, loader), url)
                Thread.sleep(SETTLE_MILLIS)
                assertEquals(0, server.accepted.get(), "a connection was offered for $url")
            }
        }
    }

    /**
     * Link-local and network addresses have nothing listening here to count, so the measure is time: a connection tried
     * to one would wait out the connect timeout, and a still refused before connecting returns at once.
     */
    @Test
    fun `a still at a link-local or network address is refused before connecting`() = runBlocking {
        val loader = thumbnailImageLoader(diskCache = ::cache)
        listOf("169.254.169.254", "10.0.0.1", "192.168.1.1", "[fe80::1]", "[::ffff:a00:1]").forEach { host ->
            val url = "https://$host/still.jpg"
            val took = measureTimeMillis { assertNull(loadBackdrop(url, 96, 56, loader), url) }
            assertTrue(took < REFUSED_WITHIN_MILLIS, "$url took $took ms: a connection was tried")
        }
    }

    /** The same loader, allowed this Mac's address as a public one would be, draws a still it is sent. */
    @Test
    fun `a still the address check allows is fetched and decoded`() = runBlocking {
        Server(png()).use { server ->
            val loader = thumbnailImageLoader(
                client = boundedImageClient(posterHttpClient { true }, maxBytes = THUMBNAIL_MAX_BYTES, requireHttps = false),
                diskCache = ::cache,
            )
            val image = assertNotNull(loadBackdrop("http://127.0.0.1:${server.port}/still.png", 96, 56, loader))
            assertTrue(image.width > 0 && image.height > 0)
            assertEquals(1, server.accepted.get())
        }
    }

    private companion object {
        const val SETTLE_MILLIS = 200L

        /** Well under the image client's 10 s connect timeout, and over a refusal's few milliseconds. */
        const val REFUSED_WITHIN_MILLIS = 2_000L
    }
}

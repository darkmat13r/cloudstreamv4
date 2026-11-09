package com.lagradost.cloudstream4.detail

import coil3.decode.Decoder
import coil3.decode.SkiaImageDecoder
import coil3.disk.DiskCache
import coil3.request.ErrorResult
import com.lagradost.cloudstream4.search.posterHttpClient
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.SocketFactory
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
import okio.Path.Companion.toOkioPath
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/** The backdrop's loader (`KMP-100`): https on every hop, and no decode past its bounds. */
class BackdropImageTest {

    private companion object {
        const val CHUNK = 8 * 1024
        const val MIB = 1024L * 1024
        const val MOVED = "<html>Moved</html>"
        /** A small cap, so the test is quick: the code under test is the same for 8 MB. */
        const val WIRE_CAP = 256L * 1024

    }

    /** One thread answering every request with [body] as an image, [encoding] if one is given, counting the requests. */
    private class Server(private val body: ByteArray, private val encoding: String? = null) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val port: Int = socket.localPort
        val requests = AtomicInteger()

        init {
            thread(isDaemon = true) {
                runCatching {
                    while (true) {
                        socket.accept().use { client ->
                            val reader = client.getInputStream().bufferedReader()
                            val line = reader.readLine() ?: return@use
                            while (!reader.readLine().isNullOrEmpty()) Unit
                            requests.incrementAndGet()
                            if (line.startsWith("GET /moved ")) {
                                // A redirect with a body of its own, as servers write them, which OkHttp closes unread.
                                val moved = "HTTP/1.1 302 Found\r\nLocation: /b.png\r\nContent-Length: ${MOVED.length}\r\n" +
                                    "Connection: close\r\n\r\n$MOVED"
                                client.getOutputStream().apply { write(moved.toByteArray()); flush() }
                                return@use
                            }
                            val head = "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${body.size}\r\n" +
                                encoding.orEmpty().let { if (it.isEmpty()) "" else "Content-Encoding: $it\r\n" } +
                                "Connection: close\r\n\r\n"
                            client.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
                        }
                    }
                }
            }
        }

        val url get() = "http://127.0.0.1:$port/b.png"

        /** Redirects to [url], with a body. */
        val moved get() = "http://127.0.0.1:$port/moved"

        override fun close() = socket.close()
    }

    /**
     * One request answered with [total] bytes, written 8 KiB at a time until the reader stops taking them, with a
     * `Content-Length` or chunked. [sent] is what the socket accepted, so what a client let in plus the socket's buffers.
     */
    private class Streamer(private val total: Long, private val declared: Boolean) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val sent = java.util.concurrent.atomic.AtomicLong()
        private val done = java.util.concurrent.CountDownLatch(1)

        init {
            thread(isDaemon = true) {
                runCatching {
                    socket.accept().use { client ->
                        val reader = client.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) Unit
                        val out = client.getOutputStream()
                        val length = if (declared) "Content-Length: $total" else "Transfer-Encoding: chunked"
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\n$length\r\nConnection: close\r\n\r\n".toByteArray())
                        val chunk = ByteArray(CHUNK)
                        while (sent.get() < total) {
                            if (declared) out.write(chunk) else {
                                out.write("${Integer.toHexString(CHUNK)}\r\n".toByteArray()); out.write(chunk); out.write("\r\n".toByteArray())
                            }
                            sent.addAndGet(CHUNK.toLong())
                        }
                        if (!declared) out.write("0\r\n\r\n".toByteArray())
                        out.flush()
                    }
                }
                done.countDown()
            }
        }

        val url get() = "http://127.0.0.1:${socket.localPort}/big.png"

        /** Waits for the writer to stop: it has sent all, or the client closed and a write failed. */
        fun finished() = check(done.await(30, java.util.concurrent.TimeUnit.SECONDS)) { "the server is still writing" }

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

    /**
     * Coil's disk cache for this test alone, one for all its loaders, so nothing it fetches lands in the one the app's
     * loaders share.
     */
    private val cacheDirectory = createTempDirectory("backdrop-cache")

    private val cache: DiskCache by lazy {
        DiskCache.Builder().directory(cacheDirectory.toOkioPath()).maxSizeBytes(64L * 1024 * 1024).build()
    }

    @AfterTest
    fun removeCache() {
        cacheDirectory.toFile().deleteRecursively()
    }

    /**
     * The backdrop's loader, built by the function the app's is, over plain http to this machine, as a test can serve:
     * its body cap and decoder are what is checked here, the https rule in its own test.
     */
    private fun localLoader(maxBytes: Long = BACKDROP_MAX_BYTES, wireCap: Long = BACKDROP_MAX_BYTES) =
        backdropImageLoader(
            boundedImageClient(
                posterHttpClient { true }.newBuilder().socketFactory(counting).build(),
                maxBytes = wireCap,
                requireHttps = false,
            ),
            BoundedDecoder.Factory(maxBytes = maxBytes, then = watchedSkia),
            diskCache = { cache },
        )

    /**
     * What the client took off its sockets, headers and all. The server's own count is no measure of that: the system's
     * socket buffers take hundreds of KiB on loopback past a reader that has stopped (measured: 384 to 530 KiB, and asking
     * for 64 KiB a side did not change it).
     */
    private val received = java.util.concurrent.atomic.AtomicLong()

    private val counting = object : SocketFactory() {
        override fun createSocket(): Socket = object : Socket() {
            private val counted by lazy {
                object : java.io.FilterInputStream(super.getInputStream()) {
                    override fun read(): Int = super.read().also { if (it >= 0) received.incrementAndGet() }
                    override fun read(b: ByteArray, off: Int, len: Int): Int =
                        super.read(b, off, len).also { if (it > 0) received.addAndGet(it.toLong()) }
                }
            }

            override fun getInputStream(): java.io.InputStream = counted
        }
        override fun createSocket(host: String, port: Int) = notUsed()
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int) = notUsed()
        override fun createSocket(host: InetAddress, port: Int) = notUsed()
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int) = notUsed()
        private fun notUsed(): Socket = throw UnsupportedOperationException("OkHttp makes the socket unconnected")
    }

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
    fun `a body past the cap is not read past it, with a length or chunked`() = runBlocking<Unit> {
        listOf(true, false).forEach { declared ->
            Streamer(total = WIRE_CAP + MIB, declared = declared).use { server ->
                // A refusal that never reaches Coil leaves the load waiting for ever: bounded here, so that fails too.
                val result = withTimeout(60_000) {
                    localLoader(wireCap = WIRE_CAP).execute(backdropRequest(server.url, 400, 200))
                }
                val error = assertIs<ErrorResult>(result, "declared=$declared").throwable
                assertTrue("past $WIRE_CAP bytes" in error.message.orEmpty(), "declared=$declared: $error")
                server.close()
                server.finished()
                val sent = server.sent.get()
                val took = received.getAndSet(0)
                // The client stopped the server short of its body. It took the read that brought the headers and, with no
                // length to refuse, the cap and the read that passed it (measured: 8192, and the cap plus 8283).
                assertTrue(sent < WIRE_CAP + MIB, "declared=$declared: the server sent all $sent")
                val most = if (declared) CHUNK.toLong() else WIRE_CAP + CHUNK + CHUNK
                assertTrue(took <= most, "declared=$declared took $took, past $most")
                assertEquals(0, decoded.get(), "nothing reached the decoder")
            }
        }
    }

    @Test
    fun `an image behind a redirect with a body loads`() = runBlocking<Unit> {
        Server(png(64, 64)).use { server ->
            // A redirect's body is closed unread inside the call: were that to cancel it, Coil would wait for ever.
            val result = withTimeout(60_000) { localLoader().execute(backdropRequest(server.moved, 64, 64)) }
            assertIs<coil3.request.SuccessResult>(result, "$result")
            assertEquals(2, server.requests.get())
        }
    }

    /**
     * One hop through the cap, with a body of 100 bytes that states its length, [read] as given and then closed: over
     * [protocol], on a connection whose socket is [socket]. Returns whether the call was cancelled.
     *
     * The chain is made by hand: a test server for HTTP/2 would be a new dependency, and OkHttp's own HTTP/2 classes
     * differ between the version this compiles against and the one it runs with (measured: `NoSuchMethodError`).
     */
    private fun throughCap(protocol: Protocol, socket: Socket, read: (okio.BufferedSource) -> Unit = {}): Boolean {
        var cancelled = false
        fun <T> fake(type: Class<T>, answer: (String, Array<out Any?>) -> Any?): T = type.cast(
            java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                answer(method.name, args ?: emptyArray())
            }
        )
        val request = Request.Builder().url("https://img.example/moved").build()
        val call = fake(okhttp3.Call::class.java) { name, _ ->
            check(name == "cancel") { "not asked of the call: $name" }
            cancelled = true
            null
        }
        val connection = fake(okhttp3.Connection::class.java) { name, _ ->
            when (name) {
                "protocol" -> protocol
                "socket" -> socket
                else -> error("not asked of the connection: $name")
            }
        }
        val chain = fake(okhttp3.Interceptor.Chain::class.java) { name, args ->
            when (name) {
                "request" -> request
                "call" -> call
                "connection" -> connection
                "proceed" -> okhttp3.Response.Builder().request(args[0] as okhttp3.Request).protocol(protocol).code(302)
                    .message("Found").body(hundredBytes()).build()
                else -> error("not asked of the chain: $name")
            }
        }
        bodyCap(BACKDROP_MAX_BYTES).intercept(chain).use { response -> read(response.body!!.source()) }
        return cancelled
    }

    /** 100 bytes, stating their length, that come at most 99 at a time, as a network's do in pieces. */
    private fun hundredBytes(): okhttp3.ResponseBody {
        val bytes = okio.Buffer().write(ByteArray(100))
        val source = object : okio.Source {
            override fun read(sink: okio.Buffer, byteCount: Long) = bytes.read(sink, minOf(byteCount, 99L))
            override fun timeout() = okio.Timeout.NONE
            override fun close() = Unit
        }
        return source.buffer().asResponseBody(contentType = null, contentLength = 100)
    }

    @Test
    fun `a body closed unread never cancels its call, and closes only an HTTP 1 hop's socket`() {
        // Cancelling would leave Coil waiting for ever. An HTTP/1 hop's socket is closed, so OkHttp does not drain the
        // rest of the body; an HTTP/2 stream is reset by OkHttp's own close, and its socket carries other streams.
        listOf(Protocol.HTTP_1_1, Protocol.HTTP_1_0, Protocol.HTTP_2, Protocol.H2_PRIOR_KNOWLEDGE).forEach { protocol ->
            Socket().use { socket ->
                assertFalse(throughCap(protocol, socket), "$protocol: the call was cancelled")
                val http1 = protocol == Protocol.HTTP_1_1 || protocol == Protocol.HTTP_1_0
                assertEquals(http1, socket.isClosed, "$protocol: socket closed")
            }
        }
    }

    @Test
    fun `a body read to its stated length leaves its socket open, as OkHttp has pooled it`() {
        // OkHttp returns the connection to its pool on the read that takes the last byte, before any end is read; the
        // socket may already be another call's.
        Socket().use { socket ->
            assertFalse(throughCap(Protocol.HTTP_1_1, socket) { source -> source.require(100) })
            assertFalse(socket.isClosed, "the socket of a body read whole was closed")
        }
        Socket().use { socket ->
            throughCap(Protocol.HTTP_1_1, socket) { source -> source.require(99) }
            assertTrue(socket.isClosed, "one byte short is a body left before its end")
        }
    }

    @Test
    fun `a gzipped body is capped as it is once decompressed`() = runBlocking<Unit> {
        val zeros = ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(ByteArray((WIRE_CAP + MIB).toInt())) }
        }.toByteArray()
        assertTrue(zeros.size < WIRE_CAP / 16, "${zeros.size} bytes on the wire")
        Server(zeros, encoding = "gzip").use { server ->
            val result = withTimeout(60_000) {
                localLoader(wireCap = WIRE_CAP).execute(backdropRequest(server.url, 400, 200))
            }
            val error = assertIs<ErrorResult>(result).throwable
            assertTrue("past $WIRE_CAP bytes" in error.message.orEmpty(), "$error")
            assertEquals(0, decoded.get(), "nothing reached the decoder")
        }
    }

    @Test
    fun `the backdrop's client sends nothing that is not https, and follows no redirect off it`() {
        Server(png(8, 8)).use { server ->
            val client = backdropHttpClient(allow = { true })
            assertFailsWith<IOException> {
                client.newCall(Request.Builder().url(server.url).build()).execute().close()
            }
            assertFalse(client.followSslRedirects, "https to http is not followed")
            // The connection is made, so the hop's scheme can be seen; no request is written on it.
            assertEquals(0, server.requests.get())
        }
    }
}

package com.lagradost.cloudstream4.media.download

import com.lagradost.cloudstream4.media.model.DownloadFailure
import com.lagradost.cloudstream4.media.model.DownloadState
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.vinceglb.filekit.PlatformFile
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The engine's own quick check: a real local server, real files. The adversarial suite is separate. */
class EngineSmokeTest {
    private val root: File = Files.createTempDirectory("csdl-smoke").toFile()
    private val client = HttpClient(OkHttp)
    private val body = Random(7).nextBytes(5 * 1024 * 1024 + 123)
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val ranges = java.util.concurrent.atomic.AtomicBoolean(true)

    init {
        server.executor = Executors.newFixedThreadPool(16)
        server.createContext("/file") { ex -> serve(ex) }
        server.createContext("/gone") { ex ->
            ex.sendResponseHeaders(404, -1)
            ex.close()
        }
        server.start()
    }

    private fun serve(ex: HttpExchange) {
        ex.responseHeaders.add("ETag", "\"v1\"")
        ex.responseHeaders.add("Content-Type", "application/octet-stream")
        val header = ex.requestHeaders.getFirst("Range")
        if (header != null && ranges.get()) {
            val (a, b) = header.removePrefix("bytes=").split('-')
            val start = a.toInt()
            val end = minOf(b.toInt(), body.size - 1)
            ex.responseHeaders.add("Content-Range", "bytes $start-$end/${body.size}")
            ex.sendResponseHeaders(206, (end - start + 1).toLong())
            ex.responseBody.use { it.write(body, start, end - start + 1) }
        } else {
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
    }

    @AfterTest
    fun cleanUp() {
        server.stop(0)
        client.close()
        root.deleteRecursively()
    }

    private fun request(name: String, path: String = "/file") = DownloadRequest(
        url = "http://127.0.0.1:${server.address.port}$path",
        folder = PlatformFile(root),
        fileName = name,
        options = DownloadOptions(parts = 8, minPartBytes = 256 * 1024, allowPrivateAddresses = true, backoffBaseMillis = 10, backoffMaxMillis = 50),
    )

    @Test
    fun `eight ranged parts join into the exact bytes`() = runBlocking {
        val last = SegmentedDownloader(client).download(request("ranged.bin")).last()
        assertEquals(DownloadState.COMPLETED, last.state, "failure=${last.failure}")
        assertTrue(last.sizeVerified)
        assertTrue(last.parts.size >= 8, "parts=${last.parts.size}")
        assertNotNull(last.file)
        assertContentEquals(body, File(root, "ranged.bin").readBytes())
        assertTrue(root.listFiles()!!.none { it.name.endsWith(".csdl") }, "temp left behind")
    }

    @Test
    fun `a server without ranges falls back to one stream`() = runBlocking {
        ranges.set(false)
        val last = SegmentedDownloader(client).download(request("single.bin")).last()
        assertEquals(DownloadState.COMPLETED, last.state, "failure=${last.failure}")
        assertContentEquals(body, File(root, "single.bin").readBytes())
    }

    @Test
    fun `a 404 is a refusal and writes no file`() = runBlocking {
        val last = SegmentedDownloader(client).download(request("none.bin", "/gone")).last()
        assertEquals(DownloadState.FAILED, last.state)
        assertEquals(DownloadFailure.ServerRefused(404), last.failure)
        assertTrue(!File(root, "none.bin").exists())
    }

    @Test
    fun `a session completes and joins`() = runBlocking {
        val session = SegmentedDownloader(client).start(this, request("session.bin"))
        val last = session.join()
        assertEquals(DownloadState.COMPLETED, last.state, "failure=${last.failure}")
        assertContentEquals(body, File(root, "session.bin").readBytes())
    }

}

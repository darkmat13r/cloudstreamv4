package com.lagradost.cloudstream4.player

import com.lagradost.cloudstream4.media.model.SubtitleSource
import com.lagradost.cloudstream4.media.model.SubtitleTrack
import com.lagradost.cloudstream4.media.subtitle.SubtitleFormat
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.Charset
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** A plugin chooses these URLs and the bytes behind them, so most of this is what it might try. */
class SubtitleLoaderTest {

    private class Server(private val respond: (Int) -> ByteArray) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val port = socket.localPort
        val connections = AtomicInteger()
        val requests: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())

        init {
            thread(isDaemon = true) {
                runCatching {
                    while (true) {
                        socket.accept().use { client ->
                            connections.incrementAndGet()
                            val reader = client.getInputStream().bufferedReader()
                            val first = reader.readLine() ?: return@use
                            val lines = mutableListOf(first)
                            while (true) {
                                val line = reader.readLine()
                                if (line.isNullOrEmpty()) break
                                lines += line
                            }
                            requests += lines
                            runCatching { client.getOutputStream().write(respond(port)) }
                        }
                    }
                }
            }
        }

        override fun close() = socket.close()
    }

    private val srt = "1\n00:00:01,000 --> 00:00:02,000\nhello\n"

    private fun ok(body: ByteArray, headers: String = "") =
        ("HTTP/1.1 200 OK\r\n${headers}Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray() + body

    private fun track(server: Server, path: String = "/s", tag: String? = null, headers: Map<String, String> = emptyMap()) =
        SubtitleTrack(SubtitleSource.Remote("http://127.0.0.1:${server.port}$path", headers), "x", tag)

    /** Allows this test's loopback server, the way the real one allows a public address. */
    private fun loader(vararg ports: Int, client: (OkHttpClient) -> OkHttpClient = { it }) = HttpSubtitleLoader(
        client = client(HttpSubtitleLoader.subtitleHttpClient { it.port in ports }),
        allowUrl = { true },
    )

    private fun load(loader: SubtitleLoader, track: SubtitleTrack) = runBlocking { loader.load(track) }

    private fun assertFailed(reason: SubtitleFailure, load: SubtitleLoad) =
        assertEquals(reason, assertIs<SubtitleLoad.Failed>(load).reason)

    @Test
    fun `a file arrives and is parsed`() {
        Server { ok(srt.toByteArray()) }.use { server ->
            val ready = assertIs<SubtitleLoad.Ready>(load(loader(server.port), track(server)))
            assertEquals(listOf("hello"), ready.timeline.cues.single().text)
            assertEquals(SubtitleFormat.SRT, ready.report.format)
        }
    }

    @Test
    fun `the real loader opens nothing for an address on this machine`() {
        Server { ok(srt.toByteArray()) }.use { server ->
            assertFailed(SubtitleFailure.Fetch, load(HttpSubtitleLoader(), track(server)))
            Thread.sleep(100)
            assertEquals(0, server.connections.get(), "not even a TCP connection")
        }
    }

    @Test
    fun `the url check alone is not what stops it, the client refuses too`() {
        Server { ok(srt.toByteArray()) }.use { server ->
            val noUrlCheck = HttpSubtitleLoader(allowUrl = { true })
            assertFailed(SubtitleFailure.Fetch, load(noUrlCheck, track(server)))
            assertEquals(0, server.requests.size, "no request reached it")
        }
    }

    @Test
    fun `a redirect to a refused address sends it nothing, and the control passes`() {
        Server { ok(srt.toByteArray()) }.use { target ->
            val redirect = "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${target.port}/x\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
            Server { redirect }.use { start ->
                assertFailed(SubtitleFailure.Fetch, load(loader(start.port), track(start)))
                assertEquals(1, start.requests.size)
                assertEquals(0, target.requests.size)

                assertIs<SubtitleLoad.Ready>(load(loader(start.port, target.port), track(start)))
                assertEquals(1, target.requests.size)
            }
        }
    }

    @Test
    fun `sends the two headers a track may carry, and no cookie it was given before`() {
        Server {
            ok(srt.toByteArray(), "Set-Cookie: session=abc\r\n")
        }.use { server ->
            val loader = loader(server.port)
            val first = track(server, headers = mapOf("Referer" to "https://example.com/", "User-Agent" to "ua"))
            load(loader, first)
            load(loader, first)
            val second = server.requests[1]
            assertTrue(second.any { it == "Referer: https://example.com/" }, "$second")
            assertTrue(second.any { it == "User-Agent: ua" }, "$second")
            assertTrue(second.none { it.startsWith("Cookie", ignoreCase = true) }, "$second")
        }
    }

    private fun redirectTo(location: String) =
        "HTTP/1.1 302 Found\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()

    private companion object {
        const val SLOW_HOP_MILLIS = 300L
        const val CHAIN_LIMIT_MILLIS = 700L
    }

    private val pluginHeaders = mapOf("Referer" to "https://example.com/?token=t", "User-Agent" to "ua")

    @Test
    fun `a redirect is checked as the first url was, and a refused one is never opened`() {
        // BUG-94 (A-5): okhttp followed redirects itself, so only the client's address checks met them, never the
        // URL check. A loopback server on another port stands in for a private address.
        Server { ok(srt.toByteArray()) }.use { target ->
            Server { redirectTo("http://127.0.0.1:${target.port}/private") }.use { start ->
                val loader = HttpSubtitleLoader(
                    client = HttpSubtitleLoader.subtitleHttpClient { it.port in setOf(start.port, target.port) },
                    allowUrl = { "/private" !in it },
                )
                assertFailed(SubtitleFailure.Fetch, load(loader, track(start)))
                assertEquals(1, start.requests.size)
                assertEquals(0, target.connections.get(), "not even a TCP connection")
            }
        }
    }

    @Test
    fun `the track's headers go to its own host, and not to the host a redirect names`() {
        Server { ok(srt.toByteArray()) }.use { other ->
            // 127.0.0.1 and localhost are two hosts to okhttp, as two names on the internet would be.
            Server { redirectTo("http://localhost:${other.port}/s") }.use { start ->
                // The DNS filter asks with port 0, so a name needs that too; the connection check still names the port.
                val loader = HttpSubtitleLoader(
                    client = HttpSubtitleLoader.subtitleHttpClient { it.port == 0 || it.port in setOf(start.port, other.port) },
                    allowUrl = { true },
                )
                assertIs<SubtitleLoad.Ready>(load(loader, track(start, headers = pluginHeaders)))
                assertTrue(start.requests.single().any { it == "User-Agent: ua" }, "${start.requests}")
                val moved = other.requests.single()
                assertTrue(moved.none { it.startsWith("Referer", ignoreCase = true) }, "$moved")
                assertTrue(moved.none { it == "User-Agent: ua" }, "$moved")
            }
        }
    }

    @Test
    fun `a redirect on the same host keeps the track's headers`() {
        // A path on the same server: another port would be another origin.
        var first = true
        Server { if (first) { first = false; redirectTo("/s") } else ok(srt.toByteArray()) }.use { server ->
            assertIs<SubtitleLoad.Ready>(load(loader(server.port), track(server, path = "/a", headers = pluginHeaders)))
            val (a, s) = server.requests.toList()
            assertTrue(a.first().startsWith("GET /a ") && s.first().startsWith("GET /s "), "${server.requests}")
            assertTrue(s.any { it == "Referer: https://example.com/?token=t" } && s.any { it == "User-Agent: ua" }, "$s")
        }
    }

    @Test
    fun `a redirect that never ends stops after a few`() {
        Server { redirectTo("/again") }.use { server ->
            assertFailed(SubtitleFailure.Fetch, load(loader(server.port), track(server)))
            assertEquals(HttpSubtitleLoader.MAX_REDIRECTS + 1, server.requests.size)
        }
    }

    @Test
    fun `https to http is refused, and the headers stay on their host or its upgrade`() {
        fun hop(from: String, to: String) = HttpSubtitleLoader.nextHop(from.toHttpUrl(), to.toHttpUrl())
        assertEquals(null, hop("https://a.example/s", "http://a.example/s"), "a downgrade, even on the same host")
        assertEquals(true, hop("https://a.example/s", "https://a.example/t")?.keepsHeaders)
        assertEquals(true, hop("http://a.example/s", "https://a.example/s")?.keepsHeaders, "an upgrade")
        assertEquals(false, hop("https://a.example/s", "https://b.example/s")?.keepsHeaders)
        assertEquals(false, hop("https://a.example/s", "https://a.example:8443/s")?.keepsHeaders, "another port")
        assertEquals(false, hop("http://a.example/s", "http://a.example.evil/s")?.keepsHeaders)
    }

    @Test
    fun `a header okhttp cannot send costs the header and not the track`() {
        Server { ok(srt.toByteArray()) }.use { server ->
            val hostile = track(server, headers = mapOf("Referer" to "bad\r\nX-Injected: 1", "User-Agent" to "é"))
            assertIs<SubtitleLoad.Ready>(load(loader(server.port), hostile))
            assertTrue(server.requests.single().none { it.startsWith("X-Injected") })
        }
    }

    @Test
    fun `an error status is a failed fetch even with a subtitle in the body`() {
        val forbidden = "HTTP/1.1 403 Forbidden\r\nContent-Length: ${srt.length}\r\nConnection: close\r\n\r\n$srt".toByteArray()
        Server { forbidden }.use { server ->
            assertFailed(SubtitleFailure.Fetch, load(loader(server.port), track(server)))
        }
    }

    @Test
    fun `a url okhttp cannot parse is a failed fetch`() {
        val bad = SubtitleTrack(SubtitleSource.Remote("not a url"), "x")
        assertFailed(SubtitleFailure.Fetch, load(HttpSubtitleLoader(allowUrl = { true }), bad))
    }

    @Test
    fun `a track that is not remote is a failed fetch`() {
        assertFailed(SubtitleFailure.Fetch, load(HttpSubtitleLoader(), SubtitleTrack(SubtitleSource.Local("/etc/passwd"), "x")))
    }

    @Test
    fun `a body that says it is too big is not read`() {
        val claim = "HTTP/1.1 200 OK\r\nContent-Length: ${HttpSubtitleLoader.MAX_BYTES + 1}\r\nConnection: close\r\n\r\n".toByteArray()
        Server { claim }.use { server ->
            assertFailed(SubtitleFailure.Fetch, load(loader(server.port), track(server)))
        }
    }

    @Test
    fun `a huge body with no length stops at the bound`() {
        val chunk = ByteArray(1024 * 1024) { 'x'.code.toByte() }
        Server {
            val head = "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".toByteArray()
            ByteArrayOutputStream().apply {
                write(head)
                repeat(32) { write(chunk) }
            }.toByteArray()
        }.use { server ->
            assertFailed(SubtitleFailure.Fetch, load(loader(server.port), track(server)))
        }
    }

    @Test
    fun `exactly the bound is read`() {
        val body = (srt + " ".repeat(HttpSubtitleLoader.MAX_BYTES - srt.length)).toByteArray()
        Server { ok(body) }.use { server ->
            assertIs<SubtitleLoad.Ready>(load(loader(server.port), track(server)))
        }
    }

    private fun gzip(bytes: ByteArray) = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(bytes) }
    }.toByteArray()

    private fun bomb() = gzip(ByteArray(64 * 1024 * 1024) { 'x'.code.toByte() })

    @Test
    fun `gzip is unpacked, whichever way it arrives`() {
        val packed = gzip(srt.toByteArray())
        Server { ok(packed) }.use { server ->
            assertIs<SubtitleLoad.Ready>(load(loader(server.port), track(server)))
        }
        Server { ok(packed, "Content-Encoding: gzip\r\n") }.use { server ->
            assertIs<SubtitleLoad.Ready>(load(loader(server.port), track(server)))
        }
    }

    @Test
    fun `a gzip bomb stops at the bound, whichever way it arrives`() {
        val bomb = bomb()
        assertTrue(bomb.size < HttpSubtitleLoader.MAX_BYTES, "small on the wire: ${bomb.size}")
        Server { ok(bomb) }.use { server ->
            assertFailed(SubtitleFailure.Fetch, load(loader(server.port), track(server)))
        }
        Server { ok(bomb, "Content-Encoding: gzip\r\n") }.use { server ->
            assertFailed(SubtitleFailure.Fetch, load(loader(server.port), track(server)))
        }
    }

    @Test
    fun `a corrupt gzip fails as a format, without throwing`() {
        val corrupt = byteArrayOf(0x1F, 0x8B.toByte(), 8, 0, 1, 2, 3)
        assertFailed(SubtitleFailure.UnsupportedFormat, HttpSubtitleLoader.parse(corrupt, null, null))
        Server { ok(corrupt) }.use { server ->
            assertFailed(SubtitleFailure.UnsupportedFormat, load(loader(server.port), track(server)))
        }
    }

    @Test
    fun `a server that stops sending fails inside the time bound`() {
        val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val held = Collections.synchronizedList(mutableListOf<java.net.Socket>())
        thread(isDaemon = true) {
            runCatching {
                while (true) {
                    val client = socket.accept()
                    held += client
                    client.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 999\r\n\r\npartial".toByteArray())
                    client.getOutputStream().flush()
                }
            }
        }
        socket.use {
            val quick = loader(socket.localPort) { it.newBuilder().callTimeout(300, TimeUnit.MILLISECONDS).build() }
            val started = System.nanoTime()
            val result = load(quick, SubtitleTrack(SubtitleSource.Remote("http://127.0.0.1:${socket.localPort}/s"), "x"))
            assertFailed(SubtitleFailure.Fetch, result)
            assertTrue(System.nanoTime() - started < 5_000_000_000L)
        }
    }

    @Test
    fun `a chain of slow redirects is cut at the whole load's limit, not one limit a hop`() {
        // Each hop is its own call, so the client's limit alone bounded one hop: 6 hops of 20 s were 120 s.
        Server { Thread.sleep(SLOW_HOP_MILLIS); redirectTo("/again") }.use { server ->
            val quick = loader(server.port) { it.newBuilder().callTimeout(CHAIN_LIMIT_MILLIS, TimeUnit.MILLISECONDS).build() }
            val started = System.nanoTime()
            assertFailed(SubtitleFailure.Fetch, load(quick, track(server)))
            val waited = (System.nanoTime() - started) / 1_000_000
            // Six hops would take 6 x 300 ms; the limit is 700 ms for all of them.
            assertTrue(waited < CHAIN_LIMIT_MILLIS + SLOW_HOP_MILLIS, "took $waited ms")
            assertTrue(server.requests.size < HttpSubtitleLoader.MAX_REDIRECTS + 1, "${server.requests.size} hops")
        }
    }

    @Test
    fun `the time bound is set on the real client`() {
        assertEquals(HttpSubtitleLoader.CALL_TIMEOUT_SECONDS * 1000, HttpSubtitleLoader.subtitleHttpClient().callTimeoutMillis.toLong())
    }

    @Test
    fun `the real client keeps no cookies and no cache`() {
        val client = HttpSubtitleLoader.subtitleHttpClient()
        assertEquals(okhttp3.CookieJar.NO_COOKIES, client.cookieJar)
        assertEquals(null, client.cache)
    }

    @Test
    fun `an ass file says so, and so does a web page`() {
        val ass = "[Script Info]\nTitle: x\n\n[Events]\nDialogue: 0,0:00:01.00,0:00:02.00,x".toByteArray()
        assertFailed(SubtitleFailure.UnsupportedFormat, HttpSubtitleLoader.parse(ass, null, null))
        assertFailed(SubtitleFailure.UnsupportedFormat, HttpSubtitleLoader.parse("<html>nope</html>".toByteArray(), null, null))
    }

    @Test
    fun `a file with no cues says so, and an empty body says that it is not a format`() {
        assertFailed(SubtitleFailure.NoCues, HttpSubtitleLoader.parse("WEBVTT\n\n".toByteArray(), null, null))
        assertFailed(SubtitleFailure.NoCues, HttpSubtitleLoader.parse("<tt><body></body></tt>".toByteArray(), null, null))
        assertFailed(SubtitleFailure.UnsupportedFormat, HttpSubtitleLoader.parse(ByteArray(0), null, null))
    }

    @Test
    fun `random bytes never throw`() {
        val random = java.util.Random(25)
        repeat(200) {
            val bytes = ByteArray(random.nextInt(4096)).also(random::nextBytes)
            HttpSubtitleLoader.parse(bytes, null, null)
        }
    }

    private fun cueText(bytes: ByteArray, declared: Charset?, tag: String?) =
        assertIs<SubtitleLoad.Ready>(HttpSubtitleLoader.parse(bytes, declared, tag)).timeline.cues.single().text.single()

    private val timing = "00:00:01,000 --> 00:00:02,000\n"

    @Test
    fun `a bom decides the encoding, even against what the server declared`() {
        val utf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + (timing + "é").toByteArray()
        assertEquals("é", cueText(utf8, Charsets.ISO_8859_1, null))
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + (timing + "é").toByteArray(Charsets.UTF_16LE)
        assertEquals("é", cueText(utf16, null, null))
    }

    @Test
    fun `valid utf8 is utf8 whatever the language`() {
        assertEquals("مرحبا", cueText((timing + "مرحبا").toByteArray(), null, "ar"))
    }

    @Test
    fun `not utf8 falls to what the server declared, then to the track's language`() {
        val cyrillic = (timing + "привет").toByteArray(Charset.forName("windows-1251"))
        // A language that would read these bytes as something else, so only the declaration can get it right.
        assertEquals("привет", cueText(cyrillic, Charset.forName("windows-1251"), "en"))
        assertEquals("привет", cueText(cyrillic, null, "ru"))
        val latin = (timing + "é").toByteArray(Charsets.ISO_8859_1)
        assertEquals("é", cueText(latin, null, null))
    }

    @Test
    fun `the language table names only charsets this JVM knows, or falls back`() {
        for (tag in listOf("ar", "ru", "pl", "el", "he", "iw", "tr", "th", "vi", "ja", "ko", "zh", "en", null, "zz", "ar-EG")) {
            HttpSubtitleLoader.legacyCharsetFor(tag)
        }
        assertEquals("windows-1256", HttpSubtitleLoader.legacyCharsetFor("ar-EG").name())
    }
}

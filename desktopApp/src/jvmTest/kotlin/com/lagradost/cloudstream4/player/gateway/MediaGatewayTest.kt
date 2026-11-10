package com.lagradost.cloudstream4.player.gateway

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.PrintStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.Dns

/**
 * `KMP-113`: the gateway against local servers. Made-up names resolve to loopback through an injected lookup, and the
 * address check is told which of those servers count as public. Every test is bounded at 60 s.
 */
class MediaGatewayTest {

    /** One request an upstream server saw: its target and its headers, names lowercased. */
    private class Seen(val target: String, val headers: Map<String, String>)

    private class Reply(val code: Int, val headers: List<Pair<String, String>> = emptyList(), val body: ByteArray = ByteArray(0))

    /** An upstream on loopback: answers every request with [route], one request per connection, and records it. */
    private class Upstream(private val route: (Seen) -> Reply) : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int = socket.localPort
        val seen = CopyOnWriteArrayList<Seen>()

        init {
            thread(isDaemon = true) {
                runCatching {
                    while (true) {
                        val client = socket.accept()
                        thread(isDaemon = true) { client.use(::answer) }
                    }
                }
            }
        }

        private fun answer(client: Socket) = runCatching {
            val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val line = reader.readLine() ?: return@runCatching
            val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }
                .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val request = Seen(line.split(' ')[1], headers)
            seen += request
            val reply = route(request)
            val out = client.getOutputStream()
            val head = buildString {
                append("HTTP/1.1 ${reply.code} X\r\n")
                reply.headers.forEach { (k, v) -> append("$k: $v\r\n") }
                if (reply.headers.none { it.first.equals("Content-Length", true) }) append("Content-Length: ${reply.body.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(reply.body)
            out.flush()
        }

        override fun close() = socket.close()
    }

    /** What libVLC would see for one request: its status, its headers (names lowercased) and its body. */
    private class Answer(val code: Int, val headers: Map<String, String>, val body: ByteArray) {
        val text get() = String(body, Charsets.UTF_8)
    }

    private val opened = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() = opened.forEach { runCatching { it.close() } }

    private fun <T : AutoCloseable> T.closing(): T = also { opened += it }

    private fun upstream(route: (Seen) -> Reply) = Upstream(route).closing()

    /** Names to loopback, so cookies can tell hosts apart and the DNS filter has something to filter. */
    private val names = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (hostname.endsWith(".example")) listOf(InetAddress.getByAddress(hostname, byteArrayOf(127, 0, 0, 1)))
            else throw java.net.UnknownHostException()
    }

    private fun gateway(
        allowed: Set<Int>,
        referer: String? = "https://site.example/page",
        userAgent: String? = "GatewayTest/1",
        limits: MediaGateway.Limits = MediaGateway.Limits(),
    ): MediaGateway = assertNotNull(
        MediaGateway.start(referer, userAgent, allow = { it.port == 0 || it.port in allowed }, limits = limits, lookup = names)
    ).closing()

    /** A refused name: resolves, then the address check says no. Port 0 is what the DNS filter is asked about. */
    private fun refusingNames(gatewayAllowed: Set<Int>, refused: String, limits: MediaGateway.Limits = MediaGateway.Limits()) =
        assertNotNull(
            MediaGateway.start(
                "https://site.example/page", "GatewayTest/1",
                allow = { (it.port == 0 && it.hostString != refused) || it.port in gatewayAllowed },
                limits = limits, lookup = names,
            )
        ).closing()

    private fun ask(gateway: MediaGateway, target: String, headers: List<Pair<String, String>> = emptyList(), method: String = "GET", v6: Boolean = false): Answer {
        val address = if (v6) Inet6Address.getByName("::1") else InetAddress.getByName("127.0.0.1")
        Socket(address, gateway.port).use { socket ->
            socket.soTimeout = 30_000
            val head = buildString {
                append("$method $target HTTP/1.1\r\nHost: 127.0.0.1:${gateway.port}\r\n")
                headers.forEach { (k, v) -> append("$k: $v\r\n") }
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().write(head.toByteArray(Charsets.ISO_8859_1))
            return read(socket.getInputStream(), method == "HEAD")
        }
    }

    private fun read(input: InputStream, headOnly: Boolean): Answer {
        val raw = input.readBytes()
        val split = raw.indexOfSequence("\r\n\r\n".toByteArray())
        val head = String(raw, 0, split, Charsets.ISO_8859_1).split("\r\n")
        val code = head.first().split(' ')[1].toInt()
        val headers = head.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val body = if (headOnly) ByteArray(0) else raw.copyOfRange(split + 4, raw.size)
        return Answer(code, headers, body)
    }

    private fun ByteArray.indexOfSequence(needle: ByteArray): Int =
        (0..size - needle.size).first { i -> needle.indices.all { this[i + it] == needle[it] } }

    private fun target(gateway: MediaGateway, url: String, role: GatewayRole = GatewayRole.Media): String {
        val full = if (role == GatewayRole.Unknown) gateway.urlFor(url) else gatewayUrlFor(gateway, url, role)
        return assertNotNull(full).substringAfter(":${gateway.port}")
    }

    /** The gateway's own encoding for [role], read off a playlist it rewrites: the public way to get one. */
    private fun gatewayUrlFor(gateway: MediaGateway, url: String, role: GatewayRole): String? {
        val first = gateway.urlFor(url) ?: return null
        return first.replaceFirst("/u/", "/${role.code}/")
    }

    private fun within60s(block: () -> Unit) {
        val pool = Executors.newSingleThreadExecutor()
        try {
            pool.submit(block).get(60, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    // ---- The address check, on every hop ----------------------------------------------------------------------

    @Test
    fun `media on a refused host gets no request, and the answer is a failure`() = within60s {
        val lan = upstream { Reply(200, body = "secret".toByteArray()) }
        val gateway = gateway(allowed = emptySet())
        val answer = ask(gateway, target(gateway, "http://127.0.0.1:${lan.port}/seg.ts"))
        assertEquals(502, answer.code)
        assertEquals(0, lan.seen.size)
        assertEquals(1, gateway.failures[GatewayFailure.RefusedAddress])
    }

    @Test
    fun `a name the DNS filter refuses gets no connection`() = within60s {
        val lan = upstream { Reply(200) }
        val gateway = refusingNames(gatewayAllowed = setOf(lan.port), refused = "lan.example")
        assertEquals(502, ask(gateway, target(gateway, "http://lan.example:${lan.port}/x.ts")).code)
        assertEquals(0, lan.seen.size)
    }

    @Test
    fun `a redirect to a refused address sends it nothing, and the start one request`() = within60s {
        val lan = upstream { Reply(200) }
        val start = upstream { Reply(302, listOf("Location" to "http://127.0.0.1:${lan.port}/x")) }
        val gateway = gateway(allowed = setOf(start.port))
        assertEquals(502, ask(gateway, target(gateway, "http://127.0.0.1:${start.port}/a")).code)
        assertEquals(1, start.seen.size)
        assertEquals(0, lan.seen.size)
    }

    @Test
    fun `redirects are followed here, up to five, and a sixth is a failure`() = within60s {
        val hops = upstream { seen ->
            val n = seen.target.removePrefix("/").toIntOrNull() ?: 0
            if (n < 5) Reply(302, listOf("Location" to "/${n + 1}")) else Reply(200, body = "end".toByteArray())
        }
        val gateway = gateway(allowed = setOf(hops.port))
        val answer = ask(gateway, target(gateway, "http://127.0.0.1:${hops.port}/0"))
        assertEquals(200, answer.code)
        assertEquals("end", answer.text)
        assertNull(answer.headers["location"])
        val endless = upstream { Reply(302, listOf("Location" to "/again")) }
        val second = gateway(allowed = setOf(endless.port))
        assertEquals(502, ask(second, target(second, "http://127.0.0.1:${endless.port}/start")).code)
        assertEquals(6, endless.seen.size)
        assertEquals(1, second.failures[GatewayFailure.TooManyRedirects])
    }

    // ---- Ranges, playlists and roles ----------------------------------------------------------------------------

    @Test
    fun `media keeps its range, and a playlist never asks for one`() = within60s {
        val cdn = upstream { seen ->
            if (seen.target.endsWith(".m3u8")) Reply(200, listOf("Content-Type" to "application/vnd.apple.mpegurl"), "#EXTM3U\n#EXTINF:4,\nseg.ts\n".toByteArray())
            else Reply(206, listOf("Content-Range" to "bytes 0-3/10", "Accept-Ranges" to "bytes"), "abcd".toByteArray())
        }
        val gateway = gateway(allowed = setOf(cdn.port))
        val media = ask(gateway, target(gateway, "http://127.0.0.1:${cdn.port}/seg.ts"), listOf("Range" to "bytes=0-3"))
        assertEquals(206, media.code)
        assertEquals("bytes 0-3/10", media.headers["content-range"])
        assertEquals("bytes=0-3", cdn.seen.last().headers["range"])
        val playlist = ask(gateway, target(gateway, "http://127.0.0.1:${cdn.port}/v.m3u8", GatewayRole.Playlist), listOf("Range" to "bytes=0-"))
        assertEquals(200, playlist.code)
        assertNull(cdn.seen.last().headers["range"], "a playlist was asked for in part")
    }

    @Test
    fun `a playlist asked for with a range is rewritten from its whole body`() = within60s {
        val body = "#EXTM3U\n#EXTINF:4,\none.ts\n#EXTINF:4,\ntwo.ts\n"
        val cdn = upstream { seen ->
            // A server that would honour the range, were it sent.
            val range = seen.headers["range"]
            if (range != null) Reply(206, listOf("Content-Range" to "bytes 0-9/${body.length}"), body.take(10).toByteArray())
            else Reply(200, listOf("Content-Type" to "application/vnd.apple.mpegurl"), body.toByteArray())
        }
        val gateway = gateway(allowed = setOf(cdn.port))
        val answer = ask(gateway, target(gateway, "http://127.0.0.1:${cdn.port}/v.m3u8", GatewayRole.Playlist), listOf("Range" to "bytes=0-9"))
        assertEquals(200, answer.code)
        val lines = answer.text.lines()
        assertTrue(lines[2].startsWith("http://127.0.0.1:${gateway.port}/") && lines[2].endsWith("/one.ts"), answer.text)
        assertTrue(lines[4].endsWith("/two.ts"), answer.text)
        assertTrue("/s/http/127.0.0.1:${cdn.port}/one.ts" in lines[2], lines[2])
        assertNull(cdn.seen.single().headers["range"], "the playlist was asked for in part")
    }

    @Test
    fun `a variant is a playlist and a segment is media, in the rewritten urls`() = within60s {
        val cdn = upstream { Reply(200, listOf("Content-Type" to "application/x-mpegURL"), "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow.m3u8\n".toByteArray()) }
        val gateway = gateway(allowed = setOf(cdn.port))
        val answer = ask(gateway, target(gateway, "http://127.0.0.1:${cdn.port}/master.m3u8", GatewayRole.Unknown))
        assertTrue("/p/http/127.0.0.1:${cdn.port}/low.m3u8" in answer.text, answer.text)
    }

    @Test
    fun `a first url that is media after all streams through whole`() = within60s {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val cdn = upstream { Reply(200, listOf("Content-Type" to "video/mp4"), bytes) }
        val gateway = gateway(allowed = setOf(cdn.port))
        val answer = ask(gateway, target(gateway, "http://127.0.0.1:${cdn.port}/film.mp4", GatewayRole.Unknown), listOf("Range" to "bytes=0-"))
        assertEquals(200, answer.code)
        assertTrue(bytes.contentEquals(answer.body))
        assertNull(cdn.seen.single().headers["range"])
    }

    @Test
    fun `a DASH manifest fails at once rather than leave libVLC waiting`() = within60s {
        val cdn = upstream { Reply(200, listOf("Content-Type" to "application/dash+xml"), "<?xml version=\"1.0\"?><MPD></MPD>".toByteArray()) }
        val gateway = gateway(allowed = setOf(cdn.port))
        val started = System.nanoTime()
        assertEquals(502, ask(gateway, target(gateway, "http://127.0.0.1:${cdn.port}/m.mpd", GatewayRole.Unknown)).code)
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5))
        assertEquals(1, gateway.failures[GatewayFailure.DashUnsupported])
    }

    @Test
    fun `a playlist that trickles in is a failure at its time limit, not a wait`() = within60s {
        val slow = ServerSocket(0, 5, InetAddress.getLoopbackAddress()).closing()
        thread(isDaemon = true) {
            runCatching {
                slow.accept().use { client ->
                    client.getInputStream().read(ByteArray(4096))
                    val out = client.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: 100000\r\n\r\n#EXTM3U\n".toByteArray())
                    out.flush()
                    Thread.sleep(20_000)
                }
            }
        }
        val gateway = gateway(allowed = setOf(slow.localPort), limits = MediaGateway.Limits(playlistMillis = 500))
        val started = System.nanoTime()
        assertEquals(502, ask(gateway, target(gateway, "http://127.0.0.1:${slow.localPort}/v.m3u8", GatewayRole.Playlist)).code)
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10), "it waited")
    }

    // ---- Headers and cookies ------------------------------------------------------------------------------------

    @Test
    fun `the stream's own Referer and User-Agent go upstream, and none of libVLC's other headers do`() = within60s {
        val cdn = upstream { Reply(200, body = "x".toByteArray()) }
        val gateway = gateway(allowed = setOf(cdn.port))
        ask(
            gateway, target(gateway, "http://127.0.0.1:${cdn.port}/seg.ts"),
            listOf(
                "Referer" to "https://libvlc.example/", "User-Agent" to "VLC/3.0.24", "Cookie" to "a=1",
                "Authorization" to "Bearer t", "Proxy-Authorization" to "Basic p", "Accept-Language" to "en",
                "Cache-Control" to "no-cache", "X-Forwarded-For" to "10.0.0.1", "Accept" to "*/*",
            ),
        )
        val seen = cdn.seen.single().headers
        assertEquals("https://site.example/page", seen["referer"])
        assertEquals("GatewayTest/1", seen["user-agent"])
        assertEquals("*/*", seen["accept"])
        listOf("cookie", "authorization", "proxy-authorization", "accept-language", "cache-control", "x-forwarded-for", "forwarded", "via")
            .forEach { assertNull(seen[it], "$it went upstream") }
    }

    @Test
    fun `a value that names the gateway never goes upstream`() = within60s {
        val cdn = upstream { Reply(206, body = "x".toByteArray()) }
        val gateway = gateway(allowed = setOf(cdn.port))
        ask(gateway, target(gateway, "http://127.0.0.1:${cdn.port}/seg.ts"), listOf("Accept" to "http://127.0.0.1:${gateway.port}/x", "Range" to "bytes=0-1"))
        assertNull(cdn.seen.single().headers["accept"])
        assertEquals("bytes=0-1", cdn.seen.single().headers["range"])
    }

    @Test
    fun `cookies stay with their host, never reach libVLC, and a cookie for another domain is not kept`() = within60s {
        val a = upstream { seen ->
            if (seen.target == "/set") Reply(200, listOf("Set-Cookie" to "token=abc; Path=/", "Set-Cookie" to "stray=1; Domain=b.example; Path=/"))
            else Reply(200)
        }
        val b = upstream { Reply(200) }
        val gateway = gateway(allowed = setOf(a.port, b.port))
        val set = ask(gateway, target(gateway, "http://a.example:${a.port}/set"))
        assertNull(set.headers["set-cookie"], "Set-Cookie reached libVLC")
        ask(gateway, target(gateway, "http://a.example:${a.port}/again"))
        assertEquals("token=abc", a.seen.last().headers["cookie"])
        ask(gateway, target(gateway, "http://b.example:${b.port}/x"))
        assertNull(b.seen.last().headers["cookie"], "a.example's cookie, or one it set for b.example, went to b.example")
    }

    // ---- Requests the gateway refuses ---------------------------------------------------------------------------

    @Test
    fun `no token, another method, a proxy request or a body are refused`() = within60s {
        val cdn = upstream { Reply(200) }
        val gateway = gateway(allowed = setOf(cdn.port))
        val good = target(gateway, "http://127.0.0.1:${cdn.port}/a")
        assertEquals(404, ask(gateway, "/s/http/127.0.0.1:${cdn.port}/a").code)
        assertEquals(405, ask(gateway, good, method = "POST").code)
        assertEquals(400, ask(gateway, "http://127.0.0.1:${cdn.port}/a").code)
        assertEquals(400, ask(gateway, "127.0.0.1:${cdn.port}", method = "CONNECT").code)
        assertEquals(400, ask(gateway, good, listOf("Content-Length" to "4")).code)
        assertEquals(0, cdn.seen.size)
    }

    @Test
    fun `the second loopback socket only holds the port`() = within60s {
        val cdn = upstream { Reply(200) }
        val gateway = gateway(allowed = setOf(cdn.port))
        assertEquals(404, ask(gateway, target(gateway, "http://127.0.0.1:${cdn.port}/a"), v6 = true).code)
        assertEquals(0, cdn.seen.size)
    }

    @Test
    fun `no other socket can take the port on either loopback address while the gateway holds it`() = within60s {
        val gateway = gateway(allowed = emptySet())
        assertFailsWith<java.net.BindException> {
            ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), gateway.port)) }
        }
        assertFailsWith<java.net.BindException> {
            ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress(Inet6Address.getByName("::1"), gateway.port)) }
        }
    }

    @Test
    fun `a connection past the limit is closed unanswered, and an oversized head is refused`() = within60s {
        val gateway = gateway(allowed = emptySet(), limits = MediaGateway.Limits(maxConnections = 2))
        val idle = List(2) { Socket("127.0.0.1", gateway.port).closing() }
        Thread.sleep(200)
        Socket("127.0.0.1", gateway.port).use { third ->
            third.soTimeout = 5_000
            assertEquals(-1, third.getInputStream().read(), "a third connection was served")
        }
        idle.forEach { it.close() }
        Thread.sleep(200)
        val huge = List(200) { "X-$it" to "y".repeat(100) }
        assertEquals(400, ask(gateway, "/x", huge).code)
    }

    // ---- Prints nothing ---------------------------------------------------------------------------------------

    @Test
    fun `nothing a stream carries is printed or written`() = within60s {
        val marker = "MARKERxyz"
        val cdn = upstream {
            Reply(200, listOf("Content-Type" to "application/vnd.apple.mpegurl", "Set-Cookie" to "c=$marker"), "#EXTM3U\n#EXTINF:4,\n$marker.ts\n".toByteArray())
        }
        val tmp = File(System.getProperty("java.io.tmpdir"))
        val before = tmp.list()?.toSet().orEmpty()
        val out = ByteArrayOutputStream()
        val realOut = System.out
        val realErr = System.err
        System.setOut(PrintStream(out, true))
        System.setErr(PrintStream(out, true))
        try {
            val gateway = gateway(allowed = setOf(cdn.port), referer = "https://$marker.example/", userAgent = marker)
            ask(gateway, target(gateway, "http://127.0.0.1:${cdn.port}/$marker.m3u8?k=$marker", GatewayRole.Unknown), listOf("Cookie" to marker, "Referer" to marker))
            ask(gateway, target(gateway, "http://127.0.0.1:1/$marker.ts?k=$marker"))
            ask(gateway, "/$marker")
        } finally {
            System.setOut(realOut)
            System.setErr(realErr)
        }
        assertFalse(marker in out.toString(), "printed: $out")
        assertEquals("", out.toString())
        val made = tmp.list()?.toSet().orEmpty() - before
        assertTrue(made.none { marker in it }, "$made")
    }
}

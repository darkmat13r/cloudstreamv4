package com.lagradost.cloudstream4.player

import com.lagradost.cloudstream4.player.gateway.GatewayFailure
import com.lagradost.cloudstream4.player.gateway.MediaGateway
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.Dns
import org.junit.Assume.assumeTrue

/**
 * `KMP-113` with the real media host: libVLC 3 under its sandbox, behind its gateway. Skips without VLC.app or
 * `sandbox-exec`, as the other host tests do. Local servers stand in for the internet; made-up names; no audio.
 */
class GatewayPlaybackTest {

    /** A server on loopback that answers every request with [body] and counts what arrives. */
    private class Counting(
        private val body: (Int) -> ByteArray,
        private val type: String,
        address: InetAddress = InetAddress.getLoopbackAddress(),
    ) : AutoCloseable {
        private val socket = ServerSocket(0, 20, address)
        val port: Int = socket.localPort
        val requests = AtomicInteger()

        init {
            thread(isDaemon = true) {
                runCatching {
                    while (true) {
                        val client = socket.accept()
                        thread(isDaemon = true) {
                            client.use {
                                val reader = it.getInputStream().bufferedReader()
                                if (reader.readLine() == null) return@use
                                while (!reader.readLine().isNullOrEmpty()) Unit
                                requests.incrementAndGet()
                                val bytes = body(port)
                                it.getOutputStream().write(
                                    ("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()
                                )
                                it.getOutputStream().write(bytes)
                            }
                        }
                    }
                }
            }
        }

        override fun close() = socket.close()
    }

    private val opened = mutableListOf<AutoCloseable>()
    private var player: VlcPlayer? = null

    @AfterTest
    fun tearDown() {
        player?.release()
        opened.forEach { runCatching { it.close() } }
    }

    private fun counting(type: String, address: InetAddress = InetAddress.getLoopbackAddress(), body: (Int) -> ByteArray) =
        Counting(body, type, address).also { opened += it }

    /**
     * This Mac's own address on its network, which the old policy allowed (it refused only `localhost`). A test that
     * used loopback here would pass with or without the gateway-only rule.
     */
    private fun lanAddress(): InetAddress? = java.net.NetworkInterface.networkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses().toList() }
        .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }

    /** Made-up names to loopback, for the gateway's own lookup. */
    private val names = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            listOf(InetAddress.getByAddress(hostname, byteArrayOf(127, 0, 0, 1)))
    }

    /** What play's own check sees: the same names, public. The gateway decides for itself. */
    private val publicLookup: (String) -> Array<InetAddress> = { host -> arrayOf(InetAddress.getByAddress(host, byteArrayOf(8, 8, 8, 8))) }

    private fun within60s(block: () -> Unit) {
        val pool = Executors.newSingleThreadExecutor()
        try {
            pool.submit(block).get(60, TimeUnit.SECONDS)
        } catch (failure: java.util.concurrent.ExecutionException) {
            // As it was thrown, so an assumption that fails is a skip, not a failure.
            throw failure.cause ?: failure
        } finally {
            pool.shutdownNow()
        }
    }

    private fun waitFor(seconds: Long, done: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!done() && System.nanoTime() < until) Thread.sleep(100)
    }

    @Test
    fun `a playlist's segment on a refused host is fetched by nobody`() = within60s {
        val lan = counting("video/mp2t") { ByteArray(188) }
        val site = counting("application/vnd.apple.mpegurl") {
            "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\nhttp://127.0.0.1:${lan.port}/seg.ts\n#EXT-X-ENDLIST\n".toByteArray()
        }
        var gateway: MediaGateway? = null
        player = VlcPlayer.createWith(
            silent = true,
            startGateway = {
                MediaGateway.start(null, null, allow = { it.port == 0 || it.port == site.port }, lookup = names)
                    .also { gateway = it }
            },
            resolve = publicLookup,
        ).also { assumeTrue("no sandboxed player here (VLC missing, or no sandbox-exec)", it != null) }
        assertTrue(player!!.play("http://site.example:${site.port}/v.m3u8", null, null, null))
        // Positive control: libVLC reached the playlist, through the gateway.
        waitFor(20) { site.requests.get() >= 1 }
        assertTrue(site.requests.get() >= 1, "libVLC never fetched the playlist")
        waitFor(8) { (gateway!!.failures[GatewayFailure.RefusedAddress] ?: 0) >= 1 }
        Thread.sleep(2_000)
        assertEquals(0, lan.requests.get(), "the refused host was reached")
        assertTrue((gateway!!.failures[GatewayFailure.RefusedAddress] ?: 0) >= 1, "the gateway never saw the segment")
    }

    @Test
    fun `a playlist url the rewriter leaves as written is one libVLC cannot reach - the sandbox refuses it`() = within60s {
        val address = lanAddress()
        assumeTrue("this Mac has no address on a network", address != null)
        val direct = counting("video/mp2t", address!!) { ByteArray(188) }
        // User info makes the gateway refuse to encode the url, so the rewriter leaves it as written, and libVLC tries it
        // itself. That is the case the design rests on: whatever the rewriter misses, only the sandbox stands in the way.
        val site = counting("application/vnd.apple.mpegurl") {
            "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\nhttp://probe@${address.hostAddress}:${direct.port}/seg.ts\n#EXT-X-ENDLIST\n".toByteArray()
        }
        player = VlcPlayer.createWith(
            silent = true,
            startGateway = { MediaGateway.start(null, null, allow = { it.port == 0 || it.port == site.port }, lookup = names) },
            resolve = publicLookup,
        ).also { assumeTrue("no sandboxed player here (VLC missing, or no sandbox-exec)", it != null) }
        assertTrue(player!!.play("http://site.example:${site.port}/v.m3u8", null, null, null))
        // Positive control: libVLC fetched the playlist through the gateway, so it has the url it would try.
        waitFor(20) { site.requests.get() >= 1 }
        assertTrue(site.requests.get() >= 1, "libVLC never fetched the playlist")
        Thread.sleep(8_000)
        assertEquals(0, direct.requests.get(), "libVLC connected past its gateway")
    }
}

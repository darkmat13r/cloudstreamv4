package com.lagradost.cloudstream4.search

import com.lagradost.cloudstream4.core.http.HttpClientFactory
import com.lagradost.cloudstream4.detail.boundedImageClient
import com.lagradost.cloudstream4.feature.extensions.sandbox.SandboxPolicy
import com.lagradost.cloudstream4.player.HttpSubtitleLoader
import io.ktor.client.request.get
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request

class PosterHttpClientTest {

    /** One thread answering every connection with [response], counting the requests that arrive. */
    private class Server(private val response: (Int) -> String) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val port: Int = socket.localPort
        val requests = AtomicInteger()

        init {
            thread(isDaemon = true) {
                runCatching {
                    while (true) {
                        socket.accept().use { client ->
                            val reader = client.getInputStream().bufferedReader()
                            // A refused address still gets a TCP connection, closed unused: the check
                            // has to see which address was reached. Only a request line is a request.
                            if (reader.readLine() == null) return@use
                            while (!reader.readLine().isNullOrEmpty()) Unit
                            requests.incrementAndGet()
                            client.getOutputStream().write(response(port).toByteArray())
                        }
                    }
                }
            }
        }

        override fun close() = socket.close()
    }

    private fun ok() = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"

    /** A blocking call is not cancelled by a coroutine's timeout, so it gets its own. Keeps every other setting. */
    private fun okhttp3.OkHttpClient.bounded() = newBuilder().callTimeout(60, TimeUnit.SECONDS).build()

    private fun get(client: okhttp3.OkHttpClient, url: String) =
        client.newCall(Request.Builder().url(url).build()).execute().use { it.code }

    @Test
    fun `an IP literal on this machine gets no request`() {
        Server { ok() }.use { server ->
            assertFailsWith<IOException> { get(posterHttpClient(), "http://127.0.0.1:${server.port}/p.jpg") }
            assertEquals(0, server.requests.get())
        }
    }

    @Test
    fun `a name resolving to this machine is refused`() {
        Server { ok() }.use { server ->
            assertFailsWith<IOException> { get(posterHttpClient(), "http://localhost:${server.port}/p.jpg") }
            assertEquals(0, server.requests.get())
        }
    }

    @Test
    fun `a redirect to a refused address gets no request, and the control passes`() {
        Server { ok() }.use { target ->
            Server { "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${target.port}/x\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" }.use { start ->
                val startOnly = posterHttpClient { it.port == start.port }
                assertFailsWith<IOException> { get(startOnly, "http://127.0.0.1:${start.port}/p.jpg") }
                assertEquals(1, start.requests.get())
                assertEquals(0, target.requests.get())

                val both = posterHttpClient { it.port == start.port || it.port == target.port }
                assertEquals(200, get(both, "http://127.0.0.1:${start.port}/p.jpg"))
                assertEquals(1, target.requests.get())
            }
        }
    }

    /**
     * `BUG-88`: a JVM proxy would be asked for the target by name, so neither check would ever see the target. Every
     * client built on [posterHttpClient] must ignore one. The proxy here is allowed as if it were public, and the
     * target's name resolves to this computer.
     */
    @Test
    fun `a JVM proxy setting is not used, by any client built on the poster client`() {
        Server { ok() }.use { proxy ->
            Server { ok() }.use { target ->
                val allowProxy: (InetSocketAddress) -> Boolean = { it.port == proxy.port }
                val url = "http://localhost:${target.port}/p.jpg"
                val clients: List<Pair<String, () -> Unit>> = listOf(
                    "poster" to { get(posterHttpClient(allowProxy).bounded(), url) },
                    "image" to {
                        get(boundedImageClient(posterHttpClient(allowProxy), requireHttps = false).bounded(), url)
                    },
                    "subtitle" to { get(HttpSubtitleLoader.subtitleHttpClient(allowProxy).bounded(), url) },
                    "download" to {
                        val download = HttpClientFactory(posterHttpClient(allowProxy)).create()
                        try {
                            runBlocking { withTimeout(60_000) { download.get(url).status.value } }
                        } finally {
                            download.close()
                        }
                    },
                )
                val before = ProxySelector.getDefault()
                ProxySelector.setDefault(object : ProxySelector() {
                    override fun select(uri: URI?) =
                        listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getLoopbackAddress(), proxy.port)))

                    override fun connectFailed(uri: URI?, address: SocketAddress?, failure: IOException?) = Unit
                })
                try {
                    clients.forEach { (name, call) ->
                        assertFailsWith<IOException>("$name: the target was reached") { call() }
                        assertEquals(0, proxy.requests.get(), "$name: a request went to the proxy")
                        assertEquals(0, target.requests.get(), "$name: a request reached the target")
                    }
                } finally {
                    ProxySelector.setDefault(before)
                }
            }
        }
    }

    @Test
    fun `address classes`() {
        listOf(
            "127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254", "0.0.0.0", "100.64.0.1",
            "224.0.0.1", "::1", "::", "fe80::1", "fc00::1", "fd12::1", "::ffff:192.168.1.1",
        ).forEach { assertEquals(false, SandboxPolicy.isPublicAddress(InetAddress.getByName(it)), it) }
        listOf("8.8.8.8", "100.128.0.1", "172.32.0.1", "2606:4700:4700::1111").forEach {
            assertEquals(true, SandboxPolicy.isPublicAddress(InetAddress.getByName(it)), it)
        }
    }
}

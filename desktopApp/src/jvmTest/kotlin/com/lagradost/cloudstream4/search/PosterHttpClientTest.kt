package com.lagradost.cloudstream4.search

import com.lagradost.cloudstream4.feature.extensions.sandbox.SandboxPolicy
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

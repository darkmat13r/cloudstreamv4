package com.lagradost.cloudstream4.net

import com.lagradost.cloudstream4.extensions.repositories.RepositoryHttp
import com.lagradost.cloudstream4.player.gateway.MediaGateway
import com.lagradost.cloudstream4.search.posterHttpClient
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * `BUG-105`: every Mac client for a URL a plugin chose refuses a private address before it connects, whatever form the
 * address is written in. OkHttp asks the DNS filter only for a name, never for an address written as one, so the
 * address is checked again at the socket, on the destination itself.
 *
 * Each case points a client at a server on this machine that counts the connections it is offered, and asserts it was
 * offered none: refusing the answer after connecting is not enough, since the connection and a TLS hello have already
 * reached whatever listens there.
 */
class ConnectTimeAddressCheckTest {

    /** Counts every connection offered to it, and closes each at once. */
    private class Listener : AutoCloseable {
        private val socket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val accepted = AtomicInteger()

        init {
            thread(isDaemon = true) {
                runCatching { while (true) socket.accept().use { accepted.incrementAndGet() } }
            }
        }

        override fun close() = socket.close()
    }

    private val listener = Listener()

    @AfterTest
    fun close() = listener.close()

    /** 127.0.0.1 written every way a URL can write it as an address, and so never looked up by name. */
    private fun literals(scheme: String) = listOf(
        "127.0.0.1",
        "[::ffff:127.0.0.1]",
        "[::ffff:7f00:1]",
        "2130706433",
        "0x7f000001",
    ).map { host -> "$scheme://$host:${listener.port}/still.jpg" }

    /** Waits briefly for any connection still on its way, then reads the count. */
    private fun acceptedAfterSettling(): Int {
        Thread.sleep(SETTLE_MILLIS)
        return listener.accepted.get()
    }

    private fun OkHttpClient.attempt(url: String) {
        runCatching { newCall(Request.Builder().url(url).build()).execute().close() }
    }

    @Test
    fun `the poster client, under every image, subtitle and download, connects to no private address written as one`() {
        val client = posterHttpClient()
        (literals("http") + literals("https")).forEach { url ->
            client.attempt(url)
            assertEquals(0, acceptedAfterSettling(), "a connection was offered for $url")
        }
    }

    @Test
    fun `the repository client connects to no private address written as one`() = runBlocking {
        val http = RepositoryHttp()
        literals("https").forEach { url ->
            runCatching { http.fetch(url, maxBytes = 1024) }
            assertEquals(0, acceptedAfterSettling(), "a connection was offered for $url")
        }
    }

    @Test
    fun `the media gateway connects upstream to no private address written as one`() {
        val gateway = assertNotNull(MediaGateway.start(referer = null, userAgent = null))
        val local = OkHttpClient.Builder().callTimeout(CALL_SECONDS, TimeUnit.SECONDS).build()
        try {
            (literals("http") + literals("https")).forEach { url ->
                val through = assertNotNull(gateway.urlFor(url), "the gateway takes $url as a stream")
                local.attempt(through)
                assertEquals(0, acceptedAfterSettling(), "the gateway offered a connection for $url")
            }
        } finally {
            gateway.close()
        }
    }

    /**
     * A SOCKS proxy is connected to by OkHttp with `Socket(proxy)`, never through the socket factory, so with one set
     * the socket check would be skipped and the proxy asked for the target. The poster client uses no proxy whatever the
     * JVM is set to: a default selector naming a SOCKS proxy on this machine is offered no connection, and neither is
     * the private target.
     */
    @Test
    fun `the poster client ignores a JVM proxy, so a SOCKS proxy cannot carry it past the check`() {
        Listener().use { proxy ->
            val before = ProxySelector.getDefault()
            ProxySelector.setDefault(object : ProxySelector() {
                override fun select(uri: URI?): List<Proxy> =
                    listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress(InetAddress.getByName("127.0.0.1"), proxy.port)))

                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
            })
            try {
                posterHttpClient().attempt("http://[::ffff:127.0.0.1]:${listener.port}/still.jpg")
                assertEquals(0, proxy.accepted.also { Thread.sleep(SETTLE_MILLIS) }.get(), "the proxy was offered a connection")
                assertEquals(0, acceptedAfterSettling(), "the target was offered a connection")
            } finally {
                ProxySelector.setDefault(before)
            }
        }
    }

    /**
     * The check is on the destination, not a blanket refusal: an address the predicate allows is connected to, as a
     * public image host is in the app.
     */
    @Test
    fun `an address the check allows is still connected to`() {
        val allowed: (InetSocketAddress) -> Boolean = { it.address.isLoopbackAddress && it.port == listener.port }
        posterHttpClient(allowed).attempt("http://127.0.0.1:${listener.port}/still.jpg")
        assertEquals(1, acceptedAfterSettling(), "the allowed address was connected to once")
    }

    private companion object {
        const val SETTLE_MILLIS = 200L
        const val CALL_SECONDS = 10L
    }
}

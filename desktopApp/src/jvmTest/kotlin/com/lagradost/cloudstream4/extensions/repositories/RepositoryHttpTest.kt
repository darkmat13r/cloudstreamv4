package com.lagradost.cloudstream4.extensions.repositories

import com.lagradost.cloudstream4.extensions.repositories.LocalRepositoryServer.Reply
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** `KMP-110`: what the repository client fetches, and what it refuses before or while asking. */
class RepositoryHttpTest {
    private val server = LocalRepositoryServer()
    private val other = LocalRepositoryServer()

    @AfterTest
    fun tearDown() {
        server.close()
        other.close()
    }

    /** Plain http to [server] only, every address allowed: what a test can serve, with the checks still in place. */
    private fun local(callTimeoutMillis: Long = RepositoryHttp.JSON_CALL_TIMEOUT_MILLIS) = RepositoryHttp(
        allow = { true },
        requireHttps = false,
        secure = { it.port == server.port },
        callTimeoutMillis = callTimeoutMillis,
    )

    private fun failure(fetched: Fetched): RepositoryFailure = assertIs<Fetched.Failed>(fetched).failure

    @Test
    fun `a body within the cap comes back whole`() = runBlocking {
        server.routes["/repo.json"] = Reply.Body("{\"name\":\"Harbour Lights\"}".toByteArray())
        val fetched = local().fetch(server.url("/repo.json"), 1024)
        assertContentEquals("{\"name\":\"Harbour Lights\"}".toByteArray(), assertIs<Fetched.Bytes>(fetched).bytes)
    }

    @Test
    fun `an http link is refused before anything is asked`() = runBlocking {
        server.routes["/repo.json"] = Reply.Body("{}".toByteArray())
        assertEquals(RepositoryFailure.NotHttps, failure(RepositoryHttp(allow = { true }).fetch(server.url("/repo.json"), 1024)))
        assertEquals(0, server.requests.get())
    }

    @Test
    fun `a redirect to a link that is not https is not followed`() = runBlocking {
        server.routes["/repo.json"] = Reply.Redirect(other.url("/repo.json"))
        other.routes["/repo.json"] = Reply.Body("{}".toByteArray())
        assertEquals(RepositoryFailure.NotHttps, failure(local().fetch(server.url("/repo.json"), 1024)))
        assertEquals(0, other.requests.get(), "the redirect's target must never be asked")
    }

    private val toLoopback = object : Dns {
        override fun lookup(hostname: String) = listOf(InetAddress.getLoopbackAddress())
    }

    /**
     * The name filter alone: it is asked with port 0 and refuses that, while the connected-address check, which sees the
     * real port, would let it through. So only the filter stands between this and the server.
     */
    @Test
    fun `a name that resolves to this computer is refused before connecting`() = runBlocking {
        server.routes["/repo.json"] = Reply.Body("{}".toByteArray())
        val http = RepositoryHttp(allow = { it.port != 0 }, requireHttps = false, secure = { true }, lookup = toLoopback)
        assertEquals(RepositoryFailure.PrivateAddress, failure(http.fetch("http://repo.example:${server.port}/repo.json", 1024)))
        assertEquals(0, server.requests.get())
    }

    /**
     * The connected-address check alone: the name filter passes (port 0 is allowed), there is no IP literal, and only
     * the check on the address actually connected to, with its real port, refuses.
     */
    @Test
    fun `the address connected to is checked again, before a request is sent`() = runBlocking {
        server.routes["/repo.json"] = Reply.Body("{}".toByteArray())
        val http = RepositoryHttp(allow = { it.port == 0 }, requireHttps = false, secure = { true }, lookup = toLoopback)
        assertEquals(RepositoryFailure.PrivateAddress, failure(http.fetch("http://repo.example:${server.port}/repo.json", 1024)))
        assertEquals(0, server.requests.get())
    }

    /** `#334` review: a JVM proxy would be asked for the target by name, which neither address check would ever see. */
    @Test
    fun `a JVM proxy setting is not used, so the address checks still see the target`() = runBlocking {
        val proxy = LocalRepositoryServer()
        val before = java.net.ProxySelector.getDefault()
        java.net.ProxySelector.setDefault(object : java.net.ProxySelector() {
            override fun select(uri: java.net.URI?) =
                listOf(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", proxy.port)))

            override fun connectFailed(uri: java.net.URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) = Unit
        })
        try {
            server.routes["/repo.json"] = Reply.Body("direct".toByteArray())
            assertIs<Fetched.Bytes>(local().fetch(server.url("/repo.json"), 1024))
            // The proxy is allowed as if it were public; the target's name resolves to this computer.
            val http = RepositoryHttp(allow = { it.port == proxy.port }, requireHttps = false, secure = { true }, lookup = toLoopback)
            assertEquals(RepositoryFailure.PrivateAddress, failure(http.fetch("http://repo.example:${server.port}/repo.json", 1024)))
            assertEquals(0, proxy.requests.get(), "a request went to the proxy")
        } finally {
            java.net.ProxySelector.setDefault(before)
            proxy.close()
        }
    }

    @Test
    fun `a loopback address written out is refused before a request is sent`() = runBlocking {
        server.routes["/repo.json"] = Reply.Body("{}".toByteArray())
        val http = RepositoryHttp(requireHttps = false, secure = { true })
        assertEquals(RepositoryFailure.PrivateAddress, failure(http.fetch(server.url("/repo.json"), 1024)))
        assertEquals(0, server.requests.get())
    }

    @Test
    fun `a redirect to a refused address sends it nothing`() = runBlocking {
        server.routes["/repo.json"] = Reply.Redirect(other.url("/repo.json"))
        other.routes["/repo.json"] = Reply.Body("{}".toByteArray())
        val http = RepositoryHttp(allow = { it.port != other.port }, requireHttps = false, secure = { true })
        assertEquals(RepositoryFailure.PrivateAddress, failure(http.fetch(server.url("/repo.json"), 1024)))
        assertEquals(1, server.requests.get())
        assertEquals(0, other.requests.get())
    }

    @Test
    fun `five redirects are followed and a sixth is refused`() = runBlocking {
        (0 until 6).forEach { server.routes["/r$it"] = Reply.Redirect("/r${it + 1}") }
        server.routes["/r6"] = Reply.Body("end".toByteArray())
        server.routes["/s1"] = Reply.Redirect("/r2")
        assertIs<Fetched.Bytes>(local().fetch(server.url("/s1"), 1024), "five redirects")
        assertEquals(RepositoryFailure.TooManyRedirects, failure(local().fetch(server.url("/r0"), 1024)))
    }

    @Test
    fun `an answer that is not a success is not found`() = runBlocking {
        assertEquals(RepositoryFailure.NotFound, failure(local().fetch(server.url("/nothing.json"), 1024)))
    }

    @Test
    fun `a declared length past the cap is refused before the body is read`() = runBlocking {
        server.routes["/big.json"] = Reply.Stream(total = 64L * 1024 * 1024, declared = true)
        assertEquals(RepositoryFailure.TooLarge, failure(local().fetch(server.url("/big.json"), 1024)))
        assertTrue(server.streamed.get() < 8L * 1024 * 1024, "streamed ${server.streamed.get()} bytes")
    }

    /** The declared-length check alone: the body is short of what it states, so only the stated length is too large. */
    @Test
    fun `a stated length past the cap is refused on the statement alone`() = runBlocking {
        server.routes["/lying.json"] = Reply.Lying(declared = 4096, sent = 10)
        assertEquals(RepositoryFailure.TooLarge, failure(local().fetch(server.url("/lying.json"), 1024)))
    }

    @Test
    fun `a body with no length fails once it passes the cap`() = runBlocking {
        server.routes["/big.json"] = Reply.Stream(total = 64L * 1024 * 1024, declared = false)
        assertEquals(RepositoryFailure.TooLarge, failure(local().fetch(server.url("/big.json"), 1024 * 1024)))
        assertTrue(server.streamed.get() < 16L * 1024 * 1024, "streamed ${server.streamed.get()} bytes")
    }

    @Test
    fun `a compressed body is capped at its size once decompressed`() = runBlocking {
        server.routes["/zeros.json"] = Reply.Body(ByteArray(4 * 1024 * 1024), gzip = true)
        assertEquals(RepositoryFailure.TooLarge, failure(local().fetch(server.url("/zeros.json"), 1024 * 1024)))
    }

    @Test
    fun `a server that stops answering hits the call's time limit`() = runBlocking {
        server.routes["/slow.json"] = Reply.Stall(millis = 20_000)
        val started = System.nanoTime()
        assertEquals(RepositoryFailure.Unreachable, failure(local(callTimeoutMillis = 500).fetch(server.url("/slow.json"), 1024)))
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue(seconds < 5, "took $seconds s")
    }

    @Test
    fun `no one listening is unreachable`() = runBlocking {
        val port = server.port
        server.close()
        assertEquals(RepositoryFailure.Unreachable, failure(local().fetch("http://127.0.0.1:$port/repo.json", 1024)))
    }
}

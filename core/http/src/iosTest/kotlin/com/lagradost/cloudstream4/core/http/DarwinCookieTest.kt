package com.lagradost.cloudstream4.core.http

import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.DarwinClientEngineConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPCookieStorage
import platform.Foundation.NSURL
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `BUG-116`: the session keeps no cookie jar. A cookie a server sets is not stored, on disk or in memory, and is not sent
 * back; a request carries a `Cookie` header only when its caller writes one. Every request goes to [LocalServer] on
 * 127.0.0.1; nothing here reaches the network.
 */
class DarwinCookieTest {

    /**
     * The production session's settings, applied after the engine's own, leave it with no cookie store. The engine
     * (Ktor's Darwin) sets the store to none before it applies ours; this reads what the session is finally given.
     */
    @Test
    fun theProductionSessionHasNoCookieStore() = runBlocking {
        val production = (HttpClientFactory().create().engine.config as DarwinClientEngineConfig).sessionConfig
        var applied = false
        var store: NSHTTPCookieStorage? = NSHTTPCookieStorage.sharedHTTPCookieStorage
        val server = LocalServer("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n")
        try {
            val client = DarwinClientFactory(
                Darwin.create {
                    configureSession {
                        production(this)
                        applied = true
                        store = HTTPCookieStorage
                    }
                },
                owned = { true },
            ).create()
            // The session is made for the first request; the gate refuses 127.0.0.1, which is fine: only the settings matter.
            runCatching { client.get("http://127.0.0.1:${server.port}/") }
            assertTrue(applied, "the session was never configured")
            assertNull(store, "the session has a cookie store")
        } finally {
            server.stop()
        }
    }

    /** A `Set-Cookie` reaches the client, and is neither kept in the shared store nor sent on the next request. */
    @Test
    fun aCookieASetsIsNotKeptOrSentBack() = runBlocking {
        val token = "bug116-" + Random.nextLong().toULong().toString(16)
        val server = LocalServer(
            "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nSet-Cookie: probe=$token; Path=/; Max-Age=3600\r\n" +
                "Content-Length: 2\r\nConnection: close\r\n\r\nok",
        )
        val url = "http://127.0.0.1:${server.port}/first"
        try {
            val client = DarwinClientFactory(Darwin.create { configureSession { bounded() } }, owned = { true }).create()
            val first = client.get(url)
            assertEquals("ok", first.bodyAsText())
            assertTrue(first.headers.getAll("Set-Cookie").orEmpty().any { token in it }, "the server's cookie never arrived")
            assertEquals("ok", client.get("http://127.0.0.1:${server.port}/second").bodyAsText())

            val requests = server.received
            assertEquals(2, requests.size, "requests: $requests")
            requests.forEach { request ->
                val headers = request.lines().map { it.substringBefore(':').trim().lowercase() }
                assertFalse("cookie" in headers, "a request carried a Cookie header:\n$request")
                assertFalse(token in request, "a request carried the cookie:\n$request")
            }
            val kept = NSHTTPCookieStorage.sharedHTTPCookieStorage.cookiesForURL(NSURL.URLWithString(url)!!).orEmpty()
                .map { (it as NSHTTPCookie).value }
            assertFalse(token in kept, "the shared store kept the cookie")
        } finally {
            server.stop()
        }
    }

    /** What a caller writes is still sent: a provider that sets `Cookie` itself is not stopped. */
    @Test
    fun aCookieTheCallerWritesIsSent() = runBlocking {
        val server = LocalServer("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n")
        try {
            val client = DarwinClientFactory(Darwin.create { configureSession { bounded() } }, owned = { true }).create()
            client.get("http://127.0.0.1:${server.port}/") { headers.append("Cookie", "given=1") }
            assertTrue(server.received.single().lines().any { it.equals("Cookie: given=1", ignoreCase = true) }, server.received.single())
        } finally {
            server.stop()
        }
    }
}

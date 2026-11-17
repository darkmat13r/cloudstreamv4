package com.lagradost.cloudstream4.core.http

import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.DarwinClientEngineConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLCache
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSUserDomainMask
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.memcpy
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** Every request goes to [LocalServer] on 127.0.0.1; nothing here reaches the network. */
class DarwinClientFactoryTest {
    /**
     * The production session without its [EgressGate], which refuses 127.0.0.1 (`BUG-111`): the cache and the timeout
     * are the session's own settings, so these tests reach [LocalServer] directly. The gate has `EgressGateTest`.
     */
    private fun ungated() = DarwinClientFactory(Darwin.create { configureSession { bounded() } })

    @Test
    fun everyClientRunsOnTheOneDarwinEngine() {
        val factory = HttpClientFactory()
        val first = factory.create()
        val second = factory.create()
        assertIs<DarwinClientEngineConfig>(first.engine.config)
        assertSame(first.engine, second.engine)
    }

    @Test
    fun closingAClientLeavesTheSharedEngineRunning() {
        val factory = HttpClientFactory()
        val closed = factory.create()
        val kept = factory.create()
        closed.close()
        assertTrue(kept.engine.isActive, "closing one client stopped the shared engine")
        assertTrue(factory.create().engine.isActive)
    }

    /** What the engine applies to its session, read back from the engine's own config. */
    @Test
    fun theEnginesSessionHasNoCacheAndBoundedTimeouts() {
        val session = NSURLSessionConfiguration.defaultSessionConfiguration
        (HttpClientFactory().create().engine.config as DarwinClientEngineConfig).sessionConfig(session)
        assertNull(session.URLCache, "the session would write to a disk cache")
        assertEquals(NSURLRequestReloadIgnoringLocalCacheData, session.requestCachePolicy)
        assertEquals(REQUEST_TIMEOUT_SECONDS, session.timeoutIntervalForRequest)
        assertEquals(RESOURCE_TIMEOUT_SECONDS, session.timeoutIntervalForResource)
    }

    /** `BUG-111`: the session's one proxy is the process's gate, for http and https, with no way around it. */
    @Test
    fun theEnginesSessionGoesOnlyThroughTheGate() {
        val session = NSURLSessionConfiguration.defaultSessionConfiguration
        (HttpClientFactory().create().engine.config as DarwinClientEngineConfig).sessionConfig(session)
        val proxy = session.connectionProxyDictionary
        assertEquals(
            mapOf<Any?, Any?>(
                "HTTPEnable" to 1L, "HTTPProxy" to "127.0.0.1", "HTTPPort" to EgressGate.shared.port.toLong(),
                "HTTPSEnable" to 1L, "HTTPSProxy" to "127.0.0.1", "HTTPSPort" to EgressGate.shared.port.toLong(),
                "SOCKSEnable" to 0L, "ProxyAutoConfigEnable" to 0L, "ExceptionsList" to emptyList<Any?>(), "ExcludeSimpleHostnames" to 0L,
            ),
            proxy?.mapValues { (_, v) -> (v as? Number)?.toLong() ?: (v as? NSNumber)?.longLongValue ?: (v as? List<*>)?.toList() ?: v },
        )
    }

    /** A response that asks to be cached for an hour leaves nothing in the shared cache or on disk. */
    @Test
    fun aCacheableResponseIsNotStored() = runBlocking {
        val body = "cache-probe"
        val server = LocalServer(
            "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nCache-Control: public, max-age=3600\r\n" +
                "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body",
        )
        // Unique per run: the simulator keeps this binary's Caches directory between runs.
        val token = "never-stored-" + Random.nextLong().toULong().toString(16)
        val url = "http://127.0.0.1:${server.port}/probe?token=$token"
        try {
            assertEquals(body, ungated().create().get(url).bodyAsText())
            assertTrue(cacheFilesMentioning(token).isEmpty(), "a cache file holds the URL: ${cacheFilesMentioning(token)}")
            val request = NSURLRequest.requestWithURL(NSURL.URLWithString(url)!!)
            assertNull(NSURLCache.sharedURLCache.cachedResponseForRequest(request), "the response is in the shared cache")
        } finally {
            server.stop()
        }
    }

    /** A server that never answers ends at [REQUEST_TIMEOUT_SECONDS], not at the platform's 60. */
    @Test
    fun aSilentServerTimesOutAtTheRequestTimeout() = runBlocking {
        val server = LocalServer(reply = null)
        try {
            val started = TimeSource.Monotonic.markNow()
            withTimeout(30_000) {
                assertFailsWith<Exception> { ungated().create().get("http://127.0.0.1:${server.port}/silent") }
            }
            val seconds = started.elapsedNow().inWholeMilliseconds / 1000.0
            assertTrue(seconds >= REQUEST_TIMEOUT_SECONDS - 1 && seconds < REQUEST_TIMEOUT_SECONDS + 5, "failed after $seconds s")
        } finally {
            server.stop()
        }
    }

    /** Every file under the app's Caches directory (Cache.db, its -wal and -shm included) whose bytes contain [needle]. */
    @OptIn(ExperimentalForeignApi::class)
    private fun cacheFilesMentioning(needle: String): List<String> {
        val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String
            ?: return emptyList()
        val files = NSFileManager.defaultManager
        val paths = files.subpathsAtPath(caches)?.map { it as String } ?: return emptyList()
        val wanted = needle.encodeToByteArray()
        return paths.filter { path ->
            val data = files.contentsAtPath("$caches/$path") ?: return@filter false
            val size = data.length.toInt()
            if (size < wanted.size) return@filter false
            val bytes = ByteArray(size)
            bytes.usePinned { memcpy(it.addressOf(0), data.bytes, size.convert()) }
            (0..size - wanted.size).any { i -> wanted.indices.all { j -> bytes[i + j] == wanted[j] } }
        }
    }
}

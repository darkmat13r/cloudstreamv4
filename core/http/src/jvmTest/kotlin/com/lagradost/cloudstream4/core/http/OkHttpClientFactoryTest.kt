package com.lagradost.cloudstream4.core.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * No network: the passed-in client's application interceptor answers every request, and the
 * `.invalid` host (RFC 6761) cannot resolve, so a request that bypassed it would fail.
 */
class OkHttpClientFactoryTest {
    private val url = "https://cloudstream.invalid/canned"
    private val seen: MutableList<Request> = Collections.synchronizedList(mutableListOf())
    private val dispatcherThreads: MutableSet<Thread> = Collections.synchronizedSet(mutableSetOf())
    private val callThreads: MutableList<Thread> = Collections.synchronizedList(mutableListOf())

    /** Stands in for `app.baseClient`: a dispatcher whose threads are recorded, a canned answer. */
    private fun appClient(): OkHttpClient = OkHttpClient.Builder()
        .dispatcher(Dispatcher(Executors.newCachedThreadPool { Thread(it).also(dispatcherThreads::add) }))
        .addInterceptor { chain ->
            seen += chain.request()
            callThreads += Thread.currentThread()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("canned".toResponseBody())
                .build()
        }
        .build()

    @Test
    fun requestsRunThroughThePassedInClient() = runBlocking {
        val client = HttpClientFactory(appClient()).create()

        val body = client.get(url) { header("X-Probe", "1") }.bodyAsText()

        assertEquals("canned", body)
        assertEquals(1, seen.size)
        assertEquals(url, seen.single().url.toString())
        assertEquals("1", seen.single().header("X-Probe"))
        assertTrue(callThreads.single() in dispatcherThreads, "ran on the shared dispatcher")
    }

    @Test
    fun closingAClientLeavesThePassedInClientRunning() = runBlocking {
        val appClient = appClient()
        val factory = HttpClientFactory(appClient)
        val client = factory.create()
        client.get(url).bodyAsText() // the engine derives and caches its OkHttpClient here

        client.close()
        client.coroutineContext.job.join()

        assertTrue(client.engine.isActive, "engine must not be closed with the client")
        assertFalse(awaitShutdown(appClient), "shared dispatcher was shut down")
        assertEquals("canned", factory.create().get(url).bodyAsText())
        assertEquals("canned", appClient.enqueueAndAwait(Request.Builder().url(url).build()))
    }

    /** Async on purpose: only `enqueue` uses the dispatcher executor that a closed engine shuts down. */
    private fun OkHttpClient.enqueueAndAwait(request: Request): String {
        val result = CompletableFuture<String>()
        newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { result.completeExceptionally(e) }
            override fun onResponse(call: Call, response: Response) { result.complete(response.body.string()) }
        })
        return result.get(5, TimeUnit.SECONDS)
    }

    /**
     * The hazard the factory exists to avoid, pinned against Ktor 3.6.0: an engine the client owns
     * shuts down the preconfigured client's dispatcher on close. If this starts failing, Ktor has
     * changed `OkHttpEngine`; re-read its `init` block before relaxing the factory's KDoc.
     */
    @Test
    fun anOwnedOkHttpEngineShutsDownThePreconfiguredDispatcherOnClose() = runBlocking {
        val appClient = appClient()
        val client = HttpClient(OkHttp) { engine { preconfigured = appClient } }
        client.get(url).bodyAsText()

        client.close()

        assertTrue(awaitShutdown(appClient))
    }

    private suspend fun awaitShutdown(client: OkHttpClient): Boolean =
        withTimeoutOrNull(SHUTDOWN_WAIT_MS) {
            while (!client.dispatcher.executorService.isShutdown) delay(10)
            true
        } ?: false

    private companion object {
        /** Observed shutdown in the owned-engine case is immediate; this is generous headroom. */
        const val SHUTDOWN_WAIT_MS = 2_000L
    }
}

package com.lagradost.cloudstream4.core.http

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.takeFrom
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSessionConfiguration

/**
 * An [HttpClientFactory] whose clients all run on one Ktor Darwin engine, and so on one
 * `NSURLSession`: the platform's TLS, HTTP/2 and connection reuse (`KMP-112`).
 *
 * The engine is created once, here, and passed to `HttpClient(engine) { ... }`, which does not own
 * it — the same arrangement as `OkHttpClientFactory`. Closing a client stops that client only; the
 * engine lives as long as this factory. Hold one factory for the process, and never call
 * `client.engine.close()`.
 *
 * The session is [bounded]: nothing it fetches is written to the disk cache, and every request has
 * a timeout. Redirects are Ktor's `HttpRedirect` plugin, which refuses HTTPS to HTTP downgrades by
 * default.
 *
 * Only public addresses (`BUG-111`), the same rule as the JVM and Android clients (`BUG-105`, `BUG-109`), in two layers
 * that each cover what the other cannot:
 *
 * - **A name** goes through the process's [EgressGate], the session's only proxy in place of the system's. It is judged
 *   by the address it resolves to, at connect time, and connected to by that address.
 * - **An address** written in the URL, a host that is not a plain ASCII name, and anything but https are refused
 *   before the session sees them ([refuseUnsafeRequests]): the session sends loopback and link-local addresses past its
 *   proxy, so the gate would never see them.
 *
 * Both apply to every request the engine sends, each redirect's included. So a URL a plugin, a provider or a page chose
 * may be fetched here.
 */
fun HttpClientFactory(): HttpClientFactory = gatedFactory(EgressGate.shared)

/**
 * Checks the [HttpClientFactory] gate and listens again if iOS took its port while the app was suspended (`BUG-128`).
 * For the app to call as it returns to the foreground, before its first requests. It returns at once and does the work
 * on the gate's own threads; a request that fails meanwhile waits for that work and is sent again. Requests heal the
 * gate on their own too, so this only spares the first ones a failed attempt.
 */
fun reviveEgress() = EgressGate.shared.reviveInBackground()

/** [HttpClientFactory]'s clients, through [gate]. Internal so tests can hold the gate they assert on. */
internal fun gatedFactory(gate: EgressGate): HttpClientFactory = DarwinClientFactory(
    Darwin.create {
        configureSession { bounded(); gatedBy(gate) }
        configureRequest { refuseUnsafeRequests() }
    },
    revive = { gate.revive() },
)

/** Seconds a request may wait for data, mirroring OkHttp's 10 s defaults on `app.baseClient`. */
internal const val REQUEST_TIMEOUT_SECONDS: Double = 10.0

/** Seconds one request may take in all. `app.baseClient` has no such cap; every request here does. */
internal const val RESOURCE_TIMEOUT_SECONDS: Double = 60.0

/**
 * What every session this factory makes is set to.
 *
 * - **No cache.** A default configuration uses the shared `NSURLCache`, whose `Cache.db` keeps request
 *   URLs, with any token in their query, and response bodies. `URLCache = null` turns it off, and
 *   [NSURLRequestReloadIgnoringLocalCacheData] stops a request reading one.
 * - **Timeouts.** [REQUEST_TIMEOUT_SECONDS] between bytes and [RESOURCE_TIMEOUT_SECONDS] in all. The
 *   platform defaults are 60 s and 7 days.
 */
internal fun NSURLSessionConfiguration.bounded() {
    setURLCache(null)
    setRequestCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
    setTimeoutIntervalForRequest(REQUEST_TIMEOUT_SECONDS)
    setTimeoutIntervalForResource(RESOURCE_TIMEOUT_SECONDS)
}

/**
 * Every request through [gate], and through nothing else: the session's only proxy, for http and https alike, with no
 * exceptions, no auto-configuration and no SOCKS. Set on the session, it replaces the system's proxy settings outright.
 */
internal fun NSURLSessionConfiguration.gatedBy(gate: EgressGate) {
    setConnectionProxyDictionary(
        mapOf<Any?, Any?>(
            "HTTPEnable" to 1,
            "HTTPProxy" to LOOPBACK,
            "HTTPPort" to gate.port,
            "HTTPSEnable" to 1,
            "HTTPSProxy" to LOOPBACK,
            "HTTPSPort" to gate.port,
            "SOCKSEnable" to 0,
            "ProxyAutoConfigEnable" to 0,
            "ExceptionsList" to emptyList<String>(),
            "ExcludeSimpleHostnames" to 0,
        ),
    )
}

private const val LOOPBACK = "127.0.0.1"

/**
 * Clients over one [engine]. Internal so tests can build one over an engine without the gate, for a loopback server.
 *
 * @param revive checks the gate and listens again if it was dead, true only then (`BUG-128`, [EgressGate.revive]). A
 *   request that fails is sent once more when it returns true, and only then: a gate that was alive, or a failure
 *   that was the network's, is not retried. A body that cannot be sent twice is never retried.
 */
internal class DarwinClientFactory(
    private val engine: HttpClientEngine,
    private val revive: () -> Boolean = { false },
) : HttpClientFactory {
    override fun create(configure: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(engine, configure).also { client ->
        client.plugin(HttpSend).intercept { request ->
            try {
                execute(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // Off the caller's thread: checking may wait for the gate's accept loop, and a request made on the main
                // thread must not hold it meanwhile. Only the waiting moves; the loop still owns the listener.
                if (!request.body.canBeSentAgain() || !withContext(Dispatchers.IO) { revive() }) throw failure
                execute(HttpRequestBuilder().takeFrom(request))
            }
        }
    }

    /** Bytes or nothing. A channel's body may already be spent, so it is not sent twice. */
    private fun Any.canBeSentAgain(): Boolean = this is OutgoingContent.ByteArrayContent || this is OutgoingContent.NoContent
}

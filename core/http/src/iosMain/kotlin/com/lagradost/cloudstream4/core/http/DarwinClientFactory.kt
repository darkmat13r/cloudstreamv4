package com.lagradost.cloudstream4.core.http

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
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
 *
 * No request goes into the session until the gate has proved, within the last second, that it still holds its port
 * (`BUG-129`, [EgressGate.ensureOwned]). The session's settings name only the port, and anything else listening there
 * would be its proxy.
 */
fun HttpClientFactory(): HttpClientFactory = gatedFactory(EgressGate.shared)

/**
 * Checks the [HttpClientFactory] gate and listens again if iOS took its port while the app was suspended (`BUG-128`).
 * For the app to call as it returns to the foreground, before its first requests. It returns at once and does the work
 * on the gate's own threads; a request that fails meanwhile waits for that work and is sent again. Requests heal the
 * gate on their own too, so this only spares the first ones a failed attempt.
 *
 * It first forgets the gate's last proof (`BUG-129`), at once and on the caller's thread, so the first request after a
 * suspension is checked again whatever the clock says.
 */
fun reviveEgress() {
    EgressGate.shared.forgetProof()
    EgressGate.shared.reviveInBackground()
}

/** [HttpClientFactory]'s clients, through [gate]. Internal so tests can hold the gate they assert on. */
internal fun gatedFactory(gate: EgressGate): HttpClientFactory = DarwinClientFactory(
    Darwin.create {
        configureSession { bounded(); gatedBy(gate) }
        configureRequest { refuseUnsafeRequests() }
    },
    owned = { gate.ensureOwned() },
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
 * @param owned whether the session may send now: the gate has proved it holds its port (`BUG-129`,
 *   [EgressGate.ensureOwned]). Asked before every send, the retry's and each redirect's included, off the caller's
 *   thread; false refuses the request with [GateNotOwned] before the session sees it. There is no default: a factory
 *   over an engine without the gate says so (`owned = { true }`, tests only).
 * @param revive checks the gate and listens again if it was dead, true only then (`BUG-128`, [EgressGate.revive]). A
 *   request that fails is sent once more when it returns true, and only then: a gate that was alive, or a failure
 *   that was the network's, is not retried. A body that cannot be sent twice is never retried.
 */
internal class DarwinClientFactory(
    private val engine: HttpClientEngine,
    private val owned: () -> Boolean,
    private val revive: () -> Boolean = { false },
) : HttpClientFactory {
    /**
     * The check is a plugin installed in the client's configuration (`BUG-132`), so a copy made with
     * `HttpClient.config { }` carries it too: the download engine makes one for every run. An interceptor added to the
     * client after it was built, as before, is not part of its configuration, and the copy sent without the check.
     */
    override fun create(configure: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(engine) {
        configure()
        install(gateCheck())
    }

    private fun gateCheck() = createClientPlugin("EgressGateCheck") {
        on(Send) { request ->
            mayGo()
            try {
                proceed(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // Off the caller's thread: checking may wait for the gate's accept loop, and a request made on the main
                // thread must not hold it meanwhile. Only the waiting moves; the loop still owns the listener.
                if (!request.body.canBeSentAgain() || !withContext(Dispatchers.IO) { revive() }) throw failure
                mayGo()
                proceed(HttpRequestBuilder().takeFrom(request))
            }
        }
    }

    /** Refuses the send unless [owned], asked off the caller's thread: it may wait for the gate's accept loop. */
    private suspend fun mayGo() {
        if (!withContext(Dispatchers.IO) { owned() }) throw GateNotOwned()
    }

    /** Bytes or nothing. A channel's body may already be spent, so it is not sent twice. */
    private fun Any.canBeSentAgain(): Boolean = this is OutgoingContent.ByteArrayContent || this is OutgoingContent.NoContent
}

/**
 * The gate could not prove that it holds its port (`BUG-129`), so the request was not sent: another process may be
 * listening there, and would be the session's proxy. It says nothing more, and the destination is never in it.
 */
internal class GateNotOwned : Exception("refused: the egress gate does not hold its port")

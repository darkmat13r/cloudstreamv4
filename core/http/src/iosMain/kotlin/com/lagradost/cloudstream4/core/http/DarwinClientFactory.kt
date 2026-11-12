package com.lagradost.cloudstream4.core.http

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSessionConfiguration

/**
 * An [HttpClientFactory] whose clients all run on one Ktor Darwin engine, and so on one
 * `NSURLSession`: the platform's TLS, proxy settings, HTTP/2 and connection reuse (`KMP-112`).
 *
 * The engine is created once, here, and passed to `HttpClient(engine) { ... }`, which does not own
 * it — the same arrangement as `OkHttpClientFactory`. Closing a client stops that client only; the
 * engine lives as long as this factory. Hold one factory for the process, and never call
 * `client.engine.close()`.
 *
 * The session is [bounded]: nothing it fetches is written to the disk cache, and every request has
 * a timeout. Redirects are Ktor's `HttpRedirect` plugin, which refuses HTTPS to HTTP downgrades by
 * default.
 */
fun HttpClientFactory(): HttpClientFactory =
    DarwinClientFactory(Darwin.create { configureSession { bounded() } })

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

private class DarwinClientFactory(private val engine: HttpClientEngine) : HttpClientFactory {
    override fun create(configure: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(engine, configure)
}

package com.lagradost.cloudstream4.core.http

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.OkHttpClient

/**
 * An [HttpClientFactory] whose clients run on Ktor's OkHttp engine with
 * `preconfigured = okHttpClient`, so they share its dispatcher, connection pool, cache, DNS
 * resolver and interceptors. `:app` passes `app.baseClient`.
 *
 * - **Never pass `insecureApp.baseClient`** — it trusts every certificate.
 * - **Rebuild when the DNS setting changes.** `app.baseClient` is replaced, not mutated, and the
 *   engine derives its clients from the instance it was given.
 * - **Redirects are Ktor's.** The engine forces `followRedirects(false)` on its derived client and
 *   Ktor's `HttpRedirect` plugin follows them instead; it refuses HTTPS to HTTP downgrades by default.
 *
 * ## Why `close()` is safe here, and `engine.close()` is not
 *
 * In Ktor 3.6.0 `OkHttpEngine.close()` calls `connectionPool.evictAll()` and
 * `dispatcher.executorService.shutdown()` on every client it derived, and `newBuilder()` shares
 * both objects with [okHttpClient] — so closing the engine would break every later async call on
 * the app's client. `HttpClient(OkHttp) { ... }` owns its engine and closes it with the client.
 * The engine here is created separately and passed to `HttpClient(engine) { ... }`, which does not
 * own it: `HttpClient.close()` completes that client and leaves the engine and [okHttpClient]
 * running. Never call `client.engine.close()` or cancel `client.engine`.
 */
fun HttpClientFactory(okHttpClient: OkHttpClient): HttpClientFactory =
    OkHttpClientFactory(okHttpClient)

private class OkHttpClientFactory(private val okHttpClient: OkHttpClient) : HttpClientFactory {
    override fun create(configure: HttpClientConfig<*>.() -> Unit): HttpClient {
        // Unmanaged engine: must not come from HttpClient(OkHttp), see the KDoc above.
        val engine = OkHttp.create { preconfigured = okHttpClient }
        return HttpClient(engine, configure)
    }
}

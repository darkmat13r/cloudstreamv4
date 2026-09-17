package com.lagradost.cloudstream4.core.http

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

/**
 * Builds CloudStream's first-party Ktor clients over a transport the platform owns.
 *
 * Every client shares that transport (on JVM and Android: the app's OkHttp client, with its
 * DNS-over-HTTPS resolver, cache and interceptors), so hold one client for the process rather
 * than one per call. `close()` is not needed: it stops that client only, never the shared
 * transport.
 *
 * Platform entry points: `HttpClientFactory(okHttpClient)` in `jvmCommonMain`.
 */
interface HttpClientFactory {
    /** A new client over the shared transport, with [configure] applied (plugins, defaults). */
    fun create(configure: HttpClientConfig<*>.() -> Unit = {}): HttpClient
}

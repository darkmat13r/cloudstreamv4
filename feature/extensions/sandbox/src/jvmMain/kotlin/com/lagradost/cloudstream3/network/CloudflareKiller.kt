package com.lagradost.cloudstream3.network

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Passes the request straight through. `:app`'s real one solves a Cloudflare challenge in a WebView,
 * and a host has none, so a challenged site returns its challenge page and the plugin fails on that,
 * which names the real cause. 8 of 53 sampled plugins refer to this class, most only from a field set
 * up in their provider, so without it they fail to load at all rather than at the one site that needs it.
 */
class CloudflareKiller : Interceptor {
    val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    fun getCookieHeaders(@Suppress("UNUSED_PARAMETER") url: String): Headers = Headers.headersOf()

    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())

    companion object {
        const val TAG = "CloudflareKiller"
    }
}

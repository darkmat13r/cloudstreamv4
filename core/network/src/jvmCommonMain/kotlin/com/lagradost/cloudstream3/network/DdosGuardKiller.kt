package com.lagradost.cloudstream3.network

import androidx.annotation.AnyThread
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.cookies
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * @param alwaysBypass will pre-emptively fetch ddos guard cookies if true.
 * If false it will only try to get cookies when a request returns 403
 * */
// As seen in https://github.com/anime-dl/anime-downloader/blob/master/anime_downloader/sites/erairaws.py
@AnyThread
class DdosGuardKiller(private val alwaysBypass: Boolean) : Interceptor {
    val savedCookiesMap = mutableMapOf<String, Map<String, String>>()

    private var ddosBypassPath: String? = null

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val request = chain.request()
        if (alwaysBypass) return@runBlocking bypassDdosGuard(request)

        val response = chain.proceed(request)
        return@runBlocking if (response.code == 403) {
            bypassDdosGuard(request)
        } else response
    }

    /**
     * Set headers > Set cookies > Default headers > Default Cookies.
     *
     * A private copy of `RequestsHelperKt.getHeaders`, which lives in `:app` and cannot be reached
     * from here.
     */
    private fun mergeHeaders(
        headers: Map<String, String>,
        cookie: Map<String, String>
    ): Headers {
        val cookieMap =
            if (cookie.isNotEmpty()) mapOf(
                "Cookie" to cookie.entries.joinToString(" ") {
                    "${it.key}=${it.value};"
                }) else mapOf()
        val tempHeaders = (mapOf("user-agent" to USER_AGENT) + headers + cookieMap)
        return tempHeaders.toHeaders()
    }

    private suspend fun bypassDdosGuard(request: Request): Response {
        ddosBypassPath = ddosBypassPath ?: Regex("'(.*?)'").find(
            app.get(
                "https://check.ddos-guard.net/check.js"
            ).text
        )?.groupValues?.get(1)

        val cookies =
            savedCookiesMap[request.url.host]
            // If no cookies are found fetch and save em.
                ?: (request.url.scheme + "://" + request.url.host + (ddosBypassPath ?: "")).let {
                    // Somehow app.get fails
                    Requests().get(it).cookies.also { cookies ->
                        savedCookiesMap[request.url.host] = cookies
                    }
                }

        val headers = mergeHeaders(request.headers.toMap(), cookies + request.cookies)
        return app.baseClient.newCall(
            request.newBuilder()
                .headers(headers)
                .build()
        ).execute()
    }
}
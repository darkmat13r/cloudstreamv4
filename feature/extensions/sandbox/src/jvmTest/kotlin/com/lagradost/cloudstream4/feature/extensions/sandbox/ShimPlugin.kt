package com.lagradost.cloudstream4.feature.extensions.sandbox

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.DataStore.getKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Touches each `KMP-18` shim group the way a real plugin's `load()` did. Runs only in a sandboxed host. */
@CloudstreamPlugin
class ShimPlugin : Plugin() {
    override fun load(context: Context) {
        val posted = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post(Runnable { posted.countDown() })
        val handlerRan = posted.await(5, TimeUnit.SECONDS)
        CloudStreamApp.setKey("shim/key", "stored")
        val stored = CloudStreamApp.context.getKey("shim/key", String::class.java)
        val noActivity = CommonActivity.activity == null
        val killer = CloudflareKiller().getCookieHeaders("https://example.invalid").size
        val webView = runCatching { WebView(context) }.exceptionOrNull()?.javaClass?.simpleName
        registerMainAPI(ShimApi("$handlerRan|$stored|$noActivity|$killer|$webView"))
    }
}

class ShimApi(private val seen: String) : MainAPI() {
    override var name = "Shim"
    override var mainUrl = "https://example.invalid"
    override val supportedTypes = setOf(TvType.Others)

    override suspend fun search(query: String, page: Int): SearchResponseList =
        newSearchResponseList(
            listOf(
                newMovieSearchResponse(
                    "$seen|" + withContext(Dispatchers.Main) { Thread.currentThread().name },
                    "$mainUrl/1",
                    TvType.Others,
                    fix = false,
                )
            ),
            false,
        )
}

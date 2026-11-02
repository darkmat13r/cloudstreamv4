package com.lagradost.cloudstream4.feature.extensions.sandbox

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * The shape 72 of 89 sampled extensions have: extends `Plugin`, registers from `load(Context)`, and
 * uses the Android utility classes. It needs no network, so a result proves the shims rather than a
 * website. Also dexed with d8 into a real `.cs3` to prove the same path through dex2jar.
 */
@CloudstreamPlugin
class AndroidStylePlugin : Plugin() {
    override fun load(context: Context) {
        val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val runs = preferences.getInt("runs", 0) + 1
        preferences.edit().putInt("runs", runs).apply()
        Log.i("AndroidStyle", "loaded, run $runs")
        openSettings = { }
        registerMainAPI(AndroidStyleApi(runs))
    }
}

class AndroidStyleApi(private val runs: Int) : MainAPI() {
    override var name = "AndroidStyle"
    override var mainUrl = "https://example.invalid"
    override val supportedTypes = setOf(TvType.Others)

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val decoded = String(Base64.decode(Base64.encodeToString(query.toByteArray(), Base64.DEFAULT), Base64.DEFAULT))
        val host = Uri.parse("https://example.invalid/watch?q=x").getHost()
        val item = newMovieSearchResponse("$decoded|$host|run=$runs", "$mainUrl/1", TvType.Others, fix = false)
        return newSearchResponseList(listOf(item), false)
    }
}

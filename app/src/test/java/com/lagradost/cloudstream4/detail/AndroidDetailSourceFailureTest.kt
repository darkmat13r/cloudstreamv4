package com.lagradost.cloudstream4.detail

import android.content.ContextWrapper
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.HostileFailures
import com.lagradost.cloudstream4.search.encodeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * `BUG-63`: the detail page's failure line is a provider's own words, so it must carry no address and no user path,
 * however they are hidden. Through the real `details`, `APIRepository.load` and `safeApiCall`, with a provider that
 * throws each hostile case.
 */
class AndroidDetailSourceFailureTest {

    private class Throwing(private val thrown: Throwable) : MainAPI() {
        override var name = "BUG-63 hostile"
        override var mainUrl = "https://hostile.invalid"
        override val supportedTypes = setOf(TvType.Movie)
        override suspend fun load(url: String): LoadResponse? = throw thrown
    }

    private var registered: MainAPI? = null

    @After
    fun unregister() {
        registered?.let(APIHolder::removePluginMapping)
    }

    /** What the page's failure says when the provider throws [thrown]. */
    private fun failureFor(thrown: Throwable, index: Int): String = runBlocking {
        val api = Throwing(thrown)
        registered?.let(APIHolder::removePluginMapping)
        APIHolder.addPluginMapping(api)
        registered = api
        val source = AndroidDetailSource(ContextWrapper(null), io = Dispatchers.Unconfined)
        // A url per case, so the repository's ten-minute cache never answers for another.
        val id = encodeId(apiName = api.name, url = "${api.mainUrl}/title/$index").value
        val failure = runCatching { source.details(id) }.exceptionOrNull()
        assertTrue("case $index did not fail: $failure", failure is IOException)
        failure!!.message.orEmpty()
    }

    @Test
    fun noHostileFailureReachesThePageWithATokenOrAPath() {
        val forgedFrame = NullPointerException().apply {
            stackTrace = arrayOf(StackTraceElement("a.B", "c", "https://cdn.example/k?token=SECRET99 provider.kt", 7))
        }
        // A plain exception's text starts on the second line, which the page does not show; `ErrorLoadingException`'s
        // is the first line, as is a `NullPointerException`'s frame. Each text is thrown both ways.
        val thrown = HostileFailures.cases.flatMap { (label, text) ->
            listOf("$label (IOException)" to IOException(text), "$label (ErrorLoadingException)" to ErrorLoadingException(text))
        } + ("forged NullPointerException frame" to forgedFrame)
        // Every case is tried, and every one that leaks is named, so a failure shows all of them at once.
        val leaks = thrown.mapIndexedNotNull { index, (label, error) ->
            val shown = failureFor(error, index)
            HostileFailures.leakIn(shown)?.let { "$label: $it in \"$shown\"" }
        }
        assertEquals(emptyList<String>(), leaks)
    }
}

package com.lagradost.cloudstream4.search

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream4.feature.search.SearchOutcome
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

/** Typing runs quick search, and it has to mean what the field says: every source. */
class DesktopSearchSourceTest {

    /** @param declaresQuick what it claims. It never implements quick search, like a real plugin can. */
    private class Fake(override var name: String, private val declaresQuick: Boolean) : MainAPI() {
        override var mainUrl = "https://example.com"
        override val hasQuickSearch: Boolean get() = declaresQuick

        override suspend fun search(query: String, page: Int): SearchResponseList =
            newSearchResponseList(listOf<SearchResponse>(newMovieSearchResponse("$name hit", "u", TvType.Movie)), false)
    }

    private val fakes = listOf(Fake("Honest", declaresQuick = false), Fake("Liar", declaresQuick = true))

    @BeforeTest
    fun setUp() {
        fakes.forEach(APIHolder::addPluginMapping)
    }

    @AfterTest
    fun tearDown() {
        fakes.forEach(APIHolder::removePluginMapping)
    }

    @Test
    fun `typing reaches every source, including ones with no working quick search`() = runBlocking {
        val outcome = assertIs<SearchOutcome.Complete>(DesktopSearchSource().quickSearch("q"))
        assertEquals(setOf("Honest hit", "Liar hit"), outcome.items.map { it.title }.toSet())
    }
}

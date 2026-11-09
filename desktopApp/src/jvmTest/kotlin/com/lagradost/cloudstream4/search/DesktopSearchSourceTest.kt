package com.lagradost.cloudstream4.search

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchQuality
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
import kotlin.test.assertNull
import java.util.Locale
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

    /** One provider's page: a film in 4K, a series with no quality, and one with neither. */
    private class Labelled : MainAPI() {
        override var name = "Labelled"
        override var mainUrl = "https://example.com"

        override suspend fun search(query: String, page: Int): SearchResponseList = newSearchResponseList(
            listOf(
                newMovieSearchResponse("Film", "f", TvType.Movie) { quality = SearchQuality.FourK },
                newMovieSearchResponse("Show", "s", TvType.TvSeries),
                newMovieSearchResponse("Bare", "b", TvType.Movie) { type = null },
            ),
            false,
        )
    }

    @Test
    fun `a card's second line is its type and quality in words, and nothing it was not sent (KMP-101, S2)`() = runBlocking {
        val labelled = Labelled()
        fakes.forEach(APIHolder::removePluginMapping)
        APIHolder.addPluginMapping(labelled)
        val locale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val outcome = assertIs<SearchOutcome.Complete>(DesktopSearchSource().search("q"))
            val byTitle = outcome.items.associateBy { it.title }
            assertEquals("Movie", byTitle.getValue("Film").typeLabel)
            assertEquals("4K", byTitle.getValue("Film").qualityLabel)
            assertEquals("Series", byTitle.getValue("Show").typeLabel)
            assertNull(byTitle.getValue("Show").qualityLabel, "no quality sent, none drawn")
            assertNull(byTitle.getValue("Bare").typeLabel)
            assertNull(byTitle.getValue("Bare").qualityLabel)
        } finally {
            Locale.setDefault(locale)
            APIHolder.removePluginMapping(labelled)
            fakes.forEach(APIHolder::addPluginMapping)
        }
    }

    @Test
    fun `each distinct type and quality is resolved once a search, not once a card (KMP-101)`() = runBlocking {
        val asked = mutableListOf<Any>()
        val counting = object : DesktopResultLabels() {
            override suspend fun type(type: TvType): String? = type.name.also { asked += type }
            override suspend fun quality(quality: SearchQuality): String? = quality.name.also { asked += quality }
        }
        val outcome = assertIs<SearchOutcome.Complete>(DesktopSearchSource(counting).search("q"))
        assertEquals(2, outcome.items.size, "both fakes' films")
        assertEquals(listOf<Any>(TvType.Movie), asked)
        assertEquals("Movie", outcome.items.first().typeLabel)
    }
}

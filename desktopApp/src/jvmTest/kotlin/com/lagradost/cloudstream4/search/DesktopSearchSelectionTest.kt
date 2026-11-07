package com.lagradost.cloudstream4.search

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream4.feature.search.SearchOutcome
import com.lagradost.cloudstream4.feature.search.SearchSourceHealth
import com.lagradost.cloudstream4.feature.search.SearchTypeFilter
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** `KMP-56`: the panel's picks and S1's chips, held for the app's life, and a failure's words with no places in them. */
class DesktopSearchSelectionTest {

    private class Fake(
        override var name: String,
        override val supportedTypes: Set<TvType>,
        private val failure: String? = null,
    ) : MainAPI() {
        override var mainUrl = "https://example.com"

        override suspend fun search(query: String, page: Int): SearchResponseList {
            failure?.let { throw IOException(it) }
            return newSearchResponseList(listOf<SearchResponse>(newMovieSearchResponse("$name hit", "u", TvType.Movie)), false)
        }
    }

    private val films = Fake("Films", setOf(TvType.Movie))
    private val anime = Fake("Animes", setOf(TvType.Anime))
    private val fakes = listOf(films, anime)

    @BeforeTest
    fun setUp() = fakes.forEach(APIHolder::addPluginMapping)

    @AfterTest
    fun tearDown() = fakes.forEach(APIHolder::removePluginMapping)

    private suspend fun DesktopSearchSource.hits(): Set<String> =
        assertIs<SearchOutcome.Complete>(search("q")).items.mapTo(HashSet()) { it.providerName }

    @Test
    fun `a first launch asks every provider, and done narrows the search to the rows left on`() = runBlocking {
        val source = DesktopSearchSource()
        assertEquals(2 to 2, source.sources().let { it.selected to it.total })
        assertTrue(source.choices().all { it.selected })

        source.selectSources(setOf("Films"))
        assertEquals(setOf("Films"), source.hits())
        assertEquals(1 to 2, source.sources().let { it.selected to it.total })
    }

    @Test
    fun `chips are the types some provider serves, and a lit one narrows the search and the panel`() = runBlocking {
        val source = DesktopSearchSource()
        assertEquals(listOf(SearchTypeFilter.Movies, SearchTypeFilter.Anime), source.typeFilters().available)

        source.selectTypes(setOf(SearchTypeFilter.Anime))
        assertEquals(setOf(SearchTypeFilter.Anime), source.typeFilters().selected)
        assertEquals(setOf("Animes"), source.hits())
        assertEquals(listOf("Animes"), source.choices().map { it.id })
    }

    @Test
    fun `a row the chips hid keeps its state through done, and search everything clears both`() = runBlocking {
        val source = DesktopSearchSource()
        source.selectTypes(setOf(SearchTypeFilter.Anime))
        // Only Animes is offered; turning it off must not turn off the hidden Films.
        source.selectSources(emptySet())
        source.selectTypes(emptySet())
        assertEquals(mapOf("Animes" to false, "Films" to true), source.choices().associate { it.id to it.selected })

        source.clearFilters()
        assertEquals(setOf("Films", "Animes"), source.hits())
        assertTrue(source.typeFilters().selected.isEmpty())
    }

    @Test
    fun `a pick that matches nothing installed falls back to every provider`() = runBlocking {
        val source = DesktopSearchSource()
        source.selectSources(setOf("Gone"))
        assertEquals(setOf("Films", "Animes"), source.hits())
    }

    @Test
    fun `the panel marks a provider by its last answer`() = runBlocking {
        val broken = Fake("Broken", setOf(TvType.Movie), failure = "HTTP 500")
        APIHolder.addPluginMapping(broken)
        try {
            val source = DesktopSearchSource()
            assertTrue(source.choices().all { it.health == SearchSourceHealth.Unknown })
            source.search("q")
            val health = source.choices().associate { it.id to it.health }
            assertEquals(SearchSourceHealth.Failed, health["Broken"])
            assertEquals(SearchSourceHealth.Ok, health["Films"])
        } finally {
            APIHolder.removePluginMapping(broken)
        }
    }

    /** As `#200` for Home: the detail reaches the screen and a pasted bug report. */
    @Test
    fun `a failure's detail carries no address and no home directory`() = runBlocking {
        val home = System.getProperty("user.home")
        val leaky = Fake("Leaky", setOf(TvType.Movie), failure = "HTTP 403 for https://cdn.example.com/x?token=s3cret at $home/.cloudstream4/a")
        APIHolder.addPluginMapping(leaky)
        try {
            val partial = assertIs<SearchOutcome.Partial>(DesktopSearchSource().search("q"))
            assertTrue("HTTP 403" in partial.detail, partial.detail)
            // The host stays, as in every copy of the rules; the path and the token never do.
            assertTrue("/x" !in partial.detail && "s3cret" !in partial.detail, partial.detail)
            assertTrue(home !in partial.detail, partial.detail)
        } finally {
            APIHolder.removePluginMapping(leaky)
        }
    }

    /** `#221` review: cleaning after redacting would join a split url back together, on screen. */
    @Test
    fun `a url split by an invisible character does not reach the detail`() = runBlocking {
        val split = Fake("Split", setOf(TvType.Movie), failure = "HTTP 403 for https:\u200B//cdn.example.com/x?token=s3cret")
        APIHolder.addPluginMapping(split)
        try {
            val partial = assertIs<SearchOutcome.Partial>(DesktopSearchSource().search("q"))
            assertTrue("s3cret" !in partial.detail && "/x" !in partial.detail, partial.detail)
        } finally {
            APIHolder.removePluginMapping(split)
        }
    }
}

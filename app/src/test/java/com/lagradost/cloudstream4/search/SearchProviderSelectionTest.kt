package com.lagradost.cloudstream4.search

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream4.feature.search.SearchContent
import com.lagradost.cloudstream4.feature.search.SearchResultGroup
import com.lagradost.cloudstream4.feature.search.SearchResultId
import com.lagradost.cloudstream4.feature.search.SearchResultItem
import com.lagradost.cloudstream4.feature.search.SearchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The non-Android half of the TV search glue. Plain JUnit, for the reason `DataStoreSearchHistoryTest` gives. */
class SearchProviderSelectionTest {

    private fun provider(name: String, vararg types: TvType): MainAPI = object : MainAPI() {
        override var name = name
        override val supportedTypes = types.toSet()
    }

    private val movies = provider("Movies", TvType.Movie)
    private val anime = provider("Anime", TvType.Anime)
    private val adult = provider("Adult", TvType.NSFW)
    private val installed = listOf(movies, anime, adult)
    private val allNames = setOf("Movies", "Anime", "Adult")

    /** The default of `prefer_media_type_key`: everything but NSFW. */
    private val defaultTypes = TvType.entries.filter { it != TvType.NSFW }.map { it.ordinal }.toSet()

    private fun select(
        languageEnabled: Set<String> = allNames,
        preferred: Set<Int> = defaultTypes,
        picked: Set<String> = allNames,
    ) = selectSearchProviders(installed, languageEnabled, preferred, picked).map { it.name }

    @Test
    fun `nsfw providers stay out unless the user prefers that type`() {
        assertEquals(listOf("Movies", "Anime"), select())
        assertEquals(listOf("Adult"), select(preferred = setOf(TvType.NSFW.ordinal)))
    }

    @Test
    fun `the user's picks narrow the search`() {
        assertEquals(listOf("Anime"), select(picked = setOf("Anime")))
    }

    @Test
    fun `a disabled language wins over a pick`() {
        assertEquals(listOf("Movies"), select(languageEnabled = setOf("Movies"), picked = setOf("Movies", "Anime")))
    }

    /** A pick that no longer exists must not strand the user: the panel that changes picks is not built. */
    @Test
    fun `when no pick survives it falls back to the eligible set and never to nsfw`() {
        assertEquals(listOf("Movies", "Anime"), select(picked = setOf("Uninstalled")))
    }

    @Test
    fun `nothing eligible is an empty selection, which the source reports as a failure`() {
        assertTrue(select(languageEnabled = emptySet()).isEmpty())
    }

    @Test
    fun `pinned come first in reverse pin order and the rest keep their order`() {
        val sorted = pinnedFirst(listOf("a", "b", "c", "d"), arrayOf("d", "b")) { it }
        assertEquals(listOf("b", "d", "a", "c"), sorted)
    }

    @Test
    fun `one character is not recorded, matching the old path`() {
        assertFalse(isRecordable("d"))
        assertFalse(isRecordable(""))
        assertTrue(isRecordable("du"))
    }

    @Test
    fun `a result's title is found by id in full and partial results`() {
        val id = SearchResultId("x")
        val results = SearchContent.Results(
            listOf(SearchResultGroup("p", listOf(SearchResultItem(id = id, title = "Dune", providerName = "p")))),
        )
        assertEquals("Dune", SearchStatus.Showing(results).titleOf(id))
        assertEquals("Dune", SearchStatus.Partial(results, 1, 2, "d").titleOf(id))
        assertNull(SearchStatus.Showing(results).titleOf(SearchResultId("gone")))
        assertNull(SearchStatus.Idle.titleOf(id))
    }
}

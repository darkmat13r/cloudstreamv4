package com.lagradost.cloudstream4.feature.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 8a's column: one ranked list from two origins, collapsed ignoring case, with counts that are only ever final. */
class SearchSuggestionsTest {

    private fun recent(vararg queries: String) = queries.map { SearchHistoryEntry(it, "2 days ago") }

    private fun item(title: String, provider: String) =
        SearchResultItem(id = SearchResultId("$provider/$title"), title = title, providerName = provider)

    @Test
    fun `the board's own case collapses dune and Dune into one row with both origins`() {
        val rows = mergeSuggestions(
            typed = "dune",
            suggestions = listOf("Dune", "Dune: Part Two", "Dune: Prophecy", "Dune (1984)", "Duna"),
            recent = recent("dune", "dune messiah", "blade runner"),
        )

        assertEquals(
            listOf("dune", "Dune: Part Two", "Dune: Prophecy", "Dune (1984)", "Duna", "dune messiah"),
            rows.map { it.query },
        )
        val first = rows.first()
        assertTrue(first.suggested, "the collapsed row keeps the suggestion's origin")
        assertEquals("2 days ago", first.recentWhen, "and the recent search's")
        assertEquals(1, rows.count { it.query.equals("dune", ignoreCase = true) }, "one row, not two")
        assertFalse(rows.last().suggested, "a recent search nobody suggested is not marked suggested")
    }

    @Test
    fun `at most six rows, and recent searches keep two of them`() {
        val rows = mergeSuggestions(
            typed = "a",
            suggestions = (1..10).map { "Alpha $it" },
            recent = recent("a b", "a c", "a d"),
        )

        assertEquals(SUGGESTION_ROWS, rows.size)
        assertEquals(listOf("a b", "a c"), rows.filter { it.recentWhen != null }.map { it.query })
        assertEquals((1..4).map { "Alpha $it" }, rows.filter { it.suggested }.map { it.query })
    }

    @Test
    fun `with few suggestions, recent searches fill the room`() {
        val rows = mergeSuggestions(typed = "a", suggestions = listOf("Alpha"), recent = recent("a b", "a c", "a d", "a e"))

        assertEquals(listOf("Alpha", "a b", "a c", "a d", "a e"), rows.map { it.query })
    }

    @Test
    fun `a recent search that does not contain the typed words is not a row`() {
        val rows = mergeSuggestions(typed = "dune", suggestions = emptyList(), recent = recent("blade runner", "DUNE messiah"))

        assertEquals(listOf("DUNE messiah"), rows.map { it.query })
    }

    @Test
    fun `nothing typed is no rows`() {
        assertTrue(mergeSuggestions(typed = "  ", suggestions = listOf("Dune"), recent = recent("dune")).isEmpty())
    }

    @Test
    fun `a count is the providers carrying the title, each once, ignoring case`() {
        val rows = listOf(SearchSuggestion("dune"), SearchSuggestion("Dune: Part Two"), SearchSuggestion("dune messiah"))
        val counted = rows.withSourceCounts(
            listOf(
                item("Dune", "Filmxy"),
                item("DUNE", "Vidplay"),
                item("Dune", "Vidplay"),
                item("Dune: Part Two", "Filmxy"),
                item("Dune: Part Two (4K)", "Zoro"),
            ),
        )

        assertEquals(listOf(2, 1, 0), counted.map { it.sources })
    }

    @Test
    fun `before the counts, a row has none rather than zero`() {
        assertEquals(null, SearchSuggestion("dune").sources)
    }
}

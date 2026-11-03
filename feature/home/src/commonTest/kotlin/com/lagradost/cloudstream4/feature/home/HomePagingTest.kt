package com.lagradost.cloudstream4.feature.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HomePagingTest {
    private fun item(id: String) = HomeItem(id, "Title $id")
    private fun page(title: String, vararg ids: String) = HomeRowPage(title, false, ids.map(::item))
    private fun loaded(vararg rows: HomeRowPage, hasNext: Boolean = false) = HomePageReply.Loaded(rows.toList(), hasNext)

    @Test
    fun `sections that all answer are shown, in section order whichever answered first`() {
        val status = HomePaging.fold(emptyList(), listOf(1 to loaded(page("B", "b")), 0 to loaded(page("A", "a"))), 2)
        assertEquals(listOf("A", "B"), assertIs<HomeStatus.Showing>(status).rows.map { it.title })
    }

    @Test
    fun `a section that fails leaves the others on screen and is named for the retry`() {
        val status = HomePaging.fold(
            emptyList(),
            listOf(0 to loaded(page("A", "a")), 1 to HomePageReply.Failed("HTTP 503"), 2 to HomePageReply.Offline),
            3,
        )
        val partial = assertIs<HomeStatus.Partial>(status)
        assertEquals(listOf("A"), partial.rows.map { it.title })
        assertEquals(listOf(1, 2), partial.failedSections)
        assertEquals(3, partial.total)
        assertEquals("HTTP 503 · OFFLINE", partial.detail)
    }

    @Test
    fun `nothing failed and nothing to show is empty, which is not a failure`() {
        assertEquals(HomeStatus.Empty, HomePaging.fold(emptyList(), listOf(0 to loaded(page("A"))), 1))
        assertEquals(HomeStatus.Empty, HomePaging.fold(emptyList(), emptyList(), 0))
    }

    @Test
    fun `every section failing is a failure, and offline only when all of them were`() {
        assertEquals(
            HomeStatus.Offline,
            HomePaging.fold(emptyList(), listOf(0 to HomePageReply.Offline, 1 to HomePageReply.Offline), 2),
        )
        assertEquals(
            HomeStatus.Failed("X · OFFLINE"),
            HomePaging.fold(emptyList(), listOf(0 to HomePageReply.Failed("X"), 1 to HomePageReply.Offline), 2),
        )
        // One section answered with nothing, so the network was there: this is not an offline machine.
        assertEquals(
            HomeStatus.Failed("OFFLINE"),
            HomePaging.fold(emptyList(), listOf(0 to loaded(page("A")), 1 to HomePageReply.Offline), 2),
        )
    }

    @Test
    fun `reasons are distinct and limited, because the line is one line`() {
        val replies = listOf("A", "A", "B", "C", "D").mapIndexed { i, why -> i to HomePageReply.Failed(why) }
        assertEquals(HomeStatus.Failed("A · B · C"), HomePaging.fold(emptyList(), replies, 5))
    }

    @Test
    fun `a next page joins its row, drops repeats, and a section's rows page together`() {
        val first = HomePaging.merge(emptyList(), 0, 1, loaded(page("A", "1", "2"), page("B", "x"), hasNext = true))
        val next = HomePaging.merge(first, 0, 2, loaded(page("A", "2", "3"), page("C", "y"), hasNext = true))
        assertEquals(listOf("A", "B", "C"), next.map { it.title })
        assertEquals(listOf("1", "2", "3"), next[0].items.map { it.id })
        assertTrue(next.all { it.page == 2 && it.hasMore })
    }

    @Test
    fun `a page that adds nothing ends the section whatever it claims`() {
        val first = HomePaging.merge(emptyList(), 0, 1, loaded(page("A", "1"), hasNext = true))
        assertTrue(first.single().hasMore)
        val repeat = HomePaging.merge(first, 0, 2, loaded(page("A", "1"), hasNext = true))
        assertFalse(repeat.single().hasMore)
    }

    @Test
    fun `a row that names the same title twice cannot make two rows`() {
        val rows = HomePaging.merge(emptyList(), 0, 1, loaded(page("A", "1"), page("A", "2")))
        assertEquals(listOf("1", "2"), rows.single().items.map { it.id })
    }

    @Test
    fun `a source cannot grow a row or a page without limit`() {
        val many = Array(HomePaging.MAX_ROW_ITEMS + 50) { "$it" }
        val row = HomePaging.merge(emptyList(), 0, 1, loaded(page("A", *many), hasNext = true)).single()
        assertEquals(HomePaging.MAX_ROW_ITEMS, row.items.size)
        assertFalse(row.hasMore, "a full row stops asking")

        val rows = Array(HomePaging.MAX_ROWS + 10) { page("R$it", "$it") }
        assertEquals(HomePaging.MAX_ROWS, HomePaging.merge(emptyList(), 0, 1, loaded(*rows)).size)
    }

    @Test
    fun `other sections keep their paging`() {
        val a = HomePaging.merge(emptyList(), 0, 1, loaded(page("A", "1"), hasNext = true))
        val b = HomePaging.merge(a, 1, 1, loaded(page("B", "2"), hasNext = false))
        assertTrue(b.first { it.title == "A" }.hasMore)
        assertFalse(b.first { it.title == "B" }.hasMore)
    }
}

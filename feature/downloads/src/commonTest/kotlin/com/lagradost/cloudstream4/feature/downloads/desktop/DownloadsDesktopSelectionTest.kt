package com.lagradost.cloudstream4.feature.downloads.desktop

import com.lagradost.cloudstream4.feature.downloads.DownloadTitle
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleId
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleKind
import com.lagradost.cloudstream4.feature.downloads.DownloadsAction
import com.lagradost.cloudstream4.feature.downloads.DownloadsState
import com.lagradost.cloudstream4.feature.downloads.DownloadsStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Finder's selection, said as the view model's actions. Each case replays the actions through the view model's own
 * rules for them (choosing starts empty, an activation toggles while choosing), so what is checked is the selection
 * the user ends up with, not the list of actions.
 */
class DownloadsDesktopSelectionTest {

    private val ids = (1..5).map(::DownloadTitleId)
    private val showing = DownloadsState(
        status = DownloadsStatus.Showing(ids.map { DownloadTitle(it, "t${it.value}", DownloadTitleKind.Film, 1, 1, 1) }),
    )

    /** The view model's reducer for the actions this file emits; `DownloadsViewModelTest` pins the real one. */
    private fun DownloadsState.after(actions: List<DownloadsAction>): DownloadsState = actions.fold(this) { state, action ->
        when (action) {
            DownloadsAction.SelectionStarted -> state.copy(selection = emptySet())
            DownloadsAction.SelectionCancelled -> state.copy(selection = null)
            DownloadsAction.SelectAllToggled -> state.copy(selection = ids.toSet())
            is DownloadsAction.TitleActivated -> state.selection?.let { chosen ->
                state.copy(selection = if (action.id in chosen) chosen - action.id else chosen + action.id)
            } ?: state
            else -> state
        }
    }

    private fun DownloadsState.chosen(vararg values: Int) = copy(selection = values.map(::DownloadTitleId).toSet())

    @Test
    fun `a click replaces the selection with one row`() {
        val state = showing.chosen(1, 2)
        assertEquals(setOf(ids[3]), state.after(DownloadsDesktopSelection.click(state, ids[3])).selection)
    }

    @Test
    fun `a click on the only selected row does nothing`() {
        val state = showing.chosen(4)
        assertEquals(emptyList(), DownloadsDesktopSelection.click(state, ids[3]))
    }

    @Test
    fun `command click adds and removes, starting from nothing`() {
        val one = showing.after(DownloadsDesktopSelection.commandClick(showing, ids[0]))
        val two = one.after(DownloadsDesktopSelection.commandClick(one, ids[2]))
        assertEquals(setOf(ids[0], ids[2]), two.selection)
        assertEquals(setOf(ids[2]), two.after(DownloadsDesktopSelection.commandClick(two, ids[0])).selection)
    }

    @Test
    fun `shift click takes the range from the anchor, either way round, and nothing else`() {
        val state = showing.chosen(1, 5)
        assertEquals(ids.subList(1, 4).toSet(), state.after(DownloadsDesktopSelection.shiftClick(state, ids[1], ids[3])).selection)
        assertEquals(ids.subList(1, 4).toSet(), state.after(DownloadsDesktopSelection.shiftClick(state, ids[3], ids[1])).selection)
    }

    @Test
    fun `shift click with no anchor is a click`() {
        assertEquals(setOf(ids[2]), showing.after(DownloadsDesktopSelection.shiftClick(showing, null, ids[2])).selection)
    }

    @Test
    fun `command A takes every row`() {
        assertEquals(ids.toSet(), showing.after(DownloadsDesktopSelection.all(showing)).selection)
    }

    @Test
    fun `opening ends the selection before it opens, so the view model opens rather than toggles`() {
        val state = showing.chosen(2)
        assertEquals(
            listOf(DownloadsAction.SelectionCancelled, DownloadsAction.TitleActivated(ids[1])),
            DownloadsDesktopSelection.open(state, ids[1]),
        )
        assertEquals(listOf<DownloadsAction>(DownloadsAction.TitleActivated(ids[1])), DownloadsDesktopSelection.open(showing, ids[1]))
    }

    @Test
    fun `delete acts on the selection when the row is in it, and on the row alone when it is not`() {
        val state = showing.chosen(1, 2)
        assertEquals(listOf<DownloadsAction>(DownloadsAction.DeleteSelected), DownloadsDesktopSelection.delete(state, ids[0]))
        val other = DownloadsDesktopSelection.delete(state, ids[4])
        assertEquals(DownloadsAction.DeleteSelected, other.last())
        assertEquals(setOf(ids[4]), state.after(other.dropLast(1)).selection)
    }

    @Test
    fun `command delete with nothing selected does nothing`() {
        assertEquals(emptyList(), DownloadsDesktopSelection.delete(showing, null))
        assertEquals(emptyList(), DownloadsDesktopSelection.delete(showing.chosen(), null))
    }

    @Test
    fun `a right click keeps a selection that holds the row, and otherwise selects the row`() {
        val state = showing.chosen(1, 2)
        assertEquals(emptyList(), DownloadsDesktopSelection.contextMenu(state, ids[1]))
        assertEquals(setOf(ids[3]), state.after(DownloadsDesktopSelection.contextMenu(state, ids[3])).selection)
    }
}

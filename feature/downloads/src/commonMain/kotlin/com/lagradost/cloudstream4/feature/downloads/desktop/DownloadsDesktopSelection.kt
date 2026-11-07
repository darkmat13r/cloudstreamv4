package com.lagradost.cloudstream4.feature.downloads.desktop

import com.lagradost.cloudstream4.feature.downloads.DownloadTitleId
import com.lagradost.cloudstream4.feature.downloads.DownloadsAction
import com.lagradost.cloudstream4.feature.downloads.DownloadsState

/**
 * A pointer's and a keyboard's selection, as the view model's actions (board D2).
 *
 * The view model was written for TV, where choosing is a mode you enter with `Select`. On a Mac it is not a mode: a click
 * selects, ⌘-click adds, ⇧-click extends and ⌘A takes everything, as in Finder. Each gesture is said here as the actions
 * that leave the view model's `selection` where Finder's would be, so the view model is shared unchanged and only this
 * file knows how a Mac chooses. An empty selection and none are the same thing on this screen.
 */
internal object DownloadsDesktopSelection {

    /** A click with no modifier: only this one. */
    fun click(state: DownloadsState, id: DownloadTitleId): List<DownloadsAction> =
        if (state.selection == setOf(id)) emptyList() else
            listOf(DownloadsAction.SelectionStarted, DownloadsAction.TitleActivated(id))

    /** ⌘-click: this one in or out, keeping the rest. */
    fun commandClick(state: DownloadsState, id: DownloadTitleId): List<DownloadsAction> =
        listOfNotNull(DownloadsAction.SelectionStarted.takeIf { state.selection == null }, DownloadsAction.TitleActivated(id))

    /** ⇧-click: every row from [anchor] to this one, in the order they are shown, and nothing else. */
    fun shiftClick(state: DownloadsState, anchor: DownloadTitleId?, id: DownloadTitleId): List<DownloadsAction> {
        val order = state.titles.map { it.id }
        val from = order.indexOf(anchor).takeIf { it >= 0 } ?: return click(state, id)
        val to = order.indexOf(id).takeIf { it >= 0 } ?: return emptyList()
        val range = order.subList(minOf(from, to), maxOf(from, to) + 1)
        return listOf(DownloadsAction.SelectionStarted) + range.map { DownloadsAction.TitleActivated(it) }
    }

    /** ⌘A. */
    fun all(state: DownloadsState): List<DownloadsAction> =
        if (state.titles.isEmpty()) emptyList() else listOf(DownloadsAction.SelectionStarted, DownloadsAction.SelectAllToggled)

    /** Escape, or a click on no row. */
    fun clear(state: DownloadsState): List<DownloadsAction> =
        if (state.selection == null) emptyList() else listOf(DownloadsAction.SelectionCancelled)

    /** A double-click, Return on one selected row, or the menu's Open: ends the selection, then opens. */
    fun open(state: DownloadsState, id: DownloadTitleId): List<DownloadsAction> =
        clear(state) + DownloadsAction.TitleActivated(id)

    /**
     * ⌘⌫, the menu's Delete or a row's Delete. Acts on the selection when [id] is in it, and otherwise on [id] alone, as
     * Finder does: a right-click on an unselected row selects it first. The view model then asks the host to confirm.
     */
    fun delete(state: DownloadsState, id: DownloadTitleId?): List<DownloadsAction> {
        val chosen = state.selection.orEmpty()
        val select = if (id == null || id in chosen) emptyList() else click(state, id)
        return if (id == null && chosen.isEmpty()) emptyList() else select + DownloadsAction.DeleteSelected
    }

    /** What a right-click acts on: the selection when the row is in it, else the row, which it selects. */
    fun contextMenu(state: DownloadsState, id: DownloadTitleId): List<DownloadsAction> =
        if (id in state.selection.orEmpty()) emptyList() else click(state, id)

    /** The one selected row, which Return opens and ⌘R shows in Finder. */
    fun single(state: DownloadsState): DownloadTitleId? = state.selection?.singleOrNull()
}

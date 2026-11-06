package com.lagradost.cloudstream4.feature.library

import androidx.compose.runtime.Immutable

/**
 * Which list the screen is showing.
 *
 * [ContinueWatching] is **derived, not stored**: it is the resumable positions, newest first, and nothing
 * writes to it. Everything else is a stored bucket. One list at a time, the way home shows one source —
 * a desktop window is wide, not tall, and seven lists stacked is a scroll nobody finishes.
 */
sealed interface LibraryTab {
    data object ContinueWatching : LibraryTab

    data class Bucket(val bucket: LibraryBucket) : LibraryTab

    /**
     * One of a tracking service's own lists ([SyncedList]), named in its words. Never in [all]: which there are
     * depends on the source, and only a screen with [LibraryState.sources] shows them.
     */
    data class Synced(val key: String, val label: String) : LibraryTab

    companion object {
        /** The order of the selector. Continue watching first, because it is the one with an answer. */
        val all: List<LibraryTab> = listOf(ContinueWatching) + LibraryBucket.entries.map(::Bucket)
    }
}

/** Where resuming this card would start. */
@Immutable
data class LibraryResume(val episodeData: String?, val positionMillis: Long)

/**
 * One card on the screen.
 *
 * @param progress `0f..1f` where something is part-watched, null where it is not. A bucket card shows it
 *   too, so a list of things you are watching says how far through each one is.
 * @param resume non-null only where there is somewhere to come back to. On a continue-watching card it is
 *   what the primary action does; on a bucket card it is the second action.
 */
@Immutable
data class LibraryCard(
    val id: LibraryTitleId,
    val title: String,
    val posterUrl: String? = null,
    val providerName: String? = null,
    /** Which list it is in now, or null. On a bucket tab this is that bucket; on continue watching it
     *  is whatever the title happens to be in, which is usually nothing. */
    val bucket: LibraryBucket? = null,
    val progress: Float? = null,
    val resume: LibraryResume? = null,
    /** The kind of title, already translated, or null where the host holds none. */
    val typeLabel: String? = null,
    /** What [posterUrl] must be requested with, or null. */
    val posterHeaders: Map<String, String>? = null,
    /** [LibraryTitle.episodesWatched]. */
    val episodesWatched: Int? = null,
    /** [LibraryTitle.episodesTotal], where it is more than 0. */
    val episodesTotal: Int? = null,
    /** [LibraryTitle.rating]. */
    val rating: String? = null,
)

/**
 * Where focus goes back to when a screen comes back from a title's page.
 *
 * In state and not in the composition, because the fragment's view is destroyed while the page is open
 * and the ViewModel is what survives. [firstVisibleIndex] puts the grid back where it was, so the focused
 * card does not jump to the top row.
 *
 * @param ready whether the screen has actually come back, which [LibraryAction.Reappeared] says. Focus waits
 *   for it. On `Television_720p` a fragment composes its view well before it resumes, and focus taken in that
 *   gap was gone by the time the page above had finished leaving: the window handed it to the rail.
 */
@Immutable
data class LibraryReturn(val id: LibraryTitleId, val firstVisibleIndex: Int, val ready: Boolean = false)

/** The wordless status. [LibraryCopy] turns it into what the design system draws. */
sealed interface LibraryStatus {
    data object Loading : LibraryStatus

    data class Showing(val cards: List<LibraryCard>) : LibraryStatus

    /** This list has nothing in it. The library as a whole may still have plenty. */
    data object Empty : LibraryStatus

    /** The store could not be read. [detail] is the exception's own words, for a bug report. */
    data class Failed(val detail: String) : LibraryStatus
}

@Immutable
data class LibraryState(
    val tab: LibraryTab = LibraryTab.ContinueWatching,
    val status: LibraryStatus = LibraryStatus.Loading,
    /**
     * Which card's list picker is open, or null.
     *
     * In state, so Escape and a click outside close the same thing — the pattern home's source picker
     * set. The id rather than a boolean, because the panel names the title it is about.
     */
    val picking: LibraryTitleId? = null,
    /**
     * Titles per list, counted from what was read, so a tab's number cannot disagree with its page. Empty
     * until the first read and after a failed one: a tab then carries no number rather than a guess.
     */
    val counts: Map<LibraryBucket, Int> = emptyMap(),
    /** Different titles in any list, or null when [counts] is empty for the same reason. */
    val totalTitles: Int? = null,
    /** Narrows the cards on every list. Never changes [counts], which are the lists' real sizes. */
    val filter: String = "",
    /** Set when a title is opened from a grid, and cleared once focus is back. */
    val returnTo: LibraryReturn? = null,
    /** Every source, where the host has [LibrarySources]. Empty on a screen without them, which shows only its store. */
    val sources: List<LibrarySource> = emptyList(),
    /** The source being shown, or null where there are no [sources]. */
    val source: LibrarySourceId? = null,
    /** The shown source's lists as tabs, in its order, where it is a tracking service. Null for the local list. */
    val syncedTabs: List<LibraryTab.Synced>? = null,
    /** Titles per [syncedTabs] key, as [counts] is per bucket: from what was read, and empty until then. */
    val syncedCounts: Map<String, Int> = emptyMap(),
    /** Whether the panel that chooses a source (`TV Library` 5e) is open. */
    val sourcesOpen: Boolean = false,
    /** The sort chosen, for every source. What a source shows is [appliedSort], which may be the fallback. */
    val sort: LibrarySort = LibrarySort.Default,
    /** Whether the sort panel (`TV Library` 5d) is open. */
    val sortOpen: Boolean = false,
) {
    /** The source being shown, where it is known. */
    val shownSource: LibrarySource?
        get() = source?.let { id -> sources.firstOrNull { it.id == id } }

    /** The sort the shown source's cards are in: [sort], or the fallback where the source cannot sort by it. */
    val appliedSort: LibrarySort
        get() = sort.appliedTo(shownSource)

    /** The card the picker is open for, where it is still on screen. */
    val pickingCard: LibraryCard?
        get() = picking?.let { id -> (status as? LibraryStatus.Showing)?.cards?.firstOrNull { it.id == id } }
}

sealed interface LibraryAction {
    data class TabSelected(val tab: LibraryTab) : LibraryAction

    /**
     * Open the title's page, as a search hit would.
     *
     * @param firstVisibleIndex where the grid was, from a screen that restores focus on its way back.
     *   Null, the default, keeps no return point.
     */
    data class TitleActivated(val id: LibraryTitleId, val firstVisibleIndex: Int? = null) : LibraryAction

    /** Focus is back where [LibraryState.returnTo] said, or that place is gone. */
    data object ReturnHandled : LibraryAction

    /** Narrows the list to titles containing this text. */
    data class FilterChanged(val text: String) : LibraryAction

    /** Play from where it was left. */
    data class ResumeActivated(val id: LibraryTitleId) : LibraryAction

    /**
     * Opens the list picker for one card.
     *
     * **The library is where a title is organised, and the detail page is where it is watched.** That
     * is why the picker is here and not on the detail page: the detail page has no overlay slot, and
     * adding one would mean changing `:feature:detail` to carry a control it does not own. A bookmark
     * button there is a convenience that can follow; this is the route that works.
     */
    data class PickerOpened(val id: LibraryTitleId) : LibraryAction

    data object PickerDismissed : LibraryAction

    /** Puts the title in [bucket], from the picker. */
    data class BucketChosen(val id: LibraryTitleId, val bucket: LibraryBucket) : LibraryAction

    /** Out of every list on a bucket tab; off continue watching on that tab. The tab decides which. */
    data class RemoveRequested(val id: LibraryTitleId) : LibraryAction

    /** Nothing kept yet, so the one thing to offer is the screen that finds something. */
    data object SearchRequested : LibraryAction

    /** Re-read the store. Sent on arrival, because a bookmark may have been made on another screen. */
    data object Refresh : LibraryAction

    /**
     * Re-read the store without a skeleton, even from an empty list.
     *
     * [Refresh] puts an empty list through Loading, which on a TV would take the focused action off the
     * screen and drop focus every time the page came back. Desktop has no such focus to lose. It is also
     * what tells a return point the screen is back.
     */
    data object Reappeared : LibraryAction

    data object Retry : LibraryAction

    /** Opens the panel that chooses a source. */
    data object SourcesOpened : LibraryAction

    /** Closes it, choosing nothing. */
    data object SourcesDismissed : LibraryAction

    /** Shows [id]'s lists, and remembers it as the source to open on. A source that is not connected is refused. */
    data class SourceChosen(val id: LibrarySourceId) : LibraryAction

    /** Opens the sort panel. */
    data object SortOpened : LibraryAction

    /** Closes it. A choice is already applied. */
    data object SortDismissed : LibraryAction

    /**
     * Sorts by [criterion], at once (`DEC-17`, a single choice). The criterion already applied turns the other way
     * round. One the shown source cannot sort by is refused.
     */
    data class SortChosen(val criterion: LibrarySortCriterion) : LibraryAction
}

sealed interface LibraryEffect {
    data class OpenTitle(val id: LibraryTitleId) : LibraryEffect

    /**
     * Play [id] from [resume]. The host resolves links, because only it can: this module has no
     * `DetailSource` and must not grow one.
     */
    data class ResumeTitle(val id: LibraryTitleId, val resume: LibraryResume) : LibraryEffect

    data object OpenSearch : LibraryEffect
}

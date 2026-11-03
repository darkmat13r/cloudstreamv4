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
)

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
) {
    /** The card the picker is open for, where it is still on screen. */
    val pickingCard: LibraryCard?
        get() = picking?.let { id -> (status as? LibraryStatus.Showing)?.cards?.firstOrNull { it.id == id } }
}

sealed interface LibraryAction {
    data class TabSelected(val tab: LibraryTab) : LibraryAction

    /** Open the title's page, as a search hit would. */
    data class TitleActivated(val id: LibraryTitleId) : LibraryAction

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

    data object Retry : LibraryAction
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

package com.lagradost.cloudstream4.feature.downloads

import androidx.compose.runtime.Immutable

/** The wordless status. The screen turns it into what the design system draws. */
sealed interface DownloadsStatus {
    data object Loading : DownloadsStatus

    data class Showing(val titles: List<DownloadTitle>) : DownloadsStatus

    data object Empty : DownloadsStatus

    /** The list could not be read. [detail] is the failure's own words, for a bug report. */
    data class Failed(val detail: String) : DownloadsStatus
}

/** A stop that sends the user somewhere else, so focus has to come back to it. */
sealed interface DownloadsStop {
    data class Title(val id: DownloadTitleId) : DownloadsStop

    data object Queue : DownloadsStop

    data object PlayFromUrl : DownloadsStop
}

/**
 * Where focus goes back to when the screen comes back from another page.
 *
 * In state and not in the composition, because the fragment's view is destroyed while the page is open.
 *
 * @param ready whether the screen has actually come back, which [DownloadsAction.Reappeared] says. A fragment
 *   composes its view well before it resumes, and focus taken in that gap is lost to the rail (`KMP-34`).
 */
@Immutable
data class DownloadsReturn(val stop: DownloadsStop, val firstVisibleIndex: Int = 0, val ready: Boolean = false)

@Immutable
data class DownloadsState(
    val status: DownloadsStatus = DownloadsStatus.Loading,
    val storage: DownloadStorage? = null,
    val queue: DownloadQueue = DownloadQueue(),
    val storageFull: StorageShortfall? = null,
    /** Whether the device says there is no network. Never true for an unknown answer. */
    val offline: Boolean = false,
    /** What is chosen for deleting, or null when the screen is not choosing. */
    val selection: Set<DownloadTitleId>? = null,
    val returnTo: DownloadsReturn? = null,
) {
    val titles: List<DownloadTitle> get() = (status as? DownloadsStatus.Showing)?.titles.orEmpty()

    val selectedBytes: Long
        get() = selection.orEmpty().let { chosen -> titles.filter { it.id in chosen }.sumOf { it.bytesDone } }
}

sealed interface DownloadsAction {
    /** OK on a row: opens it, or while choosing, chooses it. */
    data class TitleActivated(val id: DownloadTitleId, val firstVisibleIndex: Int = 0) : DownloadsAction

    /** Retry on a failed row and Resume on a paused one. Both ask for what is left. */
    data class ResumeTitle(val id: DownloadTitleId) : DownloadsAction

    data object QueueRequested : DownloadsAction

    data object PlayFromUrlRequested : DownloadsAction

    /** The `Select` chip. */
    data object SelectionStarted : DownloadsAction

    /** The banner's fix. Choosing what to delete where there is something, and otherwise the system's own screen. */
    data object FreeUpSpace : DownloadsAction

    data object SelectionCancelled : DownloadsAction

    /** All, or none if all are already chosen. */
    data object SelectAllToggled : DownloadsAction

    data object DeleteSelected : DownloadsAction

    data object LibraryRequested : DownloadsAction

    /** Focus is back where [DownloadsState.returnTo] said, or that place is gone. */
    data object ReturnHandled : DownloadsAction

    /** The fragment resumed. Also the moment something may have changed while another page was open. */
    data object Reappeared : DownloadsAction

    /** Read the list again after a failure. */
    data object Retry : DownloadsAction
}

sealed interface DownloadsEffect {
    data class OpenTitle(val id: DownloadTitleId) : DownloadsEffect

    data object OpenQueue : DownloadsEffect

    data object OpenPlayFromUrl : DownloadsEffect

    data object OpenLibrary : DownloadsEffect

    /** Nothing here to delete and the disk is still full, so what is filling it is someone else's. */
    data object OpenStorageSettings : DownloadsEffect

    /** Delete these, once the host has asked. The rows leave when the source says they have gone. */
    data class ConfirmDelete(val ids: Set<DownloadTitleId>) : DownloadsEffect
}

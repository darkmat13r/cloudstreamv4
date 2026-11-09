package com.lagradost.cloudstream4.feature.downloads.series

import androidx.compose.runtime.Immutable

/** The page as a whole. */
sealed interface SeriesStatus {
    data object Loading : SeriesStatus

    data class Showing(val episodes: List<DownloadEpisode>) : SeriesStatus

    /** Nothing of this series is on disk. A page that empties while open closes instead, as the old one did. */
    data object Empty : SeriesStatus

    /** The list could not be read. [detail] is the reason, as the source gave it. */
    data class Failed(val detail: String) : SeriesStatus
}

/** A focus stop that outlives a trip to the player or to a dialog, so focus can come back to it. */
sealed interface SeriesStop {
    data object PlayNext : SeriesStop

    data object DeleteAll : SeriesStop

    data object RetryFailed : SeriesStop

    /** One of an episode's three verbs, or its row. */
    data class Episode(val id: DownloadEpisodeId, val verb: SeriesVerb) : SeriesStop
}

/** 6d's columns: the row, then A, B and C. */
enum class SeriesVerb { Row, Play, State, Delete }

/** Where focus goes back to; [ready] once the screen has resumed, because it composes before it is shown. */
data class SeriesReturn(val stop: SeriesStop, val firstVisibleIndex: Int = 0, val ready: Boolean = false)

@Immutable
data class SeriesState(
    val name: String = "",
    val status: SeriesStatus = SeriesStatus.Loading,
    val offline: Boolean = false,
    val returnTo: SeriesReturn? = null,
    /** From [DownloadSeriesContents.freeBytes]. */
    val freeBytes: Long? = null,
    /** From [DownloadSeriesContents.posterUrl]. */
    val posterUrl: String? = null,
) {
    val episodes: List<DownloadEpisode>
        get() = (status as? SeriesStatus.Showing)?.episodes.orEmpty()

    val failedCount: Int get() = episodes.count { it.state == DownloadEpisodeState.Failed }
}

sealed interface SeriesAction {
    /** OK on a row, or on its Play: plays it, with the rest of the series after it. */
    data class PlayRequested(val id: DownloadEpisodeId, val from: SeriesVerb, val firstVisibleIndex: Int) : SeriesAction

    data class StateVerbRequested(val id: DownloadEpisodeId) : SeriesAction

    data class DeleteRequested(val id: DownloadEpisodeId, val firstVisibleIndex: Int) : SeriesAction

    /** Stops a running or waiting download, keeping nothing of it: the Mac's Cancel beside Pause (board D4). */
    data class CancelRequested(val id: DownloadEpisodeId) : SeriesAction

    data object PlayNextRequested : SeriesAction

    data object DeleteAllRequested : SeriesAction

    data object RetryFailedRequested : SeriesAction

    /** The page is on screen again, after the player or a dialog. */
    data object Reappeared : SeriesAction

    data object ReturnHandled : SeriesAction

    /** The failed read's own Retry. */
    data object Retry : SeriesAction

    /** The empty page's Go back. */
    data object BackRequested : SeriesAction
}

sealed interface SeriesEffect {
    data class Play(val id: DownloadEpisodeId) : SeriesEffect

    data class ConfirmDelete(val id: DownloadEpisodeId) : SeriesEffect

    /** Every episode of the series, through the old screen's dialog for many. */
    data class ConfirmDeleteAll(val ids: List<DownloadEpisodeId>) : SeriesEffect

    /** The last episode has gone, so the page has nothing left to show. */
    data object Close : SeriesEffect
}

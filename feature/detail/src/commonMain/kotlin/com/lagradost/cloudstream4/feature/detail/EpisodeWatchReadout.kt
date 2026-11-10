package com.lagradost.cloudstream4.feature.detail

import androidx.compose.runtime.Immutable

/**
 * What an episode row says about how much of it was watched (`KMP-63`): board 7i's `WATCHED` and
 * `24 MIN LEFT` on TV, DS-21 E2's second line on the Mac. One decision for both rows, so they cannot
 * disagree about the same episode.
 */
@Immutable
sealed interface EpisodeWatchReadout {
    /** Marked watched, or played to [DetailResume.FINISHED_FRACTION]. */
    data object Watched : EpisodeWatchReadout

    /** Started and not finished: [minutes] left, rounded up as [DetailResume.remainingMinutes] is. */
    data class Left(val minutes: Int) : EpisodeWatchReadout

    /** Nothing usable is stored. The row draws what it drew before. */
    data object None : EpisodeWatchReadout
}

/**
 * The readout for this row.
 *
 * The mark wins over a position because the host's own rules keep the two apart: `:app` clears the mark
 * when the episode is played again (`DataStoreHelper.setViewPosAndResume`), so a mark that is still set is
 * the newer fact. A position counts as started under the same guard the title's Resume uses: a usable
 * duration and a position past zero.
 */
fun DetailEpisode.watchReadout(): EpisodeWatchReadout {
    if (markedWatched) return EpisodeWatchReadout.Watched
    val stored = progress ?: return EpisodeWatchReadout.None
    if (stored.finished) return EpisodeWatchReadout.Watched
    return stored.remainingMinutes?.let { EpisodeWatchReadout.Left(it) } ?: EpisodeWatchReadout.None
}

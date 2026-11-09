package com.lagradost.cloudstream4.feature.downloads.series

import com.lagradost.cloudstream4.media.model.DownloadFailure
import com.lagradost.cloudstream4.media.model.DownloadProgress
import com.lagradost.cloudstream4.media.model.DownloadState
import com.lagradost.cloudstream4.media.model.QueuePosition
import kotlinx.coroutines.flow.Flow

/** An episode's download, by the id the downloader and the old screen both use. */
data class DownloadEpisodeId(val value: Int)

/** What one episode's verb column says: 6d's column B, pause, resume, retry or cancel, and none when it is done. */
enum class DownloadEpisodeState { Done, Running, Queued, Paused, Failed }

/**
 * One episode on 6d.
 *
 * @property bytesDone what is on disk, or what a running transfer last reported.
 * @property bytesTotal the declared or estimated total, 0 when nothing has said.
 * @property secondsLeft a running transfer's estimate, or null.
 * @property watched how far into the episode the person has watched, `0f..1f`, or null when they have not started it.
 */
data class DownloadEpisode(
    val id: DownloadEpisodeId,
    val season: Int?,
    val episode: Int?,
    val name: String?,
    val state: DownloadEpisodeState,
    val bytesDone: Long,
    val bytesTotal: Long,
    val bytesPerSecond: Long = 0L,
    val secondsLeft: Long? = null,
    val queue: QueuePosition? = null,
    val failure: DownloadFailure? = null,
    val watched: Float? = null,
) {
    /** The progress rule: none when done, as 6d draws it. */
    val progress: Float?
        get() = when {
            state == DownloadEpisodeState.Done -> null
            bytesTotal > 0L -> (bytesDone.toFloat() / bytesTotal.toFloat()).coerceIn(0f, 1f)
            else -> null
        }

    /** A file the player can open: a finished one, or the part of one that is on disk, as the old popup allowed. */
    val isPlayable: Boolean get() = bytesDone > 0L
}

/** One series, as the screen shows it: the episodes sorted as the old screen sorted them, by season then episode. */
data class DownloadSeriesContents(
    val name: String,
    val episodes: List<DownloadEpisode>,
    /** What is left on the volume the downloads are on, or null where the host does not say. The Mac's D4 shows it. */
    val freeBytes: Long? = null,
    /** The series' poster, for D4's header (`KMP-99`), or null where none was kept. */
    val posterUrl: String? = null,
)

/** Where the series page gets what it shows, and where its verbs go. Android's lives in `:app`. */
interface DownloadSeriesSource {
    /** The series, again whenever it changes. Throws when it cannot be read. */
    val contents: Flow<DownloadSeriesContents>

    /** Reads the disk again. */
    fun refresh()

    fun pause(id: DownloadEpisodeId)

    /** Resumes a paused download, or retries a failed one: the old screen's Resume, which did both. */
    fun resume(id: DownloadEpisodeId)

    /**
     * Stops a waiting or running download and discards what it fetched. TV sends it only for a waiting one, which is
     * all Android's host is relied on for. The Mac's D4 also sends it for a running one (`SeriesAction.CancelRequested`),
     * which `KMP-52`'s host stops and removes. A finished download is deleted, not cancelled.
     */
    fun cancel(id: DownloadEpisodeId)
}

/** Turns what the disk holds and what the downloader last reported into one episode's row. */
object SeriesShelf {

    /**
     * `DownloadShelf.title`'s rules for one episode, except that waiting is its own state here, because 6d gives it its
     * own verb. A fact is in memory only, so with none, the file's length against its total decides done or paused.
     */
    fun episode(
        id: Int,
        season: Int?,
        episode: Int?,
        name: String?,
        bytesOnDisk: Long,
        bytesTotal: Long,
        fact: DownloadProgress?,
        watched: Float?,
    ): DownloadEpisode {
        val state = when (fact?.state) {
            DownloadState.FAILED -> DownloadEpisodeState.Failed
            DownloadState.DOWNLOADING -> DownloadEpisodeState.Running
            DownloadState.QUEUED -> DownloadEpisodeState.Queued
            DownloadState.PAUSED -> DownloadEpisodeState.Paused
            DownloadState.COMPLETED -> DownloadEpisodeState.Done
            DownloadState.STOPPED, null ->
                if (bytesOnDisk > COMPLETE_MARGIN && bytesOnDisk + COMPLETE_MARGIN >= bytesTotal) {
                    DownloadEpisodeState.Done
                } else {
                    DownloadEpisodeState.Paused
                }
        }
        val running = state == DownloadEpisodeState.Running
        return DownloadEpisode(
            id = DownloadEpisodeId(id),
            season = season,
            episode = episode,
            name = name?.takeIf { it.isNotBlank() },
            state = state,
            bytesDone = if (running && fact != null) fact.bytesDownloaded else bytesOnDisk,
            bytesTotal = fact?.bytesTotal ?: bytesTotal,
            bytesPerSecond = if (running) fact?.bytesPerSecond ?: 0L else 0L,
            secondsLeft = if (running) fact?.estimatedSecondsRemaining else null,
            queue = fact?.queue,
            failure = if (state == DownloadEpisodeState.Failed) fact?.failure else null,
            watched = watched?.takeIf { it > 0f }?.coerceAtMost(1f),
        )
    }

    /**
     * `Play next`: the first episode, in order, that can be played and has not been watched to the end, or the first that
     * can be played at all. The player's own rule for "watched" is 95%.
     */
    fun next(episodes: List<DownloadEpisode>): DownloadEpisode? {
        val playable = episodes.filter { it.isPlayable }
        return playable.firstOrNull { (it.watched ?: 0f) < WATCHED } ?: playable.firstOrNull()
    }

    /** The old screen's order: season, then episode, with neither known first. */
    fun sorted(episodes: List<DownloadEpisode>): List<DownloadEpisode> =
        episodes.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

    private const val COMPLETE_MARGIN = 1024L
    private const val WATCHED = 0.95f
}

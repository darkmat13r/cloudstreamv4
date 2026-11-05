package com.lagradost.cloudstream4.feature.downloads

import com.lagradost.cloudstream4.media.model.DownloadFailure
import com.lagradost.cloudstream4.media.model.DownloadProgress
import com.lagradost.cloudstream4.media.model.DownloadState

/** One episode as the disk has it, or a film's one file. */
data class EpisodeOnDisk(val id: Int, val bytesOnDisk: Long, val bytesTotal: Long)

/** Turns what the disk holds and what the downloader last reported into what the screen says. */
object DownloadShelf {

    /**
     * One title's row.
     *
     * The disk is read once per list and a running transfer reports many times a second, so a report wins for
     * bytes while it is moving. A fact is in memory only: with none, the file's length against its declared
     * total is all there is, so a failure from before a restart reads as paused rather than as a claim nobody
     * can back.
     */
    fun title(
        id: DownloadTitleId,
        name: String,
        kind: DownloadTitleKind,
        episodes: List<EpisodeOnDisk>,
        facts: Map<Int, DownloadProgress>,
    ): DownloadTitle {
        val states = episodes.map { episode -> episode.state(facts[episode.id]) }
        val state = DownloadTitleState.entries.firstOrNull { it in states } ?: DownloadTitleState.Done
        val done = episodes.sumOf { it.bytesDone(facts[it.id]) }
        val total = episodes.sumOf { facts[it.id]?.bytesTotal ?: it.bytesTotal }
        val single = episodes.singleOrNull()?.let { facts[it.id]?.fraction }
        return DownloadTitle(
            id = id,
            name = name,
            kind = kind,
            episodes = episodes.size,
            bytesDone = done,
            bytesTotal = total,
            state = state,
            progress = when {
                state == DownloadTitleState.Done -> null
                single != null -> single
                total > 0L -> (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                else -> null
            },
        )
    }

    /** Whether [resume][DownloadsSource.resume] has anything to do for this episode. */
    fun isResumable(episode: EpisodeOnDisk, fact: DownloadProgress?): Boolean = when (episode.state(fact)) {
        DownloadTitleState.Failed, DownloadTitleState.Paused -> true
        DownloadTitleState.Running, DownloadTitleState.Done -> false
    }

    /** The line, from the same facts: position 1 is a running transfer, so "1 of 4" counts as the old button did. */
    fun queue(facts: Map<Int, DownloadProgress>): DownloadQueue {
        val inLine = facts.values.filter { it.queue != null }
        return DownloadQueue(
            running = inLine.count { it.state != DownloadState.QUEUED },
            total = inLine.maxOfOrNull { it.queue!!.total } ?: 0,
        )
    }

    /** The largest shortfall any transfer met, or null when none has run out of room. */
    fun storageFull(facts: Map<Int, DownloadProgress>): StorageShortfall? {
        val full = facts.values.mapNotNull { it.failure as? DownloadFailure.StorageFull }
        if (full.isEmpty()) return null
        return StorageShortfall(full.mapNotNull { it.bytesShort }.maxOrNull())
    }

    private fun EpisodeOnDisk.state(fact: DownloadProgress?): DownloadTitleState = when (fact?.state) {
        DownloadState.FAILED -> DownloadTitleState.Failed
        DownloadState.DOWNLOADING, DownloadState.QUEUED -> DownloadTitleState.Running
        DownloadState.PAUSED -> DownloadTitleState.Paused
        DownloadState.COMPLETED -> DownloadTitleState.Done
        // Cancelled, or nothing known since a restart. `BaseFetchButton.getStatus`'s own rule for the second.
        DownloadState.STOPPED, null ->
            if (bytesOnDisk > COMPLETE_MARGIN && bytesOnDisk + COMPLETE_MARGIN >= bytesTotal) {
                DownloadTitleState.Done
            } else {
                DownloadTitleState.Paused
            }
    }

    private fun EpisodeOnDisk.bytesDone(fact: DownloadProgress?): Long =
        if (fact?.state == DownloadState.DOWNLOADING) fact.bytesDownloaded else bytesOnDisk

    private const val COMPLETE_MARGIN = 1024L
}

package com.lagradost.cloudstream4.feature.downloads

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.Flow

/** A title on the list: a film, or a series with every episode of it that is on the disk. Opaque here. */
@Immutable
data class DownloadTitleId(val value: Int)

enum class DownloadTitleKind { Film, Series }

/** What a row says about a title. When a series' episodes differ, the first of these that any of them is wins. */
enum class DownloadTitleState {
    /** A transfer stopped and it is not coming back on its own: the row offers Retry. */
    Failed,

    /** Moving, or in line to. */
    Running,

    /** Short of its total with nothing running: the row offers Resume. On Android, also a failure after a restart (until KMP-60/KMP-62); the Mac keeps it Failed. */
    Paused,

    /** Everything is on the disk. */
    Done,
}

/**
 * One row of 6c.
 *
 * @param episodes how many of a series' episodes are on the disk. A film is one.
 * @param progress how far through, in `0f..1f`, or null when nothing can be said or nothing is left to do.
 */
@Immutable
data class DownloadTitle(
    val id: DownloadTitleId,
    val name: String,
    val kind: DownloadTitleKind,
    val episodes: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
    val state: DownloadTitleState = DownloadTitleState.Done,
    val progress: Float? = null,
)

/** How the disk divides, in bytes. [downloadBytes] is part of [usedBytes]. */
@Immutable
data class DownloadStorage(val usedBytes: Long, val totalBytes: Long, val downloadBytes: Long)

/** The line behind the queue button. [total] includes [running]. */
@Immutable
data class DownloadQueue(val running: Int = 0, val total: Int = 0) {
    val waiting: Int get() = (total - running).coerceAtLeast(0)
}

/**
 * A write has run out of room.
 *
 * @param bytesShort what finishing needed when that write failed, or null where it cannot be known, such as a
 *   document-tree folder. It is not re-measured when space is freed, because retrying is what finds out.
 */
@Immutable
data class StorageShortfall(val bytesShort: Long?)

/**
 * Everything the source knows at one moment.
 *
 * @param storage null until the disk has been read, or if it cannot be.
 */
@Immutable
data class DownloadsContents(
    val titles: List<DownloadTitle> = emptyList(),
    val storage: DownloadStorage? = null,
    val queue: DownloadQueue = DownloadQueue(),
    val storageFull: StorageShortfall? = null,
)

/**
 * The seam the host implements over its downloader.
 *
 * Asking before a delete, opening a title and the queue are not here. They are the host's own screens and
 * dialogs, and they arrive at it as [DownloadsEffect]s.
 */
interface DownloadsSource {
    /** The current contents, then each change. Nothing until the first read is done. A failed read throws here. */
    val contents: Flow<DownloadsContents>

    /** Read the disk again. What it finds arrives through [contents]. */
    fun refresh()

    /** Starts what a [DownloadTitleState.Failed] or [DownloadTitleState.Paused] title has left to fetch. */
    fun resume(id: DownloadTitleId)
}

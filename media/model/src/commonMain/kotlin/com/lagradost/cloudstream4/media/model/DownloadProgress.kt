package com.lagradost.cloudstream4.media.model

/**
 * What a download is doing. Restates `:app`'s `VideoDownloadManager.DownloadType`.
 *
 * [QUEUED] is `IsPending` renamed: "pending" reads as "about to finish" at a glance, and this is
 * the state a download sits in while something else holds the one download slot.
 */
enum class DownloadState {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    /** Cancelled by the user. Distinct from [FAILED]: nothing went wrong and nothing is retried. */
    STOPPED,
}

/**
 * How far a download has got.
 *
 * Today this crosses the app as `Event<Triple<Int, Long, Long>>` — an untyped triple of id, bytes
 * and total — and separately as `CreateNotificationMetadata`, which has the fields below and is
 * already free of platform types. The triple is what every consumer actually subscribes to, and
 * `BaseFetchButton.downloadProgressEvent(data: Triple<Int, Long, Long>)` is as much as the type
 * system says about it.
 *
 * ## Segments are not bytes
 *
 * An HLS download knows how many *segments* it has fetched long before it knows how many bytes
 * there are to fetch, because the total is only the sum of the parts it has already seen. A UI that
 * shows byte progress for HLS shows a bar that grows and then jumps backwards. `:app` already
 * carries `hlsProgress` and `hlsTotal` beside the byte counts for exactly this reason; here that is
 * [segments], and [fraction] prefers it when it is present.
 */
data class DownloadProgress(
    /** `:app`'s episode id. Opaque here. */
    val id: Int,
    val state: DownloadState,
    val bytesDownloaded: Long,
    /** `null` while the server has not declared a length, which is normal for HLS and chunked responses. */
    val bytesTotal: Long? = null,
    val bytesPerSecond: Long = 0L,
    val segments: SegmentProgress? = null,
    /**
     * Bytes actually on disk, or `null` when the producer does not know. [bytesDownloaded] runs ahead
     * of this, because parallel connections hold out-of-order parts in memory, and a failure is
     * reported as what was written (board 6d: "96 MB of 512 MB written").
     */
    val bytesWritten: Long? = null,
    /** Where this sits among everything started or waiting. `null` once it has finished. */
    val queue: QueuePosition? = null,
    /**
     * Why it failed or is stalling. Set whenever [state] is [DownloadState.FAILED], and can also be
     * set on [DownloadState.DOWNLOADING] when a transfer has hit a fault it cannot get past but has
     * not stopped trying.
     */
    val failure: DownloadFailure? = null,
) {
    init {
        require(bytesDownloaded >= 0) { "bytesDownloaded must not be negative, was $bytesDownloaded" }
        require(bytesWritten == null || bytesWritten >= 0) { "bytesWritten must not be negative, was $bytesWritten" }
    }

    /**
     * How far through, in `0f..1f`, or `null` when it cannot be known yet.
     *
     * Prefers [segments] over bytes, for the reason above. Returns `null` rather than `0f` for an
     * unknown total so a progress bar can stay indeterminate; a zero total is unknown, not empty.
     */
    val fraction: Float?
        get() {
            segments?.fraction?.let { return it }
            val total = bytesTotal ?: return null
            if (total <= 0L) return null
            return (bytesDownloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }

    /**
     * Seconds until this finishes at the current rate, or `null` when that cannot be estimated.
     *
     * Needs a known byte total and a non-zero rate. A paused or queued download has no estimate
     * even when both are known, because the rate describes the past, not the future.
     */
    val estimatedSecondsRemaining: Long?
        get() {
            if (state != DownloadState.DOWNLOADING) return null
            val total = bytesTotal ?: return null
            if (bytesPerSecond <= 0L) return null
            val remaining = total - bytesDownloaded
            if (remaining <= 0L) return 0L
            return remaining / bytesPerSecond
        }
}

/**
 * A download's place in line, 1-based: [position] of [total] (board 6b: "queue 2 of 5").
 *
 * [total] counts the transfers running as well as those waiting, and running ones come first, so
 * position 1 is the first transfer running and not the next one to start.
 */
data class QueuePosition(val position: Int, val total: Int) {
    init {
        require(position >= 1) { "position is 1-based, was $position" }
        require(total >= position) { "total must cover position, was $position of $total" }
    }
}

/** Why a download did not finish. Restates nothing in `:app`, whose `DownloadStatus` has no reason. */
sealed interface DownloadFailure {
    /** The connection failed: no route, a reset or a stream that ended early. */
    data object NetworkLost : DownloadFailure

    /**
     * The device ran out of room.
     *
     * @property bytesShort how much more is needed to finish, or `null` when that cannot be known,
     * either because the total is unknown or because a document-tree folder hides its free space.
     */
    data class StorageFull(val bytesShort: Long?) : DownloadFailure {
        init {
            require(bytesShort == null || bytesShort >= 0) { "bytesShort must not be negative, was $bytesShort" }
        }
    }

    /** The download folder cannot be used at all, so every source would fail the same way. */
    data object StorageUnavailable : DownloadFailure

    /** Nothing that could be downloaded: no links, an unsupported format, or a file too short to be real. */
    data object NoUsableLink : DownloadFailure

    /** The source stopped delivering, and the code that noticed cannot see the cause. */
    data object SourceFailed : DownloadFailure

    /** It failed, and nothing recorded why. */
    data object Unknown : DownloadFailure
}

/** Progress counted in parts rather than bytes, for segmented formats. */
data class SegmentProgress(val completed: Long, val total: Long) {
    init {
        require(completed >= 0) { "completed must not be negative, was $completed" }
        require(total >= 0) { "total must not be negative, was $total" }
    }

    /** `null` when [total] is still zero, i.e. the playlist has not been read yet. */
    val fraction: Float?
        get() = if (total <= 0L) null else (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

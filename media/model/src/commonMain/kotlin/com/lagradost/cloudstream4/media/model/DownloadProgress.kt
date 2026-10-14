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
) {
    init {
        require(bytesDownloaded >= 0) { "bytesDownloaded must not be negative, was $bytesDownloaded" }
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

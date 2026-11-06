package com.lagradost.cloudstream4.media.download

import com.lagradost.cloudstream4.media.model.DownloadFailure
import com.lagradost.cloudstream4.media.model.DownloadProgress
import com.lagradost.cloudstream4.media.model.DownloadState
import io.github.vinceglb.filekit.PlatformFile

/** Where one byte range of a segmented download stands. */
enum class PartState { PENDING, ACTIVE, RETRYING, DONE }

/**
 * One byte range of the file. [endExclusive] is `null` only for a single stream of unknown length.
 * [bytesDone] is what is on disk for this part, read back from the file, never a running counter.
 * [attempt] counts consecutive failed attempts, and returns to 0 when a byte arrives.
 */
data class PartSnapshot(
    val id: Int,
    val start: Long,
    val endExclusive: Long?,
    val bytesDone: Long,
    val state: PartState,
    val attempt: Int,
)

/**
 * Everything a UI needs about one download, as an immutable value. Holds no URL, header or referer.
 *
 * [file] is set only when [state] is [DownloadState.COMPLETED]. [sizeVerified] is `false` when the
 * server never declared a length, so "complete" could not be compared with anything.
 */
data class DownloadSnapshot(
    val state: DownloadState,
    val bytesDone: Long,
    val bytesTotal: Long?,
    val bytesPerSecond: Long,
    val etaSeconds: Long?,
    val parts: List<PartSnapshot>,
    val failure: DownloadFailure?,
    val file: PlatformFile?,
    val sizeVerified: Boolean,
) {
    /**
     * The `:media:model` shape for [id]. A negative count or total is clamped rather than thrown on,
     * because `DownloadProgress` rejects them and a progress report must never crash the collector.
     * Part detail is not mapped to `segments`: that field means HLS segments, and a byte range is not one.
     */
    fun toDownloadProgress(id: Int): DownloadProgress {
        val done = bytesDone.coerceAtLeast(0L)
        return DownloadProgress(
            id = id,
            state = state,
            bytesDownloaded = done,
            bytesTotal = bytesTotal?.takeIf { it >= 0L },
            bytesPerSecond = bytesPerSecond.coerceAtLeast(0L),
            bytesWritten = done,
            failure = failure,
        )
    }
}

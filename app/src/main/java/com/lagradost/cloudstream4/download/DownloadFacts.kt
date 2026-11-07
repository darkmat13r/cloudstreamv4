package com.lagradost.cloudstream4.download

import android.os.StatFs
import android.util.Log
import com.lagradost.cloudstream3.services.DownloadQueueService
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadType
import com.lagradost.cloudstream3.utils.redactedStackTrace
import com.lagradost.cloudstream4.media.model.DownloadFailure
import com.lagradost.cloudstream4.media.model.DownloadProgress
import com.lagradost.cloudstream4.media.model.DownloadState
import com.lagradost.cloudstream4.media.model.QueuePosition
import com.lagradost.cloudstream4.media.model.SegmentProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.EOFException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * What the downloader knows about each download, as a [DownloadProgress] per episode id (`Q13`).
 *
 * A sidecar: the published `downloadProgressEvent`, `downloadStatusEvent` and `DownloadStatus` stay
 * as they are, and the downloader only reports to this object. Nothing reads it back, so it cannot
 * change what a download does. Screens observe [progress]; their Android host lives in this module.
 *
 * ## This tells the truth where the older record does not
 *
 * [DownloadState.COMPLETED] means every declared byte was written, and only that. A source that ends
 * cleanly short of what it declared has no error for the downloader to see, so it reaches `IsDone`
 * with fewer bytes and stays [DownloadState.FAILED] here. That can disagree with
 * `VideoDownloadManager.downloadStatus`.
 *
 * ## In memory only
 *
 * Facts and reasons do not survive process death. After a restart a download has no fact until it
 * runs again, and why it failed is gone; bytes on disk are still in `KEY_DOWNLOAD_INFO`.
 */
internal object DownloadFacts {
    private const val TAG = "DownloadFacts"

    private val _facts = MutableStateFlow<Map<Int, DownloadProgress>>(emptyMap())

    /** The last report per id, without queue positions. Screens want [progress]. */
    val facts: StateFlow<Map<Int, DownloadProgress>> = _facts.asStateFlow()

    /** The reason noted for the current attempt. It waits here until a report says the attempt ended. */
    private val notes = MutableStateFlow<Map<Int, DownloadFailure>>(emptyMap())

    /** Every download with a fact, a running instance or a queue entry, with its place in line. */
    val progress: Flow<Map<Int, DownloadProgress>>
        get() = observe(
            facts,
            DownloadQueueService.downloadInstances.map { instances ->
                instances.map { it.downloadQueueWrapper.id }
            },
            DownloadQueueManager.queue.map { queue -> queue.map { it.id } },
        )

    /**
     * Records one notification's numbers. Never throws: `notify()` rethrows in a debug build, and an
     * observer must not be able to fail a download.
     *
     * @param bytesTotal what a screen should show, including an estimate.
     * @param totalIsDeclared whether [bytesTotal] is what the server promised, so falling short of it
     * means the file is truncated. An HLS total is an estimate and proves nothing.
     */
    fun report(
        id: Int,
        type: DownloadType,
        bytesDownloaded: Long,
        bytesWritten: Long,
        bytesTotal: Long?,
        totalIsDeclared: Boolean,
        bytesPerSecond: Long,
        segments: SegmentProgress?,
    ) = guarded {
        val written = bytesWritten.coerceAtLeast(0L)
        val total = bytesTotal?.takeIf { it > 0L }
        // A new attempt starts with nothing against it. The next mirror must not inherit a reason.
        if (type == DownloadType.IsPending) forgetNote(id)
        val note = notes.value[id]
        val truncated = type == DownloadType.IsDone &&
                (note != null || (totalIsDeclared && total != null && written < total))
        val state = if (truncated) DownloadState.FAILED else stateOf(type)
        if (type == DownloadType.IsDone && !truncated) forgetNote(id)

        val fact = DownloadProgress(
            id = id,
            state = state,
            bytesDownloaded = bytesDownloaded.coerceAtLeast(0L),
            bytesTotal = total,
            // The rate describes a transfer in progress, not the moment one ended.
            bytesPerSecond = if (state == DownloadState.DOWNLOADING) bytesPerSecond.coerceAtLeast(0L) else 0L,
            segments = segments,
            bytesWritten = written,
            failure = when (state) {
                DownloadState.FAILED -> note ?: if (truncated) DownloadFailure.SourceFailed else DownloadFailure.Unknown
                DownloadState.DOWNLOADING -> note as? DownloadFailure.StorageFull
                else -> null
            },
        )
        _facts.update { it + (id to fact) }
    }

    /**
     * Notes why the current attempt is going wrong, to be used by the report that ends it.
     *
     * A full disk also shows at once on a running fact, with [bytesDownloaded] and [bytesWritten] as
     * they stand: a transfer that hits one can keep trying without ever ending, and then no further
     * report would say so.
     */
    fun note(
        id: Int,
        failure: DownloadFailure,
        bytesDownloaded: Long? = null,
        bytesWritten: Long? = null,
    ) = guarded {
        notes.update { current ->
            val kept = current[id]
            current + (id to if (kept != null && outranks(kept, failure)) kept else failure)
        }
        val noted = notes.value[id] as? DownloadFailure.StorageFull ?: return@guarded
        _facts.update { current ->
            val fact = current[id]
            if (fact?.state != DownloadState.DOWNLOADING) return@update current
            current + (id to fact.copy(
                bytesDownloaded = bytesDownloaded?.coerceAtLeast(0L) ?: fact.bytesDownloaded,
                bytesWritten = bytesWritten?.coerceAtLeast(0L) ?: fact.bytesWritten,
                bytesPerSecond = 0L,
                failure = noted,
            ))
        }
    }

    /** The instance gave up or was cancelled, which can happen with no notification from a transfer. */
    fun finish(id: Int, type: DownloadType) = guarded {
        val state = stateOf(type)
        val failure = if (state == DownloadState.FAILED) notes.value[id] ?: DownloadFailure.Unknown else null
        _facts.update { current ->
            val fact = current[id] ?: DownloadProgress(id = id, state = state, bytesDownloaded = 0L)
            current + (id to fact.copy(state = state, bytesPerSecond = 0L, failure = failure))
        }
    }

    /** The file is gone, so nothing is left to say about it. */
    fun clear(id: Int) = guarded {
        _facts.update { it - id }
        forgetNote(id)
    }

    fun stateOf(type: DownloadType): DownloadState = when (type) {
        DownloadType.IsPending -> DownloadState.QUEUED
        DownloadType.IsDownloading -> DownloadState.DOWNLOADING
        DownloadType.IsPaused -> DownloadState.PAUSED
        DownloadType.IsDone -> DownloadState.COMPLETED
        DownloadType.IsFailed -> DownloadState.FAILED
        DownloadType.IsStopped -> DownloadState.STOPPED
    }

    /**
     * Names what a thrown error means, walking its causes.
     *
     * By class and message rather than `ErrnoException.errno`, because this has to run on a JVM test,
     * and because a stream can wrap the errno in an `IOException` whose message alone still carries it.
     * The shortfall is left unknown here: this only sees the error, not the sizes.
     */
    fun classify(error: Throwable): DownloadFailure {
        val chain = generateSequence(error) { it.cause }.take(MAX_CAUSES).toList()
        // A full disk first: a wrapper around ENOSPC must not read as anything less fixable by the user.
        if (chain.any { cause -> NO_SPACE.any { cause.message?.contains(it, ignoreCase = true) == true } }) {
            return DownloadFailure.StorageFull(null)
        }
        if (chain.any { it.isNetworkLoss() }) return DownloadFailure.NetworkLost
        return DownloadFailure.Unknown
    }

    /** How much more room finishing takes, or `null` when the total or the free space is unknown, or there is enough. */
    fun bytesShort(bytesTotal: Long?, bytesWritten: Long, bytesAvailable: Long?): Long? {
        if (bytesTotal == null || bytesTotal <= 0L || bytesAvailable == null) return null
        return (bytesTotal - bytesWritten - bytesAvailable).takeIf { it > 0L }
    }

    /**
     * Where to ask for free space, from the stored download path. `null` for a document tree, whose
     * free space is not visible through a path; [defaultPath] is where the default folder lives.
     */
    fun storagePathOf(basePath: String?, defaultPath: String?): String? = when {
        basePath.isNullOrBlank() -> defaultPath
        basePath.startsWith("content://") -> null
        else -> basePath
    }

    /** Free bytes at [path], or `null` when that cannot be read. */
    fun availableBytes(path: String?): Long? {
        if (path == null) return null
        return try {
            StatFs(path).availableBytes
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Every download's place in line: running first, then waiting, 1-based over both. That one count
     * is what the boards show: a running row reads "queue 2 of 5" (6b) and the button "Queue (0/3)" (6e).
     */
    fun queuePositions(activeIds: List<Int>, queuedIds: List<Int>): Map<Int, QueuePosition> {
        val line = (activeIds + queuedIds).distinct()
        return line.withIndex().associate { (index, id) -> id to QueuePosition(index + 1, line.size) }
    }

    /**
     * Adds what the queue knows to what the transfers reported. A download waiting, or loading its
     * links, has reported nothing yet and still has a row. An instance the service has not removed yet
     * is finished, so it takes no place in line. A finished download back in the queue is waiting
     * again and no longer carries its old failure.
     */
    fun merge(
        facts: Map<Int, DownloadProgress>,
        activeIds: List<Int>,
        queuedIds: List<Int>,
    ): Map<Int, DownloadProgress> {
        val running = activeIds.filterNot { facts[it]?.isFinished == true }
        val positions = queuePositions(running, queuedIds)
        return (facts.keys + positions.keys).associateWith { id ->
            val fact = facts[id]
            val position = positions[id]
            when {
                position == null -> fact!!
                fact == null -> DownloadProgress(id, DownloadState.QUEUED, bytesDownloaded = 0L, queue = position)
                fact.isFinished -> fact.copy(state = DownloadState.QUEUED, bytesPerSecond = 0L, failure = null, queue = position)
                else -> fact.copy(queue = position)
            }
        }
    }

    fun observe(
        facts: Flow<Map<Int, DownloadProgress>>,
        activeIds: Flow<List<Int>>,
        queuedIds: Flow<List<Int>>,
    ): Flow<Map<Int, DownloadProgress>> =
        combine(facts, activeIds, queuedIds, ::merge).distinctUntilChanged()

    private val DownloadProgress.isFinished: Boolean
        get() = state == DownloadState.COMPLETED || state == DownloadState.FAILED || state == DownloadState.STOPPED

    /**
     * Whether a noted reason stays when another arrives. A full disk stays, because a connection torn
     * down afterwards is a symptom of it. Anything named stays over [DownloadFailure.Unknown], which is
     * what the catch-all at the end of a transfer says about a fault already noted.
     */
    private fun outranks(kept: DownloadFailure, incoming: DownloadFailure): Boolean =
        kept is DownloadFailure.StorageFull || incoming == DownloadFailure.Unknown

    private fun forgetNote(id: Int) = notes.update { it - id }

    private fun Throwable.isNetworkLoss(): Boolean =
        this is UnknownHostException || this is SocketException || this is SocketTimeoutException ||
                this is EOFException || this is ProtocolException || this is SSLException

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "A download fact was lost\n${redactedStackTrace(t)}")
        }
    }

    private const val MAX_CAUSES = 16
    private val NO_SPACE = listOf("ENOSPC", "No space left")
}

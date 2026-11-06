package com.lagradost.cloudstream4.download

import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadStatus
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadType
import com.lagradost.cloudstream4.media.model.DownloadFailure
import com.lagradost.cloudstream4.media.model.DownloadState

/**
 * The decisions [EngineVideoDownload] makes, as pure functions so a JVM test can pin them.
 *
 * The [DownloadStatus] values restate `VideoDownloadManager`'s private constants, which `downloadFromResume`'s
 * mirror loop reads by field: `retrySame` calls the same link again with `tryResume = true`, `tryNext` moves to the
 * next mirror with `tryResume = false`, and `success` ends the loop as done.
 */
internal object EngineDownloadRules {
    /** Mirrors `DOWNLOAD_SUCCESS`. */
    val SUCCESS = DownloadStatus(retrySame = false, tryNext = false, success = true)

    /** Mirrors `DOWNLOAD_STOPPED`: the user stopped it, so no other mirror is wanted. */
    val STOPPED = DownloadStatus(retrySame = false, tryNext = false, success = true)

    /**
     * Mirrors `DOWNLOAD_PARTIAL_SUCCESS`: try this link once more, resuming from the staged parts, and
     * never hand over to a mirror, whose `tryResume = false` would throw those parts away.
     */
    val RETRY_SAME = DownloadStatus(retrySame = true, tryNext = false, success = false)

    /** Mirrors `DOWNLOAD_INVALID_INPUT`: this source will not work, the next one might. */
    val TRY_NEXT = DownloadStatus(retrySame = false, tryNext = true, success = false)

    /** Mirrors `DOWNLOAD_BAD_CONFIG`: every mirror writes to the same disk, so none is tried. */
    val NO_MIRROR = DownloadStatus(retrySame = false, tryNext = false, success = false)

    /** Mirrors `DOWNLOAD_FAILED`: nothing says why, so retry, then move on. */
    val FAILED = DownloadStatus(retrySame = true, tryNext = true, success = false)

    /** What the mirror loop does with an engine failure. Exhaustive, so a new case fails to compile here. */
    fun statusOf(failure: DownloadFailure): DownloadStatus = when (failure) {
        DownloadFailure.NetworkLost -> RETRY_SAME
        is DownloadFailure.ServerRefused,
        DownloadFailure.NoUsableLink,
        DownloadFailure.SourceChanged,
        DownloadFailure.AddressRefused,
        DownloadFailure.HashMismatch,
        is DownloadFailure.SizeMismatch,
        DownloadFailure.SourceFailed,
        -> TRY_NEXT
        is DownloadFailure.StorageFull,
        DownloadFailure.StorageUnavailable,
        is DownloadFailure.InsufficientSpace,
        // The engine is always asked to overwrite, so this would be a staging bug, not the source's.
        DownloadFailure.DestinationExists,
        -> NO_MIRROR
        DownloadFailure.Unknown -> FAILED
    }

    /** The old status an engine state is reported as. The engine never emits QUEUED or STOPPED from `download()`. */
    fun typeOf(state: DownloadState): DownloadType = when (state) {
        DownloadState.QUEUED -> DownloadType.IsPending
        DownloadState.DOWNLOADING -> DownloadType.IsDownloading
        DownloadState.PAUSED -> DownloadType.IsPaused
        DownloadState.COMPLETED -> DownloadType.IsDone
        DownloadState.FAILED -> DownloadType.IsFailed
        DownloadState.STOPPED -> DownloadType.IsStopped
    }

    /**
     * Written into the staging directory once the engine has finished the file and before the copy
     * starts: the staged file [fileName] is complete at [bytes]. A start that finds it copies again
     * instead of fetching again.
     */
    class StagedMarker(val bytes: Long, val fileName: String) {
        override fun equals(other: Any?) = other is StagedMarker && other.bytes == bytes && other.fileName == fileName
        override fun hashCode() = 31 * bytes.hashCode() + fileName.hashCode()
        override fun toString() = "StagedMarker($bytes, $fileName)"
    }

    const val MARKER_FILE_NAME = "complete"

    fun formatMarker(marker: StagedMarker): String = "${marker.bytes}\n${marker.fileName}\n"

    /** `null` for anything that is not exactly what [formatMarker] writes, including a torn write. */
    fun parseMarker(text: String?): StagedMarker? {
        if (text == null || !text.endsWith("\n")) return null
        val lines = text.removeSuffix("\n").split('\n')
        if (lines.size != 2) return null
        val bytes = lines[0].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val name = lines[1]
        if (name.isBlank() || name == MARKER_FILE_NAME || name.startsWith('.') || '/' in name || '\\' in name) return null
        return StagedMarker(bytes, name)
    }

    enum class StartAction {
        /** The staged file is complete: copy it to the destination, fetch nothing. */
        COPY_STAGED,

        /** Throw the staging directory away and download from byte 0. */
        DOWNLOAD_FRESH,

        /** Hand the staging directory to the engine, which resumes what its state file can prove. */
        DOWNLOAD_RESUME,
    }

    /**
     * What a start does with the staging directory. [stagedLength] is the size of the marker's file,
     * `null` when it is missing. Without [tryResume] the caller asked for a new download, as
     * `downloadThing` truncates its file then.
     */
    fun startAction(tryResume: Boolean, marker: StagedMarker?, stagedLength: Long?): StartAction = when {
        !tryResume -> StartAction.DOWNLOAD_FRESH
        marker == null -> StartAction.DOWNLOAD_RESUME
        stagedLength == marker.bytes -> StartAction.COPY_STAGED
        // The marker promises a file that is not there at that size: nothing in the directory can be trusted.
        else -> StartAction.DOWNLOAD_FRESH
    }

    /**
     * The download needs room for what is still to fetch plus a whole second copy at the end, on
     * volumes treated as one. [available] is free space now, which already excludes the [onDisk] bytes.
     */
    fun stagingShortfall(total: Long, onDisk: Long, available: Long?): DownloadFailure.InsufficientSpace? {
        if (available == null || total <= 0L) return null
        val needed = (total - onDisk).coerceAtLeast(0L) + total
        return if (available < needed) DownloadFailure.InsufficientSpace(needed, available) else null
    }

    /** The copy needs [stagedBytes] free; the staged file already occupies its own. */
    fun copyShortfall(stagedBytes: Long, available: Long?): DownloadFailure.InsufficientSpace? {
        if (available == null || available >= stagedBytes) return null
        return DownloadFailure.InsufficientSpace(stagedBytes, available)
    }

    /**
     * The staging directories (named by episode id) whose episode no longer exists, so they can only
     * leak space. A directory is kept while its id is [running] or [queued], or while it can still be
     * resumed: a resume package ([resumable]) **and** an episode entry ([listed]). The episode entry is
     * needed as well because the Downloads list prunes a failed download by removing only that entry,
     * and leaves its resume package behind. A name that is not an id is not ours and is left alone.
     */
    fun stagingToSweep(
        stagedNames: Collection<String>,
        running: Set<Int>,
        queued: Set<Int>,
        resumable: Set<Int>,
        listed: Set<Int>,
    ): List<String> = stagedNames.filter { name ->
        val id = name.toIntOrNull() ?: return@filter false
        id !in running && id !in queued && !(id in resumable && id in listed)
    }

    /** The id at the end of a stored key such as `download_resume_2/123` or `download_episode_cache/9/123`. */
    fun idOfKey(key: String): Int? = key.substringAfterLast('/').toIntOrNull()

    /** The smaller of two free-space readings, either of which may be unknown. */
    fun minOfKnown(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> minOf(a, b)
    }

    /**
     * The `name` to hand `setupStream` (which appends `.extension`) so the destination is called what
     * the engine called the staged file, or [fallback] when the staged name has lost the extension.
     */
    fun destinationStem(stagedName: String, extension: String, fallback: String): String {
        val suffix = ".$extension"
        val stem = stagedName.removeSuffix(suffix)
        return if (stagedName.endsWith(suffix) && stem.isNotEmpty()) stem else fallback
    }
}

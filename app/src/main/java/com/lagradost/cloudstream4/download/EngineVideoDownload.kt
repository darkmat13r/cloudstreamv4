package com.lagradost.cloudstream4.download

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.lagradost.cloudstream3.IDownloadableMinimum
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.services.DownloadQueueService
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DataStore.getFolderName
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.getKeys
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getBasePath
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.CreateNotificationMetadata
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadStatus
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadedFileInfo
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadedFileInfoResult
import com.lagradost.cloudstream3.utils.downloader.DownloadUtils.appendAndDontOverride
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadActionType
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadMetaData
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadType
import com.lagradost.cloudstream4.core.http.HttpClientFactory
import com.lagradost.cloudstream4.download.EngineDownloadRules.StagedMarker
import com.lagradost.cloudstream4.download.EngineDownloadRules.StartAction
import com.lagradost.cloudstream4.media.download.DownloadOptions
import com.lagradost.cloudstream4.media.download.DownloadRequest
import com.lagradost.cloudstream4.media.download.DownloadSnapshot
import com.lagradost.cloudstream4.media.download.FileKitStorage
import com.lagradost.cloudstream4.media.download.SegmentedDownloader
import com.lagradost.cloudstream4.media.model.DownloadFailure
import com.lagradost.cloudstream4.media.model.DownloadState
import com.lagradost.safefile.SafeFile
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

// Top-level and private, so they compile package-private and stay out of classes.jar's dump.
private sealed interface Fetched
private class FetchedDone(val file: File, val bytes: Long) : Fetched
private class FetchedEnded(val status: DownloadStatus) : Fetched

private sealed interface RunResult
private class RunTerminal(val snapshot: DownloadSnapshot) : RunResult
private class RunRefused(val failure: DownloadFailure) : RunResult
private class RunCrashed(val error: Throwable) : RunResult
private data object RunNoTerminal : RunResult

/**
 * One `ExtractorLinkType.VIDEO` download through the segmented engine, reported through the same
 * [DownloadMetaData] `downloadThing` uses, so `downloadProgressEvent`, `downloadStatusEvent`,
 * `downloadStatus`, [DownloadFacts] and the notification hear what they always heard. Only runs when
 * [EngineDownloadSwitch.useSegmentedEngine] is on.
 *
 * ## Staging, then one copy
 *
 * The engine needs a real directory with atomic moves, which a MediaStore or document-tree folder is
 * not. So it downloads into `<stagingRoot>/<episode id>/` (in production `noBackupFilesDir/downloads`:
 * app-private, never backed up or device-transferred, same volume as `filesDir`); on COMPLETED this writes
 * [EngineDownloadRules.MARKER_FILE_NAME] there, copies the staged file into the destination
 * `setupStream` resolves (truncating, never appending), checks the copy's length with
 * `fileLengthOnDisk`, writes `KEY_DOWNLOAD_INFO`, and only then deletes the staging directory.
 *
 * - A crash after the copy, before the delete: one visible complete file, plus the private staged copy.
 *   The next resume finds the marker and copies again; no byte is fetched.
 * - A crash during the copy: a partial visible file, plus the staged file and its marker. The next
 *   resume copies again over the partial; no byte is fetched.
 * - A copy of the wrong length: the destination is deleted, the staged file is kept, and the download
 *   fails [DownloadFailure.SizeMismatch] without trying another mirror, which would throw the staged
 *   file away.
 *
 * ## Listed while it runs (`KMP-60`)
 *
 * `KEY_DOWNLOAD_INFO` is written at the run's first snapshot, as `downloadThing` writes it after its headers, so the
 * Downloads lists can show the episode before a destination file exists: [getDownloadListInfo] gives the staged bytes
 * and an empty path, and [refusesPlay] keeps every play action off that path.
 *
 * A staging directory whose episode is gone (stopped while not running, deleted, or pruned from the
 * Downloads list after a failure) is removed by [sweepOrphanedStaging], which `DownloadQueueManager.init`
 * runs at start-up.
 *
 * ## Space
 *
 * The peak is about twice the file: the staged copy plus the destination. Checked twice, treating the
 * staging and destination volumes as one: at the first engine snapshot that carries the total (the
 * engine has already passed its own check of its own peak by then, and has been fetching for at most
 * one progress interval, 250 ms), for what is left to fetch plus the copy; and again before the copy.
 * Either failure is [DownloadFailure.InsufficientSpace]. The staged parts are kept.
 *
 * ## Pause, resume, stop
 *
 * They arrive as `downloadEvent`, as for `downloadThing`. Pause cancels the engine's collector, which
 * is its kill-safe pause: the parts stay on disk and the call waits, holding its queue slot as
 * `downloadThing` does. Resume collects again and the engine carries on from disk. Stop cancels,
 * discards the engine's temporary directory, deletes the staging directory and any destination file,
 * and returns stopped, which is what stopping `downloadThing` leaves.
 */
internal class EngineVideoDownload(
    private val context: Context,
    private val downloader: SegmentedDownloader,
    private val stagingRoot: File,
    private val allowPrivateAddresses: Boolean = false,
    /** Test-only: runs after `KEY_DOWNLOAD_INFO` is written and before the staging directory is deleted. */
    private val afterCopyVerified: () -> Unit = {},
    /** Test-only: every snapshot the engine emits, before it is mapped. */
    private val onSnapshot: (DownloadSnapshot) -> Unit = {},
) {
    /** Thrown from the collector to stop a run the space check refused. Never leaves this class. */
    private class SpaceRefused(val failure: DownloadFailure.InsufficientSpace) : RuntimeException()

    /** What one collection has done to [DownloadMetaData] so far. */
    private class RunProgress {
        var started = false
        var resumeLengthSet = false
        var spaceChecked = false
        var terminal: DownloadSnapshot? = null
    }

    /** One call's state, shared with the `downloadEvent` listener. */
    private class Call(
        val id: Int,
        val name: String,
        val folder: String,
        val baseFile: SafeFile,
        val basePath: String?,
        val staging: File,
        val metadata: DownloadMetaData,
    ) {
        val displayName = "$name.$EXTENSION"
        val actions = Channel<DownloadActionType>(Channel.UNLIMITED)
        val stopRequested = AtomicBoolean(false)
        val paused = AtomicBoolean(false)
        var stagedName: String? = null

        /** `KEY_DOWNLOAD_INFO` has been written for the list, and whether it carried a known total. */
        var listed = false
        var listedWithTotal = false
    }

    suspend fun download(
        link: IDownloadableMinimum,
        name: String,
        folder: String,
        tryResume: Boolean,
        id: Int,
        createNotificationCallback: (CreateNotificationMetadata) -> Unit,
    ): DownloadStatus = withContext(Dispatchers.IO) {
        val metadata = DownloadMetaData(
            id = id,
            linkHash = link.url.hashCode(),
            createNotificationCallback = createNotificationCallback,
            isHLS = false,
        )
        var listener: ((Pair<Int, DownloadActionType>) -> Unit)? = null
        try {
            val (baseFile, basePath) = context.getBasePath()
            if (baseFile == null) {
                return@withContext fail(id, metadata, DownloadFailure.StorageUnavailable, EngineDownloadRules.NO_MIRROR)
            }
            val call = Call(id, name, folder, baseFile, basePath, File(stagingRoot, id.toString()), metadata)
            listener = { event: Pair<Int, DownloadActionType> ->
                if (event.first == id) {
                    when (event.second) {
                        DownloadActionType.Stop -> call.stopRequested.set(true)
                        DownloadActionType.Pause -> call.paused.set(true)
                        DownloadActionType.Resume -> call.paused.set(false)
                    }
                    call.actions.trySend(event.second)
                }
            }
            VideoDownloadManager.downloadEvent += listener
            run(call, link, tryResume)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logFailure("download ended by an error", t)
            val failure = DownloadFacts.classify(t)
            fail(id, metadata, failure, EngineDownloadRules.statusOf(failure))
        } finally {
            listener?.let { VideoDownloadManager.downloadEvent -= it }
            metadata.close()
        }
    }

    private suspend fun run(call: Call, link: IDownloadableMinimum, tryResume: Boolean): DownloadStatus {
        call.metadata.storagePath = call.staging.path
        call.metadata.type = DownloadType.IsPending

        val marker = readMarker(call.staging)
        val stagedLength = marker?.let { File(call.staging, it.fileName).takeIf(File::isFile)?.length() }
        val action = EngineDownloadRules.startAction(tryResume, marker, stagedLength)
        if (action == StartAction.DOWNLOAD_FRESH) wipeStaging(call)
        if (!call.staging.isDirectory && !call.staging.mkdirs()) {
            return fail(call.id, call.metadata, DownloadFailure.StorageUnavailable, EngineDownloadRules.NO_MIRROR)
        }

        val fetched = if (action == StartAction.COPY_STAGED) {
            call.stagedName = marker!!.fileName
            call.metadata.type = DownloadType.IsDownloading
            FetchedDone(File(call.staging, marker.fileName), marker.bytes)
        } else {
            fetch(call, request(call, link))
        }
        return when (fetched) {
            is FetchedEnded -> fetched.status
            is FetchedDone -> deliver(call, fetched.file, fetched.bytes)
        }
    }

    private fun request(call: Call, link: IDownloadableMinimum) = DownloadRequest(
        url = link.url.replace(" ", "%20"),
        folder = PlatformFile(call.staging),
        fileName = call.displayName,
        headers = link.headers.appendAndDontOverride(mapOf("user-agent" to USER_AGENT)),
        referer = link.referer,
        // A staged file left by a crash between the engine's move and the marker is not provably complete.
        overwrite = true,
        options = DownloadOptions(allowPrivateAddresses = allowPrivateAddresses),
    )

    /** Runs the engine until it ends, pausing and resuming on `downloadEvent`. */
    private suspend fun fetch(call: Call, request: DownloadRequest): Fetched = coroutineScope {
        var outcome: Fetched? = null
        while (outcome == null) {
            val progress = RunProgress()
            val collector = async {
                try {
                    downloader.download(request).collect { snapshot ->
                        onSnapshot(snapshot)
                        apply(call, snapshot, progress)
                    }
                    progress.terminal?.let { RunTerminal(it) } ?: RunNoTerminal
                } catch (e: SpaceRefused) {
                    RunRefused(e.failure)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    RunCrashed(t)
                }
            }

            // What interrupted the run: null when it ended by itself.
            var interruption: DownloadActionType? = null
            while (true) {
                val action = select<DownloadActionType?> {
                    collector.onAwait { null }
                    call.actions.onReceive { it }
                } ?: break
                if (action == DownloadActionType.Resume) continue
                collector.cancelAndJoin()
                // It may have ended on its own just before the event: that result stands.
                if (collector.isCancelled) interruption = action
                break
            }

            when (interruption) {
                DownloadActionType.Stop -> outcome = FetchedEnded(stop(call))
                DownloadActionType.Pause -> {
                    var next = call.actions.receive()
                    while (next == DownloadActionType.Pause) next = call.actions.receive()
                    // Resume goes round again and collects afresh; the engine resumes from disk.
                    if (next == DownloadActionType.Stop) outcome = FetchedEnded(stop(call))
                }
                else -> outcome = when (val result = collector.await()) {
                    is RunTerminal -> finished(call, result.snapshot)
                    is RunRefused -> FetchedEnded(fail(call, result.failure, EngineDownloadRules.NO_MIRROR))
                    is RunCrashed -> {
                        logFailure("engine run ended by an error", result.error)
                        val failure = DownloadFacts.classify(result.error)
                        FetchedEnded(fail(call, failure, EngineDownloadRules.statusOf(failure)))
                    }
                    RunNoTerminal -> FetchedEnded(fail(call, DownloadFailure.Unknown, EngineDownloadRules.FAILED))
                }
            }
        }
        outcome
    }

    /** Feeds one snapshot into [DownloadMetaData] the way `downloadThing` feeds its byte counts. */
    private fun apply(call: Call, snapshot: DownloadSnapshot, progress: RunProgress) {
        val metadata = call.metadata
        when (snapshot.state) {
            DownloadState.COMPLETED, DownloadState.FAILED -> progress.terminal = snapshot
            else -> {
                if (!progress.started && !call.paused.get()) {
                    progress.started = true
                    metadata.type = EngineDownloadRules.typeOf(snapshot.state)
                }
                // The first snapshot with parts says what a resumed run already holds: not a burst of speed.
                if (snapshot.parts.isNotEmpty() && !progress.resumeLengthSet) {
                    progress.resumeLengthSet = true
                    metadata.setResumeLength(snapshot.bytesDone)
                }
                metadata.totalBytes = snapshot.bytesTotal
                metadata.bytesWritten = snapshot.bytesDone
                metadata.addBytes(snapshot.bytesDone - metadata.bytesDownloaded)

                val total = snapshot.bytesTotal
                // KMP-60: the record the Downloads lists read, written as `downloadThing` writes it once it has its
                // headers, and again when the total becomes known. It holds what that one holds: no URL or header.
                if (!call.listed || (!call.listedWithTotal && total != null)) {
                    call.listed = true
                    call.listedWithTotal = total != null
                    metadata.setDownloadFileInfoTemplate(
                        DownloadedFileInfo(
                            totalBytes = 0L, // replaced from the metadata's own total when written
                            relativePath = call.folder,
                            displayName = call.displayName,
                            basePath = call.basePath,
                        )
                    )
                }
                if (!progress.spaceChecked && total != null) {
                    progress.spaceChecked = true
                    EngineDownloadRules.stagingShortfall(total, snapshot.bytesDone, availableSpace(call))
                        ?.let { throw SpaceRefused(it) }
                }
            }
        }
    }

    private fun finished(call: Call, snapshot: DownloadSnapshot): Fetched {
        if (snapshot.state == DownloadState.FAILED) {
            val failure = snapshot.failure ?: DownloadFailure.Unknown
            return FetchedEnded(fail(call, failure, EngineDownloadRules.statusOf(failure)))
        }
        val staged = snapshot.file?.let { File(it.path) }
        if (staged == null || !staged.isFile) {
            return FetchedEnded(fail(call, DownloadFailure.Unknown, EngineDownloadRules.FAILED))
        }
        val bytes = staged.length()
        call.stagedName = staged.name
        // Before the copy: from here on a restart copies again rather than fetching again.
        File(call.staging, EngineDownloadRules.MARKER_FILE_NAME)
            .writeText(EngineDownloadRules.formatMarker(StagedMarker(bytes, staged.name)))
        return FetchedDone(staged, bytes)
    }

    /** Copy, verify the length, record, delete the staging: in that order. */
    private suspend fun deliver(call: Call, staged: File, bytes: Long): DownloadStatus {
        val metadata = call.metadata
        metadata.totalBytes = bytes
        metadata.setResumeLength(bytes)

        EngineDownloadRules.copyShortfall(bytes, availableSpace(call))?.let {
            return fail(call, it, EngineDownloadRules.NO_MIRROR)
        }

        val stem = EngineDownloadRules.destinationStem(staged.name, EXTENSION, call.name)
        metadata.storagePath = destinationPath(call)
        val stream = VideoDownloadManager.setupStream(call.baseFile, stem, call.folder, EXTENSION, tryResume = false)
        val copied = try {
            copy(staged, stream.open(), call.stopRequested)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            logFailure("copy to the destination failed", e)
            stream.delete()
            return fail(call, DownloadFacts.classify(e), EngineDownloadRules.NO_MIRROR)
        }
        if (!copied) return stop(call)

        val length = VideoDownloadManager.fileLengthOnDisk(context, stream.file.uri()) ?: stream.file.lengthOrThrow()
        if (length != bytes) {
            stream.delete()
            return fail(call, DownloadFailure.SizeMismatch(bytes, length), EngineDownloadRules.NO_MIRROR)
        }

        metadata.setDownloadFileInfoTemplate(
            DownloadedFileInfo(
                totalBytes = bytes,
                relativePath = call.folder,
                displayName = "$stem.$EXTENSION",
                basePath = call.basePath,
            )
        )
        afterCopyVerified()
        wipeStaging(call)
        metadata.type = DownloadType.IsDone
        return EngineDownloadRules.SUCCESS
    }

    /** `false` when a stop arrived first. Fresh (truncating) output, flushed and closed. */
    private suspend fun copy(from: File, output: java.io.OutputStream, stop: AtomicBoolean): Boolean {
        output.use { out ->
            from.inputStream().use { input ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (stop.get()) return false
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
                out.flush()
            }
        }
        return true
    }

    private suspend fun stop(call: Call): DownloadStatus {
        wipeStaging(call)
        val directory = call.baseFile.gotoDirectory(call.folder, createMissingDirectories = false)
        listOfNotNull(call.displayName, call.stagedName).distinct().forEach { name ->
            runCatching { directory?.findFile(name)?.delete() }
        }
        call.metadata.onDelete()
        return EngineDownloadRules.STOPPED
    }

    private suspend fun wipeStaging(call: Call) {
        runCatching { downloader.discard(PlatformFile(call.staging), call.displayName) }
        call.staging.deleteRecursively()
    }

    private fun fail(call: Call, failure: DownloadFailure, status: DownloadStatus) =
        fail(call.id, call.metadata, failure, status)

    /** The engine's own failure, as it is: [DownloadMetaData.noteFailure] would recompute a full disk's shortfall. */
    private fun fail(id: Int, metadata: DownloadMetaData, failure: DownloadFailure, status: DownloadStatus): DownloadStatus {
        DownloadFacts.note(id, failure, metadata.bytesDownloaded, metadata.bytesWritten)
        metadata.type = DownloadType.IsFailed
        return status
    }

    /**
     * The exception's class and a fixed text only. Its message and stack trace can carry the destination
     * path (the user's folder and file names), and this code never logs a URL, header or path.
     */
    private fun logFailure(what: String, t: Throwable) {
        Log.w(TAG, "$what: ${t.javaClass.name}")
    }

    private fun destinationPath(call: Call): String? =
        DownloadFacts.storagePathOf(call.basePath, Environment.getExternalStorageDirectory()?.path)

    /** Free space on the staging volume and, when it is a path, the destination's. */
    private fun availableSpace(call: Call): Long? = EngineDownloadRules.minOfKnown(
        DownloadFacts.availableBytes(call.staging.path),
        DownloadFacts.availableBytes(destinationPath(call)),
    )

    private fun readMarker(staging: File): StagedMarker? = EngineDownloadRules.parseMarker(
        runCatching { File(staging, EngineDownloadRules.MARKER_FILE_NAME).takeIf(File::isFile)?.readText() }.getOrNull()
    )

    companion object {
        private const val EXTENSION = "mp4"
        private const val COPY_BUFFER_BYTES = 1 shl 20

        /** Restates `VideoDownloadManager`'s private `USER_AGENT`, which `downloadThing` adds to video requests. */
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

        private var client: Pair<OkHttpClient, HttpClient>? = null

        /**
         * One Ktor client over `app.baseClient`, rebuilt when that is replaced (a DNS setting change
         * replaces it rather than mutating it). Never `insecureApp`: the engine verifies TLS.
         */
        @Synchronized
        private fun httpClient(): HttpClient {
            val okHttp = app.baseClient
            client?.takeIf { it.first === okHttp }?.let { return it.second }
            return HttpClientFactory(okHttp).create().also { client = okHttp to it }
        }

        /** The instance `downloadSingleEpisode` uses, with [EngineDownloadSwitch]'s test hooks applied. */
        fun fromSwitch(context: Context): EngineVideoDownload = EngineVideoDownload(
            context = context,
            downloader = SegmentedDownloader(httpClient(), EngineDownloadSwitch.storageForTests ?: FileKitStorage()),
            stagingRoot = EngineDownloadSwitch.stagingRootForTests ?: defaultStagingRoot(context),
            allowPrivateAddresses = EngineDownloadSwitch.allowPrivateAddressesForTests,
        )

        /**
         * `noBackupFilesDir/downloads`: app-private, never in an auto-backup or a device-to-device
         * transfer, and on the same volume as `filesDir`.
         */
        fun defaultStagingRoot(context: Context): File = File(context.noBackupFilesDir, "downloads")

        /**
         * Deletes every staging directory under [root] whose episode no longer exists (see
         * [EngineDownloadRules.stagingToSweep]): stopped, deleted, or pruned from the Downloads list after
         * failing. Never touches an id that is running or [queuedIds]. Runs whatever the switch says, so
         * staging left from a time it was on still goes. Never throws.
         */
        fun sweepOrphanedStaging(context: Context, queuedIds: Collection<Int>, root: File = defaultStagingRoot(context)) {
            try {
                val names = root.list()?.toList().orEmpty()
                if (names.isEmpty()) return
                val running = VideoDownloadManager.currentDownloads.value +
                    DownloadQueueService.downloadInstances.value.map { it.downloadQueueWrapper.id }
                val resumable = context.getKeys(VideoDownloadManager.KEY_RESUME_PACKAGES)
                    .mapNotNull(EngineDownloadRules::idOfKey).toSet()
                val listed = context.getKeys(DOWNLOAD_EPISODE_CACHE).mapNotNull(EngineDownloadRules::idOfKey).toSet()
                val inQueue = queuedIds.toSet() +
                    context.getKeys(VideoDownloadManager.KEY_RESUME_IN_QUEUE).mapNotNull(EngineDownloadRules::idOfKey)
                EngineDownloadRules.stagingToSweep(names, running, inQueue, resumable, listed).forEach { name ->
                    File(root, name).deleteRecursively()
                }
            } catch (t: Throwable) {
                // The class only, as in [logFailure]; a member there, so no synthetic accessor reaches classes.jar.
                Log.w(TAG, "staging sweep failed: ${t.javaClass.name}")
            }
        }

        /**
         * What the Downloads lists read for one episode (`KMP-60`): [VideoDownloadManager.getDownloadFileInfo] when the
         * destination file exists. Otherwise, with the switch on, an engine download that has a staging directory and a
         * `KEY_DOWNLOAD_INFO` record is listed with the bytes its staging holds and [Uri.EMPTY] for a path, which every
         * play action refuses ([refusesPlay]). With the switch off this is exactly `getDownloadFileInfo`.
         */
        fun getDownloadListInfo(context: Context, id: Int): DownloadedFileInfoResult? {
            VideoDownloadManager.getDownloadFileInfo(context, id)?.let { return it }
            if (!EngineDownloadSwitch.useSegmentedEngine) return null
            return try {
                val staging = stagingOf(context, id)
                if (!staging.isDirectory) return null
                // The Class overload: the reified one inlines a TypeReference class into classes.jar.
                val info = context.getKey(
                    getFolderName(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString()),
                    DownloadedFileInfo::class.java,
                ) ?: return null
                val staged = stagedBytes(staging)
                DownloadedFileInfoResult(
                    if (info.totalBytes > 0L) minOf(staged, info.totalBytes) else staged,
                    info.totalBytes,
                    Uri.EMPTY,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "list read failed: ${t.javaClass.name}")
                null
            }
        }

        /**
         * True when [id] is an engine download whose destination does not exist yet: there is nothing to play, open or
         * share, only the private staging. Always false with the switch off, and then nothing is read.
         */
        fun refusesPlay(context: Context, id: Int): Boolean =
            EngineDownloadSwitch.useSegmentedEngine &&
                stagingOf(context, id).isDirectory &&
                VideoDownloadManager.getDownloadFileInfo(context, id) == null

        /**
         * For `deleteFile`, when the destination file is missing: true when [id] has engine staging, which is then what
         * a delete removes. A running download's staging is left to its own stop, which the delete's Stop event sends;
         * an idle one's is deleted here. Always false with the switch off.
         */
        fun deleteStagingForDelete(context: Context, id: Int): Boolean {
            if (!EngineDownloadSwitch.useSegmentedEngine) return false
            val staging = stagingOf(context, id)
            if (!staging.isDirectory) return false
            val running = VideoDownloadManager.currentDownloads.value.contains(id) ||
                DownloadQueueService.downloadInstances.value.any { it.downloadQueueWrapper.id == id }
            if (!running) staging.deleteRecursively()
            return true
        }

        private fun stagingOf(context: Context, id: Int): File =
            File(EngineDownloadSwitch.stagingRootForTests ?: defaultStagingRoot(context), id.toString())

        /** The sizes of the files the staging holds, the marker aside. Sizes only: no file is opened. */
        private fun stagedBytes(staging: File): Long = staging.walkTopDown()
            .filter { it.isFile && it.name != EngineDownloadRules.MARKER_FILE_NAME }
            .sumOf { it.length() }

        private const val TAG = "EngineVideoDownload"
    }
}

package com.lagradost.cloudstream4.downloads

import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream4.core.http.HttpClientFactory
import com.lagradost.cloudstream4.feature.downloads.DownloadShelf
import com.lagradost.cloudstream4.feature.downloads.DownloadStorage
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleId
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleKind
import com.lagradost.cloudstream4.feature.downloads.DownloadsContents
import com.lagradost.cloudstream4.feature.downloads.EpisodeOnDisk
import com.lagradost.cloudstream4.feature.downloads.StorageShortfall
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisodeId
import com.lagradost.cloudstream4.feature.downloads.series.DownloadSeriesContents
import com.lagradost.cloudstream4.feature.downloads.series.SeriesShelf
import com.lagradost.cloudstream4.media.download.DownloadOptions
import com.lagradost.cloudstream4.media.download.DownloadRequest
import com.lagradost.cloudstream4.media.download.DownloadSnapshot
import com.lagradost.cloudstream4.media.download.FileKitStorage
import com.lagradost.cloudstream4.media.download.SegmentedDownloader
import com.lagradost.cloudstream4.media.model.DownloadFailure
import com.lagradost.cloudstream4.media.model.DownloadProgress
import com.lagradost.cloudstream4.media.model.DownloadState
import com.lagradost.cloudstream4.media.model.QueuePosition
import com.lagradost.cloudstream4.search.hasPosterUserInfo
import com.lagradost.cloudstream4.search.posterHttpClient
import io.github.vinceglb.filekit.PlatformFile
import io.ktor.client.HttpClient
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

/** Where one episode stands. [Done] and [Failed] read back as themselves after a restart; the rest as [Paused]. */
internal enum class Phase { Queued, Running, Paused, Failed, Done }

/** One episode as readers outside the queue see it: an immutable copy, replaced whenever anything changes. */
internal class EpisodeView(
    val episodeId: Int,
    val titleId: Int,
    val titleName: String,
    val kind: DownloadTitleKind,
    val season: Int?,
    val episode: Int?,
    val episodeName: String?,
    val folder: File,
    val file: File,
    val phase: Phase,
    val failure: DownloadFailure?,
    val bytesOnDisk: Long,
    val bytesTotal: Long?,
    val addedSeq: Long,
    val queueSeq: Long,
    val startedSeq: Long,
    val posterUrl: String? = null,
) {
    override fun toString(): String = "EpisodeView(episodeId=$episodeId, phase=$phase)"
}

/**
 * The Mac's downloads (`KMP-52`): a queue over `:media:download`, a file that survives restarts, and the data the
 * Downloads screens read through [DesktopDownloadsSource] and [DesktopDownloadSeriesSource]. No UI.
 *
 * ## The queue
 *
 * FIFO, at most three running at once (Android's default). Each run is one [SegmentedDownloader] over the one
 * [HttpClient] this holds. Every change goes through one coroutine reading a channel, so pause, resume, cancel and
 * delete apply in the order they were asked for and never race a run that is ending.
 *
 * - **Pause** cancels the run's collector, which is the engine's pause: the parts stay on disk and the slot is
 *   freed for the next in line. Unlike Android, a paused download does not hold its slot.
 * - **Resume** and **retry** put a paused or failed episode at the back of the line. The engine carries on from
 *   its parts.
 * - **Cancel** and **delete** stop the run, `discard` the engine's temporary directory, delete the file and the
 *   title's folder once it is empty, and drop the record.
 *
 * ## Files
 *
 * `<downloadRoot>/<title>/<episode>.mp4`, each name one segment made by [DownloadPaths] and fixed when the episode
 * is first enqueued. The engine downloads into that folder directly and moves the joined file into place
 * atomically, so a file exists only once it is complete. There is no staging copy, as there is on Android.
 *
 * ## After a restart
 *
 * Nothing starts on its own. An episode that was waiting or running is paused; a failed one stays failed, with its
 * stored reason. Either way its bytes are what the engine's parts hold on disk. A finished episode whose
 * file is gone is dropped, as Android's list readers drop one.
 *
 * ## Links
 *
 * The downloads file holds no link (owner's ruling): only the provider and the episode's data. A link lives in
 * memory for this process: the one [enqueue] was given, or the last one [LinkResolver] returned. A paused episode
 * resumes with it. With none in memory (after a restart) and on every retry of a failure, the run first asks the
 * [LinkResolver] and takes the first VIDEO link with an http(s) URL. None, a throw or a timeout fails the episode,
 * [DownloadFailure.NoUsableLink] or [DownloadFailure.SourceFailed], and its parts stay on disk.
 *
 * A failure is written with its typed reason (see [StoredFailure]) and still reads Failed after a restart.
 *
 * ## Security
 *
 * TLS is verified and private, loopback and link-local addresses are refused: the engine's defaults, and the
 * production client below filters resolved addresses too. Nothing here logs a URL, header, referer or path; a log
 * line is fixed text and an exception's class.
 */
class DesktopDownloads internal constructor(
    downloadRoot: File,
    stateFile: File,
    private val httpClient: HttpClient,
    /** Asks the extension for fresh links when there is none in memory: after a restart, and on every retry. */
    private val resolver: LinkResolver,
    /** The engine's options. Production takes the defaults; only a test may pass `allowPrivateAddresses`. */
    private val options: DownloadOptions,
    private val maxRunning: Int,
    /** How long [resolver] has to answer. [RESOLVE_TIMEOUT_MILLIS] in production; shorter only in a test. */
    private val resolveTimeoutMillis: Long,
    private val log: (String) -> Unit,
    private val ownsClient: Boolean,
    /** The size and free space of the volume holding a directory. The real volume in production; fixed in a test. */
    private val volumeSpace: (File) -> VolumeSpace = VolumeSpace::of,
    /**
     * How many may run at once, read at every start: Settings' Downloads at once (`KMP-90`). [maxRunning] when absent.
     * A lower value stops nothing already running; it holds the next start back until enough have finished.
     */
    private val runningLimit: (() -> Int)? = null,
) {
    /**
     * @param downloadRoot where titles' folders go. Defaults to `~/Downloads/CloudStream`.
     * @param stateFile the downloads file. Defaults to `~/.cloudstream4/downloads.json`.
     */
    constructor(
        downloadRoot: File = defaultDownloadRoot(),
        stateFile: File = DesktopDownloadStore.defaultFile(),
        runningLimit: () -> Int = { MAX_RUNNING },
    ) : this(
        downloadRoot = downloadRoot,
        stateFile = stateFile,
        httpClient = defaultHttpClient(),
        resolver = SandboxLinkResolver(),
        options = DownloadOptions(),
        maxRunning = MAX_RUNNING,
        resolveTimeoutMillis = RESOLVE_TIMEOUT_MILLIS,
        log = ::defaultLog,
        ownsClient = true,
        runningLimit = runningLimit,
    )

    private val root: File = downloadRoot.absoluteFile
    private val store = DesktopDownloadStore(stateFile)
    private val storage = FileKitStorage()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val commands = Channel<Command>(Channel.UNLIMITED)

    // ---- owned by the queue coroutine only ----
    private val items = LinkedHashMap<Int, Item>()
    private var nextSeq = 0L
    private var readable = false

    // ---- read from anywhere ----
    private val loaded = CompletableDeferred<Unit>()

    @Volatile
    private var loadFailed = false

    @Volatile
    private var view: List<EpisodeView> = emptyList()

    /** The newest engine snapshot of each running episode. Written by its run, read by [contentsNow]. */
    private val live = ConcurrentHashMap<Int, DownloadSnapshot>()

    /** Moves on every change, and on every engine snapshot. Never moves while nothing changes. */
    private val ticks = MutableStateFlow(0L)

    init {
        scope.launch {
            load()
            for (command in commands) {
                try {
                    handle(command)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log("a downloads command failed: ${e.javaClass.name}")
                    (command as? Command.WithReply)?.fail()
                }
            }
        }
    }

    // ---- public API --------------------------------------------------------------------------

    /** Puts [request] in line. Only [ExtractorLinkType.VIDEO] links; the rest are [EnqueueResult.Refused]. */
    suspend fun enqueue(request: DesktopDownloadRequest): EnqueueResult {
        val reply = CompletableDeferred<EnqueueResult>()
        commands.send(Command.Enqueue(request, reply))
        return reply.await()
    }

    fun pause(id: DownloadEpisodeId) {
        commands.trySend(Command.Pause(id.value))
    }

    /** Continues a paused episode, or retries a failed one, from what is on disk. */
    fun resume(id: DownloadEpisodeId) {
        commands.trySend(Command.Resume(id.value))
    }

    /** The same as [resume]: a failed episode continues from its parts when the source has not changed. */
    fun retry(id: DownloadEpisodeId) = resume(id)

    /** Every paused or failed episode of [id]. */
    fun resume(id: DownloadTitleId) {
        commands.trySend(Command.ResumeTitle(id.value))
    }

    /** Stops an unfinished episode for good and drops it, as [deleteEpisodes] does. A finished one is left alone. */
    fun cancel(id: DownloadEpisodeId) {
        commands.trySend(Command.Cancel(id.value))
    }

    /** Every episode of [ids]: runs stopped, temporary parts and files deleted, empty folders removed, records dropped. */
    suspend fun delete(ids: Set<DownloadTitleId>) {
        val reply = CompletableDeferred<Unit>()
        commands.send(Command.Delete(titleIds = ids.map { it.value }.toSet(), episodeIds = emptySet(), reply = reply))
        reply.await()
    }

    suspend fun deleteEpisodes(ids: Set<DownloadEpisodeId>) {
        val reply = CompletableDeferred<Unit>()
        commands.send(Command.Delete(titleIds = emptySet(), episodeIds = ids.map { it.value }.toSet(), reply = reply))
        reply.await()
    }

    /**
     * A film's file, or a series' folder, once every episode of it is done and on disk. Null before then, and for
     * an id that is not here.
     */
    fun fileOf(id: DownloadTitleId): File? {
        val episodes = view.filter { it.titleId == id.value }
        if (episodes.isEmpty() || episodes.any { it.phase != Phase.Done || !it.file.isFile }) return null
        return when (episodes.first().kind) {
            DownloadTitleKind.Film -> episodes.first().file
            DownloadTitleKind.Series -> episodes.first().folder
        }
    }

    /** The episode's file once it is done and on disk; null before then. */
    fun fileOf(id: DownloadEpisodeId): File? =
        view.firstOrNull { it.episodeId == id.value }?.takeIf { it.phase == Phase.Done }?.file?.takeIf { it.isFile }

    /** Reads the disk again: a finished file deleted outside the app drops its episode, and parts are re-measured. */
    fun refresh() {
        commands.trySend(Command.Refresh)
    }

    /** The running limit changed: starts what a raised limit now allows. A lowered one takes effect as runs finish. */
    fun limitChanged() {
        commands.trySend(Command.LimitChanged)
    }

    fun source(): DesktopDownloadsSource = DesktopDownloadsSource(this)

    fun series(id: DownloadTitleId): DesktopDownloadSeriesSource = DesktopDownloadSeriesSource(this, id)

    /** Stops every run, keeping its parts on disk to resume after the next start, and waits until they have stopped. */
    suspend fun close() {
        commands.close()
        job.cancel()
        job.join()
        if (ownsClient) httpClient.close()
    }

    // ---- what the sources read ----------------------------------------------------------------

    /**
     * [build] now, then again after each change, at most once every [EMIT_INTERVAL_MILLIS] and never while nothing
     * changes: [ticks] is a state flow, so a burst of snapshots during the wait arrives as one.
     */
    internal fun <T> throttled(build: () -> T): Flow<T> = flow {
        loaded.await()
        if (loadFailed) throw IOException("the downloads file could not be read")
        ticks.collect {
            emit(build())
            delay(EMIT_INTERVAL_MILLIS)
        }
    }.distinctUntilChanged()

    internal fun contentsNow(): DownloadsContents {
        val episodes = view
        val facts = facts(episodes)
        val titles = episodes.groupBy { it.titleId }.entries
            .sortedByDescending { (_, group) -> group.maxOf { it.addedSeq } }
            .map { (titleId, group) ->
                DownloadShelf.title(
                    id = DownloadTitleId(titleId),
                    name = group.first().titleName,
                    kind = group.first().kind,
                    episodes = group.map { EpisodeOnDisk(it.episodeId, it.bytesOnDisk, it.bytesTotal ?: 0L) },
                    facts = facts,
                    // The newest episode's: a title downloaded again later carries the page's poster as it is now.
                    posterUrl = group.sortedByDescending { it.addedSeq }.firstNotNullOfOrNull { it.posterUrl },
                )
            }
        return DownloadsContents(
            titles = titles,
            storage = storageNow(episodes),
            queue = DownloadShelf.queue(facts),
            storageFull = shortfall(episodes),
        )
    }

    internal fun seriesNow(titleId: Int, lastName: String): DownloadSeriesContents {
        val episodes = view
        val facts = facts(episodes)
        val mine = episodes.filter { it.titleId == titleId }
        return DownloadSeriesContents(
            name = mine.firstOrNull()?.titleName ?: lastName,
            episodes = SeriesShelf.sorted(
                mine.map {
                    SeriesShelf.episode(
                        id = it.episodeId,
                        season = it.season,
                        episode = it.episode,
                        name = it.episodeName,
                        bytesOnDisk = it.bytesOnDisk,
                        bytesTotal = it.bytesTotal ?: 0L,
                        fact = facts[it.episodeId],
                        watched = null,
                    )
                },
            ),
            freeBytes = freeBytesOf(storageNow(episodes)),
            posterUrl = mine.sortedByDescending { it.addedSeq }.firstNotNullOfOrNull { it.posterUrl },
        )
    }

    /** What is left on the downloads volume: the titles page's figure, so the two pages agree. Null when unread. */
    private fun freeBytesOf(storage: DownloadStorage): Long? =
        if (storage.totalBytes <= 0L) null else (storage.totalBytes - storage.usedBytes).coerceAtLeast(0L)

    /** What each episode is doing, in the shape `DownloadShelf` and `SeriesShelf` read. */
    private fun facts(episodes: List<EpisodeView>): Map<Int, DownloadProgress> {
        // Running first, in the order they started, then waiting in the order they joined: position 1 is running.
        val line = episodes.filter { it.phase == Phase.Running }.sortedBy { it.startedSeq } +
            episodes.filter { it.phase == Phase.Queued }.sortedBy { it.queueSeq }
        val positions = line.withIndex().associate { (index, episode) ->
            episode.episodeId to QueuePosition(index + 1, line.size)
        }
        return episodes.associate { episode ->
            val id = episode.episodeId
            val position = positions[id]
            id to when (episode.phase) {
                Phase.Running -> live[id]?.toDownloadProgress(id)?.copy(state = DownloadState.DOWNLOADING, queue = position)
                    ?: DownloadProgress(id, DownloadState.DOWNLOADING, episode.bytesOnDisk, episode.bytesTotal, queue = position)
                Phase.Queued -> DownloadProgress(id, DownloadState.QUEUED, episode.bytesOnDisk, episode.bytesTotal, queue = position)
                Phase.Paused -> DownloadProgress(id, DownloadState.PAUSED, episode.bytesOnDisk, episode.bytesTotal)
                Phase.Failed -> DownloadProgress(
                    id, DownloadState.FAILED, episode.bytesOnDisk, episode.bytesTotal,
                    bytesWritten = episode.bytesOnDisk, failure = episode.failure ?: DownloadFailure.Unknown,
                )
                Phase.Done -> DownloadProgress(id, DownloadState.COMPLETED, episode.bytesOnDisk, episode.bytesTotal)
            }
        }
    }

    /** The volume holding the download folder, or its nearest existing ancestor; plus the bytes of ours on it. */
    private fun storageNow(episodes: List<EpisodeView>): DownloadStorage {
        var directory: File? = root
        while (directory != null && !directory.exists()) directory = directory.parentFile
        val space = directory?.let(volumeSpace)
        val total = space?.totalBytes ?: 0L
        val usable = space?.usableBytes ?: 0L
        val ours = episodes.sumOf { episode ->
            if (episode.phase == Phase.Running) live[episode.episodeId]?.bytesDone ?: episode.bytesOnDisk else episode.bytesOnDisk
        }
        val used = (total - usable).coerceAtLeast(0L)
        return DownloadStorage(usedBytes = maxOf(used, ours), totalBytes = total, downloadBytes = ours)
    }

    /** From both ways the engine reports a lack of room: before the first byte, and at a write. */
    private fun shortfall(episodes: List<EpisodeView>): StorageShortfall? {
        val short = episodes.filter { it.phase == Phase.Failed }.mapNotNull { episode ->
            when (val failure = episode.failure) {
                is DownloadFailure.StorageFull -> Short(failure.bytesShort)
                is DownloadFailure.InsufficientSpace -> Short((failure.bytesNeeded - failure.bytesAvailable).coerceAtLeast(0L))
                else -> null
            }
        }
        if (short.isEmpty()) return null
        return StorageShortfall(short.mapNotNull { it.bytes }.maxOrNull())
    }

    private class Short(val bytes: Long?)

    // ---- the queue coroutine ------------------------------------------------------------------

    private sealed interface Command {
        sealed interface WithReply : Command {
            fun fail()
        }

        class Enqueue(val request: DesktopDownloadRequest, val reply: CompletableDeferred<EnqueueResult>) : WithReply {
            override fun fail() {
                reply.complete(EnqueueResult.Refused(EnqueueRefusal.StoreUnreadable))
            }
        }

        class Delete(val titleIds: Set<Int>, val episodeIds: Set<Int>, val reply: CompletableDeferred<Unit>) : WithReply {
            override fun fail() {
                reply.complete(Unit)
            }
        }

        class Pause(val id: Int) : Command
        class Resume(val id: Int) : Command
        class ResumeTitle(val titleId: Int) : Command
        class Cancel(val id: Int) : Command
        class Finished(val id: Int, val token: Any) : Command
        data object Refresh : Command
        data object LimitChanged : Command
    }

    /**
     * How a run ended. [snapshot] is its last, or null when it threw or never reached the engine; [failure], when
     * set, is why it never did. [link] is the link the run used, to keep in memory.
     */
    private class Outcome(
        val snapshot: DownloadSnapshot?,
        val failure: DownloadFailure? = null,
        val link: DesktopDownloadLink? = null,
    )

    private class Run(val token: Any = Any()) {
        lateinit var job: Job

        /** Set by the run before it says it has finished, so a pause that lands as it ends still sees how. */
        @Volatile
        var outcome: Outcome? = null

        /** The link this run is using, once known, so a pause keeps a freshly resolved one in memory. */
        @Volatile
        var link: DesktopDownloadLink? = null
    }

    private inner class Item(
        val episodeId: Int,
        val titleId: Int,
        val titleName: String,
        val kind: DownloadTitleKind,
        val season: Int?,
        val episode: Int?,
        val episodeName: String?,
        val folderName: String,
        var fileName: String,
        /** How to ask for links again. Null once [Phase.Done]: a finished file is never resumed, so nothing keeps it. */
        var source: DownloadSourceRef?,
        /** In memory only: never written. Null after a restart and before a retry, until the resolver answers. */
        var link: DesktopDownloadLink?,
        var phase: Phase,
        var bytesOnDisk: Long,
        var bytesTotal: Long?,
        val addedSeq: Long,
        var queueSeq: Long,
        var startedSeq: Long = 0L,
        var failure: DownloadFailure? = null,
        var run: Run? = null,
        val posterUrl: String? = null,
    ) {
        val folder: File get() = File(root, folderName)
        val file: File get() = File(folder, fileName)

        /** The engine's working directory beside the file: `.<name>.csdl`. */
        val tempDir: File get() = File(folder, ".$fileName.csdl")

        fun view() = EpisodeView(
            episodeId, titleId, titleName, kind, season, episode, episodeName, folder, file, phase, failure,
            bytesOnDisk, bytesTotal, addedSeq, queueSeq, startedSeq, posterUrl,
        )

        fun stored() = StoredEpisode(
            episodeId = episodeId,
            titleId = titleId,
            titleName = titleName,
            kind = kind.name,
            season = season,
            episode = episode,
            episodeName = episodeName,
            folder = folderName,
            fileName = fileName,
            state = phase.name,
            bytesDone = bytesOnDisk,
            bytesTotal = bytesTotal,
            failure = failure?.takeIf { phase == Phase.Failed }?.let(StoredFailure::name),
            failureNumbers = failure?.takeIf { phase == Phase.Failed }?.let(StoredFailure::numbers).orEmpty(),
            addedSeq = addedSeq,
            queueSeq = queueSeq,
            provider = source?.takeIf { phase != Phase.Done }?.providerName,
            episodeData = source?.takeIf { phase != Phase.Done }?.episodeData,
            posterUrl = posterUrl,
        )
    }

    private suspend fun handle(command: Command) {
        when (command) {
            is Command.Enqueue -> command.reply.complete(enqueueNow(command.request))
            is Command.Pause -> items[command.id]?.let { pauseNow(it) }
            is Command.Resume -> items[command.id]?.let { resumeNow(it) }
            is Command.ResumeTitle -> items.values.filter { it.titleId == command.titleId }.forEach { resumeNow(it) }
            is Command.Cancel -> items[command.id]?.let { item ->
                // A finished file is deleted, never cancelled: a stale request must not discard it.
                if (item.phase == Phase.Done) log("cancel refused for a finished download") else remove(listOf(item))
            }
            is Command.Delete -> {
                remove(items.values.filter { it.titleId in command.titleIds || it.episodeId in command.episodeIds })
                command.reply.complete(Unit)
            }
            is Command.Finished -> {
                val item = items[command.id] ?: return
                val run = item.run?.takeIf { it.token === command.token } ?: return
                item.run = null
                finish(item, run.outcome ?: Outcome(null))
            }
            Command.Refresh -> refreshNow()
            Command.LimitChanged -> pump()
        }
    }

    private fun load() {
        try {
            val stored = store.read()
            items.clear()
            var changed = false
            var maxSeq = -1L
            stored?.episodes?.forEach { episode ->
                val item = itemOf(episode)
                if (item == null || item.episodeId in items) {
                    changed = true
                    return@forEach
                }
                if (item.phase == Phase.Done && !item.file.isFile) {
                    changed = true
                    return@forEach
                }
                if (item.phase != Phase.Done) {
                    // Nothing starts on its own after a restart. A failure stays one, with its stored reason.
                    if (item.phase != Phase.Failed) {
                        if (item.phase != Phase.Paused) changed = true
                        item.phase = Phase.Paused
                    } else if (item.failure == null) {
                        item.failure = DownloadFailure.Unknown
                    }
                    item.bytesOnDisk = measureParts(item)
                } else {
                    item.bytesOnDisk = item.file.length()
                }
                maxSeq = maxOf(maxSeq, item.addedSeq, item.queueSeq)
                items[item.episodeId] = item
            }
            nextSeq = maxOf(stored?.nextSeq ?: 0L, maxSeq + 1)
            readable = true
            loadFailed = false
            if (changed) persist()
        } catch (e: IOException) {
            readable = false
            loadFailed = true
            log("the downloads file could not be read: ${e.javaClass.name}")
        } finally {
            loaded.complete(Unit)
            publish()
        }
    }

    /** A stored episode, re-checked: it was read from a file anyone with the account could have edited. */
    private fun itemOf(stored: StoredEpisode): Item? {
        if (!DownloadPaths.isSegment(stored.folder) || !DownloadPaths.isSegment(stored.fileName)) return null
        if (!stored.fileName.endsWith(".${DownloadPaths.EXTENSION}")) return null
        val kind = DownloadTitleKind.entries.firstOrNull { it.name == stored.kind } ?: return null
        val phase = Phase.entries.firstOrNull { it.name == stored.state } ?: return null
        // Only a finished episode may have none: anything else could never be resumed.
        val source = if (stored.provider == null && stored.episodeData == null) {
            null
        } else {
            sourceOf(stored.provider.orEmpty(), stored.episodeData.orEmpty()) ?: return null
        }
        if (source == null && phase != Phase.Done) return null
        return Item(
            episodeId = stored.episodeId,
            titleId = stored.titleId,
            titleName = DownloadPaths.titleText(stored.titleName),
            kind = kind,
            season = stored.season,
            episode = stored.episode,
            episodeName = DownloadPaths.displayText(stored.episodeName),
            folderName = stored.folder,
            fileName = stored.fileName,
            source = source,
            link = null,
            phase = phase,
            bytesOnDisk = stored.bytesDone.coerceAtLeast(0L),
            bytesTotal = stored.bytesTotal?.takeIf { it >= 0L },
            addedSeq = stored.addedSeq,
            queueSeq = stored.queueSeq,
            failure = if (phase == Phase.Failed) StoredFailure.decode(stored.failure, stored.failureNumbers) else null,
            posterUrl = posterOf(stored.posterUrl),
        )
    }

    private fun enqueueNow(request: DesktopDownloadRequest): EnqueueResult {
        val given = request.link
        val link = if (given == null) {
            null
        } else {
            if (given.type != ExtractorLinkType.VIDEO) return EnqueueResult.Refused(EnqueueRefusal.UnsupportedLinkType)
            usable(given) ?: return EnqueueResult.Refused(EnqueueRefusal.InvalidUrl)
        }
        val source = sourceOf(request.source.providerName, request.source.episodeData)
            ?: return EnqueueResult.Refused(EnqueueRefusal.NoSource)
        if (!readable) return EnqueueResult.Refused(EnqueueRefusal.StoreUnreadable)

        items[request.episodeId]?.let { existing ->
            if (existing.phase != Phase.Paused && existing.phase != Phase.Failed) return EnqueueResult.AlreadyPresent
            // A paused or failed episode enqueued again goes in line; a new link replaces the one in memory.
            resumeNow(existing)
            if (link != null) existing.link = link
            return EnqueueResult.Queued
        }

        val titleName = DownloadPaths.titleText(request.titleName)
        val folderName = items.values.firstOrNull { it.titleId == request.titleId }?.folderName
            ?: DownloadPaths.segment(titleName)
        val folder = File(root, folderName)
        val fileName = DownloadPaths.fileName(
            titleName = titleName,
            season = request.season,
            episode = request.episode,
            episodeName = DownloadPaths.displayText(request.episodeName),
            isFilm = request.kind == DownloadTitleKind.Film,
        ) { candidate ->
            items.values.any { it.folderName == folderName && it.fileName == candidate } ||
                exists(File(folder, candidate)) || exists(File(folder, ".$candidate.csdl"))
        }
        val seq = nextSeq++
        items[request.episodeId] = Item(
            episodeId = request.episodeId,
            titleId = request.titleId,
            titleName = titleName,
            kind = request.kind,
            season = request.season,
            episode = request.episode,
            episodeName = DownloadPaths.displayText(request.episodeName),
            folderName = folderName,
            fileName = fileName,
            source = source,
            link = link,
            phase = Phase.Queued,
            bytesOnDisk = 0L,
            bytesTotal = null,
            addedSeq = seq,
            queueSeq = seq,
            posterUrl = posterOf(request.posterUrl),
        )
        pump()
        persist()
        publish()
        return EnqueueResult.Queued
    }

    private suspend fun pauseNow(item: Item) {
        when (item.phase) {
            Phase.Running -> {
                val last = live[item.episodeId]
                if (!stopRun(item)) return
                item.bytesTotal = last?.bytesTotal ?: item.bytesTotal
                item.phase = Phase.Paused
                item.bytesOnDisk = measureParts(item)
            }
            Phase.Queued -> item.phase = Phase.Paused
            else -> return
        }
        pump()
        persist()
        publish()
    }

    private fun resumeNow(item: Item) {
        if (item.phase != Phase.Paused && item.phase != Phase.Failed) return
        // A retry asks the extension again: the link in memory may be what failed. A pause keeps it.
        if (item.phase == Phase.Failed) item.link = null
        item.phase = Phase.Queued
        item.failure = null
        item.queueSeq = nextSeq++
        pump()
        persist()
        publish()
    }

    /**
     * Cancels [item]'s run and waits for it to stop. False when it had already ended by itself, in which case it has
     * been finished here with what it reported, and the caller has nothing left to do.
     */
    private suspend fun stopRun(item: Item): Boolean {
        val run = item.run ?: return true
        run.job.cancelAndJoin()
        item.run = null
        live.remove(item.episodeId)
        run.link?.let { item.link = it }
        val outcome = run.outcome ?: return true
        finish(item, outcome)
        return false
    }

    private fun finish(item: Item, outcome: Outcome) {
        live.remove(item.episodeId)
        outcome.link?.let { item.link = it }
        val snapshot = outcome.snapshot
        when (snapshot?.state) {
            DownloadState.COMPLETED -> {
                // The engine names the file as DownloadPaths did, so it is where the item says.
                val length = if (item.file.isFile) item.file.length() else -1L
                if (length >= 0L) {
                    item.phase = Phase.Done
                    item.bytesOnDisk = length
                    item.bytesTotal = length
                    // Nothing resumes a finished file, so the reference to the extension goes, and the link with it.
                    item.source = null
                    item.link = null
                } else {
                    item.phase = Phase.Failed
                    item.failure = DownloadFailure.Unknown
                }
            }
            DownloadState.FAILED -> {
                item.phase = Phase.Failed
                item.failure = snapshot.failure ?: DownloadFailure.Unknown
                item.bytesTotal = snapshot.bytesTotal ?: item.bytesTotal
                item.bytesOnDisk = measureParts(item)
            }
            else -> {
                item.phase = Phase.Failed
                item.failure = outcome.failure ?: DownloadFailure.Unknown
                item.bytesOnDisk = measureParts(item)
            }
        }
        pump()
        persist()
        publish()
    }

    /** Starts the oldest in line while a slot is free. */
    private fun pump() {
        val limit = (runningLimit?.invoke() ?: maxRunning).coerceAtLeast(1)
        while (items.values.count { it.phase == Phase.Running } < limit) {
            val next = items.values.filter { it.phase == Phase.Queued }.minByOrNull { it.queueSeq } ?: return
            start(next)
        }
    }

    private fun start(item: Item) {
        val id = item.episodeId
        val run = Run()
        val known = item.link
        val source = item.source
        val folder = PlatformFile(item.folder)
        val fileName = item.fileName
        run.job = scope.launch(start = CoroutineStart.LAZY) {
            val link = known ?: when (val resolved = source?.let { resolve(it) } ?: Resolved.Failed(DownloadFailure.NoUsableLink)) {
                is Resolved.Link -> resolved.link
                is Resolved.Failed -> {
                    run.outcome = Outcome(null, resolved.failure)
                    commands.trySend(Command.Finished(id, run.token))
                    return@launch
                }
            }
            run.link = link
            val request = DownloadRequest(
                url = link.url,
                folder = folder,
                fileName = fileName,
                headers = link.headers,
                referer = link.referer,
                overwrite = false,
                options = options,
            )
            var last: DownloadSnapshot? = null
            try {
                SegmentedDownloader(httpClient, storage).download(request).collect { snapshot ->
                    last = snapshot
                    if (snapshot.state != DownloadState.COMPLETED && snapshot.state != DownloadState.FAILED) {
                        live[id] = snapshot
                        tick()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log("a download ended by an error: ${e.javaClass.name}")
                last = null
            }
            run.outcome = Outcome(last, link = link)
            commands.trySend(Command.Finished(id, run.token))
        }
        item.run = run
        item.phase = Phase.Running
        item.failure = null
        item.startedSeq = nextSeq++
        // Readers see Running before the run can ask the extension or fail: otherwise a retry shows its old failure
        // until the caller's own publish, and reads as failing at once (BUG-57).
        publish()
        run.job.start()
    }

    private sealed interface Resolved {
        class Link(val link: DesktopDownloadLink) : Resolved
        class Failed(val failure: DownloadFailure) : Resolved
    }

    /**
     * The first usable VIDEO link the extension offers now. None is [DownloadFailure.NoUsableLink]; a throw or no
     * answer within [resolveTimeoutMillis] is [DownloadFailure.SourceFailed]. Cancellation (a pause) passes through.
     */
    private suspend fun resolve(source: DownloadSourceRef): Resolved {
        val links = try {
            withTimeoutOrNull(resolveTimeoutMillis) {
                // On a thread of its own that a timeout or a pause interrupts, so a resolver that blocks rather than
                // suspends is stopped too. One that suspends (the sandbox's `links`) is cancelled inside it as usual.
                runInterruptible(Dispatchers.IO) { runBlocking { resolver.resolve(source) } }
            }
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive) {
                log("asking the extension for links ended by a cancellation")
                return Resolved.Failed(DownloadFailure.SourceFailed)
            }
            throw e
        } catch (e: InterruptedException) {
            log("asking the extension for links was interrupted")
            return Resolved.Failed(DownloadFailure.SourceFailed)
        } catch (e: Throwable) {
            log("asking the extension for links failed: ${e.javaClass.name}")
            return Resolved.Failed(DownloadFailure.SourceFailed)
        } ?: run {
            log("asking the extension for links timed out")
            return Resolved.Failed(DownloadFailure.SourceFailed)
        }
        val link = links.asSequence().filter { it.type == ExtractorLinkType.VIDEO }.mapNotNull(::usable).firstOrNull()
            ?: return Resolved.Failed(DownloadFailure.NoUsableLink)
        return Resolved.Link(link)
    }

    private suspend fun remove(targets: List<Item>) {
        if (targets.isEmpty()) return
        for (item in targets) {
            stopRun(item)
            discard(item)
            if (insideRoot(item.folder)) {
                try {
                    // A link is removed, never followed.
                    Files.deleteIfExists(item.file.toPath())
                } catch (e: IOException) {
                    log("a downloaded file could not be deleted: ${e.javaClass.name}")
                }
            }
            items.remove(item.episodeId)
        }
        targets.map { it.folderName }.distinct()
            .filter { folderName -> items.values.none { it.folderName == folderName } }
            .forEach { folderName ->
                val folder = File(root, folderName)
                // Only an empty folder: File.delete refuses anything else, so nobody's other files go with it.
                if (insideRoot(folder) && folder.list()?.isEmpty() == true) folder.delete()
            }
        pump()
        persist()
        publish()
    }

    private suspend fun discard(item: Item) {
        try {
            SegmentedDownloader(httpClient, storage).discard(PlatformFile(item.folder), item.fileName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("temporary download files could not be removed: ${e.javaClass.name}")
        }
    }

    private fun refreshNow() {
        if (!readable) {
            load()
            return
        }
        var changed = false
        items.values.toList().forEach { item ->
            when (item.phase) {
                Phase.Done -> if (item.file.isFile) {
                    item.bytesOnDisk = item.file.length()
                } else {
                    items.remove(item.episodeId)
                    changed = true
                }
                Phase.Paused, Phase.Failed, Phase.Queued -> item.bytesOnDisk = measureParts(item)
                Phase.Running -> Unit
            }
        }
        if (changed) persist()
        publish()
    }

    /** What the engine's parts hold on disk, which is what it will resume from. */
    private fun measureParts(item: Item): Long {
        val held = item.tempDir.listFiles()
            ?.filter { it.isFile && (it.name.startsWith("part-") || it.name == "joined") }
            ?.sumOf { it.length() } ?: 0L
        return item.bytesTotal?.let { held.coerceAtMost(it) } ?: held
    }

    /** Whether [folder] really is a child of the download root, and not a link to somewhere else. */
    private fun insideRoot(folder: File): Boolean = try {
        if (!folder.exists()) {
            true
        } else {
            folder.toPath().toRealPath().parent == root.toPath().toRealPath()
        }
    } catch (e: IOException) {
        log("a download folder could not be checked: ${e.javaClass.name}")
        false
    }

    private fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun persist() {
        if (!readable) return
        try {
            store.write(StoredDownloads(nextSeq = nextSeq, episodes = items.values.map { it.stored() }))
        } catch (e: Exception) {
            log("the downloads file could not be saved: ${e.javaClass.name}")
        }
    }

    private fun publish() {
        view = items.values.map { it.view() }
        tick()
    }

    private fun tick() = ticks.update { it + 1 }

    companion object {
        /** Android's default, `download_parallel_key`. */
        const val MAX_RUNNING = 3

        /** How long the extension has to answer for links before the episode fails with SourceFailed. */
        internal const val RESOLVE_TIMEOUT_MILLIS = 60_000L

        /** [link] with its URL cleaned by [cleanUrl], or null when that refuses it. */
        internal fun usable(link: DesktopDownloadLink): DesktopDownloadLink? {
            val url = cleanUrl(link.url) ?: return null
            return if (url == link.url) link else DesktopDownloadLink(url, link.headers, link.referer, link.type)
        }

        /** A source that can be asked again: a provider name and episode data, neither blank nor absurdly long. */
        internal fun sourceOf(provider: String, episodeData: String): DownloadSourceRef? {
            if (provider.isBlank() || episodeData.isBlank()) return null
            if (provider.length > MAX_PROVIDER_CHARS || episodeData.length > MAX_EPISODE_DATA_CHARS) return null
            return DownloadSourceRef(provider, episodeData)
        }

        private const val MAX_PROVIDER_CHARS = 200
        private const val MAX_EPISODE_DATA_CHARS = 64 * 1024

        /**
         * A poster address worth keeping (`KMP-99`): plain `http` or `https`, with no space or control character, no
         * userinfo (`BUG-78`), and not absurdly long. The library's rule; `PosterImage` checks the host again when it
         * loads.
         */
        internal fun posterOf(url: String?): String? = url?.takeIf { candidate ->
            candidate.length <= MAX_POSTER_CHARS &&
                POSTER_SCHEMES.any { candidate.startsWith(it, ignoreCase = true) } &&
                candidate.none { it.isWhitespace() || it.isISOControl() } &&
                !hasPosterUserInfo(candidate)
        }

        private const val MAX_POSTER_CHARS = 2048
        private val POSTER_SCHEMES = listOf("http://", "https://")

        /** At most four emissions a second while anything runs. */
        internal const val EMIT_INTERVAL_MILLIS = 250L

        fun defaultDownloadRoot(): File = File(File(System.getProperty("user.home"), "Downloads"), "CloudStream")

        /**
         * One Ktor client for every run, over the client posters and subtitles use: its DNS filter and network
         * interceptor refuse a private address at the connection, which also closes the engine's DNS-rebinding
         * residue on the Mac. TLS is OkHttp's default, verified.
         */
        private fun defaultHttpClient(): HttpClient = HttpClientFactory(posterHttpClient()).create()

        private fun defaultLog(text: String) = System.err.println("DesktopDownloads: $text")

        /**
         * [url] if it is an absolute `http` or `https` URL with a host and no user information, with spaces encoded
         * as Android's downloader encodes them; null otherwise. The engine checks addresses again, at every hop.
         */
        internal fun cleanUrl(url: String): String? {
            val encoded = url.trim().replace(" ", "%20")
            val uri = try {
                URI(encoded)
            } catch (_: Exception) {
                return null
            }
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            if (uri.host.isNullOrEmpty() || uri.rawUserInfo != null) return null
            return encoded
        }
    }
}

/** A volume's size and the bytes still usable on it, read once. */
internal class VolumeSpace(val totalBytes: Long, val usableBytes: Long) {
    companion object {
        fun of(directory: File): VolumeSpace = VolumeSpace(directory.totalSpace, directory.usableSpace)
    }
}

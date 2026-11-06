package com.lagradost.cloudstream4.media.download

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.div
import io.github.vinceglb.filekit.path
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Downloads one file over HTTP in parallel byte ranges, and is built so that a file it reports as
 * complete is the file the server has.
 *
 * Nothing is logged, and no URL, header or referer appears in any exception message, `toString`,
 * state file or snapshot: a signed URL is a credential.
 *
 * ## Use
 *
 * [download] is a cold flow; cancelling its collector pauses the download and keeps the temporary
 * files. [start] wraps the same engine in a [DownloadSession] with pause, resume and cancel. Either way
 * a second download of the same folder and file name while one runs throws [IllegalStateException].
 *
 * ## Security
 *
 * Every address, including every redirect hop, goes through `UrlGuard` first. Known residue: DNS
 * rebinding. A name that resolved to a public address at the check may resolve to a private one when
 * the connection is made; the real fix is a `Dns` in the OkHttp transport, which sees the address it
 * connects to.
 *
 * @param client Any Ktor client; the engine derives its own copy with redirects off and status checks off.
 */
class SegmentedDownloader(
    private val client: HttpClient,
    private val storage: DownloadStorage = FileKitStorage(),
    private val resolver: HostResolver = SystemHostResolver,
) {
    /**
     * Downloads [request]. The last emission is COMPLETED or FAILED. Snapshots are conflated: a slow
     * collector sees the newest, never a backlog, and always sees the last.
     *
     * Throws [IllegalStateException] on collection if the same folder and file name is already downloading.
     */
    fun download(request: DownloadRequest): Flow<DownloadSnapshot> = channelFlow {
        val key = keyOf(request)
        check(ActiveDownloads.tryAcquire(key)) { "a download of this file is already running" }
        try {
            runDownload(request) { trySend(it) }
        } finally {
            ActiveDownloads.release(key)
        }
    }.buffer(Channel.CONFLATED)

    /**
     * Cancels for good: removes the temporary directory for [folder] and [fileName], never the finished
     * file. Throws [IllegalStateException] if that file is being downloaded right now; cancel that
     * download's session instead.
     */
    suspend fun discard(folder: PlatformFile, fileName: String) {
        check(!ActiveDownloads.isActive(keyOf(folder, fileName))) { "a download of this file is running" }
        discardInternal(folder, fileName)
    }

    /**
     * Starts [request] on [scope] and returns at once. Throws [IllegalStateException] before any byte if
     * the same folder and file name is already downloading.
     */
    fun start(scope: CoroutineScope, request: DownloadRequest): DownloadSession {
        val key = keyOf(request)
        check(ActiveDownloads.tryAcquire(key)) { "a download of this file is already running" }
        return DownloadSession(scope, this, request, key)
    }

    internal fun tryAcquire(key: String): Boolean = ActiveDownloads.tryAcquire(key)

    internal fun release(key: String) = ActiveDownloads.release(key)

    /** Runs with the key already held; the caller releases it. */
    internal suspend fun runAcquired(request: DownloadRequest, publish: (DownloadSnapshot) -> Unit) =
        runDownload(request, publish)

    internal suspend fun discardInternal(folder: PlatformFile, fileName: String) {
        storageCall { storage.delete(folder / tempDirectoryName(sanitizeFileName(fileName))) }
    }

    private suspend fun runDownload(request: DownloadRequest, publish: (DownloadSnapshot) -> Unit) {
        DownloadRun(client, storage, resolver, request, publish).execute()
    }

    private fun keyOf(request: DownloadRequest) = keyOf(request.folder, request.fileName)

    private fun keyOf(folder: PlatformFile, fileName: String) =
        folder.path.trimEnd('/', '\\') + "\u0000" + sanitizeFileName(fileName)
}

/** The files being downloaded in this process, so two runs cannot interleave in one temporary directory. */
@OptIn(ExperimentalAtomicApi::class)
internal object ActiveDownloads {
    private val keys = AtomicReference<Set<String>>(emptySet())

    fun tryAcquire(key: String): Boolean {
        while (true) {
            val current = keys.load()
            if (key in current) return false
            if (keys.compareAndSet(current, current + key)) return true
        }
    }

    fun release(key: String) {
        while (true) {
            val current = keys.load()
            if (keys.compareAndSet(current, current - key)) return
        }
    }

    fun isActive(key: String): Boolean = key in keys.load()
}

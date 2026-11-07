package com.lagradost.cloudstream3.utils.downloader

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.MainActivity.Companion.lastError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.services.DownloadQueueService
import com.lagradost.cloudstream3.services.DownloadQueueService.Companion.downloadInstances
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DataStore.containsKey
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadQueueWrapper
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.KEY_RESUME_IN_QUEUE
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.KEY_RESUME_PACKAGES
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.downloadStatus
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.downloadStatusEvent
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.getDownloadFileInfo
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.getDownloadQueuePackage
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.getDownloadResumePackage
import com.lagradost.cloudstream4.download.EngineVideoDownload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

// 1. Put a download on the queue
// 2. The queue manager starts a foreground service to handle the queue
// 3. The service starts work manager jobs to handle the downloads?
object DownloadQueueManager {
    private const val TAG = "DownloadQueueManager"
    const val QUEUE_KEY = "download_queue_key"

    /** KMP-62: `VideoDownloadManager.KEY_RESUME_SOURCE` and its parts, restated: a shared constant would be classes.jar API. */
    private const val KEY_RESUME_SOURCE = "download_resume_source"
    private const val ITEM = "item"
    private const val MIRROR = "mirror"
    private const val ATTEMPT = "attempt"
    private const val HEX = "0123456789abcdef"

    /**
     * KMP-62: the links each queued item was handed, for this process only. The queue on disk leaves them out,
     * and after a restart the download asks the provider again.
     */
    private val liveItems = ConcurrentHashMap<Int, DownloadQueueWrapper>()

    /** Flow of all active queued download, no active downloads.
     * This flow may see many changes, do not place expensive observers.
     * downloadInstances is the flow keeping track of active downloads.
     * @see com.lagradost.cloudstream3.services.DownloadQueueService.Companion.downloadInstances
     */
    private val _queue: MutableStateFlow<Array<DownloadQueueWrapper>> by lazy {
        /** Persistent queue */
        val currentValue = getKey<Array<DownloadQueueWrapper>>(QUEUE_KEY) ?: emptyArray()
        MutableStateFlow(currentValue)
    }

    val queue: StateFlow<Array<DownloadQueueWrapper>> by lazy { _queue }

    /** Start the queue, marks all queue objects as in progress.
     * Note that this may run twice without the service restarting
     * because MainActivity may be recreated. */
    fun init(context: Context) {
        // KMP-62: before the stored queue is written back or started
        safe { dropStoredLinks(context) }

        ioSafe {
            _queue.collect { queue ->
                setKey(QUEUE_KEY, queue)
            }
        }

        ioSafe startQueue@{
            // Do not automatically start the queue if safe mode is activated.
            if (PluginManager.isSafeMode()) {
                // Prevent misleading UI
                VideoDownloadManager.cancelAllDownloadNotifications(context)
                return@startQueue
            }

            val resumeQueue =
                getPreResumeIds().filterNot {
                    VideoDownloadManager.currentDownloads.value.contains(it)
                }
                    .mapNotNull { id ->
                        getDownloadResumePackage(context, id)?.toWrapper()
                            ?: getDownloadQueuePackage(context, id)
                    }

            val newQueue = _queue.updateAndGet { localQueue ->
                // Add resume packages to the first part of the queue, since they may have been removed from the queue when they started
                (resumeQueue + localQueue).distinctBy { it.id }.toTypedArray()
            }

            // Once added to the queue they can be safely removed
            removeKeys(KEY_RESUME_IN_QUEUE)

            EngineVideoDownload.sweepOrphanedStaging(context, newQueue.map { it.id })

            // Make sure the download buttons display a pending status
            newQueue.forEach { obj ->
                setQueueStatus(obj.id, VideoDownloadManager.DownloadType.IsPending)
            }

            if (newQueue.any()) {
                startQueueService(context)
            }
        }
    }

    /** Downloads not yet started or in progress. */
    private fun getPreResumeIds(): Set<Int> {
        return getKeys(KEY_RESUME_IN_QUEUE)?.mapNotNull {
            it.substringAfter("$KEY_RESUME_IN_QUEUE/").toIntOrNull()
        }?.toSet()
            ?: emptySet()
    }

    /** Adds an object to the internal persistent queue. It does not re-add an existing item. @return true if successfully added  */
    private fun add(downloadQueueWrapper: DownloadQueueWrapper): Boolean {
        // KMP-62: the queue holds, and logs, the item without its links; this process keeps them in liveItems
        val stored = withoutLinks(downloadQueueWrapper)
        Log.d(TAG, "Download added to queue: ${stored.id}")
        val newQueue = _queue.updateAndGet { localQueue ->
            // Do not add the same episode twice
            if (downloadQueueWrapper.isCurrentlyDownloading() || localQueue.any { it.id == downloadQueueWrapper.id }) {
                return@updateAndGet localQueue
            }
            localQueue + stored
        }
        if (newQueue.any { it === stored }) {
            rememberMirror(downloadQueueWrapper)
            if (stored != downloadQueueWrapper) liveItems[stored.id] = downloadQueueWrapper
        }
        return newQueue.any { it.id == downloadQueueWrapper.id }
    }

    /** Removes all objects with the same id from the internal persistent queue */
    private fun remove(id: Int) {
        Log.d(TAG, "Download removed from the queue: $id")
        liveItems.remove(id)
        _queue.update { localQueue ->
            // The check is to prevent unnecessary updates
            if (!localQueue.any { it.id == id }) {
                return@update localQueue
            }

            localQueue.filter { it.id != id }.toTypedArray()
        }
    }

    /** Removes all items and returns the previous queue */
    private fun removeAll(): Array<DownloadQueueWrapper> {
        Log.d(TAG, "Removed everything from queue")
        liveItems.clear()
        return _queue.getAndUpdate {
            emptyArray()
        }
    }

    private fun reorder(downloadQueueWrapper: DownloadQueueWrapper, newPosition: Int) {
        _queue.update { localQueue ->
            val newIndex = newPosition.coerceIn(0, localQueue.size)
            val id = downloadQueueWrapper.id

            val newQueue = localQueue.filter { it.id != id }.toMutableList().apply {
                this.add(newIndex, withoutLinks(downloadQueueWrapper))
            }.toTypedArray()

            newQueue
        }
    }

    /** Start a real download from the first item in the queue */
    fun popQueue(context: Context): VideoDownloadManager.EpisodeDownloadInstance? {
        val first = queue.value.firstOrNull() ?: return null
        // KMP-62: the links this process was handed, which the stored queue leaves out
        val live = liveItems[first.id] ?: first

        remove(first.id)

        val downloadInstance = VideoDownloadManager.EpisodeDownloadInstance(context, live)

        return downloadInstance
    }

    /** Marks the item as in queue for the download button */
    private fun setQueueStatus(id: Int, status: VideoDownloadManager.DownloadType) {
        downloadStatusEvent.invoke(
            Pair(
                id,
                status
            )
        )
        downloadStatus[id] = status
    }

    private fun startQueueService(context: Context?) {
        if (context == null) {
            Log.d(TAG, "Cannot start download queue service, null context.")
            return
        }
        // Do not restart the download queue service
        if (DownloadQueueService.isRunning) {
            return
        }
        ioSafe {
            val intent = DownloadQueueService.getIntent(context)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    /** Cancels an active download or removes it from queue depending on where it is. */
    fun cancelDownload(id: Int) {
        Log.d(TAG, "Cancelling download: $id")

        val currentInstance = downloadInstances.value.find { it.downloadQueueWrapper.id == id }

        if (currentInstance != null) {
            currentInstance.cancelDownload()
        } else {
            removeFromQueue(id)
        }
    }

    /** Removes all queued items */
    fun removeAllFromQueue() {
        removeAll().forEach { wrapper ->
            setQueueStatus(wrapper.id, VideoDownloadManager.DownloadType.IsStopped)
        }
    }

    /** Removes all objects with the same id from the internal persistent queue  */
    fun removeFromQueue(id: Int) {
        ioSafe {
            remove(id)
            setQueueStatus(id, VideoDownloadManager.DownloadType.IsStopped)
        }
    }

    /** Will move the download queue wrapper to a new position in the queue.
     * If the item does not exist it will also insert it. */
    fun reorderItem(downloadQueueWrapper: DownloadQueueWrapper, newPosition: Int) {
        ioSafe {
            reorder(downloadQueueWrapper, newPosition)
        }
    }

    /** Add a new object to the queue. Will not queue completed downloads or current downloads. */
    fun addToQueue(downloadQueueWrapper: DownloadQueueWrapper) = safe {
        val context = CloudStreamApp.context ?: return@safe
        val fileInfo = getDownloadFileInfo(context, downloadQueueWrapper.id)
        val isComplete = fileInfo != null &&
                // Assure no division by 0
                fileInfo.totalBytes > 0 &&
                // If more than 98% downloaded then do not add to queue
                ((VideoDownloadManager.fileLengthOnDisk(context, fileInfo.path) ?: fileInfo.fileLength).toFloat() / fileInfo.totalBytes.toFloat()) > 0.98f
        // Do not queue completed files!
        if (isComplete) return@safe

        if (add(downloadQueueWrapper)) {
            setQueueStatus(downloadQueueWrapper.id, VideoDownloadManager.DownloadType.IsPending)
            startQueueService(context)
        }
    }


    private fun resumeSourceKey(id: Int, part: String) = "$KEY_RESUME_SOURCE/$id/$part"

    private fun hasResumeSource(id: Int) = CloudStreamApp.context?.containsKey(resumeSourceKey(id, ITEM)) == true

    /** Restates `EpisodeDownloadInstance.linkLabel`: the SHA-256 of a link's source and name, never the text. */
    private fun linkLabel(link: ExtractorLink): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("${link.source}\u001F${link.name}".toByteArray())
        val hex = StringBuilder(digest.size * 2)
        for (byte in digest) hex.append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF])
        return hex.toString()
    }

    /** Restates `EpisodeDownloadInstance.withoutLinks`: what may be stored of a queued item. */
    private fun withoutLinks(wrapper: DownloadQueueWrapper) = DownloadQueueWrapper(
        wrapper.resumePackage?.let { pkg ->
            if (hasResumeSource(wrapper.id)) pkg.copy(item = pkg.item.copy(links = emptyList())) else pkg
        },
        wrapper.downloadItem?.copy(links = null, subs = null),
    )

    /** A new item names the mirror the user picked, if any: only the picked-mirror action queues an item with links. */
    private fun rememberMirror(wrapper: DownloadQueueWrapper) {
        val item = wrapper.downloadItem ?: return
        val picked = item.links?.firstOrNull()
        val key = resumeSourceKey(item.episode.id, MIRROR)
        if (picked != null) setKey(key, linkLabel(picked)) else removeKey(key)
    }

    /**
     * KMP-62, on the first start after the update: what older versions stored with links is rewritten without
     * them. A resume package can only lose its links when a queued item for the same episode still says what to
     * ask the provider; otherwise it keeps them until it finishes or is removed. Each step only removes links,
     * and a side record is written before the package that needs it, so running it again or stopping halfway is safe.
     */
    private fun dropStoredLinks(context: Context) {
        val queued = _queue.value
        val ids = (getKeys(KEY_RESUME_PACKAGES).orEmpty() + getKeys(KEY_RESUME_IN_QUEUE).orEmpty())
            .mapNotNull { it.substringAfterLast('/').toIntOrNull() }
            .toSet() + queued.map { it.id }

        for (id in ids) {
            val inQueue = getDownloadQueuePackage(context, id)
            val item = inQueue?.downloadItem ?: queued.firstOrNull { it.id == id && it.downloadItem != null }?.downloadItem
            val picked = item?.links?.firstOrNull()
            if (picked != null && !context.containsKey(resumeSourceKey(id, MIRROR))) {
                setKey(resumeSourceKey(id, MIRROR), linkLabel(picked))
            }

            val pkg = getDownloadResumePackage(context, id)
            if (pkg != null && pkg.item.links.isNotEmpty()) {
                if (item != null && !hasResumeSource(id)) {
                    setKey(resumeSourceKey(id, ITEM), item.copy(links = null, subs = null))
                }
                if (hasResumeSource(id)) {
                    val attempt = pkg.item.links.getOrNull(pkg.linkIndex ?: 0)
                    if (attempt != null && !context.containsKey(resumeSourceKey(id, ATTEMPT))) {
                        setKey(resumeSourceKey(id, ATTEMPT), linkLabel(attempt))
                    }
                    setKey(KEY_RESUME_PACKAGES, id.toString(), pkg.copy(item = pkg.item.copy(links = emptyList())))
                }
            }

            if (inQueue != null) {
                val stored = withoutLinks(inQueue)
                if (stored != inQueue) setKey(KEY_RESUME_IN_QUEUE, id.toString(), stored)
            }
        }

        val stripped = _queue.updateAndGet { localQueue ->
            localQueue.map { withoutLinks(it) }.toTypedArray()
        }
        // The stored links serve this run, as the links of an item queued in it do
        queued.forEach { wrapper -> if (!stripped.contains(wrapper)) liveItems.putIfAbsent(wrapper.id, wrapper) }
        setKey(QUEUE_KEY, stripped)
    }

    /** Refreshes the queue flow with the same value, but copied.
     * Good to run if the downloads are affected by some outside value change. */
    fun forceRefreshQueue() {
        _queue.update { localQueue ->
            localQueue.copyOf()
        }
    }
}
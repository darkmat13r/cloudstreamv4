package com.lagradost.cloudstream4.download

import android.content.Context
import com.lagradost.cloudstream3.ui.download.DOWNLOAD_ACTION_PAUSE_DOWNLOAD
import com.lagradost.cloudstream3.ui.download.DOWNLOAD_ACTION_RESUME_DOWNLOAD
import com.lagradost.cloudstream3.ui.download.DownloadButtonSetup.handleDownloadClick
import com.lagradost.cloudstream3.ui.download.DownloadClickEvent
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.getKeys
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.getDownloadFileInfo
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisodeId
import com.lagradost.cloudstream4.feature.downloads.series.DownloadSeriesContents
import com.lagradost.cloudstream4.feature.downloads.series.DownloadSeriesSource
import com.lagradost.cloudstream4.feature.downloads.series.SeriesShelf
import com.lagradost.cloudstream4.media.model.DownloadProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

/**
 * One series' episodes, from the same keys the old screen reads (`DownloadViewModel.updateChildList`), with the downloader's
 * live facts over them.
 *
 * The disk is read when asked, and again when an episode's state changes. A running transfer's bytes come from its facts
 * between reads, so a row moves without the disk being read many times a second.
 *
 * The old screen listed only episodes with a file. This also lists one the downloader knows about with no file yet, a
 * queued one, because 6d draws a queued row with its own verb.
 *
 * @param folder the old screen's `folder` argument: `DOWNLOAD_EPISODE_CACHE` and the series' id.
 */
internal class AndroidDownloadSeriesSource(
    context: Context,
    private val name: String,
    private val folder: String,
    private val facts: Flow<Map<Int, DownloadProgress>> = DownloadFacts.progress,
) : DownloadSeriesSource {

    private val appContext = context.applicationContext
    private val reads = MutableStateFlow(0)

    @Volatile
    private var cached: Map<Int, DownloadObjects.DownloadEpisodeCached> = emptyMap()

    private class OnDisk(val cached: DownloadObjects.DownloadEpisodeCached, val bytes: Long, val total: Long, val watched: Float?)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val disk: Flow<List<OnDisk>> = reads.mapLatest { read() }

    override val contents: Flow<DownloadSeriesContents> = combine(disk, factsThatRefresh()) { episodes, known ->
        val ids = episodes.map { it.cached.id }.toSet()
        // A download the downloader knows about that has no file yet: queued, or loading its links.
        val waiting = cached.values.filter { it.id !in ids && known[it.id] != null }
        DownloadSeriesContents(
            name = name,
            episodes = episodes.map { episode ->
                SeriesShelf.episode(
                    id = episode.cached.id,
                    season = episode.cached.season,
                    episode = episode.cached.episode,
                    name = episode.cached.name,
                    bytesOnDisk = episode.bytes,
                    bytesTotal = episode.total,
                    fact = known[episode.cached.id],
                    watched = episode.watched,
                )
            } + waiting.map { episode ->
                SeriesShelf.episode(
                    id = episode.id,
                    season = episode.season,
                    episode = episode.episode,
                    name = episode.name,
                    bytesOnDisk = 0L,
                    bytesTotal = 0L,
                    fact = known[episode.id],
                    watched = null,
                )
            },
        )
    }

    override fun refresh() {
        reads.value += 1
    }

    /** The old popup's Pause. */
    override fun pause(id: DownloadEpisodeId) {
        cached[id.value]?.let { handleDownloadClick(DownloadClickEvent(DOWNLOAD_ACTION_PAUSE_DOWNLOAD, it)) }
    }

    /** The old popup's Resume: wakes a paused download, and puts a dead one back in the queue. */
    override fun resume(id: DownloadEpisodeId) {
        cached[id.value]?.let { handleDownloadClick(DownloadClickEvent(DOWNLOAD_ACTION_RESUME_DOWNLOAD, it)) }
    }

    /** The old popup's cancel, for one that is waiting. */
    override fun cancel(id: DownloadEpisodeId) {
        DownloadQueueManager.cancelDownload(id.value)
    }

    /** `updateChildList`'s read: every episode under the folder, those with a file listed, with how far each was watched. */
    private suspend fun read(): List<OnDisk> = withContext(Dispatchers.IO) {
        val episodes = appContext.getKeys(folder)
            .mapNotNull { appContext.getKey<DownloadObjects.DownloadEpisodeCached>(it) }
            .distinctBy { it.id }
        cached = episodes.associateBy { it.id }
        episodes.mapNotNull { episode ->
            val file = getDownloadFileInfo(appContext, episode.id) ?: return@mapNotNull null
            val position = getViewPos(episode.id)
            val watched = position?.takeIf { it.duration > 0L }?.let { it.position.toFloat() / it.duration.toFloat() }
            OnDisk(episode, file.fileLength, file.totalBytes, watched)
        }
    }

    /** Facts, reading the disk again whenever an episode's state changes, as `AndroidDownloadsSource` does. */
    private fun factsThatRefresh(): Flow<Map<Int, DownloadProgress>> {
        var lastStates: Map<Int, Any>? = null
        return facts.onStart { emit(emptyMap()) }.onEach { current ->
            val states = current.mapValues { it.value.state }
            if (lastStates != null && states != lastStates) refresh()
            lastStates = states
        }
    }
}

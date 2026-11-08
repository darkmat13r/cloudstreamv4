package com.lagradost.cloudstream4.download

import android.content.Context
import android.net.Uri
import androidx.lifecycle.asFlow
import com.lagradost.cloudstream3.isEpisodeBased
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.download.DOWNLOAD_ACTION_RESUME_DOWNLOAD
import com.lagradost.cloudstream3.ui.download.DownloadButtonSetup.handleDownloadClick
import com.lagradost.cloudstream3.ui.download.DownloadClickEvent
import com.lagradost.cloudstream3.ui.download.DownloadViewModel
import com.lagradost.cloudstream3.ui.download.VisualDownloadCached
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.getKeys
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.redactedReason
import com.lagradost.cloudstream4.download.EngineVideoDownload.Companion.getDownloadListInfo
import com.lagradost.cloudstream4.feature.downloads.DownloadShelf
import com.lagradost.cloudstream4.feature.downloads.DownloadStorage
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleId
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleKind
import com.lagradost.cloudstream4.feature.downloads.DownloadsContents
import com.lagradost.cloudstream4.feature.downloads.DownloadsSource
import com.lagradost.cloudstream4.feature.downloads.EpisodeOnDisk
import com.lagradost.cloudstream4.media.model.DownloadProgress
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

/**
 * The downloads screen's view of `:app`: the same [DownloadViewModel] the old screen reads, and [DownloadFacts] for what
 * the old screen could not show.
 *
 * [DownloadViewModel]'s list is per title, and a row's figures need each episode, so this reads them beside it with the
 * same two calls it uses. It re-reads when a download changes state, not on every report: a report only moves bytes.
 */
internal class AndroidDownloadsSource(
    context: Context,
    private val downloads: DownloadViewModel,
    private val facts: Flow<Map<Int, DownloadProgress>> = DownloadFacts.progress,
) : DownloadsSource {

    private val appContext = context.applicationContext

    /** How long the page's failure line may be: search's and detail's limit. A field, so no file class is published. */
    private val maxReasonChars = 60

    /** Episodes per title id, as last read. Written by [contents]; read by [resume], on a key press. */
    @Volatile
    private var episodes: Map<Int, List<Episode>> = emptyMap()

    private class Episode(val cached: DownloadObjects.DownloadEpisodeCached, val onDisk: EpisodeOnDisk)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val listed: Flow<Pair<List<VisualDownloadCached.Header>, Map<Int, List<Episode>>>> =
        downloads.headerCards.asFlow()
            .mapLatest { cards ->
                when (cards) {
                    is Resource.Success -> cards.value to readEpisodes().also { episodes = it }
                    is Resource.Loading -> null
                    // Shown as the page's failure line: one line, redacted, as search and detail do (`BUG-63`).
                    is Resource.Failure -> throw IOException(
                        redactedReason(cards.errorString.trim().lineSequence().first(), maxReasonChars),
                    )
                }
            }
            .filterNotNull()

    /** The disk's three figures. `updateStorageStats` posts them one at a time, and may post only the last. */
    private val storage: Flow<DownloadStorage?> = combine(
        downloads.usedBytes.asFlow().nullableUntilFirst(),
        downloads.availableBytes.asFlow().nullableUntilFirst(),
        downloads.downloadBytes.asFlow().nullableUntilFirst(),
    ) { used, available, downloaded ->
        if (used == null || available == null || downloaded == null) {
            null
        } else {
            DownloadStorage(usedBytes = used, totalBytes = used + available, downloadBytes = downloaded)
        }
    }

    override val contents: Flow<DownloadsContents> = combine(listed, storage, factsThatRefresh()) { list, disk, known ->
        val (headers, byTitle) = list
        DownloadsContents(
            titles = headers.map { header ->
                DownloadShelf.title(
                    id = DownloadTitleId(header.data.id),
                    name = header.data.name,
                    kind = if (header.data.type.isEpisodeBased()) DownloadTitleKind.Series else DownloadTitleKind.Film,
                    episodes = byTitle[header.data.id].orEmpty().map { it.onDisk },
                    facts = known,
                )
            },
            storage = disk,
            queue = DownloadShelf.queue(known),
            storageFull = DownloadShelf.storageFull(known),
        )
    }

    override fun refresh() {
        downloads.updateHeaderList(appContext)
    }

    /** `DownloadFragment`'s own path to a resume: a paused one wakes, and a dead one goes back in the queue. */
    override fun resume(id: DownloadTitleId) {
        episodes[id.value].orEmpty().forEach { episode ->
            if (DownloadShelf.isResumable(episode.onDisk, DownloadFacts.facts.value[episode.onDisk.id])) {
                handleDownloadClick(DownloadClickEvent(DOWNLOAD_ACTION_RESUME_DOWNLOAD, episode.cached))
            }
        }
    }

    /** Only what has a file counts, the rule `calculateDownloadStats` applies to the title's own totals. */
    private suspend fun readEpisodes(): Map<Int, List<Episode>> = withContext(Dispatchers.IO) {
        appContext.getKeys(DOWNLOAD_EPISODE_CACHE)
            .mapNotNull { appContext.getKey<DownloadObjects.DownloadEpisodeCached>(it) }
            .distinctBy { it.id }
            .mapNotNull { cached ->
                val file = getDownloadListInfo(appContext, cached.id) ?: return@mapNotNull null
                // An engine download that has only just started has an empty path and may hold no byte yet (KMP-60).
                if (file.fileLength <= 1 && file.path != Uri.EMPTY) return@mapNotNull null
                Episode(cached, EpisodeOnDisk(cached.id, file.fileLength, file.totalBytes))
            }
            .groupBy { it.cached.parentId }
    }

    private fun factsThatRefresh(): Flow<Map<Int, DownloadProgress>> {
        var lastStates: Map<Int, Any>? = null
        return facts.onStart { emit(emptyMap()) }.onEach { current ->
            val states = current.mapValues { it.value.state }
            if (lastStates != null && states != lastStates) refresh()
            lastStates = states
        }
    }

    private fun <T : Any> Flow<T>.nullableUntilFirst(): Flow<T?> = map<T, T?> { it }.onStart { emit(null) }
}

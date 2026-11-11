package com.lagradost.cloudstream4.downloads

import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleId
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisode
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisodeId
import com.lagradost.cloudstream4.feature.downloads.series.DownloadSeriesContents
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** One episode row's download on the title page: the download, or none, and whether this page's enqueue was refused. */
internal data class EpisodeDownloadRow(val download: DownloadEpisode?, val unavailable: Boolean)

/**
 * `KMP-109` part 2b: the title page's episode downloads, for one title, between the page's cells and [DesktopDownloads].
 *
 * **Starting one.** [download] builds the request with [episodeDownloadRequest], which carries no link, and enqueues
 * it. The service asks the extension for a link through its resolver, from the episode's source alone, and checks
 * that link as it checks every other. This adds no fetch path.
 *
 * **What a row shows** comes from the service's own record of this title ([DesktopDownloads.series]), by the
 * episode's id. Two guards keep a row to its own episode:
 * - the record must carry this page's title name. The title id is 32 bits of a hash, so another title can hold it, by
 *   chance or because a plugin chose its strings to. A row never shows another title's download.
 * - an enqueue the service refuses is kept for this page, and its row reads unavailable, with no verb. `IdTaken` means
 *   the id is another's, so the row does not claim a download that is not its own. `AlreadyPresent` changes nothing
 *   here: the record already holds the episode, and the row shows it as it is.
 *
 * A downloads file that cannot be read gives rows with no download. The Downloads page reports that failure.
 *
 * @param titleKey the page's title id (`DesktopSearchSource.encodeId`).
 * @param titleIdOf [EpisodeDownloadIds.titleId]; a test passes one that collides on purpose.
 */
internal class PageDownloads(
    private val downloads: DesktopDownloads,
    private val titleKey: String,
    private val titleIdOf: (String) -> Int = EpisodeDownloadIds::titleId,
) {
    private val titleId = DownloadTitleId(titleIdOf(titleKey))
    private val refused = MutableStateFlow<Set<Int>>(emptySet())

    /** The id an episode's download is filed under. */
    fun episodeId(detail: TitleDetail, episode: DetailEpisode): Int =
        EpisodeDownloadIds.episodeId(detail.providerName, episode.data)

    /** Each row's download, by [episodeId], again whenever it changes. Rows with nothing to show are absent. */
    fun rows(detail: TitleDetail): Flow<Map<Int, EpisodeDownloadRow>> =
        combine(
            downloads.series(titleId).contents
                .map<DownloadSeriesContents, DownloadSeriesContents?> { it }
                .catch { emit(null) },
            refused,
        ) { contents, refusedIds ->
            val own = contents?.takeIf { it.name == detail.title }?.episodes.orEmpty().associateBy { it.id.value }
            (own.keys + refusedIds).associateWith { id ->
                val download = own[id]
                EpisodeDownloadRow(download, unavailable = download == null && id in refusedIds)
            }
        }

    /** Enqueues [episode]. A refusal is kept, so its row reads unavailable; nothing is shown as an error. */
    suspend fun download(detail: TitleDetail, episode: DetailEpisode): EnqueueResult {
        val result = downloads.enqueue(episodeDownloadRequest(titleKey, detail, episode, titleIdOf))
        if (result is EnqueueResult.Refused) refused.update { it + episodeId(detail, episode) }
        return result
    }

    fun pause(id: Int) = downloads.pause(DownloadEpisodeId(id))

    /** A paused episode's Resume and a failed one's Retry, as the service has one call for both. */
    fun resume(id: Int) = downloads.resume(DownloadEpisodeId(id))

    fun cancel(id: Int) = downloads.cancel(DownloadEpisodeId(id))
}

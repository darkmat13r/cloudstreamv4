package com.lagradost.cloudstream4.downloads

import com.lagradost.cloudstream4.feature.downloads.DownloadTitleId
import com.lagradost.cloudstream4.feature.downloads.DownloadsContents
import com.lagradost.cloudstream4.feature.downloads.DownloadsSource
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisodeId
import com.lagradost.cloudstream4.feature.downloads.series.DownloadSeriesContents
import com.lagradost.cloudstream4.feature.downloads.series.DownloadSeriesSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

/**
 * The Downloads list (6c) over [DesktopDownloads].
 *
 * [contents] emits once the downloads file has been read, then after each change: at most four times a second
 * while anything runs, and not at all while nothing does. [DownloadsContents.storage] is the volume holding the
 * download folder, and is never null.
 */
class DesktopDownloadsSource(private val host: DesktopDownloads) : DownloadsSource {
    override val contents: Flow<DownloadsContents> = host.throttled { host.contentsNow() }

    override fun refresh() = host.refresh()

    override fun resume(id: DownloadTitleId) = host.resume(id)
}

/** One title's episodes (6d) over [DesktopDownloads], with the same emission rule as [DesktopDownloadsSource]. */
class DesktopDownloadSeriesSource(private val host: DesktopDownloads, private val id: DownloadTitleId) : DownloadSeriesSource {

    /** The name last seen, so a series whose last episode has just been deleted keeps its heading as it empties. */
    @Volatile
    private var lastName = ""

    override val contents: Flow<DownloadSeriesContents> =
        host.throttled { host.seriesNow(id.value, lastName) }.onEach { lastName = it.name }

    override fun refresh() = host.refresh()

    override fun pause(id: DownloadEpisodeId) = host.pause(id)

    override fun resume(id: DownloadEpisodeId) = host.resume(id)

    override fun cancel(id: DownloadEpisodeId) = host.cancel(id)
}

package com.lagradost.cloudstream4.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import com.lagradost.cloudstream4.feature.downloads.series.desktop.EpisodeDownloadDesktop
import kotlinx.coroutines.launch

/**
 * `KMP-109` part 2b: the title page's episode cell (`DetailScreenDesktop`'s `episodeTrailing`), filled from
 * [PageDownloads]: each row's download in D4's words, and its verbs while the row is hovered or focused.
 *
 * Null, so the page draws no cell, where there is no service (a test's window) or no title yet.
 *
 * @param titleKey the page's title id (`DesktopSearchSource.encodeId`).
 */
@Composable
internal fun rememberEpisodeDownloadCell(
    downloads: DesktopDownloads?,
    titleKey: String,
    detail: TitleDetail?,
): (@Composable (DetailEpisode, Boolean) -> Unit)? {
    if (downloads == null || detail == null) return null
    val page = remember(downloads, titleKey) { PageDownloads(downloads, titleKey) }
    val rows by remember(page, detail) { page.rows(detail) }.collectAsState(emptyMap())
    val scope = rememberCoroutineScope()
    return { episode, shown ->
        val id = page.episodeId(detail, episode)
        val row = rows[id]
        EpisodeDownloadDesktop(
            download = row?.download,
            shown = shown,
            formatBytes = ::formatBytes,
            // The service answers once it has the request in line; the page waits for nothing else.
            onDownload = { scope.launch { page.download(detail, episode) } },
            onPause = { page.pause(id) },
            onResume = { page.resume(id) },
            onCancel = { page.cancel(id) },
            unavailable = row?.unavailable == true,
        )
    }
}

/**
 * `KMP-122`: a film's download, at the end of its title page's actions (`DetailScreenDesktop`'s `filmTrailing`). The
 * episode cell's words and verbs over the same [PageDownloads], for the film's one file.
 *
 * Its verbs are always drawn. An episode's wait for its row to be hovered, because a page has many rows; the action
 * row is one row, and its other buttons are always there.
 *
 * Null, so the page draws nothing, where there is no service (a test's window), no title yet, or the title is not a
 * film.
 *
 * @param titleKey the page's title id (`DesktopSearchSource.encodeId`).
 */
@Composable
internal fun rememberFilmDownloadControl(
    downloads: DesktopDownloads?,
    titleKey: String,
    detail: TitleDetail?,
): (@Composable () -> Unit)? {
    if (downloads == null || detail == null) return null
    val page = remember(downloads, titleKey) { PageDownloads(downloads, titleKey) }
    val id = page.filmId(detail) ?: return null
    val rows by remember(page, detail) { page.rows(detail) }.collectAsState(emptyMap())
    val scope = rememberCoroutineScope()
    return {
        val row = rows[id]
        EpisodeDownloadDesktop(
            download = row?.download,
            shown = true,
            formatBytes = ::formatBytes,
            onDownload = { scope.launch { page.downloadFilm(detail) } },
            onPause = { page.pause(id) },
            onResume = { page.resume(id) },
            onCancel = { page.cancel(id) },
            unavailable = row?.unavailable == true,
            verbsAlignment = Alignment.Start,
        )
    }
}

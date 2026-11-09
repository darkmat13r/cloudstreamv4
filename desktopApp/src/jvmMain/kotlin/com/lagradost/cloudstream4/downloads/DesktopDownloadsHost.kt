package com.lagradost.cloudstream4.downloads

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.lagradost.cloudstream4.core.connectivity.UnknownConnectivityFlow
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisodeId
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleId
import com.lagradost.cloudstream4.feature.downloads.DownloadsAction
import com.lagradost.cloudstream4.feature.downloads.DownloadsEffect
import com.lagradost.cloudstream4.feature.downloads.DownloadsViewModel
import com.lagradost.cloudstream4.feature.downloads.desktop.DownloadsScreenDesktop
import com.lagradost.cloudstream4.feature.downloads.series.DownloadSeriesViewModel
import com.lagradost.cloudstream4.feature.downloads.series.SeriesAction
import com.lagradost.cloudstream4.feature.downloads.series.SeriesEffect
import com.lagradost.cloudstream4.feature.downloads.series.desktop.DownloadSeriesScreenDesktop
import com.lagradost.cloudstream4.search.PosterImage
import java.awt.Desktop
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Boards D1 to D6 of `DS-18` over the Mac's download service (`KMP-54` over `KMP-52`): the page behind the sidebar's
 * fourth row.
 *
 * What the host owns, and the screens leave to it:
 * - **The confirm sheet's subject.** A `ConfirmDelete` effect names what is asked about; the sheet's Delete removes
 *   exactly that, from the disk and the downloads file. Its Cancel removes nothing.
 * - **Show in Finder**, only for what [DesktopDownloads.fileOf] has on the disk: a finished file, or a series' folder.
 * - **Free space.** Already on the series page's contents (`freeBytes`), from the same volume as the titles page.
 *
 * @param viewModel the window's, made once beside the library's, so the selection and the row to return to outlast a
 *   series page over this one.
 * @param scope the window's: a delete the sheet confirmed finishes even if the page is left.
 * @param onOpenTitle a series was opened from the list: the host pushes its page.
 * @param onLibrary D1's empty state's way to the library.
 */
@Composable
internal fun DownloadsHost(
    downloads: DesktopDownloads,
    viewModel: DownloadsViewModel,
    scope: CoroutineScope,
    setEscape: ((() -> Unit)?) -> Unit,
    onOpenTitle: (DownloadTitleId) -> Unit,
    onLibrary: () -> Unit,
) {
    var confirming by remember { mutableStateOf<Set<DownloadTitleId>?>(null) }
    LaunchedEffect(viewModel) {
        // A file removed in Finder while another page was open is noticed on the way back, as Android's resume is.
        downloads.refresh()
        viewModel.onAction(DownloadsAction.Reappeared)
        viewModel.effect.collect { effect ->
            when (effect) {
                is DownloadsEffect.OpenTitle -> onOpenTitle(effect.id)
                is DownloadsEffect.ConfirmDelete -> confirming = effect.ids
                DownloadsEffect.OpenLibrary -> onLibrary()
                // Android's pages, with no Mac board: the queue is the list itself here, playing a link is the player's,
                // and the Mac has no storage settings to open.
                DownloadsEffect.OpenQueue, DownloadsEffect.OpenPlayFromUrl, DownloadsEffect.OpenStorageSettings -> Unit
            }
        }
    }
    DisposableEffect(confirming) {
        // Escape dismisses the sheet first, as its Cancel would, and only then is the window's again.
        setEscape(if (confirming != null) ({ confirming = null }) else null)
        onDispose { setEscape(null) }
    }
    val state by viewModel.state.collectAsState()
    DownloadsScreenDesktop(
        state = state,
        onAction = viewModel::onAction,
        formatBytes = ::formatBytes,
        confirming = confirming,
        onConfirmDelete = {
            val ids = confirming
            confirming = null
            if (ids != null) scope.launch { downloads.delete(ids) }
        },
        onCancelDelete = { confirming = null },
        canReveal = { title -> downloads.fileOf(title.id) != null },
        onReveal = { id -> downloads.fileOf(id)?.let(::showInFinder) },
        // KMP-99: the poster kept with the download. Only public http(s) addresses get this far; see `posterHttpClient`.
        artwork = { title -> PosterImage(url = title.posterUrl, modifier = Modifier.fillMaxSize()) },
    )
}

/**
 * D4: one series' episodes. [onClose] when it is empty, or Back.
 *
 * Its view model lives in a child of [scope], cancelled when the page leaves, so its readers stop with it. A delete the
 * sheet confirmed runs in [scope] itself and finishes either way.
 */
@Composable
internal fun DownloadSeriesHost(
    downloads: DesktopDownloads,
    id: DownloadTitleId,
    scope: CoroutineScope,
    setEscape: ((() -> Unit)?) -> Unit,
    onClose: () -> Unit,
) {
    val page = remember(downloads, id) { CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])) }
    DisposableEffect(page) { onDispose { page.cancel() } }
    val viewModel = remember(page) { DownloadSeriesViewModel(downloads.series(id), UnknownConnectivityFlow, page) }
    var confirming by remember { mutableStateOf<List<DownloadEpisodeId>?>(null) }
    LaunchedEffect(viewModel) {
        downloads.refresh()
        viewModel.onAction(SeriesAction.Reappeared)
        viewModel.effect.collect { effect ->
            when (effect) {
                is SeriesEffect.ConfirmDelete -> confirming = listOf(effect.id)
                is SeriesEffect.ConfirmDeleteAll -> confirming = effect.ids
                SeriesEffect.Close -> onClose()
                // Playing a file from the disk needs the player to take a local file, which it does not yet (`KMP-74`).
                is SeriesEffect.Play -> Unit
            }
        }
    }
    DisposableEffect(confirming) {
        setEscape(if (confirming != null) ({ confirming = null }) else onClose)
        onDispose { setEscape(null) }
    }
    val state by viewModel.state.collectAsState()
    DownloadSeriesScreenDesktop(
        state = state,
        onAction = viewModel::onAction,
        formatBytes = ::formatBytes,
        confirming = confirming,
        onConfirmDelete = {
            val ids = confirming
            confirming = null
            if (ids != null) scope.launch { downloads.deleteEpisodes(ids.toSet()) }
        },
        onCancelDelete = { confirming = null },
        canReveal = { episode -> downloads.fileOf(episode.id) != null },
        onReveal = { episodeId -> downloads.fileOf(episodeId)?.let(::showInFinder) },
        // KMP-99: D4's header, from the same kept poster as the row.
        artwork = { PosterImage(url = state.posterUrl, modifier = Modifier.fillMaxSize()) },
    )
}

/**
 * Selects [file] in a Finder window, or opens its folder where the platform cannot select. Only ever a path
 * [DesktopDownloads.fileOf] returned: inside the download root, and on the disk.
 */
private fun showInFinder(file: File) {
    runCatching {
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
            desktop.browseFileDirectory(file)
        } else if (desktop.isSupported(Desktop.Action.OPEN)) {
            desktop.open(if (file.isDirectory) file else file.parentFile)
        }
    }
}

/** A size as Finder writes it: decimal units, two places from GB up. The words are the platform's, as on Android. */
internal fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.coerceAtLeast(0).toDouble()
    var unit = 0
    while (value >= DECIMAL_STEP && unit < units.lastIndex) {
        value /= DECIMAL_STEP
        unit++
    }
    val places = if (unit >= GB_UNIT) 2 else if (unit == 0) 0 else 1
    return String.format(locale, "%.${places}f %s", value, units[unit])
}

private const val DECIMAL_STEP = 1000.0
private const val GB_UNIT = 3

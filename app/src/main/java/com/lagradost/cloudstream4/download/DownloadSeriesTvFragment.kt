package com.lagradost.cloudstream4.download

import android.os.Bundle
import android.text.format.Formatter.formatShortFileSize
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph
import androidx.navigation.fragment.FragmentNavigator
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.ui.download.DOWNLOAD_ACTION_PLAY_FILE
import com.lagradost.cloudstream3.ui.download.DownloadButtonSetup.handleDownloadClick
import com.lagradost.cloudstream3.ui.download.DownloadClickEvent
import com.lagradost.cloudstream3.ui.download.DownloadViewModel
import com.lagradost.cloudstream3.utils.BaseComposeFragment
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream4.core.connectivity.AndroidConnectivityFlow
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisodeId
import com.lagradost.cloudstream4.feature.downloads.series.DownloadSeriesViewModel
import com.lagradost.cloudstream4.feature.downloads.series.SeriesAction
import com.lagradost.cloudstream4.feature.downloads.series.SeriesEffect
import com.lagradost.cloudstream4.feature.downloads.series.tv.DownloadSeriesScreenTv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * One series' downloads on the TV layout (`Q15`), the glue around [DownloadSeriesScreenTv]: the same `navigation_download_child`
 * destination and arguments as `DownloadChildFragment`, which stays the screen on every other layout.
 *
 * Playing, pausing, resuming and deleting all go through the old screen's own calls, so they do what they did.
 */
class DownloadSeriesTvFragment : BaseComposeFragment() {

    private val downloads: DownloadViewModel by activityViewModels()

    private val folder: String? get() = arguments?.getString(ARG_FOLDER)
    private val name: String get() = arguments?.getString(ARG_NAME).orEmpty()

    /** The series' id, the last part of the folder (`getFolderName(DOWNLOAD_EPISODE_CACHE, id)`). */
    private val seriesId: Int? get() = folder?.substringAfterLast('/')?.toIntOrNull()

    private val source by lazy { AndroidDownloadSeriesSource(requireContext(), name, folder.orEmpty()) }

    private val viewModel: DownloadSeriesViewModel by viewModels {
        val app = requireContext().applicationContext
        viewModelFactory {
            initializer {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                DownloadSeriesViewModel(
                    source = source,
                    connectivity = AndroidConnectivityFlow(app, scope),
                    scope = scope,
                ).also { it.addCloseable(AutoCloseable { scope.cancel() }) }
            }
        }
    }

    /** A delete anywhere in the app, its own dialog's included, reads the disk again. */
    private val deleted: (Int) -> Unit = { source.refresh() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val folder = folder
        if (folder == null) {
            // `DownloadChildFragment`'s own answer to a page with nothing to show.
            activity?.onBackPressedDispatcher?.onBackPressed()
            return
        }
        // The old dialogs delete what the old view model lists, so it lists this series.
        downloads.updateChildList(view.context, folder)
        VideoDownloadManager.downloadDeleteEvent += deleted
    }

    /** Also what tells a stop that sent the user away that the page is back. See `SeriesReturn.ready`. */
    override fun onResume() {
        super.onResume()
        viewModel.onAction(SeriesAction.Reappeared)
    }

    override fun onDestroyView() {
        VideoDownloadManager.downloadDeleteEvent -= deleted
        downloads.clearChildren()
        super.onDestroyView()
    }

    private fun onEffect(effect: SeriesEffect) {
        when (effect) {
            is SeriesEffect.Play -> episodeOf(effect.id)?.let {
                handleDownloadClick(DownloadClickEvent(DOWNLOAD_ACTION_PLAY_FILE, it))
            }

            is SeriesEffect.ConfirmDelete -> confirmDelete(listOf(effect.id))
            is SeriesEffect.ConfirmDeleteAll -> confirmDelete(effect.ids)
            SeriesEffect.Close -> activity?.onBackPressedDispatcher?.onBackPressed()
        }
    }

    /** The old screen's dialog, which names one episode, or the series and every episode chosen. */
    private fun confirmDelete(ids: List<DownloadEpisodeId>) {
        val context = context ?: return
        downloads.clearSelectedItems()
        ids.forEach { downloads.addSelected(it.value) }
        downloads.handleMultiDelete(context)
    }

    private fun episodeOf(id: DownloadEpisodeId): DownloadObjects.DownloadEpisodeCached? {
        val folder = folder ?: return null
        return context?.getKey<DownloadObjects.DownloadEpisodeCached>(folder, id.value.toString())
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val context = LocalContext.current
        LaunchedEffect(viewModel) {
            viewModel.effect.collect { onEffect(it) }
        }
        val poster = remember(seriesId) {
            seriesId?.let { context.getKey<DownloadObjects.DownloadHeaderCached>(DOWNLOAD_HEADER_CACHE, it.toString())?.poster }
        }
        CloudStreamTheme {
            Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                DownloadSeriesScreenTv(
                    state = state,
                    onAction = viewModel::onAction,
                    formatBytes = { bytes -> formatShortFileSize(context, bytes) },
                    artwork = { Poster(poster) },
                )
            }
        }
    }

    private companion object {
        const val ARG_FOLDER = "folder"
        const val ARG_NAME = "name"
    }
}

/** The poster, from the same loader and with the app's user agent, as the old cards load theirs. */
@Composable
private fun Poster(url: String?) {
    val link = url?.takeIf { it.isNotBlank() } ?: return
    val context = LocalContext.current
    val request = remember(context, link) {
        ImageRequest.Builder(context)
            .data(link)
            .httpHeaders(NetworkHeaders.Builder().also { it["User-Agent"] = USER_AGENT }.build())
            .build()
    }
    AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
}

/**
 * Sends one series' downloads to [DownloadSeriesTvFragment]. Call once, with the graph just inflated. Deleting the call is
 * the rollback: the node stays `DownloadChildFragment`, for the reason on `openSearchOnTvScreen`.
 */
internal fun NavGraph.openDownloadSeriesOnTvScreen() {
    (findNode(R.id.navigation_download_child) as? FragmentNavigator.Destination)
        ?.setClassName(DownloadSeriesTvFragment::class.java.name)
}

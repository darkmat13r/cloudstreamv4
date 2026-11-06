package com.lagradost.cloudstream4.download

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter.formatShortFileSize
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph
import androidx.navigation.fragment.FragmentNavigator
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigationrail.NavigationRailView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.isEpisodeBased
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.download.DOWNLOAD_ACTION_PLAY_FILE
import com.lagradost.cloudstream3.ui.download.DownloadButtonSetup.handleDownloadClick
import com.lagradost.cloudstream3.ui.download.DownloadChildFragment
import com.lagradost.cloudstream3.ui.download.DownloadClickEvent
import com.lagradost.cloudstream3.ui.download.DownloadViewModel
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.attachBackPressedCallback
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.detachBackPressedCallback
import com.lagradost.cloudstream3.utils.BaseComposeFragment
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DataStore.getFolderName
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream4.core.connectivity.AndroidConnectivityFlow
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleId
import com.lagradost.cloudstream4.feature.downloads.DownloadsAction
import com.lagradost.cloudstream4.feature.downloads.DownloadsEffect
import com.lagradost.cloudstream4.feature.downloads.DownloadsViewModel
import com.lagradost.cloudstream4.feature.downloads.tv.DownloadsScreenTv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The downloads destination on the TV layout, the glue around `DownloadsScreenTv`, as `SearchTvFragment` is for
 * search. `DownloadFragment` stays as the rollback and for every other layout.
 *
 * `docs/tasks/kmp.md` lists what the old screen does that this does not.
 */
class DownloadsTvFragment : BaseComposeFragment() {

    private val downloads: DownloadViewModel by activityViewModels()

    private val viewModel: DownloadsViewModel by viewModels {
        val app = requireContext().applicationContext
        viewModelFactory {
            initializer {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                DownloadsViewModel(
                    source = AndroidDownloadsSource(app, downloads),
                    connectivity = AndroidConnectivityFlow(app, scope),
                    scope = scope,
                ).also { it.addCloseable(AutoCloseable { scope.cancel() }) }
            }
        }
    }

    /** Also what tells a stop that sent the user away that the screen is back. See `DownloadsReturn.ready`. */
    override fun onResume() {
        super.onResume()
        viewModel.onAction(DownloadsAction.Reappeared)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        downloads.updateHeaderList(view.context)
    }

    override fun onDestroyView() {
        activity?.detachBackPressedCallback(BACK_CALLBACK)
        super.onDestroyView()
    }

    private fun onEffect(effect: DownloadsEffect) {
        when (effect) {
            is DownloadsEffect.OpenTitle -> open(effect.id)
            DownloadsEffect.OpenQueue ->
                activity?.navigate(R.id.action_navigation_global_to_navigation_download_queue)

            DownloadsEffect.OpenPlayFromUrl -> activity?.showPlayFromUrlDialog()
            // Through the nav views, as `SearchTvFragment` reaches home, so the rail's selection stays right.
            DownloadsEffect.OpenLibrary -> {
                activity?.findViewById<BottomNavigationView>(R.id.nav_view)?.selectedItemId = R.id.navigation_library
                activity?.findViewById<NavigationRailView>(R.id.nav_rail_view)?.selectedItemId = R.id.navigation_library
            }

            DownloadsEffect.OpenStorageSettings -> openStorageSettings()
            is DownloadsEffect.ConfirmDelete -> {
                // `handleMultiDelete` reads the old screen's selection and asks with the old screen's dialog.
                downloads.clearSelectedItems()
                effect.ids.forEach { downloads.addSelected(it.value) }
                context?.let { downloads.handleMultiDelete(it) }
            }
        }
    }

    /** What `DownloadFragment.handleItemClick` and `DownloadAdapter` do with the same row. */
    private fun open(id: DownloadTitleId) {
        val cards = downloads.headerCards.value as? Resource.Success ?: return
        val header = cards.value.firstOrNull { it.data.id == id.value } ?: return
        val film = header.child
        when {
            film != null -> handleDownloadClick(DownloadClickEvent(DOWNLOAD_ACTION_PLAY_FILE, film))
            header.data.type.isEpisodeBased() -> activity?.navigate(
                R.id.action_navigation_downloads_to_navigation_download_child,
                DownloadChildFragment.newInstance(
                    header.data.name,
                    getFolderName(DOWNLOAD_EPISODE_CACHE, header.data.id.toString()),
                ),
            )
        }
    }

    /** The system's storage screen, or its settings on a TV whose build has no such page. */
    private fun openStorageSettings() {
        val activity = activity ?: return
        try {
            activity.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            runCatching { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val context = LocalContext.current
        LaunchedEffect(viewModel) {
            viewModel.effect.collect { onEffect(it) }
        }
        // Back ends choosing before it leaves the screen, as on the old one.
        val choosing = state.selection != null
        LaunchedEffect(choosing) {
            val host = activity ?: return@LaunchedEffect
            if (choosing) {
                host.attachBackPressedCallback(BACK_CALLBACK) {
                    viewModel.onAction(DownloadsAction.SelectionCancelled)
                }
            } else {
                host.detachBackPressedCallback(BACK_CALLBACK)
            }
        }
        CloudStreamTheme {
            Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                DownloadsScreenTv(
                    state = state,
                    onAction = viewModel::onAction,
                    formatBytes = { bytes -> formatShortFileSize(context, bytes) },
                )
            }
        }
    }

    private companion object {
        const val BACK_CALLBACK = "DownloadsTv"
    }
}

/**
 * Sends the downloads destination to [DownloadsTvFragment]. Call once, with the graph just inflated. Deleting the call
 * is the rollback: the node stays `DownloadFragment`, for the reason on `openSearchOnTvScreen`.
 */
internal fun NavGraph.openDownloadsOnTvScreen() {
    (findNode(R.id.navigation_downloads) as? FragmentNavigator.Destination)
        ?.setClassName(DownloadsTvFragment::class.java.name)
}

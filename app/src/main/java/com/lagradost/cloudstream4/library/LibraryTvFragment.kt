package com.lagradost.cloudstream4.library

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
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
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph
import androidx.navigation.fragment.FragmentNavigator
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigationrail.NavigationRailView
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.BaseComposeFragment
import com.lagradost.cloudstream4.core.connectivity.AndroidConnectivityFlow
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.library.LibraryAction
import com.lagradost.cloudstream4.feature.library.LibraryCard
import com.lagradost.cloudstream4.feature.library.LibraryEffect
import com.lagradost.cloudstream4.feature.library.LibraryStatus
import com.lagradost.cloudstream4.feature.library.LibraryTitleId
import com.lagradost.cloudstream4.feature.library.LibraryViewModel
import com.lagradost.cloudstream4.feature.library.tv.LibraryScreenTv
import com.lagradost.cloudstream4.feature.library.tv.LibraryTvTabs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The library destination on the TV layout, the glue around `LibraryScreenTv`, as `SearchTvFragment` is for
 * search: it owns the ViewModel, turns effects into navigation and loads posters.
 *
 * `docs/tasks/kmp.md` lists what the old screen does that this does not yet.
 */
class LibraryTvFragment : BaseComposeFragment() {

    private val viewModel: LibraryViewModel by viewModels {
        val app = requireContext().applicationContext
        viewModelFactory {
            initializer {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                val store = AndroidLibraryStore(app)
                val sources = AndroidLibrarySources(app, store)
                LibraryViewModel(
                    store = store,
                    scope = scope,
                    initialTab = LibraryTvTabs.first(),
                    sources = sources,
                    connectivity = AndroidConnectivityFlow(app, scope),
                ).also {
                    it.addCloseable(AutoCloseable { scope.cancel() })
                    // Kept with the ViewModel, so a new fragment after a configuration change opens what it shows.
                    it.addCloseable(SOURCES_KEY, HeldSources(sources))
                }
            }
        }
    }

    private val sources: AndroidLibrarySources?
        get() = viewModel.getCloseable<HeldSources>(SOURCES_KEY)?.sources

    /**
     * A bookmark may have changed on a result page, and the account may have changed anywhere. This is also
     * the moment a card opened from here takes focus back: see `LibraryReturn.ready`.
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        wirePanelBack()
    }

    /**
     * BACK closes the source or sort panel, as `DetailTvFragment.wirePanelBack` does for its panels and for the same reason:
     * `SidePanelHost` takes BACK only as a key event, and on `Television_720p` an injected BACK reaches the activity
     * first, which here would leave the library with the panel open. Enabled only while the panel is, so BACK with no
     * panel up still leaves the screen.
     */
    private fun wirePanelBack() {
        val callback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                val sortOpen = viewModel.state.value.sortOpen
                viewModel.onAction(if (sortOpen) LibraryAction.SortDismissed else LibraryAction.SourcesDismissed)
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { callback.isEnabled = it.sourcesOpen || it.sortOpen }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAction(LibraryAction.Reappeared)
    }

    private fun onEffect(effect: LibraryEffect) {
        when (effect) {
            is LibraryEffect.OpenTitle -> open(effect.id)
            // Nothing on this screen resumes yet. The title's page is where that is decided.
            is LibraryEffect.ResumeTitle -> open(effect.id)
            // Through the nav views, as `SearchTvFragment` reaches home, so the rail's selection stays right.
            LibraryEffect.OpenSearch -> {
                activity?.findViewById<BottomNavigationView>(R.id.nav_view)?.selectedItemId =
                    R.id.navigation_search
                activity?.findViewById<NavigationRailView>(R.id.nav_rail_view)?.selectedItemId =
                    R.id.navigation_search
            }
        }
    }

    /**
     * The old screen's default opener for a local title: the title's page, or a search by name when the provider is
     * gone. A tracking service's title goes through `openSyncedTitle`, which also honours a saved opener.
     */
    private fun open(id: LibraryTitleId) {
        // A title from a tracking service's list, through the old screen's opener.
        sources?.synced(id)?.let { synced ->
            activity?.openSyncedTitle(synced)
            return
        }
        val target = id.target() ?: return
        val name = (viewModel.state.value.status as? LibraryStatus.Showing)
            ?.cards?.firstOrNull { it.id == id }?.title.orEmpty()
        if (APIHolder.getApiFromNameNull(target.apiName) != null) {
            loadResult(url = target.url, apiName = target.apiName, name = name)
        } else {
            QuickSearchFragment.pushSearch(activity, name)
        }
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        LaunchedEffect(viewModel) {
            viewModel.effect.collect { onEffect(it) }
        }
        CloudStreamTheme {
            Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                LibraryScreenTv(
                    state = state,
                    onAction = viewModel::onAction,
                    artwork = { card -> LibraryPoster(card) },
                    now = System::currentTimeMillis,
                    // The rail is a view outside this composition, and the field consumes Left (`BUG-34`).
                    onLeftEdge = { view?.focusSearch(View.FOCUS_LEFT)?.requestFocus() },
                )
            }
        }
    }
}

/** The sources, held by the ViewModel. Nothing to close: they hold no resource of their own. */
private class HeldSources(val sources: AndroidLibrarySources) : AutoCloseable {
    override fun close() = Unit
}

private const val SOURCES_KEY = "library_sources"

/**
 * Sends the library destination to [LibraryTvFragment]. Call once, with the graph just inflated. Deleting the
 * call is the rollback: the node stays `LibraryFragment`, for the reason on `openSearchOnTvScreen`.
 */
internal fun NavGraph.openLibraryOnTvScreen() {
    (findNode(R.id.navigation_library) as? FragmentNavigator.Destination)
        ?.setClassName(LibraryTvFragment::class.java.name)
}

/** The poster, from the same loader and with the same headers as search's cards. */
@Composable
private fun LibraryPoster(card: LibraryCard) {
    val url = card.posterUrl?.takeIf { it.isNotBlank() } ?: return
    val context = LocalContext.current
    val request = remember(context, url, card.posterHeaders) {
        ImageRequest.Builder(context)
            .data(url)
            .httpHeaders(
                NetworkHeaders.Builder().also { headers ->
                    headers["User-Agent"] = USER_AGENT
                    card.posterHeaders?.forEach { (key, value) -> headers[key] = value }
                }.build(),
            )
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}

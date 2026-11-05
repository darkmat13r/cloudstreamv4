package com.lagradost.cloudstream4.library

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
                LibraryViewModel(
                    store = AndroidLibraryStore(app),
                    scope = scope,
                    initialTab = LibraryTvTabs.first(),
                ).also { it.addCloseable(AutoCloseable { scope.cancel() }) }
            }
        }
    }

    /**
     * A bookmark may have changed on a result page, and the account may have changed anywhere. This is also
     * the moment a card opened from here takes focus back: see `LibraryReturn.ready`.
     */
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
     * The old screen's default opener: the title's page, or a search by name when the provider is gone.
     * `LibraryFragment.loadLibraryItem` also honours a saved per-list opener. This does not yet.
     */
    private fun open(id: LibraryTitleId) {
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
                )
            }
        }
    }
}

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

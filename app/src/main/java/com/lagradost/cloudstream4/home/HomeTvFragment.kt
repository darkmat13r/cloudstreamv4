package com.lagradost.cloudstream4.home

import android.os.Bundle
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
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigationrail.NavigationRailView
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.MainActivity.Companion.mainPluginsLoadedEvent
import com.lagradost.cloudstream3.MainActivity.Companion.reloadAccountEvent
import com.lagradost.cloudstream3.MainActivity.Companion.reloadHomeEvent
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.ui.account.AccountHelper.showAccountSelectLinear
import com.lagradost.cloudstream3.ui.home.HomeFragment
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.attachBackPressedCallback
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.detachBackPressedCallback
import com.lagradost.cloudstream3.utils.BaseComposeFragment
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.home.HomeAction
import com.lagradost.cloudstream4.feature.home.HomeEffect
import com.lagradost.cloudstream4.feature.home.HomeHero
import com.lagradost.cloudstream4.feature.home.HomeItem
import com.lagradost.cloudstream4.feature.home.HomeStatus
import com.lagradost.cloudstream4.feature.home.HomeViewModel
import com.lagradost.cloudstream4.feature.home.tv.HomeScreenTv
import com.lagradost.cloudstream4.feature.search.SearchResultId
import com.lagradost.cloudstream4.library.AndroidLibraryStore
import com.lagradost.cloudstream4.search.decodeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * **The switch.** `false` puts `HomeFragment` back on Android TV. It is a `var` so a test can prove that. Phone and the
 * emulator layout never get [HomeTvFragment] whatever this says.
 */
object TvHomeSwitch {
    @JvmField
    var useComposeHome: Boolean = true
}

/**
 * Sends the home destination to [HomeTvFragment] on the TV layout, for every way a fragment gets made.
 *
 * **This is not `setClassName`, which is what search and the library use, because that is too late for this destination.**
 * Home is the graph's start destination, and `NavHostFragment` navigates to it while `setContentView` inflates it, before
 * `MainActivity` has a `navController` to edit. `HomeTvNavigationTest` measured `setClassName` leaving `HomeFragment` on
 * screen. A factory also covers a process restored from saved state, where the fragment's class comes from the Bundle.
 *
 * `FragmentFactory` is inherited by child fragment managers, so setting it on the activity's reaches the nav host's.
 */
internal object TvHomeFragmentFactory : FragmentFactory() {
    override fun instantiate(classLoader: ClassLoader, className: String): Fragment =
        if (className == HomeFragment::class.java.name) HomeTvFragment() else super.instantiate(classLoader, className)
}

/**
 * The home destination on the TV layout, the glue around `HomeScreenTv`, as `SearchTvFragment` is for search: it owns the
 * ViewModel, turns effects into navigation and loads posters.
 *
 * `docs/tasks/kmp.md` (`KMP-37`) lists what the old screen does that this does not.
 */
class HomeTvFragment : BaseComposeFragment() {

    private val viewModel: HomeViewModel by viewModels {
        val app = requireContext().applicationContext
        viewModelFactory {
            initializer {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                HomeViewModel(
                    source = androidHomeSource(app, startedAt = System.currentTimeMillis()),
                    scope = scope,
                    memory = DataStoreHomeSourceMemory(),
                    history = LibraryHomeHistory(AndroidLibraryStore(app)),
                ).also { it.addCloseable(AutoCloseable { scope.cancel() }) }
            }
        }
    }

    /**
     * Events arrive on whatever thread loaded the plugin, and the ViewModel's bookkeeping is the main thread's.
     *
     * The check is not redundant with the scope. A view closed as soon as it opens never reaches `ON_CREATE`, so its
     * `viewLifecycleOwner` never gets `ON_DESTROY` and what runs in its scope is not cancelled: the timer below outlived its
     * fragment, reached a `viewModels` that throws once detached, and the crash handler ended the process. Found by
     * `ExampleInstrumentedTest.layoutTest` running first in the whole suite.
     */
    private fun send(action: HomeAction) {
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            if (isAdded) viewModel.onAction(action)
        }
    }

    private val sourcesChanged: (Boolean) -> Unit = { send(HomeAction.SourcesChanged) }

    private val reloadRequested: (Boolean) -> Unit = { send(HomeAction.Retry) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // A remembered source that never arrives has to stop being waited for even if no event says so.
        viewLifecycleOwner.lifecycleScope.launch {
            delay(PLUGIN_WAIT_MS + PLUGIN_WAIT_MARGIN_MS)
            send(HomeAction.SourcesChanged)
        }
    }

    override fun onResume() {
        super.onResume()
        afterPluginsLoadedEvent += sourcesChanged
        mainPluginsLoadedEvent += sourcesChanged
        reloadAccountEvent += sourcesChanged
        reloadHomeEvent += reloadRequested
        // The first time the page opens the window may have handed focus to the first thing it found, the nav rail, which
        // would open it over a page nobody has asked to leave. Nothing has been pressed yet, so it is this page's to take.
        if (viewModel.state.value.landingOwed) view?.requestFocus()
        viewModel.onAction(HomeAction.Reappeared)
        viewModel.onAction(HomeAction.SourcesChanged)
    }

    override fun onPause() {
        viewModel.onAction(HomeAction.Disappeared)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        afterPluginsLoadedEvent -= sourcesChanged
        mainPluginsLoadedEvent -= sourcesChanged
        reloadAccountEvent -= sourcesChanged
        reloadHomeEvent -= reloadRequested
    }

    override fun onDestroyView() {
        activity?.detachBackPressedCallback(BACK_CLOSES_PICKER)
        super.onDestroyView()
    }

    private fun onEffect(effect: HomeEffect) {
        when (effect) {
            is HomeEffect.OpenTitle -> open(effect.id)
            // Through the nav views, as `SearchTvFragment` reaches home, so the rail's selection stays right.
            HomeEffect.OpenSearch -> {
                activity?.findViewById<BottomNavigationView>(R.id.nav_view)?.selectedItemId = R.id.navigation_search
                activity?.findViewById<NavigationRailView>(R.id.nav_rail_view)?.selectedItemId = R.id.navigation_search
            }
            HomeEffect.OpenExtensions -> activity?.navigate(R.id.navigation_settings_extensions)
            HomeEffect.OpenAccounts -> activity?.showAccountSelectLinear()
        }
    }

    /** A card, the hero or a Continue watching entry: all three carry the same id, and open the title's page. */
    private fun open(id: String) {
        val target = decodeId(SearchResultId(id)) ?: return
        val state = viewModel.state.value
        val status = state.status
        val rows = (status as? HomeStatus.Showing)?.rows ?: (status as? HomeStatus.Partial)?.rows
        val name = rows.orEmpty().firstNotNullOfOrNull { row -> row.items.firstOrNull { it.id == id } }?.title
            ?: (state.hero as? HomeHero.Shown)?.featured?.takeIf { it.id == id }?.title
            ?: state.history.resume.firstOrNull { it.id == id }?.title
        loadResult(url = target.url, apiName = target.apiName, name = name.orEmpty())
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        LaunchedEffect(viewModel) {
            viewModel.effect.collect { onEffect(it) }
        }
        // On API 36 Back can arrive as a callback and never as a key, so the panel's own key handler is not enough.
        LaunchedEffect(state.pickerOpen) {
            if (state.pickerOpen) {
                activity?.attachBackPressedCallback(BACK_CLOSES_PICKER) {
                    viewModel.onAction(HomeAction.PickerDismissed)
                }
            } else {
                activity?.detachBackPressedCallback(BACK_CLOSES_PICKER)
            }
        }
        CloudStreamTheme {
            Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                HomeScreenTv(
                    state = state,
                    onAction = viewModel::onAction,
                    artwork = { item -> HomePoster(item) },
                    mayTakeFocus = {
                        val owner = activity?.currentFocus
                        owner == null || view?.hasFocus() == true
                    },
                )
            }
        }
    }

    private companion object {
        const val BACK_CLOSES_PICKER = "HomeTvPicker"
        const val PLUGIN_WAIT_MARGIN_MS = 500L
    }
}

/** The poster, from the same loader and with the same headers as search's cards. */
@Composable
private fun HomePoster(item: HomeItem) {
    val url = item.posterUrl ?: return
    val context = LocalContext.current
    val request = remember(context, url, item.posterHeaders) {
        ImageRequest.Builder(context)
            .data(url)
            .httpHeaders(
                NetworkHeaders.Builder().also { headers ->
                    headers["User-Agent"] = USER_AGENT
                    item.posterHeaders?.forEach { (key, value) -> headers[key] = value }
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

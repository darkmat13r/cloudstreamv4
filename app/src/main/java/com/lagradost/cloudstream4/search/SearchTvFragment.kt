package com.lagradost.cloudstream4.search

import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.preference.PreferenceManager
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigationrail.NavigationRailView
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.search.SearchFragment
import com.lagradost.cloudstream3.ui.search.SearchSuggestionApi
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.BaseComposeFragment
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard
import com.lagradost.cloudstream4.core.connectivity.AndroidConnectivityFlow
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.search.SearchAction
import com.lagradost.cloudstream4.feature.search.SearchContent
import com.lagradost.cloudstream4.feature.search.SearchEffect
import com.lagradost.cloudstream4.feature.search.SearchResultId
import com.lagradost.cloudstream4.feature.search.SearchResultItem
import com.lagradost.cloudstream4.feature.search.SearchStatus
import com.lagradost.cloudstream4.feature.search.SearchSuggestionSource
import com.lagradost.cloudstream4.feature.search.SearchViewModel
import com.lagradost.cloudstream4.feature.search.tv.SearchScreenTv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The search destination on the TV layout: Phase 7 step 3, the glue around `SearchScreenTv`.
 *
 * Everything Android lives here and nothing else does: it owns the ViewModel, turns effects into
 * navigation, records history, loads posters. [BaseComposeFragment] supplies the `ComposeView`, the
 * disposal strategy and the system-bar padding, the same as the settings screens.
 *
 * `docs/tasks/kmp.md` (`KMP-12`) lists every behaviour of `SearchFragment` and whether this keeps it.
 */
class SearchTvFragment : BaseComposeFragment() {

    private val history = DataStoreSearchHistory()

    private val viewModel: SearchViewModel by viewModels {
        val app = requireContext().applicationContext
        viewModelFactory {
            initializer {
                // Its own scope, ended with the view model: the connectivity flow stays subscribed only while search is.
                val network = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                SearchViewModel(
                    source = ApiRepositorySearchSource(app),
                    history = history,
                    suggestions = suggestions(app),
                    connectivity = AndroidConnectivityFlow(app, network),
                ).also { it.addCloseable(AutoCloseable { network.cancel() }) }
            }
        }
    }

    /**
     * Artboard 8a's suggestions, from the old `SearchSuggestionApi`, wrapped and not moved: it is in `classes.jar`.
     * Behind the same setting the old screen reads, read when the screen is made, so a change in settings applies the
     * next time search opens. With it off, typing runs the quick search into rails, as before.
     */
    private fun suggestions(context: Context): SearchSuggestionSource? {
        val enabled = PreferenceManager.getDefaultSharedPreferences(context).getBoolean("search_suggestions_enabled", true)
        return if (enabled) SearchSuggestionSource { SearchSuggestionApi.getSuggestions(it) } else null
    }

    private val sourcesChanged: (Boolean) -> Unit = {
        viewModel.onAction(SearchAction.SourcesChanged)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        runPendingQuery()
    }

    /** Also covers coming back from settings, where the user may have changed which sources count. */
    override fun onResume() {
        super.onResume()
        afterPluginsLoadedEvent += sourcesChanged
        viewModel.onAction(SearchAction.SourcesChanged)
    }

    override fun onStop() {
        super.onStop()
        afterPluginsLoadedEvent -= sourcesChanged
    }

    override fun onDestroyView() {
        hideKeyboard(view)
        super.onDestroyView()
    }

    /**
     * A query handed over by an intent (`cloudstreamsearch://`), through the argument
     * `MainActivity` writes or `nextSearchQuery`. Cleared so reopening the page does not repeat it.
     *
     * Sent as [SearchAction.RecentActivated] because that is the action that means "this exact text,
     * full search, now". `QueryChanged` then `Submit` would also start a debounce for nothing.
     */
    private fun runPendingQuery() {
        val query = arguments?.getString(SearchFragment.SEARCH_QUERY)?.ifBlank { null }
            ?: MainActivity.nextSearchQuery
        if (query.isNullOrBlank()) return
        arguments?.remove(SearchFragment.SEARCH_QUERY)
        MainActivity.nextSearchQuery = null
        onAction(SearchAction.RecentActivated(query))
    }

    private fun onAction(action: SearchAction) {
        when (action) {
            // The old screen asks before deleting the whole history, and so does this.
            SearchAction.RecentCleared -> {
                confirmClearHistory()
                return
            }

            SearchAction.Submit -> {
                record(viewModel.state.value.query)
                hideKeyboard(view)
            }

            is SearchAction.RecentActivated -> record(action.query)

            is SearchAction.SuggestionActivated -> {
                record(action.query)
                hideKeyboard(view)
            }

            else -> Unit
        }
        viewModel.onAction(action)
    }

    private fun record(query: String) {
        lifecycleScope.launch { history.record(query) }
    }

    /** `SearchFragment.kt:577-607`, the same dialog with the same strings. */
    private fun confirmClearHistory() {
        val activity = activity ?: return
        val listener = DialogInterface.OnClickListener { _, which ->
            if (which == DialogInterface.BUTTON_POSITIVE) {
                viewModel.onAction(SearchAction.RecentCleared)
            }
        }
        try {
            AlertDialog.Builder(activity)
                .setTitle(R.string.clear_history)
                .setMessage(activity.getString(R.string.delete_message).format(activity.getString(R.string.history)))
                .setPositiveButton(R.string.sort_clear, listener)
                .setNegativeButton(R.string.cancel, listener)
                .show()
                .setDefaultFocus()
        } catch (e: Exception) {
            logError(e)
        }
    }

    private fun onEffect(effect: SearchEffect) {
        when (effect) {
            is SearchEffect.OpenResult -> decodeId(effect.id)?.let { target ->
                loadResult(
                    url = target.url,
                    apiName = target.apiName,
                    name = viewModel.state.value.status.titleOf(effect.id).orEmpty(),
                )
            }

            // Through the nav views, the way the intent handler reaches search, so the rail's
            // selection and the saved back stack stay correct.
            SearchEffect.BrowseHome -> {
                activity?.findViewById<BottomNavigationView>(R.id.nav_view)?.selectedItemId =
                    R.id.navigation_home
                activity?.findViewById<NavigationRailView>(R.id.nav_rail_view)?.selectedItemId =
                    R.id.navigation_home
            }
        }
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        LaunchedEffect(viewModel) {
            viewModel.effect.collect { onEffect(it) }
        }
        // Back on the open panel closes the panel. On API 36 the key goes to the dispatcher and not the view
        // tree, so `SidePanelHost`'s key handler never sees it, and without this it closes the screen.
        val panelOpen = state.sourcePanel != null
        DisposableEffect(panelOpen) {
            val closesPanel = object : OnBackPressedCallback(panelOpen) {
                override fun handleOnBackPressed() = viewModel.onAction(SearchAction.SourcePanelDismissed)
            }
            requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, closesPanel)
            onDispose { closesPanel.remove() }
        }
        CloudStreamTheme {
            Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                SearchScreenTv(
                    state = state,
                    onAction = ::onAction,
                    artwork = { item -> PosterArtwork(item) },
                    // The rail is a view outside this composition, and the field consumes Left (`BUG-34`).
                    onLeftEdge = { view?.focusSearch(View.FOCUS_LEFT)?.requestFocus() },
                )
            }
        }
    }
}

/**
 * Sends the search destination to [SearchTvFragment]. Call once, with the graph just inflated.
 *
 * The node stays `SearchFragment` in `mobile_navigation.xml` because a nav graph has no per-layout
 * form: `isLayout(TV)` is a preference with an auto-detect default, read at runtime, so no resource
 * qualifier can express it. A second node would be worse, because `navigation_search` is also the
 * menu item, the back-stack key, and what `MainActivity` compares against in six places.
 */
internal fun NavGraph.openSearchOnTvScreen() {
    (findNode(R.id.navigation_search) as? FragmentNavigator.Destination)
        ?.setClassName(SearchTvFragment::class.java.name)
}

/**
 * The title for a result being opened. `ResultFragment` shows it as a placeholder until the provider
 * answers, and the id does not carry it. `null` if the results changed underneath the press.
 */
internal fun SearchStatus.titleOf(id: SearchResultId): String? {
    val content = when (this) {
        is SearchStatus.Showing -> content
        is SearchStatus.Partial -> content
        else -> null
    }
    return (content as? SearchContent.Results)?.groups
        ?.firstNotNullOfOrNull { group -> group.items.firstOrNull { it.id == id } }
        ?.title
}

/**
 * The poster, from the same singleton loader and with the same headers as the old card
 * (`ImageLoader.loadImageInternal`): the app's user agent, which a provider's own may override.
 */
@Composable
private fun PosterArtwork(item: SearchResultItem) {
    val url = item.posterUrl?.takeIf { it.isNotBlank() } ?: return
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

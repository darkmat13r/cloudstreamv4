package com.lagradost.cloudstream4.search

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import androidx.navigation.fragment.DialogFragmentNavigator
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigationrail.NavigationRailView
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanelHost
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.search.QuickSearchAction
import com.lagradost.cloudstream4.feature.search.QuickSearchEffect
import com.lagradost.cloudstream4.feature.search.QuickSearchViewModel
import com.lagradost.cloudstream4.feature.search.tv.QuickSearchPanel

/**
 * Quick Search on the TV layout, artboards **8d** and **8e**: the panel over the screen that opened it.
 *
 * A dialog, not a page, because the board's point is that the caller **does not reflow**: Home or a title's page
 * stays drawn and in state under the scrim. A fragment destination would replace it. The window covers the nav
 * host and not the rail, so the rail is dimmed by `SidePanelOutsideDim`, as it is for 4d, and not twice.
 *
 * It takes the old fragment's arguments, so every `QuickSearchFragment.pushSearch` caller reaches it unchanged:
 * the query, trimmed of its `(DUB)`/`(SUB)` suffix there, and optionally the providers to ask.
 *
 * - **OK on a row** opens the title. The navigation pops this dialog, so BACK from the title returns to the caller.
 * - **Open full search** takes the query to the search screen, the way the intent handler does.
 * - **Done** and BACK close it. It closes at once, without the slide out: the window goes with the press.
 */
class QuickSearchTvFragment : DialogFragment() {

    private val viewModel: QuickSearchViewModel by viewModels {
        val app = requireContext().applicationContext
        val query = arguments?.getString(QuickSearchFragment.AUTOSEARCH_KEY).orEmpty()
        val providers = arguments?.getStringArray(QuickSearchFragment.PROVIDER_KEY)?.toSet()
        viewModelFactory {
            initializer { QuickSearchViewModel(query = query, source = ApiRepositorySearchSource(app).quickSearchPanel(providers)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, android.R.style.Theme_Translucent_NoTitleBar)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(inflater.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val state by viewModel.state.collectAsState()
                // Starts closed so the panel slides in on the first frame, as 4d's does.
                var expanded by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { expanded = true }
                LaunchedEffect(viewModel) { viewModel.effect.collect(::onEffect) }
                CloudStreamTheme {
                    SidePanelHost(
                        expanded = expanded,
                        onDismissRequest = { viewModel.onAction(QuickSearchAction.Done) },
                        panel = { QuickSearchPanel(state = state, onAction = viewModel::onAction) },
                        content = { Box(Modifier.fillMaxSize()) },
                    )
                }
            }
        }

    /** Over the nav host only, transparent, with no dim of the window's own: the host draws the scrim. */
    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        val host = activity?.findViewById<View>(R.id.nav_host_fragment)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(0f)
        if (host != null && host.width > 0) window.setLayout(host.width, host.height)
        window.setGravity(Gravity.TOP or Gravity.END)
    }

    private fun onEffect(effect: QuickSearchEffect) {
        when (effect) {
            QuickSearchEffect.Close -> dismiss()

            is QuickSearchEffect.OpenResult -> decodeId(effect.id)?.let { target ->
                val title = viewModel.state.value.progress?.items?.firstOrNull { it.id == effect.id }?.title.orEmpty()
                loadResult(url = target.url, apiName = target.apiName, name = title)
            }

            // `nextSearchQuery` is what `MainActivity` hands the search screen, and selecting the rail item is how
            // `SearchTvFragment` reaches home, so the rail's selection and the saved back stack stay right.
            is QuickSearchEffect.OpenFullSearch -> {
                MainActivity.nextSearchQuery = effect.query
                dismiss()
                activity?.findViewById<BottomNavigationView>(R.id.nav_view)?.selectedItemId = R.id.navigation_search
                activity?.findViewById<NavigationRailView>(R.id.nav_rail_view)?.selectedItemId = R.id.navigation_search
            }
        }
    }
}

/**
 * Sends Quick Search to [QuickSearchTvFragment], as a dialog with the same id, so every action and `pushSearch` that
 * names `navigation_quick_search` opens the panel. Call once, with the graph just inflated. Deleting the call is the
 * rollback: the node stays `QuickSearchFragment`, for the reason on `openSearchOnTvScreen`.
 *
 * Not `setClassName`, which the other screens use: that keeps a fragment destination, and a fragment destination
 * replaces the caller, which the board forbids. A node of the dialog navigator's kind with the same id replaces it in
 * the graph.
 */
internal fun NavController.openQuickSearchOnTvScreen() {
    val old = graph.findNode(R.id.navigation_quick_search) ?: return
    val dialogs = navigatorProvider.getNavigator(DialogFragmentNavigator::class.java)
    graph.addDestination(
        DialogFragmentNavigator.Destination(dialogs).apply {
            id = old.id
            label = old.label
            setClassName(QuickSearchTvFragment::class.java.name)
        },
    )
}

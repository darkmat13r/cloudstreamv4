package com.lagradost.cloudstream4.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.lagradost.cloudstream4.feature.library.LibraryAction
import com.lagradost.cloudstream4.feature.library.LibraryEffect
import com.lagradost.cloudstream4.feature.library.LibraryResume
import com.lagradost.cloudstream4.feature.library.LibraryTitleId
import com.lagradost.cloudstream4.feature.library.LibraryViewModel
import com.lagradost.cloudstream4.feature.library.desktop.LibraryScreenDesktop
import com.lagradost.cloudstream4.feature.settings.AppearanceSettings
import com.lagradost.cloudstream4.search.PosterImage

/**
 * The library screen, in the window.
 *
 * It asks the store for a refresh every time it is shown, because something was very likely played
 * since the last time: the ViewModel outlives the tab so that changing tab is instant, which means it
 * cannot notice on its own.
 *
 * @param setEscape told what Escape should do now, as `HomeHost` is: close the panel that is open (the list
 *   picker, Sort or the source), or nothing.
 * @param onOpen opens a title's page.
 * @param onResume opens a title's page and asks for the sources of the episode it was left on.
 */
@Composable
internal fun LibraryHost(
    viewModel: LibraryViewModel,
    setEscape: ((() -> Unit)?) -> Unit,
    onOpen: (LibraryTitleId) -> Unit,
    onResume: (LibraryTitleId, LibraryResume) -> Unit,
    onSearch: () -> Unit,
    appearance: AppearanceSettings = AppearanceSettings.Default,
) {
    val state by viewModel.state.collectAsState()
    val posterSize by appearance.posterSize.collectAsState()
    LaunchedEffect(viewModel) { viewModel.onAction(LibraryAction.Refresh) }
    SideEffect {
        // Whichever panel is open, the one on top first: the list picker can only be opened with the others shut.
        val closes = when {
            state.picking != null -> LibraryAction.PickerDismissed
            state.sortOpen -> LibraryAction.SortDismissed
            state.sourcesOpen -> LibraryAction.SourcesDismissed
            else -> null
        }
        setEscape(closes?.let { action -> { viewModel.onAction(action) } })
    }
    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is LibraryEffect.OpenTitle -> onOpen(effect.id)
                is LibraryEffect.ResumeTitle -> onResume(effect.id, effect.resume)
                LibraryEffect.OpenSearch -> onSearch()
            }
        }
    }
    LibraryScreenDesktop(
        state = state,
        onAction = viewModel::onAction,
        now = System::currentTimeMillis,
        // Only public http(s) addresses were stored, and the store checks again on the way out.
        artwork = { card -> PosterImage(url = card.posterUrl, modifier = Modifier.fillMaxSize()) },
        posterSize = posterSize,
    )
}

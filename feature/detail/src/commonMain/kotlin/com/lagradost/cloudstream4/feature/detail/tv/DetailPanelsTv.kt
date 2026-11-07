package com.lagradost.cloudstream4.feature.detail.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanel
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanelHost
import com.lagradost.cloudstream4.core.designsystem.rows.ListRow
import com.lagradost.cloudstream4.core.designsystem.rows.ListRowState
import com.lagradost.cloudstream4.core.designsystem.rows.RailHeader
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.action_add_to_bookmarks
import com.lagradost.cloudstream4.core.strings.generated.resources.go_back
import com.lagradost.cloudstream4.core.strings.generated.resources.synopsis
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.detail.DetailAction
import com.lagradost.cloudstream4.feature.detail.DetailState
import com.lagradost.cloudstream4.feature.detail.DetailWatchStatus
import com.lagradost.cloudstream4.feature.detail.label
import org.jetbrains.compose.resources.stringResource

/**
 * The page under its two side panels — the full synopsis and the watch-status picker.
 *
 * The 280dp panel is what replaces every dialog this cluster touches: the plot `AlertDialog`, the
 * full-screen source picker and the 400dp episode overlay. It is also why neither panel grows the page:
 * the board fixes the action row at one y, and an expanding plot would move it.
 *
 * `SidePanelHost` owns the focus trap, BACK, and handing focus back to the control that opened the
 * panel, so nothing here wires any of that.
 *
 * ## Both panels' open-ness is in [DetailState], and it has to be
 *
 * `SidePanelHost` consumes BACK as a Compose key event on the focused panel, and its KDoc says a host
 * "should also wire its platform back … This module deliberately does not". On `Television_720p` an
 * injected BACK reaches the activity first and finishes it, which is how
 * `theWatchStatusPickerOpensIsTrappedAndCommitsOnARowPress` failed with "No compose hierarchies found"
 * — twice, including once through the same `SOURCE_HDMI` injection every other key in that suite uses.
 * So the host has to intercept platform back, and to gate a callback on it the host needs to see that a
 * panel is open: [DetailState.panelOpen]. Keeping the picker in composition state made that impossible.
 * This is the first `SidePanelHost` caller in the app, so the gap surfaced on first use; every later
 * panel host (`TV Search` 4d, `TV Library` 5d and 5e, the player's 7e) needs the same wiring.
 *
 * @param content the page, handed the opener for the watch-status picker.
 */
@Composable
internal fun DetailPanelsTv(
    state: DetailState,
    onAction: (DetailAction) -> Unit,
    content: @Composable (openWatchStatus: () -> Unit) -> Unit,
) {
    val detail = state.detail
    val plot = detail?.plot.orEmpty()
    // A picker for a title that has gone, or for a host with no watch lists, has nothing to offer.
    val picking = state.watchStatusPicking && detail?.watchStatus != null
    val opening = when {
        picking -> DetailPanelKind.WatchStatus
        state.synopsisOpen && plot.isNotBlank() -> DetailPanelKind.Synopsis
        else -> null
    }
    // The panel that is leaving keeps the identity it had. `SidePanelHost` composes `panel` "while
    // open and while sliding", and by the time the 240ms exit runs, the state that closed the panel
    // has already gone — so a lambda that reads the live state draws the *other* panel all the way
    // out. Dismissing the picker slid a "Synopsis" header and a "Go back" row out in its place, and
    // the subtree holding the row the viewer had just pressed was disposed a frame into the exit.
    //
    // Latched rather than held in snapshot state: it is written while composing and read in the same
    // pass, and nothing needs to recompose because of the write — the state change that closed the
    // panel already did that. A snapshot write here would be a backwards write.
    val latch = remember { PanelLatch() }
    if (opening != null) latch.kind = opening
    val showing = opening ?: latch.kind
    val openers = remember { DetailPanelOpeners() }

    SidePanelHost(
        expanded = opening != null,
        onDismissRequest = { onAction(DetailAction.PanelDismissed) },
        // Each control is nested in the action row, inside the page's lazy column: `SidePanelHost` cannot find it
        // without being told (`BUG-36`).
        opener = when (showing) {
            DetailPanelKind.WatchStatus -> openers.watchStatus
            DetailPanelKind.Synopsis -> openers.synopsis
        },
        panel = {
            when (showing) {
                DetailPanelKind.WatchStatus -> WatchStatusPanel(
                    current = detail?.watchStatus,
                    // The pick is the commit and also the dismiss: see the ViewModel.
                    onPick = { status -> onAction(DetailAction.WatchStatusPicked(status)) },
                )

                DetailPanelKind.Synopsis ->
                    SynopsisPanel(plot = plot, onClose = { onAction(DetailAction.PanelDismissed) })
            }
        },
        content = {
            CompositionLocalProvider(LocalDetailPanelOpeners provides openers) {
                content { onAction(DetailAction.WatchStatusPickerOpened) }
            }
        },
    )
}

/** The two controls that open [DetailPanelsTv]'s panels, which get focus back when they close. */
internal class DetailPanelOpeners {
    val watchStatus = FocusRequester()
    val synopsis = FocusRequester()
}

/**
 * [DetailPanelOpeners] for the action row, which is three composables below the host. Null outside a host, where a
 * control has no panel to give focus back from.
 */
internal val LocalDetailPanelOpeners = staticCompositionLocalOf<DetailPanelOpeners?> { null }

/** Which of the two panels [DetailPanelsTv] is showing, or was showing while one slides out. */
private enum class DetailPanelKind { Synopsis, WatchStatus }

/** Holds the panel kind across the exit, when the state that chose it has already been cleared. */
private class PanelLatch {
    var kind: DetailPanelKind = DetailPanelKind.Synopsis
}

/**
 * The plot in full, which the page's two-line clamp hides.
 *
 * The header reads `synopsis` ("Synopsis"): nothing in `:core:strings` says "Full synopsis". The bottom
 * row reads `go_back`, because that is exactly what it does and the kit's own `Done` has no key either.
 * Provider text goes into a Compose `BasicText` and never into an HTML parser, so there is nothing to
 * sanitise and nothing that could be asked to render markup.
 */
@Composable
private fun SynopsisPanel(plot: String, onClose: () -> Unit) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    SidePanel(
        header = { RailHeader(title = stringResource(Res.string.synopsis)) },
        commit = {
            ListRow(
                state = ListRowState(title = stringResource(Res.string.go_back)),
                onClick = onClose,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            BasicText(
                text = plot,
                style = remember(typography, colors) { typography.body.copy(color = colors.ink2) },
            )
        }
    }
}

/**
 * The watch lists, in the order `DetailWatchStatus` offers them.
 *
 * A single-choice panel under `DEC-17`: pressing a row **is** the commit, so there is no Done row and
 * nothing for a dismiss to swallow. The row the title is already on carries the selected marker.
 */
@Composable
private fun WatchStatusPanel(current: DetailWatchStatus?, onPick: (DetailWatchStatus) -> Unit) {
    SidePanel(
        header = { RailHeader(title = stringResource(Res.string.action_add_to_bookmarks)) },
    ) {
        DetailWatchStatus.entries.forEach { status ->
            ListRow(
                state = ListRowState(title = status.label(), selected = status == current),
                onClick = { onPick(status) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

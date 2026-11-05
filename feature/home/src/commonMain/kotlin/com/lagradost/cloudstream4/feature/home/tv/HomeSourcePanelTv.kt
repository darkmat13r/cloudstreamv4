package com.lagradost.cloudstream4.feature.home.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanel
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanelDefaults
import com.lagradost.cloudstream4.core.designsystem.rows.ListRow
import com.lagradost.cloudstream4.core.designsystem.rows.ListRowState
import com.lagradost.cloudstream4.core.designsystem.rows.RailHeader
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.pick_source
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.feature.home.HomeAction
import com.lagradost.cloudstream4.feature.home.HomeState
import org.jetbrains.compose.resources.stringResource

/**
 * Choosing a source. No board draws this for home, so it is what desktop has, a panel from the right, built from the same
 * parts as `TV Search` 4d.
 *
 * `DEC-17`: a single choice applies at once with no buttons, and Back closes it. It opens on the chosen source.
 */
@Composable
internal fun HomeSourcePanelTv(state: HomeState, onAction: (HomeAction) -> Unit) {
    val chosen = state.sources.indexOfFirst { it.id == state.selected }.coerceAtLeast(0)
    val list = rememberLazyListState(chosen)
    // As it opened: a choice changes `selected` while the panel slides away, and the row must not then ask for focus.
    val landOn = remember { state.selected }
    SidePanel(header = { RailHeader(title = stringResource(Res.string.pick_source)) }) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(SidePanelDefaults.RowGap),
            // A focused row's ring needs this much room, and the list clips at its own edge.
            contentPadding = PaddingValues(CloudStreamDimens.FocusRingBleed),
        ) {
            items(items = state.sources, key = { it.id.value }) { option ->
                val requester = remember { FocusRequester() }
                // After `SidePanelHost` has moved focus into the panel, which lands on the first row it finds.
                LaunchedEffect(Unit) {
                    if (option.id == landOn) {
                        withFrameNanos { }
                        requester.requestFocusIfAttached()
                    }
                }
                ListRow(
                    modifier = Modifier.focusRequester(requester),
                    state = ListRowState(
                        title = option.name,
                        subtitle = option.language?.takeIf { it.isNotBlank() },
                        selected = option.id == state.selected,
                    ),
                    onClick = { onAction(HomeAction.SourceSelected(option.id)) },
                )
            }
        }
    }
}

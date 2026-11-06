package com.lagradost.cloudstream4.feature.search.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanel
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanelDefaults
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusDot
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusDotState
import com.lagradost.cloudstream4.core.designsystem.rows.ListRow
import com.lagradost.cloudstream4.core.designsystem.rows.ListRowState
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_sources_label
import com.lagradost.cloudstream4.core.strings.generated.resources.setup_done
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.search.SearchAction
import com.lagradost.cloudstream4.feature.search.SearchSourceChoice
import com.lagradost.cloudstream4.feature.search.SearchSourceHealth
import com.lagradost.cloudstream4.feature.search.SearchSources
import com.lagradost.cloudstream4.feature.search.SourcePanelState
import com.lagradost.cloudstream4.feature.search.sourcePanelMetaText
import org.jetbrains.compose.resources.stringResource

/**
 * Artboard **4d**: the source picker, a multi-select side panel that applies on Done (`DEC-17`).
 *
 * Back is [com.lagradost.cloudstream4.core.designsystem.panel.SidePanelHost]'s, and it discards. A row
 * press only changes the draft, so the header's count moves and nothing is written.
 *
 * The list can outgrow the panel and scrolls. Its viewport is pulled up one ring's bleed, and given the
 * same padding back, so the first row stays at `SidePanelFirstRowY` and its ring is not cut off by the
 * viewport, which is `BUG-37`'s clipping.
 *
 * @param total the chrome row's second number, so the panel and the chip it opened from agree.
 */
@Composable
internal fun SearchSourcePanel(
    panel: SourcePanelState,
    sources: SearchSources,
    onAction: (SearchAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    SidePanel(
        modifier = modifier,
        header = { PanelHeader(enabled = panel.draft.size, total = sources.total) },
        commit = {
            ListRow(
                state = ListRowState(title = stringResource(Res.string.setup_done)),
                onClick = { onAction(SearchAction.SourcePanelDone) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(ROWS_WEIGHT)
                .fillMaxWidth()
                .offset(y = -CloudStreamDimens.FocusRingBleed),
            contentPadding = PaddingValues(vertical = CloudStreamDimens.FocusRingBleed),
            verticalArrangement = Arrangement.spacedBy(SidePanelDefaults.RowGap),
        ) {
            items(items = panel.choices, key = { it.id }) { choice ->
                SourceRow(choice = choice, selected = choice.id in panel.draft, onAction = onAction)
            }
        }
    }
}

/** `.ptitle` over `.pmeta`, 8px apart on the board. */
@Composable
private fun PanelHeader(enabled: Int, total: Int) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    Column {
        Text(
            text = stringResource(Res.string.search_tv_sources_label),
            style = typography.railTitle,
            color = colors.ink,
            maxLines = 1,
        )
        Spacer(Modifier.height(CloudStreamDimens.StackGap))
        Text(
            text = sourcePanelMetaText(enabled = enabled, total = total),
            style = typography.captionMono,
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SourceRow(
    choice: SearchSourceChoice,
    selected: Boolean,
    onAction: (SearchAction) -> Unit,
) {
    ListRow(
        state = ListRowState(title = choice.name, subtitle = choice.detail, selected = selected),
        onClick = { onAction(SearchAction.SourceToggled(choice.id)) },
        modifier = Modifier.fillMaxWidth(),
        trailing = {
            StatusDot(
                state = when (choice.health) {
                    SearchSourceHealth.Unknown -> StatusDotState.Neutral
                    SearchSourceHealth.Ok -> StatusDotState.Ok
                    SearchSourceHealth.Failed -> StatusDotState.Failed
                },
            )
        },
    )
}

private const val ROWS_WEIGHT = 1f

package com.lagradost.cloudstream4.feature.player.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanel
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanelDefaults
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusDot
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusDotState
import com.lagradost.cloudstream4.core.designsystem.rows.ListRow
import com.lagradost.cloudstream4.core.designsystem.rows.ListRowState
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_offline_pill
import com.lagradost.cloudstream4.core.strings.generated.resources.player_panel_on
import com.lagradost.cloudstream4.core.strings.generated.resources.player_panel_open
import com.lagradost.cloudstream4.core.strings.generated.resources.player_panel_use
import com.lagradost.cloudstream4.core.strings.generated.resources.player_source_playing
import com.lagradost.cloudstream4.core.strings.generated.resources.player_source_retry
import com.lagradost.cloudstream4.core.strings.generated.resources.player_source_switch
import com.lagradost.cloudstream4.core.strings.generated.resources.setup_done
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.player.PanelRow
import com.lagradost.cloudstream4.feature.player.PlaybackPanelState
import com.lagradost.cloudstream4.feature.player.PlayerHudAction
import com.lagradost.cloudstream4.feature.player.RowVerb
import org.jetbrains.compose.resources.stringResource

/**
 * Artboard **7e** of `design/TV Detail Player.dc.html`: the Source, Subtitles and Audio panels over still-playing video.
 *
 * - **Pressing a row is the commit.** There is no Apply and no Cancel, so nothing is pending for a dismiss to swallow:
 *   each row carries its own verb (`Playing`, `Switch`, `Retry`, `On`, `Use`, `Open`).
 * - **The active row is marked three ways**, the chassis row's marker and fill and its `Playing` or `On` verb, because a
 *   playing link the quality profile filtered out used to check nothing at all (7e's note).
 * - **A failed source keeps its row**, dimmed with the failed marker; pressing it retries.
 * - `Done` is pinned at the panel's foot. BACK is the host's: up a level from a nested list, or closed.
 *
 * Rows are `SidePanel`'s, on its 60dp pitch from `SidePanelFirstRowY`; more than fit scroll.
 */
@Composable
fun PlaybackPanelTv(
    panel: PlaybackPanelState,
    onAction: (PlayerHudAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    SidePanel(
        modifier = modifier,
        header = { PanelHeader(panel) },
        commit = {
            ListRow(
                state = ListRowState(title = stringResource(Res.string.setup_done)),
                onClick = { onAction(PlayerHudAction.PanelDone) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .offset(y = -CloudStreamDimens.FocusRingBleed),
            contentPadding = PaddingValues(vertical = CloudStreamDimens.FocusRingBleed),
            verticalArrangement = Arrangement.spacedBy(SidePanelDefaults.RowGap),
        ) {
            items(items = panel.rows, key = { it.key }) { row ->
                PanelRowItem(row, onPress = { onAction(PlayerHudAction.PanelRowPressed(panel.kind, row.key)) })
            }
        }
    }
}

/** `.ptitle` over the meta lines, or the offline banner in their place: no row moves. */
@Composable
private fun PanelHeader(panel: PlaybackPanelState) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    Column(verticalArrangement = Arrangement.spacedBy(CloudStreamDimens.StackGap)) {
        Text(panel.title, style = typography.railTitle, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (panel.offline) {
            Row(horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.ListRowPadding), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(state = StatusDotState.Neutral)
                Text(stringResource(Res.string.downloads_offline_pill), style = typography.label, color = colors.ink, maxLines = 1)
            }
        }
        for (line in panel.meta) {
            Text(line, style = typography.label, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PanelRowItem(row: PanelRow, onPress: () -> Unit) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    ListRow(
        state = ListRowState(title = row.title, subtitle = row.detail, selected = row.active),
        onClick = onPress,
        modifier = Modifier.fillMaxWidth(),
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.ListRowPadding), verticalAlignment = Alignment.CenterVertically) {
                if (row.failed) StatusDot(state = StatusDotState.Failed)
                Text(
                    stringResource(verbText(row.verb)),
                    style = typography.labelMono,
                    color = if (row.active) colors.ink else colors.ink2,
                    maxLines = 1,
                )
            }
        },
    )
}

private fun verbText(verb: RowVerb) = when (verb) {
    RowVerb.Playing -> Res.string.player_source_playing
    RowVerb.Switch -> Res.string.player_source_switch
    RowVerb.Retry -> Res.string.player_source_retry
    RowVerb.On -> Res.string.player_panel_on
    RowVerb.Use -> Res.string.player_panel_use
    RowVerb.Open -> Res.string.player_panel_open
}

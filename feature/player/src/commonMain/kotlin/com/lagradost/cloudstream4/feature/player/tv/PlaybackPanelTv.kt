package com.lagradost.cloudstream4.feature.player.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanel
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanelDefaults
import com.lagradost.cloudstream4.core.designsystem.primitive.HairlineDivider
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusDot
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusDotState
import com.lagradost.cloudstream4.core.designsystem.rows.ListRow
import com.lagradost.cloudstream4.core.designsystem.rows.ListRowState
import com.lagradost.cloudstream4.core.designsystem.scroll.RowScroll
import com.lagradost.cloudstream4.core.designsystem.scroll.RowScrollSpec
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
 * Rows are `SidePanel`'s, on its 60dp pitch from `SidePanelFirstRowY`; more than fit scroll, by whole rows ([RowScroll]).
 *
 * **Focus lands on the active row**, what plays or the track in use, or the first row when nothing is. The host puts
 * focus into the panel on the frame it opens, before a lazy list has composed its rows, which left it on `Done`
 * (measured); so the panel asks again once its rows exist.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaybackPanelTv(
    panel: PlaybackPanelState,
    onAction: (PlayerHudAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = remember { FocusRequester() }
    val density = LocalDensity.current
    val bleed = with(density) { CloudStreamDimens.FocusRingBleed.roundToPx() }
    val gap = with(density) { SidePanelDefaults.RowGap.roundToPx() }
    // The same rounding `spacedBy` and the row's own height get, so a pitch here is a pitch on screen.
    val rowHeight = with(density) { CloudStreamDimens.ListRowHeight.roundToPx() }
    val pitch = rowHeight + gap
    val scrollSpec = remember(bleed, rowHeight, pitch) {
        RowScrollSpec(bleed.toFloat(), bleed.toFloat(), rowHeight.toFloat(), pitch.toFloat())
    }
    val entryKey = panel.rows.firstOrNull { it.active }?.key ?: panel.rows.firstOrNull()?.key
    LaunchedEffect(panel.kind, panel.title, panel.nested) {
        withFrameNanos { }
        runCatching { entry.requestFocus() }
    }
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
        // One child of the rows slot, list and rule together: the slot spaces its children `RowGap` apart, which would
        // cost the list 12dp (Quick Search's panel found it the hard way).
        Column(Modifier.weight(1f).fillMaxWidth()) {
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .bleedPastSlot(CloudStreamDimens.FocusRingBleed),
            ) {
                // Scrolling is RowScroll's, as Quick Search's and the downloads list's: a row that fits does not move
                // the list, and one that does not moves it by whole rows, so no row is cut against the header or rule.
                val endPadding = RowScroll.listEndPadding(constraints.maxHeight, panel.rows.size, bleed, bleed, pitch, gap)
                CompositionLocalProvider(LocalBringIntoViewSpec provides scrollSpec) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = CloudStreamDimens.FocusRingBleed,
                            bottom = with(density) { endPadding.toDp() },
                        ),
                        verticalArrangement = Arrangement.spacedBy(SidePanelDefaults.RowGap),
                    ) {
                        items(items = panel.rows, key = { it.key }) { row ->
                            PanelRowItem(
                                row,
                                onPress = { onAction(PlayerHudAction.PanelRowPressed(panel.kind, row.key)) },
                                modifier = if (row.key == entryKey) Modifier.focusRequester(entry) else Modifier,
                            )
                        }
                    }
                }
            }
            // 7e's `.rule` at y880: five rows show whole above it, and a sixth scrolls in under the pinned Done.
            Box(Modifier.fillMaxWidth().height(PlaybackPanelDefaults.RuleGap * 2)) {
                HairlineDivider(Modifier.offset(y = PlaybackPanelDefaults.RuleGap))
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
private fun PanelRowItem(row: PanelRow, onPress: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    ListRow(
        state = ListRowState(title = row.title, subtitle = row.detail, selected = row.active),
        onClick = onPress,
        modifier = modifier.fillMaxWidth(),
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

/** Measures [bleed] taller at each end than the slot and is placed [bleed] up, so a ring at either end is not cut. */
private fun Modifier.bleedPastSlot(bleed: Dp): Modifier = layout { measurable, constraints ->
    val extra = bleed.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(
            minHeight = constraints.minHeight + 2 * extra,
            maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + 2 * extra else constraints.maxHeight,
        ),
    )
    layout(placeable.width, placeable.height - 2 * extra) { placeable.place(0, -extra) }
}

/** Literals the panels own. Artboard px / 2 = dp. */
internal object PlaybackPanelDefaults {
    /** Each side of 7e's `.rule`: the fifth row ends at y832, the rule is at y880 and Done at y928. */
    val RuleGap: Dp = 24.dp
}

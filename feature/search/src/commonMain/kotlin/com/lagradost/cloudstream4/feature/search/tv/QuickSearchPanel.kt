package com.lagradost.cloudstream4.feature.search.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanel
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanelDefaults
import com.lagradost.cloudstream4.core.designsystem.primitive.HairlineDivider
import com.lagradost.cloudstream4.core.designsystem.rows.ListRow
import com.lagradost.cloudstream4.core.designsystem.rows.ListRowState
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.search_quick_empty_body
import com.lagradost.cloudstream4.core.strings.generated.resources.search_quick_empty_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.search_quick_meta_answered
import com.lagradost.cloudstream4.core.strings.generated.resources.search_quick_meta_answering
import com.lagradost.cloudstream4.core.strings.generated.resources.search_quick_open_full
import com.lagradost.cloudstream4.core.strings.generated.resources.search_quick_status
import com.lagradost.cloudstream4.core.strings.generated.resources.search_quick_title
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_failed_body
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_failed_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_offline_body
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_offline_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_retry
import com.lagradost.cloudstream4.core.strings.generated.resources.setup_done
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.feature.search.QuickSearchAction
import com.lagradost.cloudstream4.feature.search.QuickSearchProgress
import com.lagradost.cloudstream4.feature.search.QuickSearchState
import com.lagradost.cloudstream4.feature.search.SearchResultItem
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Artboards **8d** and **8e** of `design/TV Search Suggestions.dc.html`: Quick Search as a side panel over the screen it
 * was opened from, which does not reflow.
 *
 * - **8d, answering.** The header counts the sources as they answer and the rows fill in under it. Focus moves to the
 *   first row when there is one; until then the only stop is `Done`.
 * - **8e, empty.** Every source answered and none carries the query. The commit row is `Open full search`, which is
 *   the action that can change the outcome, and it takes the focus.
 * - **Every source failed** says so, offline or not, and its commit row is `Try again`.
 *
 * Rows use the chassis disc, as the board's note says: a backdrop does not fit a 48dp row.
 */
@Composable
fun QuickSearchPanel(
    state: QuickSearchState,
    onAction: (QuickSearchAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = state.progress
    val outcome = progress.outcome()
    val commitFocus = remember { FocusRequester() }
    SidePanel(
        modifier = modifier,
        header = { PanelHeader(progress) },
        commit = {
            val (label, action) = when (outcome) {
                Outcome.Empty -> Res.string.search_quick_open_full to QuickSearchAction.FullSearchRequested
                Outcome.Failed, Outcome.Offline -> Res.string.search_state_retry to QuickSearchAction.Retry
                Outcome.Answering, Outcome.Rows -> Res.string.setup_done to QuickSearchAction.Done
            }
            ListRow(
                state = ListRowState(title = stringResource(label)),
                onClick = { onAction(action) },
                modifier = Modifier.fillMaxWidth().focusRequester(commitFocus),
            )
        },
    ) {
        when (outcome) {
            Outcome.Answering, Outcome.Rows -> ResultRows(progress?.items.orEmpty(), onAction)
            Outcome.Empty -> PanelState(
                glyph = CloudStreamIcons.Search,
                headline = stringResource(Res.string.search_quick_empty_headline),
                body = UiString.Resource(Res.string.search_quick_empty_body, listOf(state.query)).resolve(),
            )
            Outcome.Failed -> PanelState(
                glyph = CloudStreamIcons.Warning,
                headline = stringResource(Res.string.search_state_failed_headline),
                body = stringResource(Res.string.search_state_failed_body),
            )
            Outcome.Offline -> PanelState(
                glyph = CloudStreamIcons.Offline,
                headline = stringResource(Res.string.search_state_offline_headline),
                body = stringResource(Res.string.search_state_offline_body),
            )
        }
    }
    // A state with no rows has one stop, and it is the one that does something about it.
    LaunchedEffect(outcome) {
        if (outcome == Outcome.Empty || outcome == Outcome.Failed || outcome == Outcome.Offline) commitFocus.requestFocus()
    }
}

private enum class Outcome { Answering, Rows, Empty, Failed, Offline }

private fun QuickSearchProgress?.outcome(): Outcome = when {
    this == null || !finished -> if (this?.items.isNullOrEmpty()) Outcome.Answering else Outcome.Rows
    items.isNotEmpty() -> Outcome.Rows
    answered > 0 -> Outcome.Empty
    offline -> Outcome.Offline
    else -> Outcome.Failed
}

/** `.ptitle`, `.pmeta`, `.pstat`. The meta line says what is true: it is left out once a source has failed. */
@Composable
private fun PanelHeader(progress: QuickSearchProgress?) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    Column(verticalArrangement = Arrangement.spacedBy(QuickSearchPanelDefaults.HeaderLineGap)) {
        Text(
            text = stringResource(Res.string.search_quick_title),
            style = typography.railTitle,
            color = colors.ink,
            maxLines = 1,
        )
        val meta: StringResource? = when {
            progress == null || !progress.finished -> Res.string.search_quick_meta_answering
            progress.failed == 0 -> Res.string.search_quick_meta_answered
            else -> null
        }
        if (meta != null) {
            Text(
                text = stringResource(meta),
                style = typography.captionMono,
                color = colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (progress != null) {
            Text(
                text = UiString.Resource(
                    Res.string.search_quick_status,
                    listOf(progress.answered, progress.total, progress.failed),
                ).resolve(),
                style = typography.labelMono,
                color = colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The rows, then 8d's rule above the commit row. The viewport reaches one ring's bleed past its slot at both ends and
 * gives it back as padding, so the first row stays at `SidePanelFirstRowY`, five rows fit whole above the rule, and
 * neither end cuts a ring (`BUG-37`).
 */
@Composable
private fun ColumnScope.ResultRows(items: List<SearchResultItem>, onAction: (QuickSearchAction) -> Unit) {
    val first = remember { FocusRequester() }
    LazyColumn(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .bleedPastSlot(CloudStreamDimens.FocusRingBleed),
        contentPadding = PaddingValues(vertical = CloudStreamDimens.FocusRingBleed),
        verticalArrangement = Arrangement.spacedBy(SidePanelDefaults.RowGap),
    ) {
        itemsIndexed(items = items, key = { _, item -> item.id.value }) { index, item ->
            ListRow(
                state = ListRowState(title = item.title, subtitle = item.detailLine()),
                onClick = { onAction(QuickSearchAction.ResultActivated(item.id)) },
                modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                leading = { ResultDisc() },
            )
        }
    }
    PanelRule()
    // The first answer moves focus off `Done`, which was the only stop until then: 8d draws it on the first row.
    val hasRows = items.isNotEmpty()
    LaunchedEffect(hasRows) { if (hasRows) first.requestFocus() }
}

/** `VIDSRC · MOVIE · 4K`: what the board's mono line says that the item carries. Provider text is cleaned at the edge. */
private fun SearchResultItem.detailLine(): String =
    listOfNotNull(providerName, typeLabel, qualityLabel).filter { it.isNotBlank() }.joinToString(" · ").uppercase()

/** `.ldisc`: round, raised, with the glyph for what OK does on the row, which is to open the title's page. */
@Composable
private fun ResultDisc() {
    Box(
        modifier = Modifier
            .size(CloudStreamDimens.ListRowDiscSize)
            .background(CloudStreamTheme.colors.raised, CloudStreamTheme.shapes.pill),
        contentAlignment = Alignment.Center,
    ) {
        CloudStreamIcon(
            icon = CloudStreamIcons.Disclosure,
            contentDescription = null,
            size = CloudStreamIconSize.Small,
            tint = CloudStreamTheme.colors.muted,
        )
    }
}

/** 8e's glyph, headline and body, centred in the panel, then the rule. The glyph says which state it is. */
@Composable
private fun ColumnScope.PanelState(glyph: ImageVector, headline: String, body: String) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(QuickSearchPanelDefaults.StateTop))
        Box(
            modifier = Modifier
                .size(QuickSearchPanelDefaults.StateGlyphSize)
                .background(colors.raised, CloudStreamTheme.shapes.large),
            contentAlignment = Alignment.Center,
        ) {
            CloudStreamIcon(
                icon = glyph,
                contentDescription = null,
                size = CloudStreamIconSize.Large,
                tint = colors.muted,
            )
        }
        Spacer(Modifier.height(QuickSearchPanelDefaults.StateGlyphGap))
        Text(text = headline, style = typography.railTitle, color = colors.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(QuickSearchPanelDefaults.StateTextGap))
        Text(text = body, style = typography.label, color = colors.muted, textAlign = TextAlign.Center)
    }
    PanelRule()
}

/**
 * `.rule`: y880, 24dp below the fifth row and 24dp above the commit row at y928. The hairline is drawn in the gap, not
 * stacked under it, so the commit row stays on the step.
 */
@Composable
private fun PanelRule() {
    Box(Modifier.fillMaxWidth().height(QuickSearchPanelDefaults.RuleGap * 2)) {
        HairlineDivider(Modifier.offset(y = QuickSearchPanelDefaults.RuleGap))
    }
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

/** Literals this panel owns. Artboard px / 2 = dp. */
internal object QuickSearchPanelDefaults {
    /** Between `.ptitle`, `.pmeta` and `.pstat`: y64+56 → 136 and 136+24 → 176 leave 16px. Mirrors `dimens.ListRowPadding`. */
    val HeaderLineGap: Dp = 8.dp

    /** Each side of `.rule`: the fifth row ends at y832, the rule is at y880 and the commit row at y928. */
    val RuleGap: Dp = 24.dp

    /** 8e's `.svg1` at y416 against the first row's y256. */
    val StateTop: Dp = 80.dp

    /** `.svg1`, 96px. Mirrors `StateViewDefaults.GlyphSize`. */
    val StateGlyphSize: Dp = 48.dp

    /** `.svg1`'s bottom at y512 to `.svh` at y544. */
    val StateGlyphGap: Dp = 16.dp

    /** `.svh.sm`'s bottom at y600 to `.svb.sm` at y624. */
    val StateTextGap: Dp = 12.dp
}

package com.lagradost.cloudstream4.feature.search.tv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import com.lagradost.cloudstream4.core.designsystem.card.MediaCardSlots
import com.lagradost.cloudstream4.core.designsystem.card.MediaCardState
import com.lagradost.cloudstream4.core.designsystem.card.PosterCard
import com.lagradost.cloudstream4.core.designsystem.card.PosterRung
import com.lagradost.cloudstream4.core.designsystem.primitive.Badge
import com.lagradost.cloudstream4.core.designsystem.rows.RailHeader
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.feature.search.SearchAction
import com.lagradost.cloudstream4.feature.search.SearchResultGroup
import com.lagradost.cloudstream4.feature.search.SearchResultItem

/**
 * One rail per provider: artboards **4a** and **4b**.
 *
 * 4a's caption is the whole reason this is not a grid — *"One rail per provider, so you can see
 * which source answered"* — and 4b is the same layout with two of twelve providers present and the
 * shortfall reported in the chip row above.
 *
 * ## The pitch is measured, not chosen
 *
 * The design file states it: *"Rail pitch 352: header 296…352, cards 384…624, next header
 * 648…704, its cards 736…976."* Halved, and with the tokens that already carry those numbers:
 *
 * | | Artboard | dp | From |
 * |---|---|---|---|
 * | header | 296…352 | 148…176 | `RailHeaderDefaults.Height` (28dp) |
 * | header → cards | 352…384 | 176…192 | `LiftedRowClearance` (16dp) |
 * | cards | 384…624 | 192…312 | `PosterRung.Medium` (80×120dp) |
 * | cards → next header | 624…648 | 312…324 | `MinFocusGap` (12dp) |
 *
 * Horizontally the cards are spaced at `ChipRowSpacing`, for the reason in the code below: the
 * design file's CSS and its own prose disagree, and the design system has already chosen.
 *
 * `LiftedRowClearance` is 16dp and not 12 because these cards lift on focus: the design's note
 * reads *"The lift is 8, so a lifting row sits 32px below what precedes it — 24 of clearance plus
 * the 8 it rises."* 32 artboard px is 16dp, and `FocusLift` is the 4dp half of it.
 */
@Composable
internal fun SearchResultRails(
    groups: List<SearchResultGroup>,
    copy: SearchTvCopy,
    onAction: (SearchAction) -> Unit,
    artwork: @Composable (SearchResultItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap),
    ) {
        items(items = groups, key = { it.providerName }) { group ->
            ProviderRail(group = group, copy = copy, onAction = onAction, artwork = artwork)
        }
    }
}

@Composable
private fun ProviderRail(
    group: SearchResultGroup,
    copy: SearchTvCopy,
    onAction: (SearchAction) -> Unit,
    artwork: @Composable (SearchResultItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        RailHeader(
            title = group.providerName,
            count = copy.resultCount(group.items.size),
            modifier = Modifier.padding(start = SearchScreenTvDefaults.ContentInsetStart),
        )
        Spacer(Modifier.height(CloudStreamDimens.LiftedRowClearance))
        LazyRow(
            // ChipRowSpacing and not MinFocusGap: `SkeletonRail` spaces its cards at
            // ChipRowSpacing and documents it as the rail card gap, and `UiStateHost`'s KDoc warns
            // that a skeleton shaped unlike the content makes the screen jump when content lands.
            // The artboard CSS reads `.row { gap: 24px }`, which is 12dp at px/2, but the design
            // file's own prose says "a Row with 24dp spacing" — it contradicts itself, so this
            // follows the design system, which already chose. Reported in `KMP-12`.
            horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.ChipRowSpacing),
            // The rail is the one child the screen does not inset, because a `LazyRow` must carry
            // its own: content padding scrolls with the content and leaves the focus ring room at
            // the viewport edge, which an outer `padding` would clip (`PosterCard`'s KDoc).
            contentPadding = PaddingValues(
                start = SearchScreenTvDefaults.ContentInsetStart,
                end = SearchScreenTvDefaults.ContentInsetEnd,
            ),
        ) {
            items(items = group.items, key = { it.id.value }) { item ->
                ResultCard(item = item, onAction = onAction, artwork = artwork)
            }
        }
    }
}

/**
 * One 2:3 poster, with its title revealed on focus.
 *
 * The reveal is `MediaCardState.title`, which `PosterCard` draws as an overlay *inside* the card
 * rather than a row beneath it — so the rail measures the same focused or not and the pitch above
 * holds. Nothing here chooses a dp.
 *
 * The badge is the quality label when the provider gave one. `MediaCardSlots` is hoisted with
 * `remember` because its own KDoc says an instance built in a composition body allocates on every
 * recomposition and defeats skipping.
 *
 * Focus is observed with `onFocusChanged` and activation handled by a `clickable` with no
 * indication — the same pair `ListRow` uses, rather than a hand-rolled key handler. `clickable`
 * already makes the card a focus stop and already treats D-pad centre and Enter as a click; the
 * card reads focus as *state* because `MediaCardState` is hoisted and the card never observes
 * anything itself.
 */
@Composable
private fun ResultCard(
    item: SearchResultItem,
    onAction: (SearchAction) -> Unit,
    artwork: @Composable (SearchResultItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val slots = remember(item.qualityLabel) {
        val quality = item.qualityLabel
        if (quality == null) MediaCardSlots.None else MediaCardSlots(
            trailingBadge = { Badge(text = quality) },
        )
    }

    PosterCard(
        rung = PosterRung.Medium,
        state = MediaCardState(
            focused = focused,
            title = item.title,
            meta = item.dubLabel ?: item.typeLabel,
            progress = item.watchProgress,
        ),
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = null,
                indication = null,
                onClick = { onAction(SearchAction.ResultActivated(item.id)) },
            ),
        slots = slots,
    ) {
        // The artwork slot. Image loading is a platform concern and `SearchResultItem.posterUrl`
        // is deliberately a String: Coil's `AsyncImage` goes in the glue, not in commonMain.
        Box(Modifier.fillMaxSize()) { artwork(item) }
    }
}

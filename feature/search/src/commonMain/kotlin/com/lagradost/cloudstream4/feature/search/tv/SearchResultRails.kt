package com.lagradost.cloudstream4.feature.search.tv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.lagradost.cloudstream4.core.designsystem.card.MediaCardSlots
import com.lagradost.cloudstream4.core.designsystem.card.MediaCardState
import com.lagradost.cloudstream4.core.designsystem.card.PosterCard
import com.lagradost.cloudstream4.core.designsystem.card.PosterRung
import com.lagradost.cloudstream4.core.designsystem.card.SeeAllCard
import com.lagradost.cloudstream4.core.designsystem.primitive.Badge
import com.lagradost.cloudstream4.core.designsystem.rows.RailHeader
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.load_more
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.feature.search.SearchAction
import com.lagradost.cloudstream4.feature.search.SearchResultGroup
import com.lagradost.cloudstream4.feature.search.SearchResultItem
import com.lagradost.cloudstream4.feature.search.resultCountText
import org.jetbrains.compose.resources.stringResource

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
 * Horizontally the cards are spaced at `MinFocusGap`, which is the board's `.row { gap: 24px }` halved.
 *
 * `LiftedRowClearance` is 16dp and not 12 because these cards lift on focus: the design's note
 * reads *"The lift is 8, so a lifting row sits 32px below what precedes it — 24 of clearance plus
 * the 8 it rises."* 32 artboard px is 16dp, and `FocusLift` is the 4dp half of it.
 */
@Composable
internal fun SearchResultRails(
    groups: List<SearchResultGroup>,
    onAction: (SearchAction) -> Unit,
    artwork: @Composable (SearchResultItem) -> Unit,
    stops: SearchStops,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap),
    ) {
        items(items = groups, key = { it.providerName }) { group ->
            ProviderRail(group = group, onAction = onAction, artwork = artwork, stops = stops)
        }
    }
}

/**
 * One provider's cards, then the trailing card when it has another page.
 *
 * **The end of a rail is a wall** ([RailEnd]): Right there stays put. Left to geometry it found `Sources`,
 * the only thing further right on the whole screen.
 *
 * **The trailing card pages in place,** because no board draws where `See all` would go. When the page lands,
 * focus moves to its first new card. If it brought nothing and there is no more, the card goes and focus moves
 * to the last card. If it failed, the card stays and the failure rides the chip row.
 */
@Composable
private fun ProviderRail(
    group: SearchResultGroup,
    onAction: (SearchAction) -> Unit,
    artwork: @Composable (SearchResultItem) -> Unit,
    stops: SearchStops,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val landed = remember { FocusRequester() }
    // The first index a press of the trailing card is waiting for.
    var awaited by remember { mutableStateOf<Int?>(null) }
    val target = awaited?.let { first ->
        when {
            group.items.size > first -> first
            !group.hasMore -> first - 1
            else -> null
        }
    }
    LaunchedEffect(target) {
        if (target == null) return@LaunchedEffect
        listState.scrollToItem(target)
        withFrameNanos { }
        landed.requestFocus()
        awaited = null
    }
    Column(modifier) {
        RailHeader(
            title = group.providerName,
            count = resultCountText(group.items.size),
            modifier = Modifier.padding(start = SearchScreenTvDefaults.ContentInsetStart),
        )
        Spacer(Modifier.height(CloudStreamDimens.LiftedRowClearance))
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap),
            // The rail is the one child the screen does not inset, because a `LazyRow` must carry
            // its own: content padding scrolls with the content and leaves the focus ring room at
            // the viewport edge, which an outer `padding` would clip (`PosterCard`'s KDoc).
            contentPadding = PaddingValues(
                start = SearchScreenTvDefaults.ContentInsetStart,
                end = SearchScreenTvDefaults.ContentInsetEnd,
            ),
        ) {
            itemsIndexed(items = group.items, key = { _, item -> item.id.value }) { index, item ->
                ResultCard(
                    item = item,
                    onAction = onAction,
                    artwork = artwork,
                    modifier = Modifier
                        .searchStop(stops)
                        .then(if (index == target) Modifier.focusRequester(landed) else Modifier)
                        .then(if (!group.hasMore && index == group.items.lastIndex) RailEnd else Modifier),
                )
            }
            if (group.hasMore) {
                item(key = MORE_KEY) {
                    MoreCard(
                        stops = stops,
                        onClick = {
                            awaited = group.items.size
                            onAction(SearchAction.RailMoreRequested(group.providerName))
                        },
                    )
                }
            }
        }
    }
}

private val RailEnd = Modifier.focusProperties { right = FocusRequester.Cancel }

/** `SeeAllCard` at the rail's rung, saying what it does: it loads a page, and no board draws what `See all` opens. */
@Composable
private fun MoreCard(stops: SearchStops, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    SeeAllCard(
        size = PosterRung.Medium.size,
        label = stringResource(Res.string.load_more),
        focused = focused,
        modifier = Modifier
            .searchStop(stops)
            .then(RailEnd)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = null, indication = null, onClick = onClick),
    )
}

private const val MORE_KEY = "search-rail-more"

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
    // 4a's `.badges`: the quality first, then `DUB` or `SUB`, side by side at the leading corner.
    val slots = remember(item.qualityLabel, item.dubLabel) {
        val labels = listOfNotNull(item.qualityLabel, item.dubLabel)
        if (labels.isEmpty()) MediaCardSlots.None else MediaCardSlots(
            leadingBadge = {
                Row(horizontalArrangement = Arrangement.spacedBy(SearchScreenTvDefaults.BadgeGap)) {
                    labels.forEach { Badge(text = it) }
                }
            },
        )
    }

    PosterCard(
        rung = PosterRung.Medium,
        state = MediaCardState(
            focused = focused,
            title = item.title,
            // 4a's reveal is the title alone. The dub status is a badge, and the type has no place on the card.
            meta = null,
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

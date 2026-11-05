package com.lagradost.cloudstream4.feature.home.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import com.lagradost.cloudstream4.core.designsystem.card.BackdropCard
import com.lagradost.cloudstream4.core.designsystem.card.BackdropRung
import com.lagradost.cloudstream4.core.designsystem.card.MediaCardSlots
import com.lagradost.cloudstream4.core.designsystem.card.MediaCardState
import com.lagradost.cloudstream4.core.designsystem.card.PosterCard
import com.lagradost.cloudstream4.core.designsystem.card.PosterRung
import com.lagradost.cloudstream4.core.designsystem.card.SeeAllCard
import com.lagradost.cloudstream4.core.designsystem.primitive.Badge
import com.lagradost.cloudstream4.core.designsystem.rows.RailHeader
import com.lagradost.cloudstream4.core.designsystem.rows.RailHeaderDefaults
import com.lagradost.cloudstream4.core.designsystem.state.SkeletonCard
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.load_more
import com.lagradost.cloudstream4.core.strings.generated.resources.loading
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_retry
import com.lagradost.cloudstream4.core.strings.generated.resources.source_error
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.feature.home.HomeAction
import com.lagradost.cloudstream4.feature.home.HomeItem
import com.lagradost.cloudstream4.feature.home.HomeMore
import com.lagradost.cloudstream4.feature.home.HomeRow
import com.lagradost.cloudstream4.feature.home.HomeRowKey
import org.jetbrains.compose.resources.stringResource

/** A card that should take focus once it is composed. Anything that wants focus on a card goes through this. */
internal data class HomeFocusTarget(val row: HomeRowKey, val id: String)

/**
 * One rail of `TV Home` 3d: a title that is not a stop, then cards, then the trailing card.
 *
 * **The edge at the end of a rail is a wall.** Without [focusProperties] RIGHT from the last card leaves for whatever is
 * nearest in that direction, which is a card on another rail, and a person pressing RIGHT along a row has not asked for
 * that. `INF-10` measured the same thing on search. The start of a rail is not a wall, because that way is the nav rail.
 *
 * **The trailing card asks for the next page.** So a rail with nothing left to load has none: it would be a stop that
 * does nothing. While a page is loading it stays where it is and says so. When the page lands focus moves to the first
 * new card, and if the page brought nothing the card goes and focus moves to the last title.
 *
 * @param columns every title loaded is on the one line a rail has, which is what `RowMoreRequested` is told. That is
 *   also what makes each press fetch a page rather than reveal cards the row already holds.
 * @param progress how far through each title is, by id, where this device has a position for it. Drawn as the card's
 *   progress rule.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeRailTv(
    row: HomeRow,
    focusTarget: HomeFocusTarget?,
    initialCard: Int,
    initialCardOffset: Int,
    onFocusTarget: (HomeFocusTarget?) -> Unit,
    onFocusTaken: () -> Unit,
    onOpen: (HomeItem, LazyListState) -> Unit,
    stops: HomeStops,
    onAction: (HomeAction) -> Unit,
    artwork: @Composable (HomeItem) -> Unit,
    modifier: Modifier = Modifier,
    progress: Map<String, Float> = emptyMap(),
) {
    val rail = rememberLazyListState(initialCard, initialCardOffset)
    val direction = LocalLayoutDirection.current
    val density = LocalDensity.current
    val spec = remember(density) {
        with(density) {
            HomeAcrossSpec(
                start = HomeScreenTvDefaults.ContentInsetStart.toPx(),
                end = HomeScreenTvDefaults.ContentInsetEnd.toPx(),
            )
        }
    }
    // Set by the press, so a page that arrives because a sibling row asked moves no one's focus.
    var askedAt by remember { mutableStateOf<Int?>(null) }
    val more = row.more
    val itemCount = row.items.size
    val hasTrailingCard = row.hasMore || more !is HomeMore.Idle

    LaunchedEffect(askedAt, more, itemCount) {
        val asked = askedAt
        if (asked == null || more == HomeMore.Loading || more is HomeMore.Failed) return@LaunchedEffect
        askedAt = null
        when {
            itemCount > asked -> onFocusTarget(HomeFocusTarget(row.key, row.items[asked].id))
            !hasTrailingCard -> row.items.lastOrNull()?.let { onFocusTarget(HomeFocusTarget(row.key, it.id)) }
        }
    }

    Column(modifier) {
        RailHeader(
            title = row.title,
            count = (more as? HomeMore.Failed)?.let { "${stringResource(Res.string.source_error)} · ${it.detail}" },
            modifier = Modifier.padding(
                start = HomeScreenTvDefaults.ContentInsetStart,
                end = HomeScreenTvDefaults.ContentInsetEnd,
            ),
        )
        Spacer(Modifier.height(CloudStreamDimens.LiftedRowClearance))
        CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
            LazyRow(
                state = rail,
                horizontalArrangement = Arrangement.spacedBy(HomeScreenTvDefaults.CardGap),
                contentPadding = PaddingValues(
                    start = HomeScreenTvDefaults.ContentInsetStart,
                    end = HomeScreenTvDefaults.ContentInsetEnd,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusProperties {
                        onExit = {
                            val end = if (direction == LayoutDirection.Rtl) FocusDirection.Left else FocusDirection.Right
                            if (requestedFocusDirection == end) cancelFocusChange()
                        }
                    }
                    .focusGroup(),
            ) {
                items(items = row.items, key = { it.id }) { item ->
                    HomeCardTv(
                        item = item,
                        landscape = row.landscape,
                        progress = progress[item.id],
                        takeFocus = focusTarget == HomeFocusTarget(row.key, item.id),
                        onFocusTaken = onFocusTaken,
                        onOpen = { onOpen(item, rail) },
                        stops = stops,
                        artwork = artwork,
                    )
                }
                if (hasTrailingCard) {
                    item(key = TRAILING_KEY) {
                        HomeTrailingCardTv(
                            row = row,
                            stops = stops,
                            onPress = {
                                askedAt = itemCount
                                onAction(HomeAction.RowMoreRequested(row.key, columns = itemCount.coerceAtLeast(1)))
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One title, with its name revealed on focus.
 *
 * Every card keeps the same modifier chain, focus requester included. `KMP-34` measured adding one only on the card being
 * returned to: it rebuilt that card's focus node when the condition went false, which dropped the focus it had just taken.
 */
@Composable
internal fun HomeCardTv(
    item: HomeItem,
    landscape: Boolean,
    progress: Float?,
    takeFocus: Boolean,
    onFocusTaken: () -> Unit,
    onOpen: () -> Unit,
    stops: HomeStops,
    artwork: @Composable (HomeItem) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(takeFocus) {
        if (!takeFocus) return@LaunchedEffect
        // After a frame: a card composed in this one has not been placed.
        withFrameNanos { }
        requester.requestFocusIfAttached()
        onFocusTaken()
    }
    val slots = remember(item.badge) {
        val badge = item.badge
        if (badge == null) MediaCardSlots.None else MediaCardSlots(leadingBadge = { Badge(text = badge) })
    }
    val state = MediaCardState(
        focused = focused,
        title = item.title,
        progress = progress?.takeUnless { it.isNaN() }?.coerceIn(MediaCardState.PROGRESS_EMPTY, MediaCardState.PROGRESS_FULL),
    )
    val chain = Modifier
        .focusRequester(requester)
        .onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) stops.last = requester
        }
        .clickable(interactionSource = null, indication = null, onClick = onOpen)
    val image: @Composable () -> Unit = { Box(Modifier.fillMaxSize()) { artwork(item) } }
    if (landscape) {
        BackdropCard(BackdropRung.Medium, state, chain, slots, image)
    } else {
        PosterCard(PosterRung.Medium, state, chain, slots, image)
    }
}

/**
 * The trailing card, at the same rung as the cards before it.
 *
 * Its description names the row, because every rail's says the same words and a screen reader has only this to tell
 * them apart.
 */
@Composable
private fun HomeTrailingCardTv(row: HomeRow, stops: HomeStops, onPress: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val label = when (row.more) {
        HomeMore.Idle -> stringResource(Res.string.load_more)
        HomeMore.Loading -> stringResource(Res.string.loading)
        is HomeMore.Failed -> stringResource(Res.string.search_state_retry)
    }
    SeeAllCard(
        size = if (row.landscape) BackdropRung.Medium.size else PosterRung.Medium.size,
        label = label,
        focused = focused,
        modifier = Modifier
            .homeStop(stops)
            .semantics { contentDescription = "$label · ${row.title}" }
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = null, indication = null, onClick = onPress),
    )
}

/** Loading, shaped like what lands: the same title band and cards at the same pitch, so nothing jumps. */
@Composable
internal fun HomeRailSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.padding(start = HomeScreenTvDefaults.ContentInsetStart)) {
        Spacer(Modifier.height(RailHeaderDefaults.Height + CloudStreamDimens.LiftedRowClearance))
        Row(horizontalArrangement = Arrangement.spacedBy(HomeScreenTvDefaults.CardGap)) {
            repeat(SKELETON_CARDS) { SkeletonCard(rung = PosterRung.Medium.size) }
        }
    }
}

/**
 * The last stop that held focus, so coming back into the page lands on it.
 *
 * `SidePanelHost`'s `focusRestorer` is meant to do this and does not here. Measured on `Television_720p`: closing the panel,
 * and RIGHT from the nav rail, both landed on the first card. A page's view losing focus is an exit `focusRestorer` does not
 * see (`KMP-33`), and neither case gave back what had yielded it.
 */
internal class HomeStops {
    var last: FocusRequester? = null
}

/** A page's focus stop. */
@Composable
internal fun Modifier.homeStop(stops: HomeStops): Modifier {
    val requester = remember { FocusRequester() }
    return focusRequester(requester).onFocusChanged { if (it.isFocused) stops.last = requester }
}

/** Sends focus entering the page to the last stop, if that stop is still there. If it is not, the default takes over. */
internal fun Modifier.homeStopsGroup(stops: HomeStops): Modifier = focusProperties {
    onEnter = {
        val last = stops.last
        if (last != null && last.tryRequestFocus()) cancelFocusChange()
    }
}.focusGroup()

private fun FocusRequester.tryRequestFocus(): Boolean = try {
    requestFocus()
} catch (_: IllegalStateException) {
    false
}

/** A `FocusRequester` with no node attached throws, and "nothing to focus yet" is not an error here. */
internal fun FocusRequester.requestFocusIfAttached() {
    try {
        requestFocus()
    } catch (_: IllegalStateException) {
        // See above.
    }
}

private const val TRAILING_KEY = "\u0000trailing"
private const val SKELETON_CARDS = 9

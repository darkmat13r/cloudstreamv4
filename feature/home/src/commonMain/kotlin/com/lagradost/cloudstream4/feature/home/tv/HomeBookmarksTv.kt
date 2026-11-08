package com.lagradost.cloudstream4.feature.home.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.lagradost.cloudstream4.core.designsystem.card.PosterRung
import com.lagradost.cloudstream4.core.designsystem.target.Chip
import com.lagradost.cloudstream4.core.designsystem.target.ChipRow
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.error_bookmarks_text
import com.lagradost.cloudstream4.core.strings.generated.resources.home_bookmarks_no_status_body
import com.lagradost.cloudstream4.core.strings.generated.resources.home_bookmarks_no_status_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.type_completed
import com.lagradost.cloudstream4.core.strings.generated.resources.type_dropped
import com.lagradost.cloudstream4.core.strings.generated.resources.type_on_hold
import com.lagradost.cloudstream4.core.strings.generated.resources.type_plan_to_watch
import com.lagradost.cloudstream4.core.strings.generated.resources.type_watching
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.home.HomeBookmarks
import com.lagradost.cloudstream4.feature.home.HomeItem
import com.lagradost.cloudstream4.feature.home.HomeRowKey
import com.lagradost.cloudstream4.feature.home.HomeWatchStatus
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * `TV Home Extras` 3e and 3f: the bookmarks rail, its title sharing a row with the watch-status filter, then the chosen
 * status's titles, or 3f's band when no status is chosen.
 *
 * It behaves as a rail of `HomeRailTv` does: the same cards, the same wall at its end, and the same focus target, so focus
 * comes back to the card that was opened. It has no trailing card, because the old rail does not page.
 *
 * **Chips apply at once and selection does not follow focus**: a chip is pressed, not walked. Focus stays on the chip pressed,
 * as 3f draws it, and the cards change under it.
 *
 * @param dimmed 3g: offline, opening a card needs a connection, so the cards are drawn at
 *   [HomeScreenTvDefaults.OFFLINE_CARD_ALPHA]. They can still be focused and opened, and the chips work as ever.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeBookmarksTv(
    bookmarks: HomeBookmarks,
    focusTarget: HomeFocusTarget?,
    initialCard: Int,
    initialCardOffset: Int,
    onFocusTaken: () -> Unit,
    onOpen: (HomeItem, LazyListState) -> Unit,
    onFilter: (HomeWatchStatus) -> Unit,
    stops: HomeStops,
    artwork: @Composable (HomeItem) -> Unit,
    progress: Map<String, Float>,
    dimmed: Boolean,
    modifier: Modifier = Modifier,
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
    val applied = bookmarks.applied
    val shown = bookmarks.shown
    // Another status is another list, which starts at its first title. Not on the first composition, which may be a return.
    val firstStatus = remember { applied }
    LaunchedEffect(applied) {
        if (applied != firstStatus) rail.scrollToItem(0)
    }
    val wall = Modifier.focusProperties {
        onExit = {
            val end = if (direction == LayoutDirection.Rtl) FocusDirection.Left else FocusDirection.Right
            if (requestedFocusDirection == end) cancelFocusChange()
        }
    }
    Column(modifier) {
        BookmarksHeader(bookmarks, applied, onFilter, stops, wall)
        Spacer(Modifier.height(CloudStreamDimens.LiftedRowClearance))
        if (applied == null || shown.isEmpty()) {
            NoStatusBand(Modifier.contentPadding())
        } else {
            CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
                LazyRow(
                    state = rail,
                    horizontalArrangement = Arrangement.spacedBy(HomeScreenTvDefaults.CardGap),
                    contentPadding = PaddingValues(
                        start = HomeScreenTvDefaults.ContentInsetStart,
                        end = HomeScreenTvDefaults.ContentInsetEnd,
                    ),
                    modifier = Modifier.fillMaxWidth().then(wall).focusGroup(),
                ) {
                    items(items = shown, key = { it.id }) { item ->
                        // The same wrapper on every card, dimmed or not, so no card's chain changes (`HomeCardTv`).
                        Box(Modifier.graphicsLayer { alpha = if (dimmed) HomeScreenTvDefaults.OFFLINE_CARD_ALPHA else 1f }) {
                            HomeCardTv(
                                item = item,
                                landscape = false,
                                progress = progress[item.id],
                                takeFocus = focusTarget == HomeFocusTarget(HomeRowKey.Bookmarks, item.id),
                                onFocusTaken = onFocusTaken,
                                onOpen = { onOpen(item, rail) },
                                stops = stops,
                                artwork = artwork,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 3e's `.chiprow`: the title on the chips' 48dp row rather than a header of its own, which costs 20dp and not a whole chip
 * band. A chip only for a status that holds a title.
 *
 * **The chips scroll, as Library's tabs do** (`BUG-62`). The title plus five status chips measure 592dp of the row's
 * 832dp in English and 908dp in Tamil. A `Row` takes that overflow out of whatever is left when it reaches the last
 * child, so in Tamil the fifth chip was drawn as a sliver against the screen's edge, past the content's end, with its
 * word ellipsised away: still a focus stop, and unreadable. The scroll is applied from outside `ChipRow`, which says
 * to raise an overflow rather than clip one itself, and the row's start gap and end inset go **inside** the viewport
 * so a focused chip's ring has room at both ends instead of being cut by the scroll's own clip. `LibraryTabRow` is
 * the same three modifiers in the same order.
 *
 * The title is not inside the scroll: it is the row's heading, it stays where 3e draws it, and scrolling it away would
 * leave the chips captioned by nothing.
 */
@Composable
private fun BookmarksHeader(
    bookmarks: HomeBookmarks,
    applied: HomeWatchStatus?,
    onFilter: (HomeWatchStatus) -> Unit,
    stops: HomeStops,
    wall: Modifier,
) {
    val colors = CloudStreamTheme.colors
    val style = CloudStreamTheme.typography.railTitle
    Row(
        modifier = Modifier
            .padding(start = HomeScreenTvDefaults.ContentInsetStart)
            .fillMaxWidth()
            .height(CloudStreamDimens.ChipFocusCell),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = stringResource(Res.string.error_bookmarks_text),
            style = remember(style, colors) { style.copy(color = colors.ink) },
            maxLines = 1,
            modifier = Modifier.semantics { heading() },
        )
        ChipRow(
            modifier = wall
                .focusGroup()
                .weight(WEIGHT_REMAINING)
                .horizontalScroll(rememberScrollState())
                .padding(
                    start = CloudStreamDimens.ChipRowSpacing,
                    end = HomeScreenTvDefaults.ContentInsetEnd,
                ),
        ) {
            bookmarks.statuses.forEach { status ->
                Chip(
                    label = stringResource(status.words),
                    selected = status == applied,
                    onClick = { onFilter(status) },
                    modifier = Modifier.homeStop(stops),
                )
            }
        }
    }
}

/** 3f's band: the cards' height, so nothing below moves, with what to do about it. Not a stop: DOWN goes past it. */
@Composable
private fun NoStatusBand(modifier: Modifier = Modifier) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val shape = CloudStreamTheme.shapes.small
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(HomeScreenTvDefaults.BookmarksCardHeight)
            .background(colors.surface, shape)
            .border(CloudStreamDimens.Hairline, colors.line, shape),
        verticalArrangement = Arrangement.spacedBy(CloudStreamDimens.StackGap, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            text = stringResource(Res.string.home_bookmarks_no_status_headline),
            style = typography.body.copy(color = colors.ink, textAlign = TextAlign.Center),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        BasicText(
            text = stringResource(Res.string.home_bookmarks_no_status_body),
            style = typography.label.copy(color = colors.muted, textAlign = TextAlign.Center),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun Modifier.contentPadding(): Modifier = padding(
    start = HomeScreenTvDefaults.ContentInsetStart,
    end = HomeScreenTvDefaults.ContentInsetEnd,
)

/** The old chips' words (`WatchType.kt:7` in `:app`). */
private val HomeWatchStatus.words: StringResource
    get() = when (this) {
        HomeWatchStatus.Watching -> Res.string.type_watching
        HomeWatchStatus.PlanToWatch -> Res.string.type_plan_to_watch
        HomeWatchStatus.OnHold -> Res.string.type_on_hold
        HomeWatchStatus.Dropped -> Res.string.type_dropped
        HomeWatchStatus.Completed -> Res.string.type_completed
    }

/**
 * The rail's height: the 48dp chip row, the clearance, and a poster. What a state under it has to leave room for, as
 * `ContinueRailHeight` is for Continue watching.
 */
internal val BookmarksRailHeight: Dp =
    CloudStreamDimens.ChipFocusCell + CloudStreamDimens.LiftedRowClearance + HomeScreenTvDefaults.BookmarksCardHeight

/** The chips take what the title leaves, and scroll inside it. */
private const val WEIGHT_REMAINING = 1f

package com.lagradost.cloudstream4.feature.home.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.lagradost.cloudstream4.core.designsystem.card.ContinueCard
import com.lagradost.cloudstream4.core.designsystem.rows.RailHeader
import com.lagradost.cloudstream4.core.designsystem.rows.RailHeaderDefaults
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.continue_watching
import com.lagradost.cloudstream4.core.strings.generated.resources.episode_short
import com.lagradost.cloudstream4.core.strings.generated.resources.season_short
import com.lagradost.cloudstream4.core.theme.CloudStreamCardSizes
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.feature.home.HomeItem
import com.lagradost.cloudstream4.feature.home.HomeResume
import com.lagradost.cloudstream4.feature.home.HomeRowKey
import org.jetbrains.compose.resources.stringResource

/**
 * 3c's Continue watching: what this device has played, newest first, on `DS-08`'s `ContinueCard` with its progress rule.
 *
 * It behaves as a rail of `HomeRailTv` does, with the same wall at its end and the same focus target, so focus comes back
 * to the card that was opened. It has no trailing card: everything is already here.
 *
 * **Removing an entry is not here.** No board draws it; the old home does it with a long press (`KMP-40`).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeContinueTv(
    entries: List<HomeResume>,
    focusTarget: HomeFocusTarget?,
    initialCard: Int,
    initialCardOffset: Int,
    onFocusTaken: () -> Unit,
    onOpen: (HomeResume, LazyListState) -> Unit,
    stops: HomeStops,
    artwork: @Composable (HomeItem) -> Unit,
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
    Column(modifier) {
        RailHeader(
            title = stringResource(Res.string.continue_watching),
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
                items(items = entries, key = { it.id }) { entry ->
                    HomeContinueCardTv(
                        entry = entry,
                        takeFocus = focusTarget == HomeFocusTarget(HomeRowKey.Continue, entry.id),
                        onFocusTaken = onFocusTaken,
                        onOpen = { onOpen(entry, rail) },
                        stops = stops,
                        artwork = artwork,
                    )
                }
            }
        }
    }
}

/** One entry. The same modifier chain on every card, for the reason on `HomeCardTv`. */
@Composable
private fun HomeContinueCardTv(
    entry: HomeResume,
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
        withFrameNanos { }
        requester.requestFocusIfAttached()
        onFocusTaken()
    }
    val art = remember(entry) {
        HomeItem(id = entry.id, title = entry.title, posterUrl = entry.posterUrl, posterHeaders = entry.posterHeaders)
    }
    val subtitle = continueSubtitle(entry)
    ContinueCard(
        title = entry.title,
        subtitle = subtitle,
        progress = entry.progress,
        focused = focused,
        modifier = Modifier
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) stops.last = requester
            }
            // The title is what tells two cards apart; the line under it is read after it.
            .semantics { contentDescription = entry.title }
            .clickable(interactionSource = null, indication = null, onClick = onOpen),
    ) { Box(Modifier.fillMaxSize()) { artwork(art) } }
}

/**
 * 3c's `.cs`, `S2 E4 · 28m left · vidplay 1080p`, from what is stored: the part, and the source it reopens from. How long is
 * left and the quality are not: the first has no word without a line break in it, and the second is not stored.
 */
@Composable
private fun continueSubtitle(entry: HomeResume): String? {
    val season = entry.season?.let { "${stringResource(Res.string.season_short)}$it" }
    val episode = entry.episode?.let { "${stringResource(Res.string.episode_short)}$it" }
    val part = listOfNotNull(season, episode).joinToString(" ").ifEmpty { null }
    return listOfNotNull(part, entry.sourceName).joinToString(HomeScreenTvDefaults.META_SEPARATOR).ifEmpty { null }
}

/**
 * The rail's height: header, clearance, and the card's 152dp block. Mirrors `ContinueCard`'s KDoc: art
 * (`LandscapeLarge`, 108) + 4 + rule 4 + 4 + the 32dp text stack.
 */
internal val ContinueRailHeight: Dp =
    RailHeaderDefaults.Height + CloudStreamDimens.LiftedRowClearance + CloudStreamCardSizes.LandscapeLarge.height +
        CloudStreamDimens.StackGap * 3 + CloudStreamDimens.StackTitleLineBox + CloudStreamDimens.StackGap +
        CloudStreamDimens.StackSubtitleLineBox

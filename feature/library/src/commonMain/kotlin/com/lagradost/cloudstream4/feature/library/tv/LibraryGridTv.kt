package com.lagradost.cloudstream4.feature.library.tv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.lagradost.cloudstream4.core.designsystem.card.MediaCardSlots
import com.lagradost.cloudstream4.core.designsystem.card.MediaCardState
import com.lagradost.cloudstream4.core.designsystem.card.PosterCard
import com.lagradost.cloudstream4.core.designsystem.card.PosterRung
import com.lagradost.cloudstream4.core.designsystem.primitive.Badge
import com.lagradost.cloudstream4.core.designsystem.state.SkeletonCard
import com.lagradost.cloudstream4.feature.library.LibraryAction
import com.lagradost.cloudstream4.feature.library.LibraryCard
import com.lagradost.cloudstream4.feature.library.LibraryReturn
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 5a's grid: 192x288px posters, which is [PosterRung.Large], at 240px across and 320px down.
 *
 * Cells are a fixed size rather than a fixed count, so the columns come from the width the shell leaves:
 * seven at the artboard's and at the real `Television_720p` width, whichever rail is beside it. Two rows
 * and a peek of the third fit, as the board's closing note says, and reaching the third scrolls it in.
 *
 * The grid carries its inset as content padding. An outer `padding` would clip the ring of the first
 * column at the viewport's edge.
 */
@Composable
internal fun LibraryGridTv(
    cards: List<LibraryCard>,
    returnTo: LibraryReturn?,
    onAction: (LibraryAction) -> Unit,
    artwork: @Composable (LibraryCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = returnTo?.firstVisibleIndex ?: 0)

    // Puts the card on screen. The card takes focus itself, once it is composed.
    LaunchedEffect(returnTo?.id) {
        val target = returnTo?.id ?: return@LaunchedEffect
        val index = cards.indexOfFirst { it.id == target }
        if (index < 0) return@LaunchedEffect
        withFrameNanos { }
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == index }) gridState.scrollToItem(index)
    }

    LazyVerticalGrid(
        columns = GridCells.FixedSize(PosterRung.Large.size.width),
        modifier = modifier,
        state = gridState,
        contentPadding = PaddingValues(
            start = LibraryScreenTvDefaults.ContentInsetStart,
            end = LibraryScreenTvDefaults.ContentInsetEnd,
            top = LibraryScreenTvDefaults.TabToGridGap,
            bottom = LibraryScreenTvDefaults.GridBottomInset,
        ),
        horizontalArrangement = Arrangement.spacedBy(LibraryScreenTvDefaults.GridColumnGap),
        verticalArrangement = Arrangement.spacedBy(LibraryScreenTvDefaults.GridRowGap),
    ) {
        items(items = cards, key = { it.id.value }) { card ->
            LibraryGridCard(
                card = card,
                onOpen = { onAction(LibraryAction.TitleActivated(card.id, gridState.firstVisibleItemIndex)) },
                artwork = artwork,
                takeFocus = returnTo?.ready == true && card.id == returnTo.id,
                onFocusTaken = { onAction(LibraryAction.ReturnHandled) },
            )
        }
    }
}

/**
 * One poster: the kind of title top left, the title and provider revealed on focus, and how far through
 * the last-played part is.
 *
 * 5a's episode count and the user's own rating are not here: local bookmarks hold neither. The full list of
 * what this screen reduces is in `docs/tasks/kmp.md`.
 *
 * Every card keeps the same modifier chain, focus requester included. Adding or removing one only on the
 * card being returned to rebuilt its focus node when [takeFocus] went false, and dropped the focus it had
 * just taken, which on the real app sent it to the rail (measured on `Television_720p`).
 */
@Composable
private fun LibraryGridCard(
    card: LibraryCard,
    onOpen: () -> Unit,
    artwork: @Composable (LibraryCard) -> Unit,
    takeFocus: Boolean,
    onFocusTaken: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(takeFocus) {
        if (!takeFocus) return@LaunchedEffect
        // After a frame: a card composed in this one has not been placed.
        withFrameNanos { }
        focusRequester.requestFocusIfAttached()
        onFocusTaken()
    }
    val slots = remember(card.typeLabel) {
        val type = card.typeLabel
        if (type == null) MediaCardSlots.None else MediaCardSlots(leadingBadge = { Badge(text = type) })
    }
    PosterCard(
        rung = PosterRung.Large,
        state = MediaCardState(
            focused = focused,
            title = card.title,
            meta = card.providerName,
            progress = card.progress,
        ),
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = null, indication = null, onClick = onOpen),
        slots = slots,
    ) {
        Box(Modifier.fillMaxSize()) { artwork(card) }
    }
}

/** Loading, shaped like what lands: two rows of the same card at the same pitch, so nothing jumps. */
@Composable
internal fun LibraryGridSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(top = LibraryScreenTvDefaults.TabToGridGap),
        verticalArrangement = Arrangement.spacedBy(LibraryScreenTvDefaults.GridRowGap),
    ) {
        repeat(SKELETON_ROWS) {
            Row(horizontalArrangement = Arrangement.spacedBy(LibraryScreenTvDefaults.GridColumnGap)) {
                repeat(SKELETON_COLUMNS) { SkeletonCard(rung = PosterRung.Large.size) }
            }
        }
    }
}

private const val RETURN_FOCUS_RETRY_MS = 50L
private const val RETURN_FOCUS_TIMEOUT_MS = 2_000L
private const val SKELETON_ROWS = 2
private const val SKELETON_COLUMNS = 7

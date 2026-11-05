package com.lagradost.cloudstream4.feature.search.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.rows.ListRow
import com.lagradost.cloudstream4.core.designsystem.rows.ListRowDefaults
import com.lagradost.cloudstream4.core.designsystem.rows.ListRowState
import com.lagradost.cloudstream4.core.designsystem.rows.RailHeader
import com.lagradost.cloudstream4.core.designsystem.target.IconCell
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.action_remove_from_bookmarks
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_clear_all
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_recent_headline
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.search.SearchAction
import com.lagradost.cloudstream4.feature.search.SearchHistoryEntry
import com.lagradost.cloudstream4.feature.search.recentCountText
import org.jetbrains.compose.resources.stringResource

/**
 * Recent searches: artboard **4c**, *"Search — history (empty query)"*.
 *
 * This is what the screen shows before anything is typed, and it is the artboard that changed this
 * module's state model. 4c's caption: *"Today this is a blank screen for a new user and the stored
 * `searchedAt` is never shown; here every row carries when it was searched and a per-row remove,
 * with Clear all pinned at the foot rather than scrolling away."*
 *
 * ## All three are here
 *
 * - **When it was searched** — the row's subtitle. `SearchHistoryEntry.whenLabel` arrives resolved;
 *   see its KDoc for why a `Long` would not do.
 * - **A per-row remove** — `ListRow`'s `actions`, a separate stop beside the row and not nested in it. When
 *   it takes the focused row away, focus moves to the row that took its place, or the last one.
 * - **Clear all, pinned at the foot** — outside the list, so it does not scroll away. It is the last
 *   focus stop on the screen, which is where the design puts a destructive action.
 *
 * ## Two columns, as drawn
 *
 * x200…1016 and x1048…1864 on the board, at the same 120px pitch the single list had.
 *
 * ## The row is the chassis row
 *
 * `ListRow` at `ListRowHeight` (48dp = the artboard's 96), with the two-line stack that totals
 * exactly its 32dp content box. The design's cause 5 is what that box is for: *"Title 32 plus a
 * 4px gap plus subtitle 24 is 60, which centres at +18 — so every line of every list row was off
 * the step. The gap is 8, the stack is 64, the offset is 16."* Halved, that is
 * `StackTitleLineBox` 16 + `StackGap` 4 + `StackSubtitleLineBox` 12 = 32, and `ListRowDefaults`
 * derives it from the padding rather than asserting it.
 */
@Composable
internal fun SearchRecentColumn(
    entries: List<SearchHistoryEntry>,
    onAction: (SearchAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val landed = remember { FocusRequester() }
    // The index a remove left focus without a row. Cleared once it has one again.
    var removedAt by remember { mutableStateOf<Int?>(null) }
    val target = removedAt?.coerceAtMost(entries.lastIndex)?.takeIf { it >= 0 }
    LaunchedEffect(entries.size) {
        if (target == null) return@LaunchedEffect
        withFrameNanos { }
        landed.requestFocus()
        removedAt = null
    }
    Column(modifier.fillMaxSize()) {
        RailHeader(
            title = stringResource(Res.string.search_tv_recent_headline),
            count = recentCountText(entries.size),
        )

        // The viewport starts at the header's foot and the first row one ring's bleed below it. A list's
        // viewport clips what is above its top, and a spacer outside it cut the ring off (`BUG-37`).
        LazyVerticalGrid(
            columns = GridCells.Fixed(COLUMNS),
            modifier = Modifier.weight(ROWS_WEIGHT).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = CloudStreamDimens.FocusRingBleed),
            horizontalArrangement = Arrangement.spacedBy(SearchRecentDefaults.ColumnGap),
            verticalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap),
        ) {
            itemsIndexed(items = entries, key = { _, entry -> entry.query }) { index, entry ->
                RecentRow(
                    entry = entry,
                    endsTheLine = index % COLUMNS == COLUMNS - 1,
                    onAction = onAction,
                    onRemoved = { removedAt = index },
                    modifier = if (index == target) Modifier.focusRequester(landed) else Modifier,
                )
            }
        }

        Spacer(Modifier.height(CloudStreamDimens.MinFocusGap - CloudStreamDimens.FocusRingBleed))
        ClearAllRow(onClick = { onAction(SearchAction.RecentCleared) })
    }
}

@Composable
private fun RecentRow(
    entry: SearchHistoryEntry,
    endsTheLine: Boolean,
    onAction: (SearchAction) -> Unit,
    onRemoved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListRow(
        state = ListRowState(title = entry.query, subtitle = entry.whenLabel),
        onClick = { onAction(SearchAction.RecentActivated(entry.query)) },
        modifier = modifier.fillMaxWidth(),
        leading = { HistoryDisc() },
        actions = {
            IconCell(
                onClick = {
                    onRemoved()
                    onAction(SearchAction.RecentRemoved(entry.query))
                },
                // Right past the second column stays put. Left to geometry it found `Sources`.
                modifier = if (endsTheLine) Modifier.focusProperties { right = FocusRequester.Cancel } else Modifier,
            ) { focused ->
                CloudStreamIcon(
                    icon = CloudStreamIcons.Close,
                    contentDescription = stringResource(Res.string.action_remove_from_bookmarks),
                    size = CloudStreamIconSize.Small,
                    tint = if (focused) CloudStreamTheme.colors.ink else CloudStreamTheme.colors.muted,
                )
            }
        },
    )
}

/** `.ldisc`: `ListRowDiscSize`, round and raised, with the glyph for what the row is. */
@Composable
private fun HistoryDisc() {
    Box(
        modifier = Modifier
            .size(CloudStreamDimens.ListRowDiscSize)
            .background(CloudStreamTheme.colors.raised, CloudStreamTheme.shapes.pill),
        contentAlignment = Alignment.Center,
    ) {
        CloudStreamIcon(
            icon = CloudStreamIcons.History,
            contentDescription = null,
            size = CloudStreamIconSize.Small,
            tint = CloudStreamTheme.colors.muted,
        )
    }
}

/**
 * `Clear all searches`: a list row's geometry with the board's `.lt`, 24/32px, which is the `label` token.
 *
 * Not `ListRow`, because its lone title is always `body` (28/40px) and that is a choice for every list on TV.
 */
@Composable
private fun ClearAllRow(onClick: () -> Unit) {
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.medium
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(CloudStreamDimens.ListRowHeight)
            .focusProperties { right = FocusRequester.Cancel }
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
            .tvFocusRing(focused = focused, shape = shape)
            .padding(CloudStreamDimens.ListRowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(SearchRecentDefaults.MarkerInset))
        Text(
            text = stringResource(Res.string.search_tv_clear_all),
            style = CloudStreamTheme.typography.label,
            color = colors.ink,
            maxLines = 1,
            modifier = Modifier.weight(ROWS_WEIGHT),
        )
        CloudStreamIcon(
            icon = CloudStreamIcons.Delete,
            contentDescription = null,
            size = CloudStreamIconSize.Small,
            tint = if (focused) colors.ink else colors.muted,
        )
    }
}

/** The values 4c writes down. */
object SearchRecentDefaults {
    /** Between the columns: x1016 to x1048 on the board, halved. */
    val ColumnGap: Dp = 16.dp

    /** A list row's marker cell and gap, so this text lines up with theirs. `.lmark`'s 8px, halved, then `InnerGap`. */
    val MarkerInset: Dp = 4.dp + ListRowDefaults.InnerGap
}

private const val COLUMNS = 2
private const val ROWS_WEIGHT = 1f

package com.lagradost.cloudstream4.feature.search.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lagradost.cloudstream4.core.designsystem.rows.ListRow
import com.lagradost.cloudstream4.core.designsystem.rows.ListRowState
import com.lagradost.cloudstream4.core.designsystem.rows.RailHeader
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_clear_all
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_recent_headline
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
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
 * ## Two of those three are here
 *
 * - **When it was searched** — the row's subtitle. `SearchHistoryEntry.whenLabel` arrives resolved;
 *   see its KDoc for why a `Long` would not do.
 * - **Clear all, pinned at the foot** — a `ListRow` of its own, outside the `LazyColumn`, so it
 *   does not scroll away. It is the last focus stop on the screen, which is where the design puts
 *   a destructive action.
 * - **The per-row remove is not.** It needs either a glyph — forbidden by `.claude/rules/icons.md`
 *   while the icon pack is `DEC-11`, unanswered — or a second focus stop nested inside a row that
 *   is itself a focus stop, and the artboard does not draw that nesting in enough detail to build
 *   it. `SearchAction.RecentRemoved` exists for when one of those two is settled; nothing emits it
 *   yet, which is better than a glyph nobody can focus.
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
    Column(modifier.fillMaxSize()) {
        RailHeader(
            title = stringResource(Res.string.search_tv_recent_headline),
            count = recentCountText(entries.size),
        )
        Spacer(Modifier.height(CloudStreamDimens.MinFocusGap))

        LazyColumn(
            modifier = Modifier.weight(ROWS_WEIGHT).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap),
        ) {
            items(items = entries, key = { it.query }) { entry ->
                ListRow(
                    state = ListRowState(title = entry.query, subtitle = entry.whenLabel),
                    onClick = { onAction(SearchAction.RecentActivated(entry.query)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(CloudStreamDimens.MinFocusGap))
        ListRow(
            state = ListRowState(title = stringResource(Res.string.search_tv_clear_all)),
            onClick = { onAction(SearchAction.RecentCleared) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val ROWS_WEIGHT = 1f

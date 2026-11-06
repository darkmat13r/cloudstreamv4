package com.lagradost.cloudstream4.feature.search.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.rows.ListRow
import com.lagradost.cloudstream4.core.designsystem.rows.ListRowState
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.search.SearchAction
import com.lagradost.cloudstream4.feature.search.SearchSuggestion
import com.lagradost.cloudstream4.feature.search.suggestionOriginText
import com.lagradost.cloudstream4.feature.search.suggestionSourcesText
import androidx.compose.material3.Text

/**
 * Suggestions while typing: artboard **8a** of `design/TV Search Suggestions.dc.html`.
 *
 * One ranked column, the board's answer to the two-column draft's dedupe contradiction: a recent search and a
 * suggestion with the same words are one row, with both origins on its second line and one count.
 *
 * Each row is the chassis `ListRow`, at the same 120px pitch as 4c (96 tall, 24 apart), with a leading disc for what
 * it is and a trailing count of the sources that carry it. The count is drawn only once every source asked has
 * answered, so a number on screen is always final, which is the board's other finding. A `0` is `muted`, as `.zero`.
 */
@Composable
internal fun SearchSuggestionColumn(
    rows: List<SearchSuggestion>,
    onAction: (SearchAction) -> Unit,
    stops: SearchStops,
    modifier: Modifier = Modifier,
) {
    // The viewport's top is the content region's, and the first row sits one ring's bleed below it, as 4c's (`BUG-37`).
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = CloudStreamDimens.FocusRingBleed),
        verticalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap),
    ) {
        items(items = rows, key = { it.query.lowercase() }) { row ->
            ListRow(
                state = ListRowState(title = row.query, subtitle = suggestionOriginText(row)),
                onClick = { onAction(SearchAction.SuggestionActivated(row.query)) },
                // A row spans the content width, so Right has nothing to reach. Left to geometry it found `Sources`.
                modifier = Modifier
                    .searchStop(stops)
                    .focusProperties { right = FocusRequester.Cancel }
                    .fillMaxWidth(),
                leading = { OriginDisc(recent = row.recentWhen != null) },
                trailing = row.sources?.let { count -> { SourceCount(count) } },
            )
        }
    }
}

/** `.ldisc`: the history glyph for a recent search, the search glyph for a suggestion. */
@Composable
private fun OriginDisc(recent: Boolean) {
    Box(
        modifier = Modifier
            .size(CloudStreamDimens.ListRowDiscSize)
            .background(CloudStreamTheme.colors.raised, CloudStreamTheme.shapes.pill),
        contentAlignment = Alignment.Center,
    ) {
        CloudStreamIcon(
            icon = if (recent) CloudStreamIcons.History else CloudStreamIcons.Search,
            contentDescription = null,
            size = CloudStreamIconSize.Small,
            // `.ldisc.rec .gl3` is `ink2`; a suggestion's is `muted`.
            tint = if (recent) CloudStreamTheme.colors.ink2 else CloudStreamTheme.colors.muted,
        )
    }
}

/** `.trail`: the count over its word, right-aligned in the board's 240px, with a row's two line boxes. */
@Composable
private fun SourceCount(count: Int) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    Box(
        Modifier.widthIn(min = SearchSuggestionDefaults.TrailWidth).height(CloudStreamDimens.StackTitleLineBox),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(
            text = suggestionSourcesText(count),
            style = typography.label,
            color = if (count == 0) colors.muted else colors.ink2,
            maxLines = 1,
            textAlign = TextAlign.End,
        )
    }
}

/** The value 8a writes down. */
object SearchSuggestionDefaults {
    /** `.trail { width: 240px }`, halved. */
    val TrailWidth: Dp = 120.dp
}

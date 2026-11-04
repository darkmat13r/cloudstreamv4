package com.lagradost.cloudstream4.feature.search.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import com.lagradost.cloudstream4.core.designsystem.state.FailureBanner
import com.lagradost.cloudstream4.core.designsystem.state.FailureNotice
import com.lagradost.cloudstream4.core.designsystem.target.Chip
import com.lagradost.cloudstream4.core.designsystem.target.ChipRow
import com.lagradost.cloudstream4.core.designsystem.target.TextField
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_field_placeholder
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_sources_label
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.feature.search.SearchAction
import com.lagradost.cloudstream4.feature.search.SearchSources
import com.lagradost.cloudstream4.feature.search.sourcesCountText
import org.jetbrains.compose.resources.stringResource

/**
 * The chrome row: the query field, and the source chip that says how many sources answer to it.
 *
 * Artboards 4a–4c, y56…152 on the artboard, which is `ChromeRowTop` (28dp) to `ChromeRowBottom`
 * (76dp) with `ChromeRowHeight` (48dp) between them.
 *
 * The field is the leading focusable on the screen and takes the remaining width; the chip is at
 * its intrinsic width and ends on `ContentEndX`. The design puts the Sources button at artboard
 * x1440–1864, and its own note records what happens when something assumes a fixed width for it:
 * *"the chip row stopped at x1416 while the Sources button spans x1440–1864, so DOWN from Sources
 * had no target at all."*
 *
 * The field itself is the design system's
 * [com.lagradost.cloudstream4.core.designsystem.target.TextField], which is where its geometry and
 * the note on the artboard's missing magnifier now live. It was this file's `private QueryField`
 * until `DS-10` moved it; nothing about how it renders changed.
 */
@Composable
internal fun SearchChromeRow(
    query: String,
    sources: SearchSources,
    onAction: (SearchAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.height(CloudStreamDimens.ChromeRowHeight),
        horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = query,
            onValueChange = { onAction(SearchAction.QueryChanged(it)) },
            placeholder = stringResource(Res.string.search_tv_field_placeholder),
            modifier = Modifier.weight(FIELD_WEIGHT),
            imeAction = ImeAction.Search,
            onSubmit = { onAction(SearchAction.Submit) },
        )
        Chip(
            label = stringResource(Res.string.search_tv_sources_label),
            selected = false,
            // Artboard 4d's side panel is what this opens, and it is not built. Until it is, the
            // chip is the readout the no-results copy quotes, not a control: an affordance that
            // looks focusable and does nothing is the defect `StateAction` exists to prevent.
            onClick = {},
            count = sourcesCountText(sources),
        )
    }
}

/**
 * The chip row, which is where a partial failure is reported.
 *
 * Artboard 4b, y176…272 — `ChipRowTop` to `ChipRowBottom`, one `ChipFocusCell` tall. The row is
 * mounted even when there is nothing to report, because it is a band of the chassis: a row that
 * appears and disappears moves the content region under the user's focus.
 *
 * The screen's filter chips are not here yet. `TV Search Suggestions` 8c's copy depends on them —
 * *"Movies only —
 * your type filter is narrowing the search"*, with an action that clears the provider **and** the
 * type filter — so they arrive with the state that models them.
 */
@Composable
internal fun SearchChipRow(
    notice: FailureNotice?,
    modifier: Modifier = Modifier,
) {
    ChipRow(modifier = modifier.height(CloudStreamDimens.ChipFocusCell)) {
        if (notice != null) FailureBanner(notice = notice)
    }
}

private const val FIELD_WEIGHT = 1f

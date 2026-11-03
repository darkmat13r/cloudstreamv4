package com.lagradost.cloudstream4.feature.search.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.designsystem.state.FailureBanner
import com.lagradost.cloudstream4.core.designsystem.state.FailureNotice
import com.lagradost.cloudstream4.core.designsystem.target.Chip
import com.lagradost.cloudstream4.core.designsystem.target.ChipRow
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_field_placeholder
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_sources_label
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
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
 * ## The field has no magnifier
 *
 * `.claude/rules/icons.md` forbids drawing one, and the icon pack is `DEC-11`, unanswered. The
 * artboard has a glyph here. Until the pack lands the field is text and its placeholder carries
 * the meaning instead — artboard 4c's own placeholder, *"Search all sources"*, does exactly that.
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
        QueryField(
            query = query,
            placeholder = stringResource(Res.string.search_tv_field_placeholder),
            onAction = onAction,
            modifier = Modifier.weight(FIELD_WEIGHT),
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

@Composable
private fun QueryField(
    query: String,
    placeholder: String,
    onAction: (SearchAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.medium

    Box(
        modifier = modifier
            .height(CloudStreamDimens.ChromeRowHeight)
            .tvFocusRing(focused = focused, shape = shape)
            .background(color = colors.surface, shape = shape)
            .border(width = CloudStreamDimens.Hairline, color = colors.line, shape = shape)
            .padding(horizontal = FieldDefaults.HorizontalPadding),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = query,
            onValueChange = { onAction(SearchAction.QueryChanged(it)) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = typography.body.copy(color = colors.ink),
            singleLine = true,
            cursorBrush = SolidColor(colors.ink),
            interactionSource = interactionSource,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onAction(SearchAction.Submit) }),
        )
        if (query.isEmpty()) {
            BasicText(
                text = placeholder,
                style = remember(typography, colors) {
                    typography.body.copy(color = colors.muted)
                },
                maxLines = SINGLE_LINE,
            )
        }
    }
}

/** The field's own geometry. See `.claude/rules/dimension-scale.md` rule 2. */
private object FieldDefaults {
    /**
     * Mirrors `CloudStreamDimens.MinFocusGap` (12dp), which is the artboard's `padding: 0 24px`
     * halved. Not `ChipDefaults.VisualHorizontalPadding`, which happens to be the same number for
     * a different reason.
     */
    val HorizontalPadding: Dp = CloudStreamDimens.MinFocusGap
}

private const val FIELD_WEIGHT = 1f
private const val SINGLE_LINE = 1

package com.lagradost.cloudstream4.core.designsystem.rows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * The title-and-subtitle stack inside a [ListRow].
 *
 * ## Rule 5, and why the gap is 4dp and not 2dp
 *
 * **A text stack must total a multiple of 16px to centre in a 96px row.** On the artboard, title
 * 32px + gap 4px + subtitle 24px is 60px, which centres in a 96px row at +18px — off the 4px
 * artboard step and therefore off the 4dp step in code. The gap is 8px, the stack is 64px, and
 * the offset is 16px.
 *
 * In dp that is [CloudStreamDimens.StackTitleLineBox] (16) + [CloudStreamDimens.StackGap] (4) +
 * [CloudStreamDimens.StackSubtitleLineBox] (12) = **32dp**, centred in a
 * [CloudStreamDimens.ListRowHeight] row at **+8dp**. A 2dp gap would make the stack 30dp and
 * centre it at +9dp — one dp off the step, invisible in a screenshot and wrong in every layout
 * that aligns to it. Do not "tighten" the gap.
 *
 * A single line centred alone needs a [CloudStreamDimens.StackSingleLineBox] (24dp, 48px) box,
 * not a 20dp (40px) one, to land at +12dp.
 *
 * ## Why the line boxes are dp, and why the subtitle is clipped rather than reflowed
 *
 * The boxes are `Dp` heights and each line is measured inside one, so the stack totals exactly
 * 32dp whatever the user's font scale is. The alternative — letting the `sp` line heights decide —
 * puts the stack at 32dp only at font scale 1.0 and takes the row off the step at every other
 * scale. The grid wins here because the row height it centres in is itself a whole number of
 * steps; the cost is that text at a very large font scale is clipped by its box rather than
 * growing the row.
 *
 * ## The title's box is a floor, because a side panel is 280dp wide (`BUG-62`)
 *
 * **Inside a side panel** the title may take a second line; the subtitle may not, and in a list
 * wide enough for one line nothing may. A panel leaves a single-line row 232dp of title (280 less
 * its 16dp inset twice and the row's 8dp padding twice), and `sort_release_date_old` measures
 * 310dp in Tamil, 283dp in Greek and 278dp in Albanian against 157dp in English — 8 of a panel's
 * 21 row titles are over in some language (measured on `Television_720p` in all 61 locales). One
 * line meant `Release Date (Old to …` with the direction, which is the whole point of the row,
 * cut off.
 *
 * Which it is comes from [LocalListRowTitleLines], which `SidePanel` provides — see that local for
 * why the surface decides this and not the row.
 *
 * So the title's box is one [CloudStreamDimens.StackTitleLineBox] taller per extra line, and the
 * row is one [ListRowDefaults.HeightStep] taller to match. A title that fits is laid out in
 * exactly the box it always was and **nothing about a one-line row changes**; both stacks still
 * centre on the step:
 *
 * | | one line | two lines |
 * |---|---|---|
 * | title alone | 24dp stack, 48dp row | 40dp stack, 64dp row |
 * | title and subtitle | 32dp stack, 48dp row | 48dp stack, 64dp row |
 *
 * A third line is not allowed: [ListRowDefaults.TITLE_LINES_IN_PANEL] is 2, and a title that
 * needs more is a string to shorten or a panel to widen, not a row to grow again.
 *
 * ## The type the boxes imply
 *
 * A 16dp box is exactly `typography.label`'s line height (24/32px) and a 12dp box is exactly
 * `typography.caption`'s (20/24px), so a two-line row is label over caption. A 24dp box holds
 * `typography.body` (28/40px) with room to centre, so a single-line row is body. That makes a
 * lone title 14sp and a title with a subtitle 12sp — see the task's return value, this reads as
 * a genuine design decision but is worth confirming.
 *
 * The subtitle inherits the ramp's open question: `caption` is 10sp, below Android TV's 12sp
 * legibility minimum at three metres. It is encoded, not resolved.
 */
@Composable
internal fun ListRowTextStack(
    title: String,
    subtitle: String?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    titleLines: Int = ListRowDefaults.TITLE_LINES,
    onTitleLines: (Int) -> Unit = {},
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val primary = if (enabled) colors.ink else colors.muted
    // `.ls` is `muted` on every board with a list row; it used to be `ink2` here.
    val secondary = colors.muted

    Column(modifier, verticalArrangement = Arrangement.Center) {
        if (subtitle == null) {
            TitleLineBox(title, CloudStreamDimens.StackSingleLineBox, typography.body, primary, titleLines, onTitleLines)
            return@Column
        }
        TitleLineBox(title, CloudStreamDimens.StackTitleLineBox, typography.label, primary, titleLines, onTitleLines)
        Spacer(Modifier.height(CloudStreamDimens.StackGap))
        FixedLineBox(
            text = subtitle,
            lineBox = CloudStreamDimens.StackSubtitleLineBox,
            style = typography.caption,
            color = secondary,
        )
    }
}

/**
 * The title's line box: [lineBox] tall for one line, and as tall as it needs for a second where
 * [LocalListRowTitleLines] allows one.
 *
 * The box is a **floor**, not a height, which is the one difference from [FixedLineBox] and the
 * whole of `BUG-62`'s fix for a narrow row — see this file's KDoc. A title that fits one line
 * measures exactly [lineBox], so every row that was 48dp still is.
 *
 * [onTitleLines] reports what the text actually did, which is how [ListRow] knows whether to be
 * 48dp or 64dp. **The line count, not the measured height, is what decides** — see
 * `ListRow.kt`'s `rowHeight`.
 */
@Composable
private fun TitleLineBox(
    text: String,
    lineBox: Dp,
    style: TextStyle,
    color: Color,
    titleLines: Int,
    onTitleLines: (Int) -> Unit,
) {
    val merged = remember(style, color) { style.copy(color = color) }
    val height = lineBox + CloudStreamDimens.StackTitleLineBox * (titleLines - ListRowDefaults.TITLE_LINES)
    Box(
        modifier = Modifier.fillMaxWidth().height(height),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            text = text,
            style = merged,
            modifier = Modifier.unboundedHeight(),
            maxLines = LocalListRowTitleLines.current,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { onTitleLines(it.lineCount) },
        )
    }
}

/**
 * Measures the text with no height limit, so it reports the lines it **would** take rather than
 * the lines that fit what it has.
 *
 * Without it the second line can never be discovered: a row is 48dp until its title says it needs
 * more, and a title measured inside a 48dp row's content box reports one line — Compose does not
 * lay out a line it has no room for. The row would stay 48dp and the title stay cut, which is the
 * state `BUG-62` is about, with the fix apparently applied.
 *
 * The box around it keeps its exact height either way, so a title that takes one line is laid out
 * in exactly the box it always had and draws exactly the pixels it always drew. A title that takes
 * two overflows this box for one frame, and the row is 64dp on the next.
 */
private fun Modifier.unboundedHeight(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(
        constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity),
    )
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}

/**
 * One line of text in a box of exactly [lineBox] height, vertically centred.
 *
 * The box, not the text, owns the height — that is what keeps the stack's total on the step.
 * [TextStyle] and [Color] are merged once per (style, colour) pair so nothing is allocated per
 * recomposition.
 *
 * This is the **subtitle**'s box. The title's is [TitleLineBox], which may grow by a line.
 */
@Composable
private fun FixedLineBox(
    text: String,
    lineBox: Dp,
    style: TextStyle,
    color: Color,
) {
    val merged = remember(style, color) { style.copy(color = color) }
    Box(
        modifier = Modifier.fillMaxWidth().height(lineBox),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            text = text,
            style = merged,
            maxLines = ListRowDefaults.SUBTITLE_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

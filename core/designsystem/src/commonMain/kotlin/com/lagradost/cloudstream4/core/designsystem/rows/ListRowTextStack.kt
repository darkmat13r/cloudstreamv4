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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

private const val SINGLE_LINE = 1

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
 * ## Why the line boxes are dp and the text is clipped rather than reflowed
 *
 * The boxes are fixed `Dp` heights and each line is measured inside one, so the stack totals
 * exactly 32dp whatever the user's font scale is. The alternative — letting the `sp` line heights
 * decide — puts the stack at 32dp only at font scale 1.0 and takes the row off the step at every
 * other scale. The grid wins here because the row height it centres in is itself fixed; the cost
 * is that text at a very large font scale is clipped by its box rather than growing the row.
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
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val primary = if (enabled) colors.ink else colors.muted
    // `.ls` is `muted` on every board with a list row; it used to be `ink2` here.
    val secondary = colors.muted

    Column(modifier, verticalArrangement = Arrangement.Center) {
        if (subtitle == null) {
            FixedLineBox(title, CloudStreamDimens.StackSingleLineBox, typography.body, primary)
            return@Column
        }
        FixedLineBox(title, CloudStreamDimens.StackTitleLineBox, typography.label, primary)
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
 * One line of text in a box of exactly [lineBox] height, vertically centred.
 *
 * The box, not the text, owns the height — that is what keeps the stack's total on the step.
 * [TextStyle] and [Color] are merged once per (style, colour) pair so nothing is allocated per
 * recomposition.
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
            maxLines = SINGLE_LINE,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

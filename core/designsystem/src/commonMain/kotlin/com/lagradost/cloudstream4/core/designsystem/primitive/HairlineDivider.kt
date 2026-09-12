package com.lagradost.cloudstream4.core.designsystem.primitive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/** The axis a [HairlineDivider] runs along. */
@Immutable
enum class HairlineOrientation {
    /** A full-width rule that separates two stacked elements. */
    Horizontal,

    /** A full-height rule that separates two side-by-side elements. */
    Vertical,
}

/**
 * A standalone [CloudStreamDimens.Hairline] rule between two elements.
 *
 * Unlike [Modifier.hairline], this **does** occupy layout — [thickness] in its own axis, and
 * nothing in the other. That is legal and intended: rule 6 of the geometry exempts a hairline's
 * thickness *in its own axis* from the 4dp step, because it is a stroke weight rather than a
 * layout dimension. The rule's cross axis is still governed — a `HairlineDivider` inside a 48dp
 * row is a 48dp-tall, 1dp-wide element, and both numbers are correct.
 *
 * Two knock-on consequences worth knowing before you reach for it:
 *
 * - **A divider in a stack shifts everything after it by [thickness].** If a spec puts the next
 *   element on the step, the divider must either be drawn with [Modifier.hairline] on a
 *   neighbouring element (free, rule 1) or the surrounding gaps must absorb the 1dp. When in
 *   doubt, prefer [Modifier.hairline]; this composable is for the case where the rule genuinely
 *   is its own item, e.g. a separator entry in a list.
 * - **It fills its cross axis by default.** Inset it by passing `Modifier.padding(...)`, using
 *   an on-step inset such as [CloudStreamDimens.ListRowPadding].
 *
 * @param modifier applied before the size and background, so a caller can constrain the rule or
 *   inset it; sizing is applied after, so the rule's own [thickness] always wins.
 * @param orientation which axis the rule runs along.
 * @param color defaults to `CloudStreamTheme.colors.line`, the design's only rule colour.
 * @param thickness the stroke weight, defaulting to [CloudStreamDimens.Hairline].
 */
@Composable
fun HairlineDivider(
    modifier: Modifier = Modifier,
    orientation: HairlineOrientation = HairlineOrientation.Horizontal,
    color: Color = CloudStreamTheme.colors.line,
    thickness: Dp = CloudStreamDimens.Hairline,
) {
    val sized = when (orientation) {
        HairlineOrientation.Horizontal -> modifier.fillMaxWidth().height(thickness)
        HairlineOrientation.Vertical -> modifier.fillMaxHeight().width(thickness)
    }
    Box(modifier = sized.background(color))
}

@Preview
@Composable
private fun HairlineDividerPreview() {
    CloudStreamTheme {
        Column(
            modifier = Modifier
                .background(CloudStreamTheme.colors.bg)
                .padding(CloudStreamDimens.SidePanelContentInset),
            verticalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CloudStreamDimens.ListRowHeight)
                    .background(CloudStreamTheme.colors.raised)
                    .hairline(HairlineSide.Bottom),
            )
            HairlineDivider()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CloudStreamDimens.ListRowHeight)
                    .background(CloudStreamTheme.colors.surface),
            )
        }
    }
}

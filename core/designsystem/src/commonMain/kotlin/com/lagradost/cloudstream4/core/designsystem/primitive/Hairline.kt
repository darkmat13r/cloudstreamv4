package com.lagradost.cloudstream4.core.designsystem.primitive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * Which edge of the element a [hairline] is drawn on. [Start] and [End] resolve against the
 * layout direction; [Top] and [Bottom] do not.
 */
@Immutable
enum class HairlineSide {
    Top,
    Bottom,
    Start,
    End,
}

/**
 * Draws a [CloudStreamDimens.Hairline] rule on one edge of this element **without consuming any
 * layout**.
 *
 * ## The contract — this is rule 1 of the CloudStream 4 geometry
 *
 * **A hairline must not consume layout.** `Modifier.border` and a 1dp padding both shift the
 * content box by 1dp, which puts everything inside it off the 4dp step: on the artboard a 560px
 * panel became 561px and started at 1359 instead of 1360. Every dimension downstream of that
 * panel then inherited the error.
 *
 * This modifier is draw-only — `drawWithCache`, no layout phase, no padding, no
 * `Modifier.border`. Applying it can never change a measured size, move a sibling, or alter an
 * intrinsic. The rule is painted **inside** the element's own box, overlapping its outermost
 * [thickness], so the element's content box is byte-for-byte what it was without the hairline.
 *
 * That overlap is deliberate and is the whole point: the hairline is *free*. If you want the
 * content to clear the rule, add that padding yourself as a real, on-step design decision — do
 * not expect the hairline to have made room.
 *
 * **Thickness is exempt from the 4dp step in its own axis.** A hairline is a stroke *weight*, not
 * a layout dimension (`.claude/rules/dimension-scale.md`). [CloudStreamDimens.Hairline] is the
 * only weight this design has; passing anything else is a design change.
 *
 * ## Canonical use
 *
 * ```kotlin
 * Row(
 *     Modifier
 *         .height(CloudStreamDimens.ChromeRowHeight)
 *         .hairline(HairlineSide.Bottom)          // theme `line` colour
 * ) { /* the row measures exactly ChromeRowHeight, hairline or not */ }
 * ```
 *
 * For a rule that stands on its own between two elements — one that *should* occupy its own
 * [thickness] of the layout — use [HairlineDivider] instead.
 *
 * @param side which edge to paint.
 * @param color the rule colour. `CloudStreamTheme.colors.line` is the only correct value in the
 *   design; the parameter exists for previews and inverse surfaces. Prefer the `@Composable`
 *   overload, which supplies it.
 * @param thickness the stroke weight. Defaults to [CloudStreamDimens.Hairline].
 */
fun Modifier.hairline(
    side: HairlineSide,
    color: Color,
    thickness: Dp = CloudStreamDimens.Hairline,
): Modifier = this.drawWithCache {
    val rect = hairlineRect(side = side, thicknessPx = thickness.toPx())
    onDrawWithContent {
        drawContent()
        drawRect(color = color, topLeft = rect.topLeft, size = rect.size)
    }
}

/**
 * [hairline] with the rule colour taken from [CloudStreamTheme]. Prefer this at call sites; see
 * the other overload for the full contract.
 */
@Composable
fun Modifier.hairline(
    side: HairlineSide,
    thickness: Dp = CloudStreamDimens.Hairline,
): Modifier = hairline(
    side = side,
    color = CloudStreamTheme.colors.line,
    thickness = thickness,
)

/**
 * The rectangle the rule occupies, always inside the element's own box so the content box is
 * unchanged. Computed once per size change in the `drawWithCache` build block, never per frame.
 */
private fun CacheDrawScope.hairlineRect(side: HairlineSide, thicknessPx: Float): Rect {
    val width = size.width
    val height = size.height
    val isLtr = layoutDirection == LayoutDirection.Ltr
    val startX = if (isLtr) ORIGIN else width - thicknessPx
    val endX = if (isLtr) width - thicknessPx else ORIGIN
    return when (side) {
        HairlineSide.Top -> Rect(Offset(ORIGIN, ORIGIN), Size(width, thicknessPx))
        HairlineSide.Bottom -> Rect(Offset(ORIGIN, height - thicknessPx), Size(width, thicknessPx))
        HairlineSide.Start -> Rect(Offset(startX, ORIGIN), Size(thicknessPx, height))
        HairlineSide.End -> Rect(Offset(endX, ORIGIN), Size(thicknessPx, height))
    }
}

private const val ORIGIN = 0f

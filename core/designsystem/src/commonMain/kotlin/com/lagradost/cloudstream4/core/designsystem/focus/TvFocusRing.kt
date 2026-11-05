package com.lagradost.cloudstream4.core.designsystem.focus

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * Draws the CloudStream 4 TV focus ring around this element.
 *
 * ## The contract — read this before writing a focusable
 *
 * **A focus ring is not a border.** It is a [CloudStreamDimens.FocusRingStroke] (2dp) stroke
 * sitting [CloudStreamDimens.FocusRingOffset] (2dp) outside the element's edge, so it occupies
 * [CloudStreamDimens.FocusRingBleed] (4dp) *entirely outside* the element's box. Concretely:
 *
 * - the inner edge of the stroke is 2dp outside the element,
 * - the outer edge of the stroke is 4dp outside the element,
 * - the centre of the stroke is 3dp outside the element, which is where this modifier puts it.
 *
 * **It must not change the element's measured size, and it does not.** This is a draw-only
 * modifier — `drawWithCache`, no layout phase, no padding, no `Modifier.border`. Drawn as a
 * transparent border, a ring of this geometry pushed a card's badge 4dp off the 4dp step; that is
 * the bug this modifier exists to make impossible. Applying it can never move a sibling, change
 * an intrinsic measurement, or alter what the parent measures.
 *
 * **Where the 4dp bleed lives.** In the shared chassis it lives inside
 * [CloudStreamDimens.FocusBandInset] (28dp): every focusable's own box lies inside the 28dp
 * margin, and the ring bleeds into the 24..28dp reserve. That is exactly why the artboard margin
 * is 56px and not 48px. It follows that **no ancestor may clip.** A ring on an element inside a
 * `Modifier.clip(...)`, a `graphicsLayer { clip = true }`, or a `LazyRow`'s viewport edge is
 * silently cut off. If you need clipping, clip a child, not the focusable.
 *
 * **Ring the thing you can see.** A chip's visual is [CloudStreamDimens.ChipVisualHeight] (24dp)
 * centred in a [CloudStreamDimens.ChipFocusCell] (48dp) cell; the cell takes focus and this
 * modifier goes on the visual, so the gap is even on all four sides (`DEC-17`, 2026-11-05). A
 * segmented option does the same, and a text button and a list row ring themselves. An icon-only
 * target rings its [CloudStreamDimens.IconTargetCell], because it has no fill to ring.
 *
 * **Corners.** [shape] is the element's own shape. The ring is drawn on a shape expanded by 3dp
 * in every direction, so a rounded element gets a ring that stays a constant 2dp away from it all
 * the way round rather than pinching at the corners. Only [CornerBasedShape]s are expanded; any
 * other [Shape] is stroked at its own corner geometry on the enlarged rect.
 *
 * ## Canonical use
 *
 * ```kotlin
 * var focused by remember { mutableStateOf(false) }
 * Box(
 *     Modifier
 *         .size(CloudStreamDimens.IconTargetCell)
 *         .onFocusChanged { focused = it.isFocused }
 *         .focusable()
 *         .tvFocusRing(focused = focused, shape = CloudStreamTheme.shapes.medium)
 * )
 * ```
 *
 * Combine with [tvFocusLift] by placing the lift **first**, so the ring travels with the element:
 * `Modifier.tvFocusLift(focused).tvFocusRing(focused)`.
 *
 * @param focused whether the ring is drawn. Hoisted state; this modifier never observes focus
 *   itself, so the caller decides whether a child's focus, or a whole group's, counts.
 * @param color the ring colour. Defaults to `CloudStreamTheme.colors.focus`, which is the only
 *   correct value in the design; the parameter exists for previews and inverse surfaces.
 * @param shape the element's own shape, expanded internally. Defaults to [RectangleShape].
 * @param stroke the ring's stroke weight. Defaults to [CloudStreamDimens.FocusRingStroke]; the
 *   design has exactly one, so overriding this is a design change.
 * @param offset the gap between the element's edge and the ring's inner edge. Defaults to
 *   [CloudStreamDimens.FocusRingOffset]; changing it changes the 4dp bleed the 28dp band pays for.
 */
fun Modifier.tvFocusRing(
    focused: Boolean,
    color: Color,
    shape: Shape = RectangleShape,
    stroke: Dp = CloudStreamDimens.FocusRingStroke,
    offset: Dp = CloudStreamDimens.FocusRingOffset,
): Modifier = this.drawWithCache {
    val strokePx = stroke.toPx()
    // The stroke straddles the path, so its centre sits offset + half a stroke outside the box.
    val centreInset = offset.toPx() + strokePx / STROKE_CENTRE_DIVISOR
    val ringSize = Size(
        width = size.width + centreInset * BOTH_SIDES,
        height = size.height + centreInset * BOTH_SIDES,
    )
    val ringOutline = shape
        .expandedBy(centreInset)
        .createOutline(ringSize, layoutDirection, this)
    val ringStyle = Stroke(width = strokePx)

    onDrawWithContent {
        drawContent()
        if (focused) {
            translate(left = -centreInset, top = -centreInset) {
                drawOutline(outline = ringOutline, color = color, style = ringStyle)
            }
        }
    }
}

/**
 * [tvFocusRing] with the ring colour taken from [CloudStreamTheme]. Prefer this at call sites;
 * see the other overload for the full contract.
 */
@Composable
fun Modifier.tvFocusRing(
    focused: Boolean,
    shape: Shape = RectangleShape,
): Modifier = tvFocusRing(
    focused = focused,
    color = CloudStreamTheme.colors.focus,
    shape = shape,
)

/**
 * Raises this element by [CloudStreamDimens.FocusLift] while [lifted].
 *
 * **The focus lift is 4dp (8px on the artboard), not 6dp.** A 12px lift takes every focused card
 * off the 4dp step. Opt in per component: not every focusable lifts, and the ones that do force
 * their row to leave [CloudStreamDimens.LiftedRowClearance] (16dp) above it — 12dp of clearance
 * plus the 4dp it rises.
 *
 * Like [tvFocusRing] this is draw-only: `graphicsLayer` translation changes nothing the layout
 * phase measured, so a lifting card cannot push its neighbours or resize its row. Place it
 * **before** [tvFocusRing] in the chain so the ring rises with the element.
 *
 * Animation is the caller's: this modifier is a hard step. Drive it from an animated boolean, or
 * pass an animated [lift] from `animateDpAsState`.
 *
 * @param lifted whether the element is currently raised.
 * @param lift how far. Defaults to [CloudStreamDimens.FocusLift]; any other fixed value is a
 *   design change, but an animated value passing through is expected.
 */
fun Modifier.tvFocusLift(
    lifted: Boolean,
    lift: Dp = CloudStreamDimens.FocusLift,
): Modifier = this.graphicsLayer {
    translationY = if (lifted) -lift.toPx() else NO_LIFT
}

private const val STROKE_CENTRE_DIVISOR = 2f
private const val BOTH_SIDES = 2f
private const val NO_LIFT = 0f

/**
 * Grows a shape outward by [radiusPx] so a stroke drawn on it stays a constant distance from the
 * original. For a [CornerBasedShape] that means every corner radius grows by the same amount;
 * anything else is returned unchanged and simply stroked on the enlarged rect.
 */
private fun Shape.expandedBy(radiusPx: Float): Shape = when (this) {
    is CornerBasedShape -> copy(
        topStart = ExpandedCornerSize(topStart, radiusPx),
        topEnd = ExpandedCornerSize(topEnd, radiusPx),
        bottomEnd = ExpandedCornerSize(bottomEnd, radiusPx),
        bottomStart = ExpandedCornerSize(bottomStart, radiusPx),
    )

    else -> this
}

private class ExpandedCornerSize(
    private val base: CornerSize,
    private val extraPx: Float,
) : CornerSize {
    override fun toPx(shapeSize: Size, density: Density): Float =
        base.toPx(shapeSize, density) + extraPx
}

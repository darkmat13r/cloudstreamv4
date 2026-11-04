package com.lagradost.cloudstream4.core.designsystem.primitive

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens

/**
 * Strokes [shape] just inside this element's edge, the `box-shadow: inset 0 0 0 1px` the boards use.
 *
 * Draw-only, so it obeys the rule on [CloudStreamDimens.Hairline]: a hairline must not consume layout. It is
 * [hairline] for a whole outline, where that one paints a single straight edge.
 */
internal fun Modifier.insetOutline(
    color: Color,
    shape: Shape,
    stroke: Dp = CloudStreamDimens.Hairline,
): Modifier = this.drawWithCache {
    val strokePx = stroke.toPx()
    // The stroke straddles the path, so the path sits half a stroke inside the box on every side.
    val inset = strokePx / HALF
    val outline = shape.createOutline(Size(size.width - strokePx, size.height - strokePx), layoutDirection, this)
    val style = Stroke(width = strokePx)
    onDrawWithContent {
        drawContent()
        translate(left = inset, top = inset) { drawOutline(outline = outline, color = color, style = style) }
    }
}

private const val HALF = 2f

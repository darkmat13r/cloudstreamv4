package com.lagradost.cloudstream4.core.designsystem.target

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * Two to four options, always visible, of which one is chosen (`Components` §01). More than four is a [ChipRow].
 *
 * Each option is its own focus stop, so LEFT and RIGHT move between them and centre chooses. Selection is hoisted.
 *
 * ## A 24dp visual in a 48dp cell
 *
 * As on [Chip], the option's focus and hit area are [SegmentedControlDefaults.FocusCell] tall and the control measures
 * the cell. The ring goes on the option's visual, [CloudStreamDimens.FocusRingOffset] clear on all four sides and with
 * that option's corners: rounded on the control's outer edge, square where it meets a neighbour (`DEC-17`, 2026-11-05).
 *
 * **`Components` draws this 56px, which is 28dp. It is 24dp here on purpose:** 28 centred in 48 lands at +10dp, off
 * the 4dp step, where 24 lands at +12dp. No board uses the control yet, so nothing has to be redrawn.
 *
 * The track is painted by the row and not clipped, because a clip would cut the ring. The focused option draws above
 * its neighbours, or the next one's fill would paint over the ring.
 *
 * @param options already-localised labels. Two to four, or this throws: the wrong component is a programming error.
 * @param selectedIndex the chosen option, an index into [options].
 * @param onSelect invoked with the option's index on centre, Enter or a tap.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    require(options.size in SegmentedControlDefaults.OPTIONS_MIN..SegmentedControlDefaults.OPTIONS_MAX) {
        "a segmented control has ${SegmentedControlDefaults.OPTIONS_MIN} to " +
            "${SegmentedControlDefaults.OPTIONS_MAX} options, was ${options.size}"
    }
    require(selectedIndex in options.indices) { "selectedIndex $selectedIndex is not in ${options.indices}" }
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.medium
    Row(
        modifier = modifier
            .height(SegmentedControlDefaults.FocusCell)
            .segmentedTrack(fill = colors.raised, line = colors.line, shape = shape)
            .selectableGroup(),
    ) {
        options.forEachIndexed { index, label ->
            key(index) {
                Segment(
                    label = label,
                    selected = index == selectedIndex,
                    visualShape = shape.forSegment(first = index == 0, last = index == options.lastIndex),
                    onClick = { onSelect(index) },
                )
            }
        }
    }
}

@Composable
private fun Segment(
    label: String,
    selected: Boolean,
    visualShape: Shape,
    onClick: () -> Unit,
) {
    val colors = CloudStreamTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Box(
        modifier = Modifier
            .zIndex(if (focused) FOCUSED_LAYER else RESTING_LAYER)
            .fillMaxHeight()
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(SegmentedControlDefaults.VisualHeight)
                .tvFocusRing(focused = focused, shape = visualShape)
                .background(if (selected) colors.line else Color.Transparent, visualShape)
                .padding(horizontal = SegmentedControlDefaults.HorizontalPadding),
            contentAlignment = Alignment.Center,
        ) {
            TargetLabel(text = label, style = CloudStreamTheme.typography.label, color = if (selected) colors.ink else colors.muted)
        }
    }
}

/**
 * Paints the raised track and its hairline across the whole row, in the [SegmentedControlDefaults.VisualHeight] band
 * at the middle of the cell. Draw-only, and behind the options.
 */
private fun Modifier.segmentedTrack(fill: Color, line: Color, shape: Shape): Modifier = drawWithCache {
    val bandHeight = SegmentedControlDefaults.VisualHeight.toPx()
    val top = (size.height - bandHeight) / HALF
    val strokePx = CloudStreamDimens.Hairline.toPx()
    val inset = strokePx / HALF
    val track = shape.createOutline(Size(size.width, bandHeight), layoutDirection, this)
    val edge = shape.createOutline(Size(size.width - strokePx, bandHeight - strokePx), layoutDirection, this)
    val stroke = Stroke(width = strokePx)
    onDrawBehind {
        translate(top = top) { drawOutline(outline = track, color = fill) }
        translate(left = inset, top = top + inset) { drawOutline(outline = edge, color = line, style = stroke) }
    }
}

/** Keeps only the corners on the control's outer edge; an inner edge meets its neighbour square. */
private fun Shape.forSegment(first: Boolean, last: Boolean): Shape = when (this) {
    is CornerBasedShape -> copy(
        topStart = if (first) topStart else ZeroCornerSize,
        bottomStart = if (first) bottomStart else ZeroCornerSize,
        topEnd = if (last) topEnd else ZeroCornerSize,
        bottomEnd = if (last) bottomEnd else ZeroCornerSize,
    )

    else -> this
}

/** Values [SegmentedControl] needs that `:core:theme` does not name yet. */
@Immutable
object SegmentedControlDefaults {

    /** The visual. `Components` draws 28dp; see [SegmentedControl]. Mirrors `CloudStreamDimens.ChipVisualHeight`. */
    val VisualHeight: Dp = CloudStreamDimens.ChipVisualHeight

    /** An option's focus and hit area, the minimum target. Mirrors `CloudStreamDimens.ChipFocusCell`. */
    val FocusCell: Dp = CloudStreamDimens.ChipFocusCell

    /** Each side of a label, 24px on the artboard. Equal to `CloudStreamDimens.MinFocusGap` in value only. */
    val HorizontalPadding: Dp = 12.dp

    /** The fewest options the board allows. */
    const val OPTIONS_MIN: Int = 2

    /** The most options the board allows. */
    const val OPTIONS_MAX: Int = 4
}

private const val HALF = 2f
private const val RESTING_LAYER = 0f
private const val FOCUSED_LAYER = 1f

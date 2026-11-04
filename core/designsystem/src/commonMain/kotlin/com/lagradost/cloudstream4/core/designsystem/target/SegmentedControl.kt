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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.designsystem.primitive.insetOutline
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * Two to four options, always visible, of which one is chosen (`Components` §01). More than four is a [ChipRow].
 *
 * Each option is its own focus stop, so LEFT and RIGHT move between them and centre chooses. Selection is hoisted.
 *
 * **Not clipped, unlike the board's `overflow: hidden`.** A clip would cut the focus ring, so each option rounds
 * its own outer corners instead. The focused option also draws above its neighbours, or the next one's fill would
 * paint over the ring.
 *
 * The label's line box is the control's own height, the same fix `TV Library` records for its chips: the 16dp box
 * of `label` would centre in 28dp at +6dp, off the step.
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
            .height(SegmentedControlDefaults.Height)
            .background(colors.raised, shape)
            .insetOutline(colors.line, shape)
            .selectableGroup(),
    ) {
        options.forEachIndexed { index, label ->
            key(index) {
                Segment(
                    label = label,
                    selected = index == selectedIndex,
                    shape = shape.forSegment(first = index == 0, last = index == options.lastIndex),
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
    shape: Shape,
    onClick: () -> Unit,
) {
    val colors = CloudStreamTheme.colors
    val base = CloudStreamTheme.typography.label
    val style = remember(base) { base.copy(lineHeight = SegmentedControlDefaults.LineHeight) }
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
            )
            .tvFocusRing(focused = focused, shape = shape)
            .background(if (selected) colors.line else colors.raised, shape)
            .padding(horizontal = SegmentedControlDefaults.HorizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        TargetLabel(text = label, style = style, color = if (selected) colors.ink else colors.muted)
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

    /** 56px on the artboard. On the step, and between the chip visual and the text button by design. */
    val Height: Dp = 28.dp

    /** Each side of a label, 24px on the artboard. Equal to `CloudStreamDimens.MinFocusGap` in value only. */
    val HorizontalPadding: Dp = 12.dp

    /** [Height] in `sp`, so the line box fills the control. See [SegmentedControl]. */
    val LineHeight: TextUnit = 28.sp

    /** The fewest options the board allows. */
    const val OPTIONS_MIN: Int = 2

    /** The most options the board allows. */
    const val OPTIONS_MAX: Int = 4
}

private const val RESTING_LAYER = 0f
private const val FOCUSED_LAYER = 1f

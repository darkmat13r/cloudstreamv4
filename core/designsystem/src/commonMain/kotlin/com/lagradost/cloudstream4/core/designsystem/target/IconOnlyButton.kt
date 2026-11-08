package com.lagradost.cloudstream4.core.designsystem.target

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * An **icon-only** button that names itself when focused: overflow (`More`) and search. A toggle is
 * [IconOnlyToggle].
 *
 * The owner, 2026-11-05 (`DEC-17`): icon-only is allowed only for toggles, overflow and search, and
 * such a button **shows its text label when focused**, as well as carrying it as a content
 * description. Every other action is a text button, with a glyph when its action has one.
 *
 * ## Geometry
 *
 * A [CloudStreamDimens.IconTargetCell] (48dp) focus and hit cell, holding a
 * [IconOnlyButtonDefaults.TileSize] (32dp) tile drawn like a [GhostTextButton] — no fill, a `line`
 * hairline, an `ink` glyph of [CloudStreamIconSize.Small] — so it sits on the same row as the text
 * buttons at their height. The ring goes on the tile, [CloudStreamDimens.FocusRingOffset] clear on
 * all four sides, as on a text button and a chip.
 *
 * ## The label
 *
 * While focused, [label] is drawn centred under the cell, one line in `typography.label` and `ink`,
 * starting at the cell's bottom edge: 8dp below the tile, clear of the ring. Near the screen's edge it
 * moves inward just enough to stay on screen. It is **drawn, not laid
 * out**: the button measures as its 48dp cell whether focused or not, so focusing it moves nothing.
 * The caller leaves [IconOnlyButtonDefaults.LabelReserve] (16dp, one label line box) unclipped below
 * the cell, the way every focusable leaves room for its ring. The visible label is hidden from
 * accessibility, because the button already carries it as its content description.
 *
 * @param icon a glyph from `CloudStreamIcons`.
 * @param label already-localised text: the content description always, the visible label on focus.
 * @param onClick invoked on D-pad centre, Enter, or a tap.
 * @param modifier applied to the 48dp cell, which is the focusable.
 * @param enabled when false the button neither focuses nor clicks, and its glyph drops to `muted`.
 */
@Composable
fun IconOnlyButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    IconOnlyChassis(
        icon = icon,
        label = label,
        selected = false,
        dimmed = false,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        ),
    )
}

/**
 * An **icon-only toggle**: bookmark, watchlist, favourite. Everything in [IconOnlyButton] holds; on
 * top of it, [checked] is the state.
 *
 * Checked is drawn as the system already draws *selected* (`Design System v2`): a `raised` fill with
 * an `ink` glyph. Unchecked has no fill and a `muted` glyph, because a fill alone is about 1.15:1
 * against the ground and does not read at three metres, and Tabler has no filled bookmark or heart to
 * switch to (`DEC-17`, 2026-11-05). The ring is focus, and only focus. The state is also in the
 * semantics tree as a toggle, so a screen reader says on or off.
 *
 * @param checked whether the thing is on: the title is bookmarked, say.
 * @param onCheckedChange invoked with the new value on D-pad centre, Enter, or a tap.
 */
@Composable
fun IconOnlyToggle(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    IconOnlyChassis(
        icon = icon,
        label = label,
        selected = checked,
        dimmed = !checked,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier.toggleable(
            value = checked,
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = Role.Checkbox,
            onValueChange = onCheckedChange,
        ),
    )
}

@Composable
private fun IconOnlyChassis(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    dimmed: Boolean,
    enabled: Boolean,
    interactionSource: MutableInteractionSource,
    modifier: Modifier,
) {
    val focused by interactionSource.collectIsFocusedAsState()
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.medium
    Layout(
        modifier = modifier.semantics { contentDescription = label },
        content = {
            Box(Modifier.size(CloudStreamDimens.IconTargetCell), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(IconOnlyButtonDefaults.TileSize)
                        .tvFocusRing(focused = focused, shape = shape)
                        .background(if (selected) colors.raised else Color.Transparent, shape)
                        .hairlineOutline(color = colors.line, shape = shape),
                    contentAlignment = Alignment.Center,
                ) {
                    CloudStreamIcon(
                        icon = icon,
                        contentDescription = null,
                        size = CloudStreamIconSize.Small,
                        tint = if (enabled && !dimmed) colors.ink else colors.muted,
                    )
                }
            }
            if (focused) {
                TargetLabel(
                    text = label,
                    style = CloudStreamTheme.typography.label,
                    color = colors.ink,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        },
    ) { measurables, _ ->
        val cell = measurables[0].measure(Constraints())
        val text = measurables.getOrNull(1)?.measure(Constraints())
        layout(cell.width, cell.height) {
            cell.place(0, 0)
            text?.let {
                val centred = (cell.width - it.width) / 2
                it.place(centred + onScreenShift(coordinates, centred, it.width), cell.height)
            }
        }
    }
}

/**
 * How far to move a label centred under its cell so it stays inside the root: a cell at the screen's edge with a long
 * name (`BUG-60`: "Switch account" in Macedonian is 25 letters, under the TV Home chrome's last cell) would otherwise
 * draw part of it off screen. 0 when the centred label fits, so a label is only moved when it would be cut. A label
 * wider than the root starts at its left edge.
 */
private fun onScreenShift(coordinates: LayoutCoordinates?, left: Int, width: Int): Int {
    val placed = coordinates ?: return 0
    val rootWidth = placed.findRootCoordinates().size.width
    val start = placed.positionInRoot().x.roundToInt() + left
    return when {
        start < 0 || width >= rootWidth -> -start
        start + width > rootWidth -> rootWidth - (start + width)
        else -> 0
    }
}

/** Values the icon-only buttons need that `:core:theme` does not name yet. */
@Immutable
object IconOnlyButtonDefaults {

    /**
     * The visible tile: 64px on the artboard, halved. Mirrors `CloudStreamDimens.TextButtonHeight`
     * (32dp), so a tile and a text button stand the same height on one row.
     */
    val TileSize: Dp = 32.dp

    /**
     * What a caller leaves unclipped below the cell for the label: one `typography.label` line box,
     * 16dp. Mirrors `CloudStreamDimens.StackTitleLineBox`.
     */
    val LabelReserve: Dp = 16.dp
}

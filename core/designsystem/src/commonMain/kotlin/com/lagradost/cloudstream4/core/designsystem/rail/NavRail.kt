package com.lagradost.cloudstream4.core.designsystem.rail

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.target.TargetLabel
import com.lagradost.cloudstream4.core.theme.CloudStreamColors
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * One destination on a [NavRail].
 *
 * @property label already localised. Shown when the rail is expanded, and read out when it is not.
 * @property icon a meaning from `CloudStreamIcons`.
 */
@Immutable
data class NavRailItem(val label: String, val icon: ImageVector)

/**
 * The TV navigation rail (`TV Home` 3a, 3b). The items are the caller's; `DEC-17` rules what they are.
 *
 * ## It never reflows the content
 *
 * The rail always measures [CloudStreamDimens.NavRailWidth]. Expanded, it paints out to
 * [NavRailDefaults.ExpandedWidth] over whatever is beside it. Its `zIndex` lifts it over its own siblings, in a `Row`
 * or a `Box` and in either order, so make the content a sibling. No ancestor may clip it.
 *
 * ## Focus
 *
 * It is expanded exactly while focus is inside it, and the item you are on is an `ink` fill with a `bg` label, as
 * 3b draws it. The current page stays marked with a raised fill while focus travels, and only committing calls
 * [onSelect].
 *
 * Focus entering the rail lands on the current page's item, from any direction and from a `FocusRequester` on
 * [modifier], so the current page is what someone sees first.
 *
 * Items are never swapped between a collapsed and an expanded layout: removing the focused node would lose focus
 * at the moment it arrived. The same node changes width.
 *
 * Returning focus to the exact view that yielded it, on RIGHT, is the screen's: this component does not know the
 * view. Use `focusRestorer` on the content.
 *
 * @param selectedIndex the current page, an index into [items].
 * @param onSelect invoked with the item's index on centre, Enter or a tap.
 * @param logo the brand mark, in a [NavRailDefaults.LogoSize] cell. Its space is kept when null, so the items do
 *   not move. It is artwork, so this module does not draw it.
 */
@Composable
fun NavRail(
    items: List<NavRailItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    logo: (@Composable () -> Unit)? = null,
) {
    require(selectedIndex in items.indices) { "selectedIndex $selectedIndex is not in ${items.indices}" }
    var expanded by remember { mutableStateOf(false) }
    val currentItem = remember { FocusRequester() }
    val ground = CloudStreamTheme.colors.bg
    val brush = remember(ground, expanded) { railBrush(ground, expanded) }
    Box(modifier.width(CloudStreamDimens.NavRailWidth).fillMaxHeight().zIndex(RAIL_LAYER)) {
        Column(
            modifier = Modifier
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .width(if (expanded) NavRailDefaults.ExpandedWidth else CloudStreamDimens.NavRailWidth)
                .fillMaxHeight()
                .background(brush)
                .padding(start = CloudStreamDimens.NavRailIconInset, top = CloudStreamDimens.FocusBandInset)
                .onFocusChanged { expanded = it.hasFocus }
                .focusProperties { onEnter = { currentItem.requestFocus() } }
                .focusGroup()
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(NavRailDefaults.ItemGap),
        ) {
            LogoCell(logo)
            items.forEachIndexed { index, item ->
                RailItem(
                    item = item,
                    current = index == selectedIndex,
                    expanded = expanded,
                    onClick = { onSelect(index) },
                    modifier = if (index == selectedIndex) Modifier.focusRequester(currentItem) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun LogoCell(logo: (@Composable () -> Unit)?) {
    Column {
        Box(
            modifier = Modifier
                .padding(horizontal = NavRailDefaults.LogoInset)
                .size(NavRailDefaults.LogoSize),
            contentAlignment = Alignment.Center,
        ) { logo?.invoke() }
        Spacer(Modifier.height(NavRailDefaults.LogoGap))
    }
}

@Composable
private fun RailItem(
    item: NavRailItem,
    current: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CloudStreamTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val content = itemContent(colors, focused, current, expanded)
    Row(
        modifier = modifier
            .width(if (expanded) NavRailDefaults.ExpandedItemWidth else CloudStreamDimens.NavRailIconCell)
            .height(CloudStreamDimens.NavRailIconCell)
            .background(itemFill(colors, focused, current), CloudStreamTheme.shapes.medium)
            .selectable(
                selected = current,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(start = if (expanded) NavRailDefaults.ExpandedItemInset else NavRailDefaults.CollapsedGlyphInset),
        horizontalArrangement = Arrangement.spacedBy(NavRailDefaults.GlyphGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CloudStreamIcon(
            icon = item.icon,
            contentDescription = if (expanded) null else item.label,
            size = CloudStreamIconSize.Small,
            tint = content,
        )
        if (expanded) {
            TargetLabel(text = item.label, style = CloudStreamTheme.typography.label, color = content)
        }
    }
}

private fun itemFill(colors: CloudStreamColors, focused: Boolean, current: Boolean): Color = when {
    focused -> colors.ink
    current -> colors.raised
    else -> Color.Transparent
}

private fun itemContent(
    colors: CloudStreamColors,
    focused: Boolean,
    current: Boolean,
    expanded: Boolean,
): Color = when {
    focused -> colors.bg
    current -> if (expanded) colors.ink2 else colors.ink
    else -> colors.muted
}

private fun railBrush(ground: Color, expanded: Boolean): Brush =
    if (expanded) {
        Brush.horizontalGradient(
            NavRailDefaults.EXPANDED_SOLID_STOP to ground,
            NavRailDefaults.EXPANDED_FADE_STOP to ground.copy(alpha = NavRailDefaults.EXPANDED_FADE_ALPHA),
            RAIL_EDGE to ground.copy(alpha = 0f),
        )
    } else {
        Brush.horizontalGradient(
            NavRailDefaults.COLLAPSED_SOLID_STOP to ground.copy(alpha = NavRailDefaults.COLLAPSED_SOLID_ALPHA),
            RAIL_EDGE to ground.copy(alpha = 0f),
        )
    }

/** Values [NavRail] needs that `:core:theme` does not name yet. */
@Immutable
object NavRailDefaults {

    /** The rail expanded, 376px on the artboard. It paints over the content and is never laid out. */
    val ExpandedWidth: Dp = 188.dp

    /** An item expanded: 376 - 56 - 16 = 304px on the artboard. */
    val ExpandedItemWidth: Dp = 152.dp

    /** Between items, 8px on the artboard. Mirrors `CloudStreamDimens.StackGap`. */
    val ItemGap: Dp = 4.dp

    /** The brand mark's cell, 48px on the artboard. */
    val LogoSize: Dp = 24.dp

    /** Each side of the mark, centring it over the 48dp item column: (48 - 24) / 2. */
    val LogoInset: Dp = 12.dp

    /** Below the mark, 32px on the artboard. [ItemGap] adds to it, so the first item is at 144px, 72dp. */
    val LogoGap: Dp = 16.dp

    /** The glyph's start when collapsed, centring 16dp in the 48dp cell. */
    val CollapsedGlyphInset: Dp = 16.dp

    /** The glyph's start when expanded, 24px on the artboard. */
    val ExpandedItemInset: Dp = 12.dp

    /** Glyph to label, 16px on the artboard. Mirrors `CloudStreamDimens.ListRowPadding`. */
    val GlyphGap: Dp = 8.dp

    /** Where the collapsed band starts to fade, from `TV Home`'s `.railc`. */
    const val COLLAPSED_SOLID_STOP: Float = 0.6f

    /** The collapsed band's opacity, from `TV Home`'s `.railc`. */
    const val COLLAPSED_SOLID_ALPHA: Float = 0.96f

    /** Where the expanded band stops being solid, from `TV Home`'s `.raile`. */
    const val EXPANDED_SOLID_STOP: Float = 0.72f

    /** Where the expanded band reaches [EXPANDED_FADE_ALPHA], from `TV Home`'s `.raile`. */
    const val EXPANDED_FADE_STOP: Float = 0.9f

    /** The expanded band's opacity at [EXPANDED_FADE_STOP], from `TV Home`'s `.raile`. */
    const val EXPANDED_FADE_ALPHA: Float = 0.9f
}

private const val RAIL_LAYER = 1f
private const val RAIL_EDGE = 1f

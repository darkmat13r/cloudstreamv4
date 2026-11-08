package com.lagradost.cloudstream4.core.designsystem.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/** The geometry of [DesktopSwitch] and [DesktopPopupButton]: shapes the design system defines, not glyphs. */
object DesktopControlDefaults {
    /** The switch's track. Mirrors `DS-24`'s boards at 1 px = 1 dp. */
    val SwitchWidth: Dp = 40.dp
    val SwitchHeight: Dp = 24.dp

    /** The knob, and the gap it keeps inside the track on every side. */
    val KnobSize: Dp = 16.dp
    val KnobInset: Dp = 4.dp

    /** A pop-up's list: wide enough for a short value with its check, as the boards draw it. */
    val MenuMinWidth: Dp = 160.dp

    /** The menu opens just under its button. Mirrors `DesktopDimens.RelatedGap / 2`. */
    val MenuGap: Dp = 4.dp
}

/**
 * An on/off switch: the knob at the end shows the state, and the track fills with ink when on. Colour says focus and
 * shape says state, as `DS-17` rules for TV toggles: off is a hollow track, on a filled one.
 *
 * @param label the setting's name, read out with the state; the row draws it, so the switch shows no words.
 */
@Composable
fun DesktopSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = CloudStreamTheme.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.pill
    Box(
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        modifier = modifier
            .semantics { contentDescription = label }
            .tvFocusRing(focused = focused, shape = shape)
            .size(DesktopControlDefaults.SwitchWidth, DesktopControlDefaults.SwitchHeight)
            .background(if (checked) colors.ink else colors.raised, shape)
            .border(CloudStreamDimens.Hairline, if (checked) Color.Transparent else colors.line, shape)
            .toggleable(
                value = checked,
                interactionSource = source,
                indication = null,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(DesktopControlDefaults.KnobInset),
    ) {
        Box(
            Modifier
                .size(DesktopControlDefaults.KnobSize)
                .background(if (checked) colors.bg else colors.muted, shape),
        )
    }
}

/**
 * A Mac pop-up button: the chosen value and the selector mark; pressing it lists the choices under it, with a check on
 * the one chosen. Choosing commits and closes; a click outside closes with nothing chosen.
 *
 * @param label the setting's name, read out with the value; the row draws it.
 * @param optionLabel the words for a choice, in the current language.
 */
@Composable
fun <T> DesktopPopupButton(
    selected: T,
    options: List<T>,
    onSelect: (T) -> Unit,
    label: String,
    optionLabel: @Composable (T) -> String,
    modifier: Modifier = Modifier,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.medium
    var open by remember { mutableStateOf(false) }
    val value = optionLabel(selected)
    Box(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
            modifier = Modifier
                .semantics { contentDescription = "$label, $value" }
                .tvFocusRing(focused = focused, shape = shape)
                .background(if (hovered) colors.line else colors.raised, shape)
                .clickable(interactionSource = source, indication = null, role = Role.DropdownList) { open = true }
                .heightIn(min = DesktopDimens.RowControlHeight)
                .padding(start = DesktopDimens.RowPadding, end = DesktopDimens.RelatedGap),
        ) {
            BasicText(value, style = typography.label.copy(color = colors.ink), maxLines = 1)
            CloudStreamIcon(CloudStreamIcons.Selector, null, size = CloudStreamIconSize.Small, tint = colors.muted)
        }
        if (open) {
            PopupMenu(
                options = options,
                selected = selected,
                optionLabel = optionLabel,
                onDismiss = { open = false },
                onSelect = { choice ->
                    open = false
                    if (choice != selected) onSelect(choice)
                },
            )
        }
    }
}

@Composable
private fun <T> PopupMenu(
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.large
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, 0),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .padding(top = DesktopDimens.RowControlHeight + DesktopControlDefaults.MenuGap)
                .width(DesktopControlDefaults.MenuMinWidth)
                .background(colors.raised, shape)
                .border(CloudStreamDimens.Hairline, colors.line, shape)
                .padding(DesktopControlDefaults.MenuGap),
        ) {
            options.forEach { option ->
                PopupMenuItem(optionLabel(option), chosen = option == selected) { onSelect(option) }
            }
        }
    }
}

@Composable
private fun PopupMenuItem(text: String, chosen: Boolean, onClick: () -> Unit) {
    val colors = CloudStreamTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DesktopDimens.RowControlHeight)
            .background(if (hovered) colors.line else Color.Transparent, CloudStreamTheme.shapes.medium)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = DesktopDimens.RelatedGap),
    ) {
        if (chosen) {
            CloudStreamIcon(CloudStreamIcons.Done, null, size = CloudStreamIconSize.Small, tint = colors.ink)
        } else {
            Spacer(Modifier.size(CloudStreamDimens.ChipGlyphSize))
        }
        BasicText(text, style = CloudStreamTheme.typography.label.copy(color = colors.ink), maxLines = 1)
    }
}

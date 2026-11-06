package com.lagradost.cloudstream4.home

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopFeedback
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * The window's sidebar (`KMP-53`): every destination with its glyph and name, the one shown filled, and at its foot
 * whatever the window needs to say, such as extensions waiting to be allowed.
 *
 * `docs/plans/desktop-design.md` kept three tabs while three did the job, and said "a sidebar is worth it at the
 * fourth". Downloads is the fourth (`KMP-54`, board D1 of `design/Desktop Downloads.dc.html`), so this replaces the bar
 * for every top-level screen. Its look is board D1's: a 220 column on `surface` with a hairline on its right edge, 32
 * rows with a 16 glyph, and the chosen row `raised`. ⌘1 to ⌘3 reach the rows from the keyboard ([DesktopShortcuts]); the
 * menu bar that will list them in words needs strings of its own, so it is a later step (noted in `KMP-53`).
 */
@Composable
internal fun DesktopSidebar(
    destinations: List<TopLevelDestination>,
    selected: TopLevelDestination,
    onSelect: (TopLevelDestination) -> Unit,
    label: @Composable (TopLevelDestination) -> String,
    modifier: Modifier = Modifier,
    foot: @Composable () -> Unit = {},
) {
    val colors = CloudStreamTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(DesktopShellDefaults.ItemGap),
        modifier = modifier
            .width(DesktopShellDefaults.SidebarWidth)
            .fillMaxHeight()
            .background(colors.surface)
            .rightRule(colors.line)
            .padding(horizontal = DesktopDimens.RowPadding, vertical = DesktopDimens.WindowMargin),
    ) {
        destinations.forEach { destination ->
            SidebarItem(
                icon = destination.icon(),
                label = label(destination),
                selected = destination == selected,
                onClick = { onSelect(destination) },
            )
        }
        Spacer(Modifier.weight(1f))
        foot()
    }
}

@Composable
private fun SidebarItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = CloudStreamTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.medium
    val content = if (selected) colors.ink else colors.ink2
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = Modifier
            .fillMaxWidth()
            .height(DesktopShellDefaults.ItemHeight)
            .desktopFeedback(source, lift = false)
            .tvFocusRing(focused = focused, shape = shape)
            .background(
                when {
                    selected -> colors.raised
                    hovered -> colors.line
                    else -> Color.Transparent
                },
                shape,
            )
            .selectable(selected = selected, interactionSource = source, indication = null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = DesktopDimens.RowPadding),
    ) {
        // Decoration: the name beside it says where it goes.
        CloudStreamIcon(icon, null, size = CloudStreamIconSize.Small, tint = content)
        BasicText(
            text = label,
            style = CloudStreamTheme.typography.label.copy(color = content),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One glyph per meaning: the same entries the TV rail uses for these destinations. */
private fun TopLevelDestination.icon(): ImageVector = when (this) {
    TopLevelDestination.Home -> CloudStreamIcons.Home
    TopLevelDestination.Search -> CloudStreamIcons.Search
    TopLevelDestination.Library -> CloudStreamIcons.Library
}

/** A hairline down the right edge, where the sidebar meets the page. A stroke, so it is exempt from the 4 dp step. */
private fun Modifier.rightRule(color: Color): Modifier = drawBehind {
    val stroke = CloudStreamDimens.Hairline.toPx()
    drawRect(color, topLeft = Offset(size.width - stroke, 0f), size = Size(stroke, size.height))
}

/**
 * The window's own shortcuts, read before any screen sees the key, as [com.lagradost.cloudstream4.EscapeHandler] is:
 * ⌘1 to ⌘3 go to a destination in the sidebar's order, and ⌘[ goes back where there is something to go back to. The
 * host sets what each does; a screen that has its own ⌘ keys (the downloads page's ⌘A, ⌘R) does not collide with these.
 */
internal class DesktopShortcuts {
    var onDestination: ((TopLevelDestination) -> Unit)? = null
    var onBack: (() -> Unit)? = null

    fun handle(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || !event.isMetaPressed) return false
        val destination = when (event.key) {
            Key.One -> TopLevelDestination.Home
            Key.Two -> TopLevelDestination.Search
            Key.Three -> TopLevelDestination.Library
            else -> null
        }
        return when {
            destination != null -> onDestination?.let { it(destination); true } ?: false
            event.key == Key.LeftBracket -> onBack?.let { it(); true } ?: false
            else -> false
        }
    }
}

/** Values the shell needs that `DesktopDimens` does not name. */
internal object DesktopShellDefaults {
    /** Board D1's sidebar column. */
    val SidebarWidth: Dp = 220.dp

    /** A row: a 24 label line and 4 above and below. */
    val ItemHeight: Dp = 32.dp

    /** Between rows. Mirrors `CloudStreamDimens.StackGap`. */
    val ItemGap: Dp = 4.dp
}

package com.lagradost.cloudstream4.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopPageBarDefaults
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopFeedback
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.desktop_menu_go
import com.lagradost.cloudstream4.core.strings.generated.resources.go_back
import com.lagradost.cloudstream4.core.strings.generated.resources.title_settings
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import org.jetbrains.compose.resources.stringResource
import javax.swing.JRootPane

/**
 * The window's sidebar (`KMP-53`): every destination with its glyph and name, the one shown filled, and at its foot
 * whatever the window needs to say, such as extensions waiting to be allowed.
 *
 * `docs/plans/desktop-design.md` kept three tabs while three did the job, and said "a sidebar is worth it at the
 * fourth". Downloads is the fourth (`KMP-54`, board D1 of `design/Desktop Downloads.dc.html`), so this replaces the bar
 * for every top-level screen. Its look is board D1's: a 220 column on `surface` with a hairline on its right edge, 32
 * rows with a 16 glyph, and the chosen row `raised`. ⌘1 to ⌘3 reach the rows from the keyboard, through the menu bar's
 * Go menu ([DesktopMenuBar]).
 *
 * `KMP-98`: the column runs the window's full height, under a transparent title bar, so its first
 * [DesktopShellDefaults.TitleBand] is left to the traffic lights, level with each page's bar. Settings is its last row.
 *
 * @param onSettings opens the Settings window. Null draws no row, as where nothing can open it.
 */
@Composable
internal fun DesktopSidebar(
    destinations: List<TopLevelDestination>,
    selected: TopLevelDestination,
    onSelect: (TopLevelDestination) -> Unit,
    label: @Composable (TopLevelDestination) -> String,
    modifier: Modifier = Modifier,
    onSettings: (() -> Unit)? = null,
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
            .padding(
                start = DesktopDimens.RowPadding,
                top = DesktopShellDefaults.TitleBand,
                end = DesktopDimens.RowPadding,
                bottom = DesktopDimens.WindowMargin,
            ),
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
        if (onSettings != null) {
            SidebarItem(
                icon = CloudStreamIcons.Settings,
                label = stringResource(Res.string.title_settings),
                selected = false,
                onClick = onSettings,
                // The key the app menu's Settings… has on a Mac. Elsewhere there is no such key, so none is shown.
                hint = if (DesktopShortcuts.onMac) DesktopShellDefaults.SettingsKey else null,
                role = Role.Button,
            )
        }
    }
}

/**
 * A sidebar row. A destination is a tab and shows when it is chosen; Settings opens a window and is a button.
 *
 * @param hint the row's key, at its trailing end in the muted ink, as a Mac menu shows one.
 */
@Composable
private fun SidebarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    hint: String? = null,
    role: Role = Role.Tab,
) {
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
            .then(
                if (role == Role.Tab) {
                    Modifier.selectable(selected = selected, interactionSource = source, indication = null, role = role, onClick = onClick)
                } else {
                    Modifier.clickable(interactionSource = source, indication = null, role = role, onClick = onClick)
                },
            )
            .padding(horizontal = DesktopDimens.RowPadding),
    ) {
        // Decoration: the name beside it says where it goes.
        CloudStreamIcon(icon, null, size = CloudStreamIconSize.Small, tint = content)
        BasicText(
            text = label,
            style = CloudStreamTheme.typography.label.copy(color = content),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // All the row the hint leaves, so the hint sits at the trailing end and the name gives way only to it.
            modifier = Modifier.weight(1f),
        )
        if (hint != null) {
            BasicText(
                text = hint,
                style = CloudStreamTheme.typography.label.copy(color = colors.muted),
                maxLines = 1,
            )
        }
    }
}

/** One glyph per meaning: the same entries the TV rail uses for these destinations. */
private fun TopLevelDestination.icon(): ImageVector = when (this) {
    TopLevelDestination.Home -> CloudStreamIcons.Home
    TopLevelDestination.Search -> CloudStreamIcons.Search
    TopLevelDestination.Library -> CloudStreamIcons.Library
    TopLevelDestination.Downloads -> CloudStreamIcons.Download
}

/** A hairline down the right edge, where the sidebar meets the page. A stroke, so it is exempt from the 4 dp step. */
private fun Modifier.rightRule(color: Color): Modifier = drawBehind {
    val stroke = CloudStreamDimens.Hairline.toPx()
    drawRect(color, topLeft = Offset(size.width - stroke, 0f), size = Size(stroke, size.height))
}

/**
 * The window's destinations and Back, and their keys (`KMP-53`). The menu bar ([DesktopMenuBar]) owns the keys, so they
 * work wherever focus is and each is listed in words; nothing else in the window reads them, which is what keeps a key
 * from firing twice (a doubled ⌘[ would pop two screens).
 *
 * The host sets what each does for the screen on top: [onDestination] wherever the sidebar is, the top level and a
 * title's page (`KMP-77`), and [onBack] only where something is under. [atTop], which is true wherever [onDestination]
 * is set, and [canGoBack] are observable, so the menu greys what does nothing.
 */
internal class DesktopShortcuts {
    var onDestination: ((TopLevelDestination) -> Unit)? = null
        set(value) {
            field = value
            atTop = value != null
        }
    var onBack: (() -> Unit)? = null
        set(value) {
            field = value
            canGoBack = value != null
        }

    var atTop by mutableStateOf(false)
        private set
    var canGoBack by mutableStateOf(false)
        private set

    fun go(destination: TopLevelDestination) {
        onDestination?.invoke(destination)
    }

    fun back() {
        onBack?.invoke()
    }

    companion object {
        /** The Mac's keys on a Mac, and the platform's own elsewhere. */
        val onMac: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")

        /** ⌘1 to ⌘4 on a Mac, Ctrl+1 to Ctrl+4 elsewhere, in the sidebar's order. */
        fun keyFor(destination: TopLevelDestination, mac: Boolean = onMac): KeyShortcut {
            val key = when (destination) {
                TopLevelDestination.Home -> Key.One
                TopLevelDestination.Search -> Key.Two
                TopLevelDestination.Library -> Key.Three
                TopLevelDestination.Downloads -> Key.Four
            }
            return KeyShortcut(key, meta = mac, ctrl = !mac)
        }

        /** ⌘[ on a Mac, as Finder and Safari; Alt+Left elsewhere, as Windows' and GNOME's file browsers. */
        fun backKey(mac: Boolean = onMac): KeyShortcut =
            if (mac) KeyShortcut(Key.LeftBracket, meta = true) else KeyShortcut(Key.DirectionLeft, alt = true)
    }
}

/**
 * The menu bar's Go menu: every destination, then Back, each with its key. On a Mac this is the screen's menu bar, where
 * a Mac user looks for a command they cannot see.
 */
@Composable
internal fun FrameWindowScope.DesktopMenuBar(shortcuts: DesktopShortcuts, label: @Composable (TopLevelDestination) -> String) {
    val labels = TopLevelDestination.entries.associateWith { label(it) }
    val goBack = stringResource(Res.string.go_back)
    MenuBar {
        Menu(stringResource(Res.string.desktop_menu_go)) {
            TopLevelDestination.entries.forEach { destination ->
                Item(
                    text = labels.getValue(destination),
                    shortcut = DesktopShortcuts.keyFor(destination),
                    enabled = shortcuts.atTop,
                    onClick = { shortcuts.go(destination) },
                )
            }
            Separator()
            Item(text = goBack, shortcut = DesktopShortcuts.backKey(), enabled = shortcuts.canGoBack, onClick = shortcuts::back)
        }
    }
}

/**
 * The window's title bar over its content, transparent and without the title, or a plain one above it (`KMP-98`). Over
 * where the sidebar is, so the column runs the window's full height with the traffic lights in it, as in the Settings
 * window; plain over the player and the review, which have no column to give them.
 *
 * Measured on Temurin 17 by asking AppKit which view a point hit-tests to: while it is over, every point of the title
 * band is the content's, so a control in a page's bar takes its click, and the content view answers yes to
 * `mouseDownCanMoveWindow`, which is what lets a press on an empty part move the window. Switching it while the window
 * is open applies at once: the frame's top inset goes 28 to 0 and back. Off a Mac the properties are not read.
 */
internal fun JRootPane.titleBarOverContent(over: Boolean) {
    if (getClientProperty(FULL_WINDOW_CONTENT) == over) return
    putClientProperty(FULL_WINDOW_CONTENT, over)
    putClientProperty("apple.awt.transparentTitleBar", over)
    putClientProperty("apple.awt.windowTitleVisible", !over)
}

private const val FULL_WINDOW_CONTENT = "apple.awt.fullWindowContent"

/** Values the shell needs that `DesktopDimens` does not name. */
internal object DesktopShellDefaults {
    /** Board D1's sidebar column. */
    val SidebarWidth: Dp = 220.dp

    /** A row: a 24 label line and 4 above and below. */
    val ItemHeight: Dp = 32.dp

    /** Between rows. Mirrors `CloudStreamDimens.StackGap`. */
    val ItemGap: Dp = 4.dp

    /**
     * The top of the sidebar, left to the traffic lights (`KMP-98`). Mirrors `DesktopPageBarDefaults.Height`, so the
     * first row starts where each page does, under its bar.
     */
    val TitleBand: Dp = DesktopPageBarDefaults.Height

    /** Settings…'s key in every Mac app's menu, which the app menu's item also has. */
    const val SettingsKey: String = "⌘,"
}

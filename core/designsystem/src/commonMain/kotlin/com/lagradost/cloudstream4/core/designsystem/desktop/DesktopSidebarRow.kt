package com.lagradost.cloudstream4.core.designsystem.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * How a row on a [CloudStreamMaterial.Sidebar][com.lagradost.cloudstream4.core.designsystem.material.CloudStreamMaterial.Sidebar]
 * ground shows that it is chosen or under the pointer (`KMP-111`), in the Mac window and the Settings window alike.
 *
 * Chosen: the `line` fill, the ink label and a leading marker in `ink2`, the player panels' marker (`KMP-93`). The fill
 * is only 1.16:1 on the sidebar, so a chosen row is never told by its fill alone. Under the pointer: `ink` at
 * [HoverAlpha] over the row, 1.12:1 on the sidebar.
 */
object DesktopSidebarRowDefaults {
    /** Mirrors `PlayerScreenDefaults.SourceMarkerWidth`: artboard 7e's 8 x 48 marker, halved. */
    val MarkerWidth: Dp = 4.dp

    /** Mirrors `PlayerScreenDefaults.SourceMarkerHeight`. */
    val MarkerHeight: Dp = 24.dp

    /**
     * `ink` over the sidebar under the pointer: `#23272D`, 1.12:1 on the sidebar and 1.04:1 against a chosen row's
     * fill, with an `ink2` label at 9.34:1. Muted text falls to 4.07:1 on it, so a hint on a row under the pointer
     * is drawn in `ink2` ([hintColor]).
     */
    const val HoverAlpha: Float = 0.04f
}

/** A row's fill: [chosen] first, then [hovered], else none. */
@Composable
fun sidebarRowFill(chosen: Boolean, hovered: Boolean): Color = when {
    chosen -> CloudStreamTheme.colors.line
    hovered -> CloudStreamTheme.colors.ink.copy(alpha = DesktopSidebarRowDefaults.HoverAlpha)
    else -> Color.Transparent
}

/** A row's trailing hint: muted, or `ink2` on a filled row, where muted would fall under 4.5:1. */
@Composable
fun hintColor(filled: Boolean): Color = if (filled) CloudStreamTheme.colors.ink2 else CloudStreamTheme.colors.muted

/** The leading marker: drawn for a chosen row, an empty slot of the same size otherwise, so labels never move. */
@Composable
fun SidebarRowMarker(chosen: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(DesktopSidebarRowDefaults.MarkerWidth, DesktopSidebarRowDefaults.MarkerHeight)
            .background(if (chosen) CloudStreamTheme.colors.ink2 else Color.Transparent, CloudStreamTheme.shapes.small),
    )
}

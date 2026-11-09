package com.lagradost.cloudstream4.core.designsystem.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * The Mac's offline pill: the wifi-off glyph and a muted label on a `raised` pill. It costs no height, so it sits
 * beside whatever it is about. Home's H4 drew it first; Downloads (board D5) and a series' downloads use the same one
 * (`KMP-101`).
 *
 * The glyph carries no description: the label beside it says the same thing.
 */
@Composable
fun DesktopOfflinePill(label: String, modifier: Modifier = Modifier) {
    val colors = CloudStreamTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = modifier
            .testTag(DesktopOfflinePillDefaults.TestTag)
            .height(DesktopOfflinePillDefaults.Height)
            .background(colors.raised, CloudStreamTheme.shapes.pill)
            .padding(horizontal = DesktopDimens.RelatedGap),
    ) {
        CloudStreamIcon(CloudStreamIcons.Offline, null, size = CloudStreamIconSize.Small, tint = colors.muted)
        BasicText(
            text = label,
            style = CloudStreamTheme.typography.caption.copy(color = colors.muted),
            maxLines = 1,
        )
    }
}

object DesktopOfflinePillDefaults {
    /** Mirrors `dimens.ChipVisualHeight`. Was Home's `PillHeight`. */
    val Height: Dp = 24.dp

    /** For a test to find the pill. */
    const val TestTag: String = "desktop-offline-pill"
}

package com.lagradost.cloudstream4.core.designsystem.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * A page's top bar on the Mac (`KMP-98`): one band, [DesktopPageBarDefaults.Height] tall, with a hairline along its
 * bottom edge. Every page beside the window's sidebar starts with one, so the band lines up from page to page (boards
 * S1, H1 and D1).
 *
 * The window's title bar is transparent over the band, as it is over the Settings window's, so the window still drags
 * from its empty parts. A control in it takes its click: the title band hit-tests to the window's content, not to the
 * title bar.
 *
 * @param contentPadding the inset at each end. The window margin, or less where a quiet icon button leads, so its glyph
 *   and not its hover fill lines up with the page.
 */
@Composable
fun DesktopPageBar(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(DesktopDimens.RelatedGap),
    contentPadding: Dp = DesktopDimens.WindowMargin,
    content: @Composable RowScope.() -> Unit,
) {
    val line = CloudStreamTheme.colors.line
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = horizontalArrangement,
        modifier = modifier
            .testTag(DesktopPageBarDefaults.TestTag)
            .fillMaxWidth()
            .height(DesktopPageBarDefaults.Height)
            // A stroke, so it is exempt from the 4 dp step. Inside the band, so the band stays on it.
            .drawBehind {
                val stroke = CloudStreamDimens.Hairline.toPx()
                drawRect(line, topLeft = Offset(0f, size.height - stroke), size = Size(size.width, stroke))
            }
            .padding(horizontal = contentPadding),
        content = content,
    )
}

object DesktopPageBarDefaults {
    /**
     * The band: a [DesktopDimens.ButtonHeight] control with [DesktopDimens.RelatedGap] above and below. Also the
     * Settings window's title band (`SettingsWindowDefaults.TitleBarHeight`), so the two windows' chrome is one height.
     */
    val Height: Dp = 52.dp

    /** For a test to find the band and measure it. */
    const val TestTag: String = "desktop-page-bar"
}

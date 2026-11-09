package com.lagradost.cloudstream4.core.designsystem.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.primitive.insetOutline
import com.lagradost.cloudstream4.core.designsystem.state.FailureNotice
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * A partial failure on the Mac (`KMP-101`, board S3): one band the width of the page, a warning glyph, what failed,
 * the mono detail, and an outlined retry at the far end.
 *
 * The TV's `FailureBanner` is a pill sized to ride a chip row, because a TV's vertical budget decides whether a rail
 * reaches the focus band. A window has the height to spare, and a band across the page reads as about all of it.
 *
 * @param retryLabel the retry's words. The Mac's counts the sources it asks again; [FailureNotice.retry] keeps its
 *   glyph and what it does.
 */
@Composable
fun DesktopFailureBanner(
    notice: FailureNotice,
    modifier: Modifier = Modifier,
    retryLabel: String = notice.retry.label,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val shape = CloudStreamTheme.shapes.medium
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = modifier
            .testTag(DesktopFailureBannerDefaults.TestTag)
            .fillMaxWidth()
            .heightIn(min = DesktopFailureBannerDefaults.MinHeight)
            .background(colors.surface, shape)
            .insetOutline(colors.line, shape)
            .padding(start = DesktopDimens.RowPadding, end = DesktopDimens.RelatedGap),
    ) {
        // Said, not only drawn: the headline beside it names the failure, so the glyph is decoration.
        CloudStreamIcon(CloudStreamIcons.Warning, null, size = CloudStreamIconSize.Small, tint = colors.ink)
        BasicText(
            text = notice.headline,
            style = typography.label.copy(color = colors.ink),
            maxLines = 1,
        )
        BasicText(
            text = notice.detail,
            style = typography.labelMono.copy(color = colors.muted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        DesktopButton(
            label = retryLabel,
            onClick = notice.retry.onActivate,
            icon = notice.retry.icon,
            tone = DesktopButtonTone.Outlined,
        )
    }
}

object DesktopFailureBannerDefaults {
    /** A [DesktopDimens.ButtonHeight] retry with [DesktopDimens.RelatedGap] above and below. */
    val MinHeight: Dp get() = DesktopDimens.ButtonHeight + DesktopDimens.RelatedGap * 2

    /** For a test to find the band. */
    const val TestTag: String = "desktop-failure-banner"
}

package com.lagradost.cloudstream4.core.designsystem.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import com.lagradost.cloudstream4.core.designsystem.rows.ListRow
import com.lagradost.cloudstream4.core.designsystem.rows.ListRowState
import com.lagradost.cloudstream4.core.designsystem.rows.RailHeader
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

// Previews of the panel are drawn at the artboard's own dp size (960x540) so the y values in
// SidePanel's KDoc can be checked against the render: first row at 128, commit row at 464.
private const val ARTBOARD_WIDTH = 960
private const val ARTBOARD_HEIGHT = 540

private val PREVIEW_SOURCES = listOf("1080p · vidsrc", "720p · vidsrc", "480p · mirror")

@Preview(widthDp = ARTBOARD_WIDTH, heightDp = ARTBOARD_HEIGHT)
@Composable
private fun SidePanelHostPreview() {
    CloudStreamTheme {
        SidePanelHost(
            expanded = true,
            onDismissRequest = {},
            panel = { PreviewSourcePanel() },
            content = { Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) },
        )
    }
}

@Composable
private fun PreviewSourcePanel() {
    SidePanel(
        header = { RailHeader(title = "SOURCES", count = PREVIEW_SOURCES.size.toString()) },
        commit = { PreviewCommitRow() },
    ) {
        PREVIEW_SOURCES.forEachIndexed { index, source ->
            ListRow(
                state = ListRowState(
                    title = source.substringBefore(SOURCE_SEPARATOR),
                    subtitle = source.substringAfter(SOURCE_SEPARATOR),
                    selected = index == 0,
                ),
                onClick = {},
            )
        }
    }
}

/**
 * Stands in for the real Apply/Cancel buttons, which belong to the buttons group of the design
 * system and do not exist yet. The geometry is the design's:
 * [CloudStreamDimens.TextButtonHeight] tall, [CloudStreamDimens.TextButtonMinWidth] wide,
 * [CloudStreamDimens.MinFocusGap] apart, centred in the 48dp commit row.
 */
@Composable
private fun PreviewCommitRow() {
    Row(
        modifier = Modifier.height(CloudStreamDimens.ListRowHeight),
        horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val colors = CloudStreamTheme.colors
        PreviewButton(label = "Cancel", fill = colors.raised, ink = colors.ink)
        PreviewButton(label = "Apply", fill = colors.ink, ink = colors.bg)
    }
}

@Composable
private fun PreviewButton(label: String, fill: Color, ink: Color) {
    val style: TextStyle = CloudStreamTheme.typography.label
    Box(
        modifier = Modifier
            .height(CloudStreamDimens.TextButtonHeight)
            .defaultMinSize(minWidth = CloudStreamDimens.TextButtonMinWidth)
            .background(fill, CloudStreamTheme.shapes.medium)
            .padding(horizontal = CloudStreamDimens.ListRowPadding),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text = label, style = remember(style, ink) { style.copy(color = ink) })
    }
}

private const val SOURCE_SEPARATOR = " · "

package com.lagradost.cloudstream4.core.designsystem.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.lagradost.cloudstream4.core.designsystem.rows.ListRow
import com.lagradost.cloudstream4.core.designsystem.rows.ListRowState
import com.lagradost.cloudstream4.core.designsystem.rows.RailHeader
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

// Previews of the panel are drawn at the artboard's own dp size (960x540) so the y values in
// SidePanel's KDoc can be checked against the render: first row at 128, Done row at 464.
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

/**
 * The multi-select kind: `Sources`, which under `DEC-17` applies on **Done** — one row, drawn
 * exactly as `TV Library` 5d and `TV Detail Player` 7e draw it, and not the Apply/Cancel pair this
 * preview held before that decision.
 */
@Composable
private fun PreviewSourcePanel() {
    SidePanel(
        header = { RailHeader(title = "SOURCES", count = PREVIEW_SOURCES.size.toString()) },
        commit = { ListRow(state = ListRowState(title = "Done"), onClick = {}) },
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
 * The single-choice kind: `Sort`, which applies at once and therefore has no bottom row at all.
 *
 * Also the disabled-with-a-reason row from `TV Library` 5d — *"Your rating"*, which Local cannot
 * sort by — which stays a focus stop so its reason can be read.
 */
@Preview(widthDp = ARTBOARD_WIDTH, heightDp = ARTBOARD_HEIGHT)
@Composable
private fun SidePanelSingleChoicePreview() {
    CloudStreamTheme {
        SidePanelHost(
            expanded = true,
            onDismissRequest = {},
            panel = {
                SidePanel(header = { RailHeader(title = "SORT", count = "4") }) {
                    ListRow(
                        state = ListRowState(
                            title = "Last updated",
                            subtitle = "WHEN YOU LAST CHANGED IT",
                            selected = true,
                        ),
                        onClick = {},
                    )
                    ListRow(
                        state = ListRowState(title = "Title", subtitle = "ALPHABETICAL"),
                        onClick = {},
                    )
                    ListRow(
                        state = ListRowState(
                            title = "Your rating",
                            subtitle = "NOT STORED BY LOCAL LISTS",
                            enabled = false,
                            focusableWhenDisabled = true,
                        ),
                        onClick = {},
                    )
                }
            },
            content = { Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) },
        )
    }
}

private const val SOURCE_SEPARATOR = " · "

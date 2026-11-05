package com.lagradost.cloudstream4.core.designsystem.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusDot
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusDotState
import com.lagradost.cloudstream4.core.designsystem.rail.NavRail
import com.lagradost.cloudstream4.core.designsystem.rail.NavRailItem
import com.lagradost.cloudstream4.core.designsystem.rows.ListRow
import com.lagradost.cloudstream4.core.designsystem.rows.ListRowState
import com.lagradost.cloudstream4.core.designsystem.rows.RailHeader
import com.lagradost.cloudstream4.core.devicetest.assertScreenshot
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `TV Search` 4d: an open source panel over a screen, with the rail dimmed beside it. The rail carries
 * [sidePanelOutsideDim] as the TV shell's does, so the case shows what the review found missing: the rail dimmed with
 * the page, the panel on `surface` with its selected row `raised`, and the header at the top. On Android TV the rail is a
 * separate view; `TvRailNavigationTest` measures that on the real shell.
 */
@RunWith(AndroidJUnit4::class)
class SidePanelScreenshotDeviceTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun screenshotPanelOpenOverTheRail() {
        rule.setContent {
            CloudStreamTheme {
                Row(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                    NavRail(
                        items = Items,
                        selectedIndex = SEARCH_INDEX,
                        onSelect = {},
                        modifier = Modifier.sidePanelOutsideDim(CloudStreamTheme.colors.bg),
                    )
                    SidePanelHost(
                        expanded = true,
                        onDismissRequest = {},
                        modifier = Modifier.weight(1f),
                        panel = { SourcesPanel() },
                    ) {
                        Box(Modifier.fillMaxSize().padding(start = CloudStreamDimens.MinFocusGap, top = CloudStreamDimens.ChromeRowTop)) {
                            Box(Modifier.size(width = FIELD_WIDTH.dp, height = CloudStreamDimens.ChromeRowHeight).background(CloudStreamTheme.colors.surface))
                        }
                    }
                }
            }
        }
        rule.waitUntil(WAIT_MS) { SidePanelOutsideDim.alpha > 0f }
        rule.waitForIdle()
        rule.onNodeWithText(ROWS.first()).assertExists()
        assertTrue("the rail is dimmed", SidePanelOutsideDim.alpha > 0f)
        rule.assertScreenshot("panel-open-over-rail")
    }

    @Composable
    private fun SourcesPanel() {
        SidePanel(
            header = { RailHeader(title = "Sources", count = "3 OF 5") },
            commit = { ListRow(state = ListRowState(title = "Done"), onClick = {}, modifier = Modifier.fillMaxWidth()) },
        ) {
            ROWS.forEachIndexed { index, name ->
                ListRow(
                    state = ListRowState(title = name, subtitle = "EN · MOVIES", selected = index != 1),
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                    trailing = { StatusDot(if (index == 2) StatusDotState.Failed else StatusDotState.Ok) },
                )
            }
        }
    }

    private companion object {
        const val SEARCH_INDEX = 1
        const val FIELD_WIDTH = 480
        const val WAIT_MS = 5_000L
        val ROWS = listOf("Vidsrc", "Gogoanime", "Zoro")
        val Items = listOf(
            NavRailItem("Home", CloudStreamIcons.Home),
            NavRailItem("Search", CloudStreamIcons.Search),
            NavRailItem("Library", CloudStreamIcons.Library),
            NavRailItem("Downloads", CloudStreamIcons.Download),
            NavRailItem("Settings", CloudStreamIcons.Settings),
        )
    }
}

package com.lagradost.cloudstream4.core.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.tooling.preview.Preview
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.rail.NavRail
import com.lagradost.cloudstream4.core.designsystem.rail.NavRailItem
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * The [NavRail] with the items `DEC-17` ruled. Like the rest of this suite these render nothing until the plugin
 * works on this toolchain (`DS-04`). Only focus expands the rail, so the expanded case asks for focus in an effect;
 * whether the plugin captures after effects run cannot be known until it renders. `NavRailTest` holds that state today.
 */
private val DecSeventeenItems = listOf(
    NavRailItem("Home", CloudStreamIcons.Home),
    NavRailItem("Search", CloudStreamIcons.Search),
    NavRailItem("Library", CloudStreamIcons.Library),
    NavRailItem("Downloads", CloudStreamIcons.Download),
    NavRailItem("Settings", CloudStreamIcons.Settings),
)

@Composable
private fun RailSurface(content: @Composable () -> Unit) {
    CloudStreamTheme {
        Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.raised)) { content() }
    }
}

/** At rest: 76dp, glyphs only, the current page raised. */
@Preview(name = "NavRail - collapsed, Home current", widthDp = 240, heightDp = 360)
@Composable
fun NavRailCollapsed() {
    RailSurface { NavRail(DecSeventeenItems, selectedIndex = 0, onSelect = {}) }
}

/** Focus on Home, with Library current: the two marks must not read alike, and the band must paint past 76dp. */
@Preview(name = "NavRail - expanded, focus on Home, Library current", widthDp = 240, heightDp = 360)
@Composable
fun NavRailExpanded() {
    val requester = remember { FocusRequester() }
    RailSurface {
        NavRail(DecSeventeenItems, selectedIndex = 2, onSelect = {}, Modifier.focusRequester(requester))
    }
    LaunchedEffect(requester) { requester.requestFocus() }
}

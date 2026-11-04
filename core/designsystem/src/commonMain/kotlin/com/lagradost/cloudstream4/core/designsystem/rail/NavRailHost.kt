package com.lagradost.cloudstream4.core.designsystem.rail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * A [NavRail] with what `TV Home` 3b draws around it and `DS-09` left to its host: the dim over the page and the
 * rail's shadow. Both exist only while focus is inside the rail.
 *
 * It is meant to be the only thing in its own window-level view, so it owns the keys there: towards the page calls
 * [onLeave], away from the page does nothing, and up and down stay inside the rail. Left to the platform, up from
 * the first item or down from the last would fall through to the nearest thing on the page.
 *
 * @param onLeave move focus back to the page. The page is the host's, so this composable does not know what yielded
 *   focus.
 * @param onExpandedChange the view hosting this must be given the whole screen while this is true, and may be
 *   [com.lagradost.cloudstream4.core.theme.CloudStreamDimens.NavRailWidth] wide otherwise.
 */
@Composable
fun NavRailHost(
    items: List<NavRailItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
    onExpandedChange: (Boolean) -> Unit = {},
    logo: (@Composable () -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val towardsPage = if (LocalLayoutDirection.current == LayoutDirection.Rtl) Key.DirectionLeft else Key.DirectionRight
    val awayFromPage = if (towardsPage == Key.DirectionLeft) Key.DirectionRight else Key.DirectionLeft
    val ground = CloudStreamTheme.colors.bg
    Box(modifier.fillMaxSize()) {
        if (expanded) {
            Box(Modifier.fillMaxSize().background(ground.copy(alpha = NavRailHostDefaults.DIM_ALPHA)))
            Box(
                Modifier
                    .padding(start = NavRailHostDefaults.ShadowStart)
                    .width(NavRailHostDefaults.ShadowWidth)
                    .fillMaxHeight()
                    .background(shadowBrush(ground)),
            )
        }
        NavRail(
            items = items,
            selectedIndex = selectedIndex,
            onSelect = onSelect,
            logo = logo,
            modifier = Modifier
                .onFocusChanged {
                    if (expanded != it.hasFocus) {
                        expanded = it.hasFocus
                        onExpandedChange(it.hasFocus)
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        towardsPage -> onLeave()
                        awayFromPage -> Unit
                        Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                        Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                },
        )
    }
}

private fun shadowBrush(ground: Color): Brush = Brush.horizontalGradient(
    *NavRailHostDefaults.SHADOW_STOPS
        .map { (stop, alpha) -> stop to ground.copy(alpha = alpha) }
        .toTypedArray(),
)

/** Values [NavRailHost] draws, from `TV Home` 3b and its `.raile`. */
@Immutable
object NavRailHostDefaults {

    /** The dim over the page, `rgba(0,0,0,0.46)` on 3b. */
    const val DIM_ALPHA: Float = 0.46f

    /**
     * Where the shadow starts. It runs on behind the rail as far as the rail is solid, which is 72% of
     * [NavRailDefaults.ExpandedWidth], so no edge of it shows.
     */
    val ShadowStart: Dp = 136.dp

    /** From [ShadowStart] to where the shadow has faded out, at 236dp. */
    val ShadowWidth: Dp = 100.dp

    /**
     * `.raile`'s `box-shadow: 24px 0 68px rgba(0,0,0,0.88)` in dp: it starts at 200dp, 12dp past the rail, with a
     * sigma of 17dp. The rail is as tall as the screen, so the shadow only varies across it, and this is that curve
     * sampled every sigma over [ShadowWidth].
     */
    val SHADOW_STOPS: List<Pair<Float, Float>> = listOf(
        0f to 0.88f,
        0.30f to 0.86f,
        0.47f to 0.74f,
        0.64f to 0.44f,
        0.81f to 0.14f,
        0.98f to 0.02f,
        1f to 0f,
    )
}

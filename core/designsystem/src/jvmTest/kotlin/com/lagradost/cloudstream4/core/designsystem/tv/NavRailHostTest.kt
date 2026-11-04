package com.lagradost.cloudstream4.core.designsystem.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.rail.NavRailDefaults
import com.lagradost.cloudstream4.core.designsystem.rail.NavRailHost
import com.lagradost.cloudstream4.core.designsystem.rail.NavRailHostDefaults
import com.lagradost.cloudstream4.core.designsystem.rail.NavRailItem
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [NavRailHost] drawn offscreen at density 1 on a blue page, so a pixel coordinate reads as dp. */
class NavRailHostTest {

    private val items = listOf("A", "B", "C").map { NavRailItem(it, CloudStreamIcons.Home) }

    private inner class Rig(direction: LayoutDirection = LayoutDirection.Ltr) {
        var selected by mutableStateOf(1)
        var pageHasFocus = false
        var leaves = 0
        val expansions = mutableListOf<Boolean>()
        private var manager: FocusManager? = null

        val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                CloudStreamTheme {
                    manager = LocalFocusManager.current
                    Box(Modifier.fillMaxSize().background(Color.Blue)) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(start = CloudStreamDimens.NavRailWidth)
                                .onFocusChanged { pageHasFocus = it.hasFocus }
                                .focusable(),
                        )
                        NavRailHost(
                            items = items,
                            selectedIndex = selected,
                            onSelect = { selected = it },
                            onLeave = { leaves++ },
                            onExpandedChange = { expansions += it },
                        )
                    }
                }
            }
        }

        /** Focus on the page, then one move towards the rail. */
        fun openRail(towards: FocusDirection = FocusDirection.Left) {
            scene.render()
            requireNotNull(manager).moveFocus(FocusDirection.Next)
            scene.render()
            assertTrue(pageHasFocus, "the rig starts with focus on the page")
            requireNotNull(manager).moveFocus(towards)
            scene.render()
        }

        fun moveTo(direction: FocusDirection) {
            requireNotNull(manager).moveFocus(direction)
            scene.render()
        }

        fun frame(): PixelMap {
            scene.render()
            return scene.render().toComposeImageBitmap().toPixelMap()
        }

        // The only way to build a key event without a window. Test scope, as in NavRailTest.
        @OptIn(InternalComposeUiApi::class)
        fun press(key: Key) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
            scene.render()
        }
    }

    private fun rig(direction: LayoutDirection = LayoutDirection.Ltr, block: Rig.() -> Unit) {
        val rig = Rig(direction)
        try {
            rig.block()
        } finally {
            rig.scene.close()
        }
    }

    private fun PixelMap.blue(x: Int, y: Int = HEIGHT / 2): Int = (this[x, y].blue * 255).toInt()

    private fun assertNear(expected: Int, actual: Int, tolerance: Int, what: String) =
        assertTrue(abs(expected - actual) <= tolerance, "$what: expected about $expected, got $actual")

    @Test
    fun `nothing covers the page while the rail is collapsed`() = rig {
        scene.render()
        assertEquals(PAGE_BLUE, frame().blue(FAR_RIGHT))
        assertEquals(PAGE_BLUE, frame().blue(SHADOW_MIDDLE))
    }

    @Test
    fun `focus in the rail dims the page by 46 percent`() = rig {
        openRail()
        assertNear((PAGE_BLUE * (1 - NavRailHostDefaults.DIM_ALPHA)).toInt(), frame().blue(FAR_RIGHT), 1, "dim")
    }

    @Test
    fun `when focus goes back to the page the dim comes off`() = rig {
        openRail()
        moveTo(FocusDirection.Right)
        assertTrue(pageHasFocus, "the rig's page took focus")
        assertEquals(PAGE_BLUE, frame().blue(FAR_RIGHT))
        assertEquals(listOf(true, false), expansions, "reported once each way")
    }

    @Test
    fun `the shadow falls off past the rail's edge and is gone by 236`() = rig {
        openRail()
        val frame = frame()
        val dimmed = (PAGE_BLUE * (1 - NavRailHostDefaults.DIM_ALPHA)).toInt()
        assertTrue(frame.blue(SHADOW_MIDDLE) < dimmed, "darker than the dim at the middle of the shadow")
        assertTrue(frame.blue(SHADOW_MIDDLE) < frame.blue(SHADOW_MIDDLE + 16), "and it fades outwards")
        assertNear(dimmed, frame.blue(SHADOW_END), 1, "past the shadow only the dim is left")
    }

    @Test
    fun `its shadow starts behind the solid part of the rail`() {
        val solidEnd = NavRailDefaults.ExpandedWidth * NavRailDefaults.EXPANDED_SOLID_STOP
        assertTrue(NavRailHostDefaults.ShadowStart >= solidEnd - 1.dp, "starts at ${NavRailHostDefaults.ShadowStart}")
        assertTrue(NavRailHostDefaults.ShadowStart < NavRailDefaults.ExpandedWidth)
        assertEquals(236.dp, NavRailHostDefaults.ShadowStart + NavRailHostDefaults.ShadowWidth)
    }

    @Test
    fun `up and down stay in the rail at both ends`() = rig {
        openRail()
        press(Key.DirectionUp)
        press(Key.DirectionUp)
        press(Key.DirectionUp)
        assertTrue(!pageHasFocus, "up from the first item stays in the rail")
        repeat(5) { press(Key.DirectionDown) }
        assertTrue(!pageHasFocus, "down from the last item stays in the rail")
        assertEquals(listOf(true), expansions, "and it never collapsed on the way")
    }

    @Test
    fun `away from the page does nothing`() = rig {
        openRail()
        press(Key.DirectionLeft)
        assertEquals(0, leaves)
        assertEquals(listOf(true), expansions)
    }

    @Test
    fun `only right leaves in a left to right layout, only left in a right to left one`() {
        rig {
            openRail()
            press(Key.DirectionRight)
            assertEquals(1, leaves)
        }
        rig(LayoutDirection.Rtl) {
            openRail(FocusDirection.Right)
            press(Key.DirectionRight)
            assertEquals(0, leaves, "right is away from the page")
            press(Key.DirectionLeft)
            assertEquals(1, leaves)
        }
    }

    @Test
    fun `other keys are not the rail's, so committing still works`() = rig {
        openRail()
        assertEquals(1, selected)
        press(Key.DirectionDown)
        press(Key.Enter)
        assertEquals(2, selected)
    }

    private companion object {
        const val WIDTH = 960
        const val HEIGHT = 540
        const val PAGE_BLUE = 255
        const val FAR_RIGHT = 600
        const val SHADOW_MIDDLE = 200
        const val SHADOW_END = 240
    }
}

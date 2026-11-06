package com.lagradost.cloudstream4.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.lagradost.cloudstream4.DesktopApp
import com.lagradost.cloudstream4.EscapeHandler
import com.lagradost.cloudstream4.core.designsystem.desktop.forDesktop
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.LocalCloudStreamTypography
import com.lagradost.cloudstream4.feature.home.HomePageReply
import com.lagradost.cloudstream4.feature.home.HomeSource
import com.lagradost.cloudstream4.feature.home.HomeSourceId
import com.lagradost.cloudstream4.feature.home.HomeSources
import com.lagradost.cloudstream4.library.InMemoryLibraryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The window's sidebar and its ⌘ keys (`KMP-53`), through the whole app with nothing installed. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class DesktopShellTest {

    private object EmptyHome : HomeSource {
        override suspend fun sources() = HomeSources(emptyList(), installed = 0)

        override suspend fun page(source: HomeSourceId, section: Int, page: Int) = HomePageReply.Offline
    }

    private var at = ""
    private val shortcuts = DesktopShortcuts()

    private fun scene() = ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
        CloudStreamTheme {
            CompositionLocalProvider(LocalCloudStreamTypography provides CloudStreamTheme.typography.forDesktop()) {
                Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                    DesktopApp(EscapeHandler(), EmptyHome, library = InMemoryLibraryStore(), onScreen = { at = it }, shortcuts = shortcuts)
                }
            }
        }
    }

    private fun ImageComposeScene.settle() = repeat(SETTLE_FRAMES) { render(it * FRAME_NANOS) }

    private fun command(key: Key) = shortcuts.handle(KeyEvent(key, KeyEventType.KeyDown, isMetaPressed = true))

    @Test
    fun `command 1 to 3 go to the sidebar's destinations in order`() {
        val scene = scene()
        scene.settle()
        assertEquals("Home", at)
        assertTrue(command(Key.Two))
        scene.settle()
        assertEquals("Search", at)
        assertTrue(command(Key.Three))
        scene.settle()
        assertEquals("Library", at)
        assertTrue(command(Key.One))
        scene.settle()
        assertEquals("Home", at)
        scene.close()
    }

    @Test
    fun `command left bracket does nothing where nothing is under, and a plain 2 is not a shortcut`() {
        val scene = scene()
        scene.settle()
        assertFalse(command(Key.LeftBracket))
        assertFalse(shortcuts.handle(KeyEvent(Key.Two, KeyEventType.KeyDown)))
        assertEquals("Home", at)
        scene.close()
    }

    @Test
    fun `a click on a sidebar row goes there`() {
        val scene = scene()
        scene.settle()
        val search = Offset(ITEM_X, SEARCH_Y)
        scene.sendPointerEvent(PointerEventType.Move, search)
        scene.sendPointerEvent(PointerEventType.Press, search)
        scene.sendPointerEvent(PointerEventType.Release, search)
        scene.settle()
        assertEquals("Search", at)
        scene.close()
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 800
        const val SETTLE_FRAMES = 30
        const val FRAME_NANOS = 50_000_000L
        const val ITEM_X = 100f
        const val SEARCH_Y = 76f
    }
}

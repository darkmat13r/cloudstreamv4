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
import androidx.compose.ui.input.key.KeyShortcut
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

/** The window's sidebar and the Go menu's routing and keys (`KMP-53`), through the whole app with nothing installed. */
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

    @Test
    fun `the Go menu's destinations go there in the sidebar's order, at the top level only`() {
        val scene = scene()
        scene.settle()
        assertEquals("Home", at)
        assertTrue(shortcuts.atTop)
        assertFalse(shortcuts.canGoBack, "nothing is under home, so Back is greyed")
        shortcuts.go(TopLevelDestination.Search)
        scene.settle()
        assertEquals("Search", at)
        shortcuts.go(TopLevelDestination.Library)
        scene.settle()
        assertEquals("Library", at)
        shortcuts.go(TopLevelDestination.Home)
        scene.settle()
        assertEquals("Home", at)
        scene.close()
    }

    @Test
    fun `the keys are the Mac's on a Mac and the platform's own elsewhere`() {
        assertEquals(KeyShortcut(Key.Two, meta = true), DesktopShortcuts.keyFor(TopLevelDestination.Search, mac = true))
        assertEquals(KeyShortcut(Key.Two, ctrl = true), DesktopShortcuts.keyFor(TopLevelDestination.Search, mac = false))
        assertEquals(KeyShortcut(Key.LeftBracket, meta = true), DesktopShortcuts.backKey(mac = true))
        assertEquals(KeyShortcut(Key.DirectionLeft, alt = true), DesktopShortcuts.backKey(mac = false))
        val keys = TopLevelDestination.entries.map { DesktopShortcuts.keyFor(it, mac = true) }
        assertEquals(listOf(Key.One, Key.Two, Key.Three).map { KeyShortcut(it, meta = true) }, keys, "in the sidebar's order")
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

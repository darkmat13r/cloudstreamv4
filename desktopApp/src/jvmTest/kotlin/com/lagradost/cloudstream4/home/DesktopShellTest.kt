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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.lagradost.cloudstream4.DesktopApp
import com.lagradost.cloudstream4.EscapeHandler
import com.lagradost.cloudstream4.assertPageBar
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopPageBarDefaults
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopTypography
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.LocalCloudStreamTypography
import com.lagradost.cloudstream4.feature.home.HomePageReply
import com.lagradost.cloudstream4.feature.home.HomeSource
import com.lagradost.cloudstream4.feature.home.HomeSourceId
import com.lagradost.cloudstream4.feature.home.HomeSources
import com.lagradost.cloudstream4.library.InMemoryLibraryStore
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.JRootPane
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
    private var settingsOpened = 0
    private val sidebarShown = mutableListOf<Boolean>()

    private fun scene(onOpenSettings: (() -> Unit)? = { settingsOpened++ }) = ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
        CloudStreamTheme {
            CompositionLocalProvider(LocalCloudStreamTypography provides desktopTypography()) {
                Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                    DesktopApp(
                        EscapeHandler(),
                        EmptyHome,
                        library = InMemoryLibraryStore(),
                        onScreen = { at = it },
                        shortcuts = shortcuts,
                        onOpenSettings = onOpenSettings,
                        onSidebarShown = { sidebarShown += it },
                    )
                }
            }
        }
    }

    private fun ImageComposeScene.click(at: Offset) {
        sendPointerEvent(PointerEventType.Move, at)
        sendPointerEvent(PointerEventType.Press, at)
        sendPointerEvent(PointerEventType.Release, at)
        settle()
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> = semanticsOwners.flatMap { it.rootSemanticsNode.all() }

    private fun SemanticsNode.all(): List<SemanticsNode> = listOf(this) + children.flatMap { it.all() }

    private fun SemanticsNode.words(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + children.flatMap { it.words() }

    /** The sidebar's rows, top to bottom, by their words: the tabs and the Settings button. */
    private fun ImageComposeScene.sidebarRows(): List<SemanticsNode> = nodes()
        .filter { node ->
            val role = node.config.getOrNull(SemanticsProperties.Role)
            (role == Role.Tab || role == Role.Button) && node.boundsInRoot.right <= DesktopShellDefaults.SidebarWidth.value
        }
        .sortedBy { it.boundsInRoot.top }

    private fun ImageComposeScene.save(name: String) {
        val directory = System.getProperty("cloudstream.shell.renders") ?: return
        settle()
        val png = checkNotNull(render(SETTLE_FRAMES * FRAME_NANOS).encodeToData(EncodedImageFormat.PNG)).bytes
        File(directory, "$name.png").writeBytes(png)
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
        assertEquals(listOf(Key.One, Key.Two, Key.Three, Key.Four).map { KeyShortcut(it, meta = true) }, keys, "in the sidebar's order")
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

    @Test
    fun `the sidebar's rows start under the title band, where the traffic lights are (KMP-98, D1)`() {
        val scene = scene()
        scene.settle()
        scene.save("D1-window-home")
        val first = scene.sidebarRows().first()
        assertEquals(listOf("Home"), first.words())
        assertEquals(DesktopShellDefaults.TitleBand.value, first.boundsInRoot.top, "the first row starts where the page bar ends")
        assertEquals(DesktopPageBarDefaults.Height, DesktopShellDefaults.TitleBand, "one band across the window")
        scene.assertPageBar(WIDTH, atNanos = SETTLE_FRAMES * FRAME_NANOS, left = DesktopShellDefaults.SidebarWidth.value.toInt())
        scene.close()
    }

    @Test
    fun `Settings is the sidebar's last row, and a click on it opens the window (KMP-98)`() {
        val scene = scene()
        scene.settle()
        val rows = scene.sidebarRows()
        val settings = rows.last()
        assertEquals("Settings", settings.words().first(), "below every destination, at the foot")
        assertEquals(Role.Button, settings.config[SemanticsProperties.Role], "it opens a window; it is not a place")
        assertEquals(listOf("Home", "Search", "Library", "Downloads"), rows.dropLast(1).map { it.words().first() })
        val shownKey = settings.words().drop(1)
        assertEquals(if (DesktopShortcuts.onMac) listOf("⌘,") else emptyList(), shownKey, "the app menu's key, on a Mac only")
        assertTrue(settings.boundsInRoot.bottom <= HEIGHT - DesktopDimens.WindowMargin.value, "inside the column's foot margin")
        scene.click(settings.boundsInRoot.center)
        assertEquals(1, settingsOpened)
        assertEquals("Home", at, "the page under it stays")
        scene.close()
    }

    @Test
    fun `with nothing to open Settings there is no row`() {
        val scene = scene(onOpenSettings = null)
        scene.settle()
        assertEquals(listOf("Home", "Search", "Library", "Downloads"), scene.sidebarRows().map { it.words().first() })
        scene.close()
    }

    @Test
    fun `the window is told the sidebar is shown, on every page that has one (KMP-98)`() {
        val scene = scene()
        scene.settle()
        TopLevelDestination.entries.forEach { destination ->
            shortcuts.go(destination)
            scene.settle()
        }
        assertTrue(sidebarShown.isNotEmpty())
        assertTrue(sidebarShown.all { it }, "home, search, library and downloads each have the sidebar")
        scene.close()
    }

    @Test
    fun `the title bar is over the content while the sidebar is shown, and plain otherwise (KMP-98)`() {
        val root = JRootPane()
        root.titleBarOverContent(true)
        assertEquals(true, root.getClientProperty("apple.awt.fullWindowContent"))
        assertEquals(true, root.getClientProperty("apple.awt.transparentTitleBar"))
        assertEquals(false, root.getClientProperty("apple.awt.windowTitleVisible"), "the title would sit over the page bar")
        root.titleBarOverContent(false)
        assertEquals(false, root.getClientProperty("apple.awt.fullWindowContent"))
        assertEquals(false, root.getClientProperty("apple.awt.transparentTitleBar"))
        assertEquals(true, root.getClientProperty("apple.awt.windowTitleVisible"), "the player's window keeps its name")
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 800
        const val SETTLE_FRAMES = 30
        const val FRAME_NANOS = 50_000_000L
        const val ITEM_X = 100f

        /** Search's row: the second, under the 52 title band (`KMP-98`), a 32 row and a 4 gap down, and half a row. */
        const val SEARCH_Y = 104f
    }
}

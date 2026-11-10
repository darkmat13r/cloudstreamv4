package com.lagradost.cloudstream4.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.lagradost.cloudstream4.DesktopApp
import com.lagradost.cloudstream4.EscapeHandler
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopSidebarRowDefaults
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopTypography
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.LocalCloudStreamTypography
import com.lagradost.cloudstream4.core.theme.cloudStreamDarkColors
import com.lagradost.cloudstream4.feature.home.HomePageReply
import com.lagradost.cloudstream4.feature.home.HomeSource
import com.lagradost.cloudstream4.feature.home.HomeSourceId
import com.lagradost.cloudstream4.feature.home.HomeSources
import com.lagradost.cloudstream4.feature.settings.SettingsPane
import com.lagradost.cloudstream4.feature.settings.SettingsRepository
import com.lagradost.cloudstream4.feature.settings.SettingsStorage
import com.lagradost.cloudstream4.feature.settings.desktop.SettingsWindowDefaults
import com.lagradost.cloudstream4.feature.settings.desktop.SettingsWindowState
import com.lagradost.cloudstream4.library.InMemoryLibraryStore
import com.lagradost.cloudstream4.settings.SettingsContent
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `KMP-111`: the sidebar column is `CloudStreamMaterial.Sidebar`, a step lighter than the page, in the main window and
 * the Settings window alike, read from the rendered pixels. The tone's contrast is measured on
 * `CloudStreamPalette.Sidebar`; here the screens are held to the token.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class SidebarMaterialTest {
    private val colors = cloudStreamDarkColors()

    private object EmptyHome : HomeSource {
        override suspend fun sources() = HomeSources(emptyList(), installed = 0)
        override suspend fun page(source: HomeSourceId, section: Int, page: Int) = HomePageReply.Offline
    }

    private fun themed(width: Int, height: Int, content: @Composable () -> Unit) = ImageComposeScene(width, height, Density(1f)) {
        CloudStreamTheme {
            CompositionLocalProvider(LocalCloudStreamTypography provides desktopTypography()) {
                Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) { content() }
            }
        }
    }

    private inline fun ImageComposeScene.use(block: (ImageComposeScene) -> Unit) {
        try { block(this) } finally { close() }
    }

    private fun ImageComposeScene.pixel(x: Int, y: Int): Color {
        repeat(SETTLE_FRAMES) { render(it * FRAME_NANOS) }
        return render(SETTLE_FRAMES * FRAME_NANOS).toComposeImageBitmap().toPixelMap()[x, y]
    }

    private fun SemanticsNode.all(): List<SemanticsNode> = listOf(this) + children.flatMap { it.all() }
    private fun SemanticsNode.words(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + children.flatMap { it.words() }

    /** The smallest sidebar node that says [word]: the row itself, not the column. */
    private fun ImageComposeScene.row(word: String) = semanticsOwners.flatMap { it.rootSemanticsNode.all() }
        .filter { it.boundsInRoot.right <= SIDEBAR && it.config.getOrNull(SemanticsProperties.Role) != null && word in it.words() }
        .minBy { it.boundsInRoot.width * it.boundsInRoot.height }.boundsInRoot

    private fun assertColor(expected: Color, actual: Color, what: String) {
        // The PNG path is exact; a channel off by one would be the rasteriser, not a different colour.
        val close = listOf(expected.red - actual.red, expected.green - actual.green, expected.blue - actual.blue).all { abs(it) <= 1f / 255 }
        assertTrue(close && actual.alpha == 1f, "$what: expected $expected, was $actual")
    }

    @Test
    fun `the main window's sidebar is the sidebar tone, title band included, and the page is the ground`() {
        themed(WIDTH, HEIGHT) { DesktopApp(EscapeHandler(), EmptyHome, library = InMemoryLibraryStore(), onOpenSettings = {}) }.use { scene ->
            assertColor(colors.sidebar, scene.pixel(110, 20), "the title band over the column")
            assertColor(colors.sidebar, scene.pixel(110, 400), "the column's middle")
            assertColor(colors.bg, scene.pixel(700, 400), "the page")
            assertNotEquals(colors.surface, colors.sidebar, "a step of its own, not the inset surface it was")
        }
    }

    @Test
    fun `a chosen row is the line fill with its marker, and a row under the pointer is ink at the named alpha`() {
        themed(WIDTH, HEIGHT) { DesktopApp(EscapeHandler(), EmptyHome, library = InMemoryLibraryStore(), onOpenSettings = {}) }.use { scene ->
            scene.pixel(0, 0)
            val home = scene.row("Home")
            // Home is chosen at start: its fill at the row's trailing end, its marker at the leading.
            assertColor(colors.line, scene.pixel(home.right.toInt() - 8, home.center.y.toInt()), "the chosen row's fill")
            assertColor(colors.ink2, scene.pixel(home.left.toInt() + 14, home.center.y.toInt()), "the chosen row's marker")
            val search = scene.row("Search")
            scene.sendPointerEvent(PointerEventType.Move, search.center)
            val hover = colors.ink.copy(alpha = DesktopSidebarRowDefaults.HoverAlpha)
            val expected = Color(
                red = hover.red * hover.alpha + colors.sidebar.red * (1 - hover.alpha),
                green = hover.green * hover.alpha + colors.sidebar.green * (1 - hover.alpha),
                blue = hover.blue * hover.alpha + colors.sidebar.blue * (1 - hover.alpha),
            )
            assertColor(expected, scene.pixel(search.right.toInt() - 8, search.center.y.toInt()), "the row under the pointer")
            scene.sendPointerEvent(PointerEventType.Move, Offset(700f, 400f))
            assertColor(colors.sidebar, scene.pixel(search.right.toInt() - 8, search.center.y.toInt()), "and none once it leaves")
        }
    }

    @Test
    fun `the Settings window's sidebar is the same tone, so the two windows match`() {
        val root = Files.createTempDirectory("sidebar-material").toFile()
        try {
            val width = SettingsWindowDefaults.Width.value.toInt()
            themed(width, SettingsWindowDefaults.Height.value.toInt()) {
                SettingsContent(SettingsRepository(SettingsStorage.InMemory()), root, SettingsWindowState(pane = SettingsPane.Appearance))
            }.use { scene ->
                val sidebar = SettingsWindowDefaults.SidebarWidth.value.toInt()
                assertColor(colors.sidebar, scene.pixel(sidebar / 2, 20), "the title band over the column")
                assertColor(colors.sidebar, scene.pixel(sidebar / 2, 500), "the column's foot")
                assertColor(colors.bg, scene.pixel(width - 8, 500), "the pane")
                val appearance = scene.row("Appearance")
                assertColor(colors.line, scene.pixel(appearance.right.toInt() - 8, appearance.center.y.toInt()), "the chosen pane's fill")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the tone is the one measured`() {
        assertEquals(Color(0xFF1A1E24), colors.sidebar)
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 800
        const val SIDEBAR = 220f
        const val SETTLE_FRAMES = 30
        const val FRAME_NANOS = 50_000_000L
    }
}

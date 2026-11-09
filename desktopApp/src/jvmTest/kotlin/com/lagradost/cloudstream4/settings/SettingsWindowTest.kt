package com.lagradost.cloudstream4.settings

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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.lagradost.cloudstream4.core.designsystem.desktop.forDesktop
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.LocalCloudStreamTypography
import com.lagradost.cloudstream4.feature.settings.SettingsPane
import com.lagradost.cloudstream4.feature.settings.SettingsRepository
import com.lagradost.cloudstream4.feature.settings.SettingsStorage
import com.lagradost.cloudstream4.feature.settings.desktop.SettingsWindowDefaults
import com.lagradost.cloudstream4.feature.settings.desktop.SettingsWindowState
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `KMP-90`: the Settings window, part 1, through real pointer and key events in a scene. Each control is found by what
 * a screen reader is told, not by a pixel, so a layout change cannot make a test press the wrong thing. A PNG of each
 * state is written when `cloudstream.settings.renders` names a directory; they are looked at, and guard nothing.
 *
 * The download folder is a made-up name in a temporary directory: no real path is drawn or touched.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class SettingsWindowTest {
    private val home: File = Files.createTempDirectory("settings-window").toFile()
    private val folder = File(home, "Harbor Films").apply { mkdirs() }

    @AfterTest
    fun cleanUp() {
        home.deleteRecursively()
    }

    private fun scene(height: Int = HEIGHT, content: @Composable () -> Unit) =
        ImageComposeScene(WIDTH, height, Density(1f)) {
            CloudStreamTheme {
                CompositionLocalProvider(LocalCloudStreamTypography provides CloudStreamTheme.typography.forDesktop()) {
                    Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) { content() }
                }
            }
        }

    private fun window(
        settings: SettingsRepository,
        state: SettingsWindowState = SettingsWindowState(),
        root: File = folder,
        height: Int = HEIGHT,
    ) = scene(height) { SettingsContent(settings, root, state) }

    /** `KMP-90g`: the Appearance pane's first row. Home reads the same flow; `HomeScreenTest` shows it hiding Random. */
    @Test
    fun `Random Button writes its setting and reads it back after a restart`() {
        val storage = SettingsStorage.InMemory()
        val settings = SettingsRepository(storage)
        window(settings, SettingsWindowState(pane = SettingsPane.Appearance)).use { scene ->
            scene.save("appearance")
            assertEquals(true, scene.toggleState("Random Button"), "on by default")
            assertTrue(scene.allText().none { it.startsWith("On Home and Library") }, "no line the Mac would make untrue")
            scene.click(scene.bounds("Random Button").center)
            assertEquals(false, settings.showRandom.value)
            assertEquals("false", storage.stored["random_button_key"])
            scene.save("appearance-random-off")
        }
        window(SettingsRepository(SettingsStorage.InMemory(storage.stored))).use { scene ->
            assertEquals(false, scene.toggleState("Random Button"))
        }
    }

    @Test
    fun `Downloads at once writes the chosen count and reads it back`() {
        val storage = SettingsStorage.InMemory()
        val settings = SettingsRepository(storage)
        window(settings, SettingsWindowState(pane = SettingsPane.Downloads)).use { scene ->
            scene.save("downloads")
            scene.click(scene.bounds("Downloads at once, 3").center)
            scene.save("downloads-menu")
            scene.click(scene.bounds("5", text = true).center)
            assertEquals(5, settings.downloadsAtOnce.value)
            assertEquals("5", storage.stored["download_parallel_key"])
            assertNotNull(scene.find("Downloads at once, 5"), "the button reads the new count")
        }
    }

    @Test
    fun `the folder shows its name only, and Show in Finder only while it exists`() {
        val settings = SettingsRepository(SettingsStorage.InMemory())
        window(settings, SettingsWindowState(pane = SettingsPane.Downloads)).use { scene ->
            assertNotNull(scene.find("Harbor Films", text = true))
            assertNotNull(scene.find("Show in Finder", text = true))
            assertTrue(scene.allText().none { home.path in it }, "no path is drawn")
        }
        val missing = File(home, "Gone Disk")
        window(settings, SettingsWindowState(pane = SettingsPane.Downloads), root = missing).use { scene ->
            scene.save("downloads-missing")
            assertTrue(scene.allText().any { it.startsWith("Gone Disk is missing") })
            assertNull(scene.find("Show in Finder", text = true), "nothing to show is not offered")
            assertTrue(scene.allText().none { home.path in it }, "no path is drawn")
        }
    }

    @Test
    fun `search keeps only the panes it finds, says when it finds none, and Escape empties it`() {
        val settings = SettingsRepository(SettingsStorage.InMemory())
        val state = SettingsWindowState()
        window(settings, state).use { scene ->
            assertEquals(listOf("Appearance", "Downloads"), scene.panes())

            state.query = "at once"
            scene.settle()
            assertEquals(listOf("Downloads"), scene.panes())
            scene.save("search-found")

            state.query = "random"
            scene.settle()
            assertEquals(listOf("Appearance"), scene.panes())

            state.query = "episode"
            scene.settle()
            assertEquals(emptyList(), scene.panes(), "Play the next episode is not a row until Up next reads it")

            state.query = "cast"
            scene.settle()
            assertEquals(emptyList(), scene.panes())
            assertNotNull(scene.find("No results for “cast”", text = true))
            scene.save("search-none")

            scene.click(scene.bounds("Search").center)
            scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown))
            scene.settle()
            assertEquals("", state.query)
            assertEquals(listOf("Appearance", "Downloads"), scene.panes())
        }
    }

    @Test
    fun `the minimum window fits every pane across, and the sidebar keeps every pane`() {
        val settings = SettingsRepository(SettingsStorage.InMemory())
        val min = SettingsWindowDefaults.MinHeight.value.toInt()
        SettingsPane.entries.forEach { pane ->
            window(settings, SettingsWindowState(pane = pane), height = min).use { scene ->
                scene.save("minimum-${pane.name.lowercase()}")
                assertEquals(listOf("Appearance", "Downloads"), scene.panes())
                scene.nodes().forEach { node ->
                    val box = node.boundsInRoot
                    assertTrue(box.left >= 0f && box.right <= WIDTH, "nothing runs off the side: $box")
                }
            }
        }
        assertEquals(WIDTH, SettingsWindowDefaults.Width.value.toInt())
    }

    // ---- the scene --------------------------------------------------------------------------------------------------

    private fun ImageComposeScene.settle() = repeat(SETTLE_FRAMES) { render(it * FRAME_NANOS) }

    private fun ImageComposeScene.click(at: Offset) {
        sendPointerEvent(PointerEventType.Move, at)
        sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
        settle()
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        settle()
        return semanticsOwners.flatMap { owner -> owner.rootSemanticsNode.all() }
    }

    /** The node a screen reader names [label], or that draws exactly [label] as text. */
    private fun ImageComposeScene.find(label: String, text: Boolean = false): SemanticsNode? = nodes().lastOrNull { node ->
        if (text) {
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
        } else {
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == label }
        }
    }

    private fun ImageComposeScene.bounds(label: String, text: Boolean = false): Rect =
        checkNotNull(find(label, text)) { "nothing is named \"$label\"" }.boundsInRoot

    private fun ImageComposeScene.toggleState(label: String): Boolean? =
        find(label)?.config?.getOrNull(SemanticsProperties.ToggleableState)?.let { it == androidx.compose.ui.state.ToggleableState.On }

    private fun ImageComposeScene.allText(): List<String> =
        nodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    /** The panes the sidebar lists, in order: the nodes that are tabs. */
    private fun ImageComposeScene.panes(): List<String> = nodes()
        .filter { node -> node.config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Tab }
        .sortedBy { it.boundsInRoot.top }
        .map { node -> allTextUnder(node).first() }

    private fun allTextUnder(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::allTextUnder)

    private fun SemanticsNode.all(): List<SemanticsNode> = listOf(this) + children.flatMap { it.all() }

    private fun ImageComposeScene.save(name: String) {
        val directory = System.getProperty("cloudstream.settings.renders") ?: return
        settle()
        val png = checkNotNull(render(SETTLE_FRAMES * FRAME_NANOS).encodeToData(EncodedImageFormat.PNG)).bytes
        File(directory, "$name.png").writeBytes(png)
    }

    private inline fun ImageComposeScene.use(block: (ImageComposeScene) -> Unit) {
        try {
            settle()
            block(this)
        } finally {
            close()
        }
    }

    private companion object {
        val WIDTH = SettingsWindowDefaults.Width.value.toInt()
        val HEIGHT = SettingsWindowDefaults.Height.value.toInt()
        const val SETTLE_FRAMES = 30
        const val FRAME_NANOS = 50_000_000L
    }
}

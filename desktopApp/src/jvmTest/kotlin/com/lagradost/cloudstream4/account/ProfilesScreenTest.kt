package com.lagradost.cloudstream4.account

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
import com.lagradost.cloudstream4.DesktopApp
import com.lagradost.cloudstream4.EscapeHandler
import com.lagradost.cloudstream4.core.designsystem.desktop.forDesktop
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.LocalCloudStreamTypography
import com.lagradost.cloudstream4.feature.home.HomePageReply
import com.lagradost.cloudstream4.feature.home.HomeSource
import com.lagradost.cloudstream4.feature.home.HomeSourceId
import com.lagradost.cloudstream4.feature.home.HomeSources
import com.lagradost.cloudstream4.feature.library.LibraryStore
import com.lagradost.cloudstream4.feature.library.LibraryTitle
import com.lagradost.cloudstream4.feature.library.LibraryTitleId
import com.lagradost.cloudstream4.home.DesktopShortcuts
import com.lagradost.cloudstream4.home.TopLevelDestination
import kotlinx.coroutines.runBlocking
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
 * `KMP-95a`: the launch picker (`DS-25` A1, A15) and the sidebar's profile button and list (A2), through real pointer
 * and key events, over profiles and libraries in a temporary directory. Each control is found by what a screen reader
 * is told. A PNG of each state is written when `cloudstream.account.renders` names a directory; they are compared with
 * the boards by eye, and guard nothing.
 *
 * All names are made up.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ProfilesScreenTest {
    private val directory: File = Files.createTempDirectory("profiles-screen").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private object EmptyHome : HomeSource {
        override suspend fun sources() = HomeSources(emptyList(), installed = 0)

        override suspend fun page(source: HomeSourceId, section: Int, page: Int) = HomePageReply.Offline
    }

    /** Alex (the first profile, current), Sam and Robin. */
    private fun household(current: Int = 0): DesktopProfiles {
        val file = DesktopProfileFile(File(directory, DesktopProfileFile.FILE_NAME))
        file.save(
            StoredProfiles(
                current = current,
                profiles = listOf(StoredProfile(0, "Alex"), StoredProfile(1, "Sam"), StoredProfile(2, "Robin")),
            ),
        )
        return DesktopProfiles(file, libraryDirectory = directory, onSaveFailed = { throw it })
    }

    private fun scene(width: Int = WIDTH, height: Int = HEIGHT, content: @Composable () -> Unit) =
        ImageComposeScene(width, height, Density(1f)) {
            CloudStreamTheme {
                CompositionLocalProvider(LocalCloudStreamTypography provides CloudStreamTheme.typography.forDesktop()) {
                    Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) { content() }
                }
            }
        }

    @Test
    fun `with two profiles or more the window opens on the picker, and Return opens the current one`() {
        val profiles = household()
        val libraries = mutableListOf<LibraryStore>()
        scene { ProfilesHost(profiles, EscapeHandler()) { library, _ -> libraries += library } }.use { scene ->
            scene.save("A1-picker")
            assertNotNull(scene.find("Who's watching?", text = true))
            assertNotNull(scene.find("Alex, Current · No sync"))
            assertNotNull(scene.find("Sam, No sync"))
            assertTrue(libraries.isEmpty(), "nothing of a profile is open while the picker is up")
            scene.press(Key.Enter)
            assertNull(scene.find("Who's watching?", text = true))
            assertEquals(0, profiles.stored.current)
            assertTrue(libraries.isNotEmpty())
        }
    }

    @Test
    fun `the arrow keys move between cards, and Return opens the one with focus`() {
        val profiles = household()
        scene { ProfilesHost(profiles, EscapeHandler()) { _, _ -> } }.use { scene ->
            scene.press(Key.DirectionRight)
            scene.press(Key.DirectionRight)
            scene.press(Key.DirectionLeft)
            scene.save("A1-focus-sam")
            scene.press(Key.Enter)
            assertEquals(1, profiles.stored.current)
        }
    }

    @Test
    fun `a click opens the card under the pointer, and it is remembered for the next launch`() {
        val profiles = household()
        scene { ProfilesHost(profiles, EscapeHandler()) { _, _ -> } }.use { scene ->
            scene.click(scene.bounds("Robin, No sync").center)
            assertEquals(2, profiles.stored.current)
        }
        assertEquals(2, household(current = 2).stored.current)
        assertEquals(2, DesktopProfileFile(File(directory, DesktopProfileFile.FILE_NAME)).load().current)
    }

    @Test
    fun `with one profile there is no picker`() {
        val file = DesktopProfileFile(File(directory, DesktopProfileFile.FILE_NAME))
        val profiles = DesktopProfiles(file, libraryDirectory = directory)
        var opened = false
        scene { ProfilesHost(profiles, EscapeHandler()) { _, _ -> opened = true } }.use { scene ->
            assertNull(scene.find("Who's watching?", text = true))
            assertTrue(opened)
        }
    }

    @Test
    fun `at the minimum window the heading moves up and four cards fit in one row`() {
        val file = DesktopProfileFile(File(directory, DesktopProfileFile.FILE_NAME))
        file.save(
            StoredProfiles(
                profiles = listOf(StoredProfile(0, "Alex"), StoredProfile(1, "Sam"), StoredProfile(2, "Robin"), StoredProfile(3, "Kai")),
            ),
        )
        val profiles = DesktopProfiles(file, libraryDirectory = directory)
        val big = scene { ProfilesHost(profiles, EscapeHandler()) { _, _ -> } }
        val bigTop = big.use { it.bounds("Who's watching?", text = true).top }
        scene(MIN_WIDTH, MIN_HEIGHT) { ProfilesHost(profiles, EscapeHandler()) { _, _ -> } }.use { scene ->
            scene.save("A15-minimum")
            val heading = scene.bounds("Who's watching?", text = true)
            assertTrue(heading.top < bigTop, "the heading moves up: ${heading.top} under $bigTop")
            val cards = listOf("Alex, Current · No sync", "Sam, No sync", "Robin, No sync", "Kai, No sync").map { scene.bounds(it) }
            assertEquals(1, cards.map { it.top }.distinct().size, "one row: $cards")
            cards.forEach { assertTrue(it.left >= 0f && it.right <= MIN_WIDTH && it.bottom <= MIN_HEIGHT, "inside the window: $it") }
        }
    }

    @Test
    fun `the sidebar's profile button lists the profiles, and choosing one opens Home over that profile's library`() {
        val profiles = household()
        runBlocking { profiles.libraryFor(0).remember(LibraryTitle(LibraryTitleId("harbor"), "Harbor Lights", null, 0L)) }
        val shortcuts = DesktopShortcuts()
        var at = ""
        val seen = mutableListOf<LibraryStore>()
        val escape = EscapeHandler()
        scene {
            ProfilesHost(profiles, escape) { library, foot ->
                seen += library
                DesktopApp(escape, EmptyHome, library = library, onScreen = { at = it }, shortcuts = shortcuts, profileFoot = foot)
            }
        }.use { scene ->
            scene.press(Key.Enter) // Alex, from the picker
            shortcuts.go(TopLevelDestination.Library)
            scene.settle()
            assertEquals("Library", at)
            runBlocking { assertEquals(listOf("Harbor Lights"), seen.last().titles().map { it.title }, "Alex's window reads Alex's library") }

            val button = scene.bounds("Alex")
            assertTrue(button.bottom > HEIGHT - 80, "the button is at the sidebar's foot: $button")
            scene.click(button.center)
            scene.save("A2-list")
            val sam = scene.bounds("Sam, No sync")
            assertTrue(sam.bottom <= button.top, "the list opens above its button: $sam over $button")
            assertNotNull(scene.find("Alex, Current · No sync"), "the current one is listed, checked")

            scene.click(sam.center)
            assertEquals(1, profiles.stored.current)
            assertEquals("Home", at, "a new profile starts at Home")
            assertNotNull(scene.find("Sam"), "the button says who is watching now")
            assertNull(scene.find("Sam, No sync"), "the list closed")
            runBlocking { assertEquals(emptyList(), seen.last().titles(), "Sam's window reads Sam's library, with none of Alex's") }
        }
    }

    @Test
    fun `Escape, the window's or the list's own, and choosing the current profile close the list with nothing changed`() {
        val profiles = household()
        val escape = EscapeHandler()
        scene {
            ProfilesHost(profiles, escape) { library, foot -> DesktopApp(escape, EmptyHome, library = library, profileFoot = foot) }
        }.use { scene ->
            scene.press(Key.Enter)
            scene.click(scene.bounds("Alex").center)
            assertNotNull(scene.find("Sam, No sync"))
            scene.press(Key.Escape)
            assertNull(scene.find("Sam, No sync"), "the list's own Escape")

            scene.click(scene.bounds("Alex").center)
            assertTrue(escape.handle(), "the window's Escape goes to the list first")
            scene.settle()
            assertNull(scene.find("Sam, No sync"))
            assertNull(escape.overlay, "and lets go once it is closed")

            scene.click(scene.bounds("Alex").center)
            scene.click(scene.bounds("Alex, Current · No sync").center)
            assertNull(scene.find("Sam, No sync"))
            assertEquals(0, profiles.stored.current)
        }
    }

    // ---- the scene --------------------------------------------------------------------------------------------------

    private fun ImageComposeScene.settle() = repeat(SETTLE_FRAMES) { render(it * FRAME_NANOS) }

    /** A key as AWT sends it: desktop's `clickable` reads the native key code, not only [Key]. */
    private fun ImageComposeScene.press(key: Key) {
        val (code, char) = when (key) {
            Key.Enter -> java.awt.event.KeyEvent.VK_ENTER to '\n'
            Key.Escape -> java.awt.event.KeyEvent.VK_ESCAPE to '\u001b'
            Key.DirectionLeft -> java.awt.event.KeyEvent.VK_LEFT to java.awt.event.KeyEvent.CHAR_UNDEFINED
            Key.DirectionRight -> java.awt.event.KeyEvent.VK_RIGHT to java.awt.event.KeyEvent.CHAR_UNDEFINED
            else -> error("no native event for $key")
        }
        val source = java.awt.Container()
        val down = java.awt.event.KeyEvent(source, java.awt.event.KeyEvent.KEY_PRESSED, 0L, 0, code, char)
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, nativeEvent = down))
        val up = java.awt.event.KeyEvent(source, java.awt.event.KeyEvent.KEY_RELEASED, 0L, 0, code, char)
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, nativeEvent = up))
        settle()
    }

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

    private fun SemanticsNode.all(): List<SemanticsNode> = listOf(this) + children.flatMap { it.all() }

    private fun ImageComposeScene.save(name: String) {
        val directory = System.getProperty("cloudstream.account.renders") ?: return
        settle()
        val png = checkNotNull(render(SETTLE_FRAMES * FRAME_NANOS).encodeToData(EncodedImageFormat.PNG)).bytes
        File(directory, "$name.png").writeBytes(png)
    }

    private inline fun <T> ImageComposeScene.use(block: (ImageComposeScene) -> T): T {
        try {
            settle()
            return block(this)
        } finally {
            close()
        }
    }

    private companion object {
        /** `DS-25`'s window less macOS's 28 title bar, which the scene does not draw. */
        const val WIDTH = 1280
        const val HEIGHT = 772
        const val MIN_WIDTH = 800
        const val MIN_HEIGHT = 492
        const val SETTLE_FRAMES = 30
        const val FRAME_NANOS = 50_000_000L
    }
}

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
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `KMP-95c`: the PIN prompts (`DS-25` A3, A3b, A4, A4b, A6d, A7, A7b, A7c), through real key and pointer events, on a fake
 * boot clock so a wait can be run down without waiting. PINs are typed as digit keys. PNGs are written when
 * `cloudstream.account.renders` names a directory. All names and PINs are made up.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class PinFlowTest {
    private val directory: File = Files.createTempDirectory("pin-flow").toFile()
    private val file = DesktopProfileFile(File(directory, DesktopProfileFile.FILE_NAME))
    private var now = 9_000_000L

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private object EmptyHome : HomeSource {
        override suspend fun sources() = HomeSources(emptyList(), installed = 0)

        override suspend fun page(source: HomeSourceId, section: Int, page: Int) = HomePageReply.Offline
    }

    /** Alex (first, current), Sam (locked, 4821) and Robin. */
    private fun household(): DesktopProfiles {
        file.save(StoredProfiles(profiles = listOf(StoredProfile(0, "Alex"), StoredProfile(1, "Sam"), StoredProfile(2, "Robin"))))
        val profiles = DesktopProfiles(file, libraryDirectory = directory, onSaveFailed = { throw it }, pins = DesktopPins(directory, clock = { now }))
        profiles.setPin(1, SAM_PIN)
        return profiles
    }

    private fun window(profiles: DesktopProfiles, escape: EscapeHandler = EscapeHandler(), onScreen: (String) -> Unit = {}) =
        scene {
            ProfilesHost(profiles, escape) { library, foot ->
                DesktopApp(escape, EmptyHome, library = library, onScreen = onScreen, profileFoot = foot)
            }
        }

    @Test
    fun `a locked profile asks its PIN, a wrong one says so with no count, and the right one opens it`() {
        val profiles = household()
        var at = ""
        window(profiles, onScreen = { at = it }).use { scene ->
            assertNotNull(scene.find("Sam, Locked · PIN"), "the card says only that it is locked")
            scene.click(scene.bounds("Sam, Locked · PIN").center)
            assertNotNull(scene.find("Enter PIN for Sam", text = true))
            scene.type("48")
            scene.save("A3-pin")
            scene.type("00")
            assertNotNull(scene.find("Incorrect PIN. Please try again.", text = true), "A3b")
            assertTrue(scene.allText().none { t -> t.isNotEmpty() && t.all(Char::isDigit) }, "a cell shows a dot, never a digit")
            scene.save("A3b-wrong")
            assertEquals(0, profiles.stored.current)
            scene.type(SAM_PIN)
            assertEquals(1, profiles.stored.current)
            assertEquals("Home", at)
        }
    }

    @Test
    fun `a pins file that cannot be read keeps a locked profile shut and says why, counting nothing`() {
        val profiles = household()
        val pinsFile = File(directory, DesktopPins.PINS_FILE)
        window(profiles).use { scene ->
            scene.click(scene.bounds("Sam, Locked · PIN").center)
            Files.setPosixFilePermissions(pinsFile.toPath(), PosixFilePermissions.fromString("---------"))
            try {
                scene.type(SAM_PIN)
                assertNotNull(scene.find("Couldn't read the PINs on this computer, so this profile stays locked.", text = true))
                assertNull(scene.find("Incorrect PIN. Please try again.", text = true))
                assertEquals(0, profiles.stored.current, "Sam did not open, not even with the right PIN")
                scene.save("A3-pins-unreadable")
            } finally {
                Files.setPosixFilePermissions(pinsFile.toPath(), PosixFilePermissions.fromString("rw-------"))
            }
            scene.type(SAM_PIN)
            assertEquals(1, profiles.stored.current, "readable again, the right PIN opens it")
        }
        assertEquals(0L, profiles.pins.waitMs(1))
    }

    @Test
    fun `the fifth wrong PIN starts a wait that nothing typed gets past, and it runs down on the boot clock`() {
        val profiles = household()
        window(profiles).use { scene ->
            scene.click(scene.bounds("Sam, Locked · PIN").center)
            repeat(5) { scene.type("0000") }
            assertNotNull(scene.find("Too many wrong PINs. Try again in 00:30", text = true))
            scene.save("A4-wait")
            scene.type(SAM_PIN)
            assertEquals(0, profiles.stored.current, "the right PIN does nothing during a wait")
            now += 12_000L
            scene.settle()
            assertNotNull(scene.find("Too many wrong PINs. Try again in 00:18", text = true), "it counts down")
            now += 18_000L
            scene.settle()
            assertNull(scene.find("Too many wrong PINs. Try again in 00:00", text = true))
            scene.type(SAM_PIN)
            assertEquals(1, profiles.stored.current)
        }
    }

    @Test
    fun `a wait outlives closing the prompt and a restart`() {
        val profiles = household()
        window(profiles).use { scene ->
            scene.click(scene.bounds("Sam, Locked · PIN").center)
            repeat(5) { scene.type("0000") }
            scene.press(Key.Escape)
            scene.click(scene.bounds("Sam, Locked · PIN").center)
            assertNotNull(scene.find("Too many wrong PINs. Try again in 00:30", text = true), "Cancel and back is still waiting")
        }
        val restarted = DesktopProfiles(file, libraryDirectory = directory, pins = DesktopPins(directory, clock = { now }))
        assertEquals(30_000L, restarted.pins.waitMs(1), "so is a restart")
    }

    @Test
    fun `Backspace takes the last digit back`() {
        val profiles = household()
        window(profiles).use { scene ->
            scene.click(scene.bounds("Sam, Locked · PIN").center)
            scene.type("482")
            scene.press(Key.Backspace)
            scene.type("21")
            assertEquals(1, profiles.stored.current, "4, 8, 2, back, 2, 1 is 4821")
        }
    }

    @Test
    fun `in Manage a locked profile's editor opens only after its PIN, and shows its PIN as on`() {
        val profiles = household()
        window(profiles).use { scene ->
            scene.click(scene.bounds("Manage profiles", text = true).center)
            scene.click(scene.bounds("Sam, Locked · PIN").center)
            assertNull(scene.find("Edit profile", text = true), "not before the PIN")
            scene.type(SAM_PIN)
            assertNotNull(scene.find("Edit profile", text = true))
            assertNotNull(scene.find("On · asked to open it", text = true))
            assertNotNull(scene.find("Change PIN", text = true))
            scene.save("A6d-locked")
        }
    }

    @Test
    fun `Set PIN asks twice, a mismatch starts over, and the profile is then locked`() {
        val profiles = household()
        val escape = EscapeHandler()
        window(profiles, escape).use { scene ->
            scene.click(scene.bounds("Manage profiles", text = true).center)
            scene.click(scene.bounds("Robin, No sync").center)
            assertNotNull(scene.find("Off · anyone can open this profile", text = true))
            scene.click(scene.bounds("Set PIN", text = true).center)
            scene.save("A7-set")
            scene.type("1357")
            assertNotNull(scene.find("Type it again to confirm.", text = true))
            scene.save("A7b-confirm")
            scene.type("1358")
            assertNotNull(scene.find("Type a 4-digit PIN.", text = true), "a mismatch starts over")
            assertFalse(profiles.pins.hasPin(2))
            scene.type("1357")
            scene.type("1357")
            assertTrue(profiles.pins.hasPin(2))
            assertNotNull(scene.find("On · asked to open it", text = true), "back in the editor, PIN on")
            assertNotNull(scene.find("Robin, Locked · PIN"))
        }
    }

    @Test
    fun `Change and Remove ask the PIN in use first, under the same limit`() {
        val profiles = household()
        window(profiles).use { scene ->
            scene.click(scene.bounds("Manage profiles", text = true).center)
            scene.click(scene.bounds("Sam, Locked · PIN").center)
            scene.type(SAM_PIN)
            scene.click(scene.bounds("Change PIN", text = true).center)
            assertNotNull(scene.find("Enter Current PIN", text = true))
            scene.save("A7c-current")
            scene.type("0000")
            assertNotNull(scene.find("Incorrect PIN. Please try again.", text = true))
            scene.type(SAM_PIN)
            scene.type("2468")
            scene.type("2468")
            assertEquals(PinCheck.Right, profiles.pins.check(1, "2468"), "the new PIN is in use")

            scene.click(scene.bounds("Remove PIN", text = true).center)
            scene.type("2468")
            assertFalse(profiles.pins.hasPin(1))
            assertNotNull(scene.find("Off · anyone can open this profile", text = true))
        }
    }

    @Test
    fun `a PIN change that cannot be written changes nothing and says so, for Set and for Remove`() {
        file.save(StoredProfiles(profiles = listOf(StoredProfile(0, "Alex"), StoredProfile(1, "Sam"), StoredProfile(2, "Robin"))))
        val pinDirectory = File(directory, "pins").apply { mkdirs() }
        val failures = mutableListOf<Throwable>()
        val pins = DesktopPins(pinDirectory, clock = { now }, onSaveFailed = { failures += it })
        val profiles = DesktopProfiles(file, libraryDirectory = directory, onSaveFailed = { throw it }, pins = pins)
        assertTrue(profiles.setPin(1, SAM_PIN))
        val pinsFile = File(pinDirectory, DesktopPins.PINS_FILE)
        val written = pinsFile.readText()
        window(profiles).use { scene ->
            scene.click(scene.bounds("Manage profiles", text = true).center)
            pinDirectory.setWritable(false)
            try {
                scene.click(scene.bounds("Robin, No sync").center)
                scene.click(scene.bounds("Set PIN", text = true).center)
                scene.type("1357")
                scene.type("1357")
                assertNotNull(scene.find("Couldn't save the PIN change. Nothing was changed.", text = true), "it says so")
                assertNotNull(scene.find("Off · anyone can open this profile", text = true), "Robin is not locked")
                assertFalse(profiles.pins.hasPin(2))
                scene.save("A6-pin-not-saved")
                scene.press(Key.Escape)

                scene.click(scene.bounds("Sam, Locked · PIN").center)
                scene.type(SAM_PIN)
                scene.click(scene.bounds("Remove PIN", text = true).center)
                scene.type(SAM_PIN)
                assertNotNull(scene.find("Couldn't save the PIN change. Nothing was changed.", text = true))
                assertNotNull(scene.find("On · asked to open it", text = true), "Sam stays locked")
                assertTrue(profiles.pins.hasPin(1))
            } finally {
                pinDirectory.setWritable(true)
            }
        }
        assertEquals(written, pinsFile.readText(), "the file is as it was")
        assertTrue(failures.isNotEmpty(), "the failures were reported")
        val restarted = DesktopPins(pinDirectory, clock = { now })
        assertTrue(restarted.hasPin(1), "after a restart, as on screen: Sam locked")
        assertFalse(restarted.hasPin(2), "and Robin open")
    }

    @Test
    fun `the first profile's editor says it can't be locked, and offers no PIN`() {
        val profiles = household()
        window(profiles).use { scene ->
            scene.click(scene.bounds("Manage profiles", text = true).center)
            scene.click(scene.bounds("Alex, Current · No sync").center)
            assertNotNull(scene.find("The first profile can't be locked", text = true))
            assertNull(scene.find("Set PIN", text = true))
        }
    }

    @Test
    fun `Escape on a PIN step goes back to the editor`() {
        val profiles = household()
        val escape = EscapeHandler()
        window(profiles, escape).use { scene ->
            scene.click(scene.bounds("Manage profiles", text = true).center)
            scene.click(scene.bounds("Robin, No sync").center)
            scene.click(scene.bounds("Set PIN", text = true).center)
            assertTrue(escape.handle())
            scene.settle()
            assertNotNull(scene.find("Edit profile", text = true))
            assertFalse(profiles.pins.hasPin(2))
        }
    }

    // ---- the scene --------------------------------------------------------------------------------------------------

    private fun scene(content: @Composable () -> Unit) = ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
        CloudStreamTheme {
            CompositionLocalProvider(LocalCloudStreamTypography provides CloudStreamTheme.typography.forDesktop()) {
                Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) { content() }
            }
        }
    }

    private fun ImageComposeScene.settle() = repeat(SETTLE_FRAMES) { render(it * FRAME_NANOS) }

    private fun ImageComposeScene.type(digits: String) = digits.forEach { press(DIGITS.getValue(it)) }

    /** A key as AWT sends it, with its native code. */
    private fun ImageComposeScene.press(key: Key) {
        val (code, char) = when (key) {
            Key.Enter -> java.awt.event.KeyEvent.VK_ENTER to '\n'
            Key.Escape -> java.awt.event.KeyEvent.VK_ESCAPE to '\u001b'
            Key.Backspace -> java.awt.event.KeyEvent.VK_BACK_SPACE to '\b'
            else -> {
                val digit = DIGITS.entries.first { it.value == key }.key
                (java.awt.event.KeyEvent.VK_0 + (digit - '0')) to digit
            }
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

    private fun ImageComposeScene.find(label: String, text: Boolean = false): SemanticsNode? = nodes().lastOrNull { node ->
        if (text) {
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
        } else {
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == label }
        }
    }

    private fun ImageComposeScene.bounds(label: String, text: Boolean = false): Rect =
        checkNotNull(find(label, text)) { "nothing is named \"$label\"" }.boundsInRoot

    private fun ImageComposeScene.allText(): List<String> =
        nodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

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
        const val SAM_PIN = "4821"
        const val WIDTH = 1280
        const val HEIGHT = 772
        const val SETTLE_FRAMES = 30
        const val FRAME_NANOS = 50_000_000L
        val DIGITS = mapOf(
            '0' to Key.Zero, '1' to Key.One, '2' to Key.Two, '3' to Key.Three, '4' to Key.Four,
            '5' to Key.Five, '6' to Key.Six, '7' to Key.Seven, '8' to Key.Eight, '9' to Key.Nine,
        )
    }
}

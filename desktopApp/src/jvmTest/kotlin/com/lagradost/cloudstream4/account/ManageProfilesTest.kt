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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
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
import com.lagradost.cloudstream4.feature.library.LibraryTitle
import com.lagradost.cloudstream4.feature.library.LibraryTitleId
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `KMP-95b`: Manage profiles (`DS-25` A5), the editor (A6, A6b, A6c) and the delete (A8), through real pointer and key
 * events over profiles and libraries in a temporary directory. A name is typed through the field's own `SetText`, as an
 * accessibility client would. PNGs are written when `cloudstream.account.renders` names a directory.
 *
 * All names are made up.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ManageProfilesTest {
    private val directory: File = Files.createTempDirectory("manage-profiles").toFile()
    private val file = DesktopProfileFile(File(directory, DesktopProfileFile.FILE_NAME))

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private object EmptyHome : HomeSource {
        override suspend fun sources() = HomeSources(emptyList(), installed = 0)

        override suspend fun page(source: HomeSourceId, section: Int, page: Int) = HomePageReply.Offline
    }

    private fun profiles(vararg names: String?, current: Int = 0): DesktopProfiles {
        if (names.isNotEmpty()) {
            file.save(StoredProfiles(current = current, profiles = names.mapIndexed { id, name -> StoredProfile(id, name) }))
        }
        return DesktopProfiles(file, libraryDirectory = directory, onSaveFailed = { throw it })
    }

    private fun window(profiles: DesktopProfiles, escape: EscapeHandler = EscapeHandler(), onScreen: (String) -> Unit = {}) =
        scene {
            ProfilesHost(profiles, escape) { library, foot ->
                DesktopApp(escape, EmptyHome, library = library, onScreen = onScreen, profileFoot = foot)
            }
        }

    @Test
    fun `with one profile, Manage from the sidebar adds a second, and Done goes back to the window`() {
        val profiles = profiles()
        var at = ""
        window(profiles, onScreen = { at = it }).use { scene ->
            assertEquals("Home", at, "one profile: no picker")
            scene.click(scene.bounds("Default").center)
            scene.click(scene.bounds("Manage profiles").center)
            assertNotNull(scene.find("Choose a profile to edit it", text = true), "A5")
            scene.save("A5-one-profile")

            scene.click(scene.bounds("Add profile").center)
            assertEquals("Profile 2", scene.fieldText(), "A6b starts with the next number")
            scene.save("A6b-add")
            scene.click(scene.bounds("Add", text = true).center)
            assertEquals(listOf(null, "Profile 2"), profiles.stored.profiles.map { it.name })
            assertNotNull(scene.find("Profile 2, No sync"), "the new card is in Manage")
            assertEquals(0, profiles.stored.current, "adding does not switch")

            scene.click(scene.bounds("Done", text = true).center)
            assertNull(scene.find("Choose a profile to edit it", text = true))
            assertEquals("Home", at)
        }
    }

    @Test
    fun `from the launch picker, Manage edits a name with Return, a blank name keeps the old one, and Done returns to choosing`() {
        val profiles = profiles("Alex", "Sam")
        window(profiles).use { scene ->
            scene.click(scene.bounds("Manage profiles", text = true).center)
            scene.click(scene.bounds("Sam, No sync").center)
            assertEquals("Sam", scene.fieldText())
            assertNotNull(scene.find("Delete profile", text = true), "Sam can be deleted")
            scene.save("A6-edit")
            scene.typeName("Samira")
            scene.press(Key.Enter)
            assertEquals("Samira", profiles.stored.profiles[1].name)
            assertNotNull(scene.find("Samira, No sync"))

            scene.click(scene.bounds("Samira, No sync").center)
            scene.typeName("   ")
            scene.click(scene.bounds("Save", text = true).center)
            assertEquals("Samira", profiles.stored.profiles[1].name, "a blank name keeps the one it had")

            scene.click(scene.bounds("Done", text = true).center)
            assertNotNull(scene.find("Who's watching?", text = true), "back to choosing")
        }
    }

    @Test
    fun `the first profile's editor has no Delete`() {
        val profiles = profiles("Alex", "Sam")
        window(profiles).use { scene ->
            scene.click(scene.bounds("Manage profiles", text = true).center)
            scene.click(scene.bounds("Alex, Current · No sync").center)
            scene.save("A6c-first")
            assertNull(scene.find("Delete profile", text = true))
        }
    }

    @Test
    fun `Delete asks first, Return and Escape land on Cancel, and a click deletes the profile and its library`() {
        val profiles = profiles("Alex", "Sam", "Robin")
        runBlocking { profiles.libraryFor(2).remember(LibraryTitle(LibraryTitleId("harbor"), "Harbor Lights", null, 0L)) }
        val escape = EscapeHandler()
        window(profiles, escape).use { scene ->
            scene.click(scene.bounds("Manage profiles", text = true).center)
            scene.click(scene.bounds("Robin, No sync").center)
            scene.click(scene.bounds("Delete profile", text = true).center)
            assertNotNull(scene.find("Delete Robin?", text = true))
            scene.save("A8-delete")

            scene.press(Key.Enter)
            assertNull(scene.find("Delete Robin?", text = true), "Return is Cancel")
            assertEquals("Robin", scene.fieldText(), "back in Robin's editor")
            scene.click(scene.bounds("Delete profile", text = true).center)
            scene.press(Key.Escape)
            assertNull(scene.find("Delete Robin?", text = true), "Escape is Cancel")
            assertEquals(3, profiles.stored.profiles.size)

            scene.click(scene.bounds("Delete profile", text = true).center)
            scene.click(scene.bounds("Delete", text = true).center)
            assertEquals(listOf("Alex", "Sam"), profiles.stored.profiles.map { it.name })
            assertNull(scene.find("Robin, No sync"))
            assertFalse(profiles.titlesFile(2).exists(), "Robin's library left with Robin")
        }
    }

    @Test
    fun `deleting the profile watching makes the first one watch`() {
        val profiles = profiles("Alex", "Sam", current = 1)
        var at = ""
        window(profiles, onScreen = { at = it }).use { scene ->
            scene.press(Key.Enter) // Sam, from the picker
            scene.click(scene.bounds("Sam").center)
            scene.click(scene.bounds("Manage profiles").center)
            scene.click(scene.bounds("Sam, Current · No sync").center)
            scene.click(scene.bounds("Delete profile", text = true).center)
            scene.click(scene.bounds("Delete", text = true).center)
            assertEquals(0, profiles.stored.current)
            scene.click(scene.bounds("Done", text = true).center)
            assertEquals("Home", at)
            assertNotNull(scene.find("Alex"), "the sidebar's button says Alex")
        }
    }

    @Test
    fun `the window's Escape closes the sheet first, then Manage`() {
        val profiles = profiles("Alex", "Sam")
        val escape = EscapeHandler()
        window(profiles, escape).use { scene ->
            scene.click(scene.bounds("Manage profiles", text = true).center)
            scene.click(scene.bounds("Sam, No sync").center)
            assertTrue(escape.handle())
            scene.settle()
            assertNull(scene.find("Delete profile", text = true), "the sheet closed")
            assertNotNull(scene.find("Choose a profile to edit it", text = true), "Manage is still up")
            assertTrue(escape.handle())
            scene.settle()
            assertNotNull(scene.find("Who's watching?", text = true), "back to choosing")
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

    /** A key as AWT sends it: desktop's `clickable` reads the native key code, not only [Key]. */
    private fun ImageComposeScene.press(key: Key) {
        val (code, char) = when (key) {
            Key.Enter -> java.awt.event.KeyEvent.VK_ENTER to '\n'
            Key.Escape -> java.awt.event.KeyEvent.VK_ESCAPE to '\u001b'
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

    private fun ImageComposeScene.find(label: String, text: Boolean = false): SemanticsNode? = nodes().lastOrNull { node ->
        if (text) {
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
        } else {
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == label }
        }
    }

    private fun ImageComposeScene.bounds(label: String, text: Boolean = false): Rect =
        checkNotNull(find(label, text)) { "nothing is named \"$label\"" }.boundsInRoot

    /** The editor's name field: the node named Name that edits text. */
    private fun ImageComposeScene.nameField(): SemanticsNode = checkNotNull(
        nodes().lastOrNull { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == "Name" } &&
                node.config.getOrNull(SemanticsActions.SetText) != null
        },
    ) { "no name field" }

    private fun ImageComposeScene.fieldText(): String =
        nameField().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun ImageComposeScene.typeName(name: String) {
        checkNotNull(nameField().config.getOrNull(SemanticsActions.SetText)?.action).invoke(AnnotatedString(name))
        settle()
    }

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
        /** `DS-25`'s window less macOS's 28 title bar. */
        const val WIDTH = 1280
        const val HEIGHT = 772
        const val SETTLE_FRAMES = 30
        const val FRAME_NANOS = 50_000_000L
    }
}

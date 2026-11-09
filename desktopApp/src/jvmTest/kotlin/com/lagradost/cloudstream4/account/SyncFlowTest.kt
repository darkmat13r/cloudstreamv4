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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `KMP-95d`: the sync sheets (`DS-25` A9, A11, A11b, A12, A13, A13b, A14, A16, A16b) through real key and pointer events,
 * over a fake client and a fake clock, so a code can run down without waiting. Fields are typed through their own
 * `SetText`. PNGs are written when `cloudstream.account.renders` names a directory.
 *
 * Every name, email, password, code and token here is made up.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class SyncFlowTest {
    private val directory: File = Files.createTempDirectory("sync-flow").toFile()
    private val file = DesktopProfileFile(File(directory, DesktopProfileFile.FILE_NAME))
    private val clock = MutableStateFlow(0L)
    private val opened = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private object EmptyHome : HomeSource {
        override suspend fun sources() = HomeSources(emptyList(), installed = 0)

        override suspend fun page(source: HomeSourceId, section: Int, page: Int) = HomePageReply.Offline
    }

    /** A service that answers as told, and counts what it was asked. */
    private class FakeClient(override var simklAvailable: Boolean = true) : SignInClient {
        var code: SignInResult<PairCode> = SignInResult.Done(PairCode(CODE, "https://simkl.com/pin", expiresInSeconds = 600, intervalSeconds = 5))
        var approved = false
        var password: (DesktopService, String, String) -> SignInResult<Credential> = { service, email, password ->
            if (email == EMAIL && password == PASSWORD) {
                SignInResult.Done(if (service == DesktopService.OpenSubtitles) Credential(TOKEN, email, password) else Credential(TOKEN, email))
            } else {
                SignInResult.Refused
            }
        }
        var codes = 0
        var polls = 0

        override suspend fun simklCode(): SignInResult<PairCode> {
            codes++
            return code
        }

        override suspend fun simklPoll(code: PairCode): SignInResult<Credential> {
            polls++
            return if (approved) SignInResult.Done(Credential(TOKEN)) else SignInResult.Pending
        }

        override suspend fun passwordSignIn(service: DesktopService, email: String, password: String) = password(service, email, password)
    }

    /** Alex (first, current) and Sam. */
    private fun household(): DesktopProfiles {
        file.save(StoredProfiles(profiles = listOf(StoredProfile(0, "Alex"), StoredProfile(1, "Sam"))))
        return DesktopProfiles(file, libraryDirectory = directory, onSaveFailed = { throw it })
    }

    private fun window(profiles: DesktopProfiles, client: SignInClient, escape: EscapeHandler = EscapeHandler()) =
        scene {
            ProfilesHost(
                profiles,
                escape,
                signInClient = client,
                syncTick = { ms -> val target = clock.value + ms; clock.first { it >= target } },
                openUrl = { opened += it },
            ) { library, foot ->
                DesktopApp(escape, EmptyHome, library = library, profileFoot = foot)
            }
        }

    /** The picker, then Alex, then the sidebar's list and Sync services. */
    private fun ImageComposeScene.openSync() {
        click(checkNotNull(nodes().lastOrNull { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith("Alex, Current") }
        }) { "no card for Alex" }.boundsInRoot.center)
        click(bounds("Alex").center)
        click(bounds("Sync services").center)
    }

    private fun ImageComposeScene.advance(seconds: Int) {
        repeat(seconds) {
            clock.value += 1_000L
            settle()
        }
    }

    @Test
    fun `A9 lists the current profile's services by presence, says sign-ins last until you quit, and Done closes it`() {
        val profiles = household()
        window(profiles, FakeClient()).use { scene ->
            scene.openSync()
            assertNotNull(scene.find("Sync for Alex", text = true), "A9")
            assertNotNull(scene.find("Sign-ins last until you quit CloudStream.", text = true), "D3, said on the screen")
            listOf("Simkl", "OpenSubtitles", "SubDL").forEach { assertNotNull(scene.find(it, text = true), it) }
            listOf("AniList", "MyAnimeList", "Kitsu").forEach { assertNull(scene.find(it, text = true), "$it is not on the Mac in 95d") }
            assertEquals(3, scene.allText().count { it == "Not connected" })
            scene.save("A9-sync")
            scene.click(scene.bounds("Done", text = true).center)
            assertNull(scene.find("Sync for Alex", text = true))
        }
    }

    @Test
    fun `Simkl shows its code, counts down, polls on its interval and, once approved, is connected for this profile only`() {
        val profiles = household()
        val client = FakeClient()
        window(profiles, client).use { scene ->
            scene.openSync()
            scene.click(scene.signIn(0))
            assertNotNull(scene.find("Pair Simkl", text = true), "A11")
            assertNotNull(scene.find(CODE, text = true))
            assertNotNull(scene.find("Expires in 10:00", text = true))
            assertNotNull(scene.find("Sign-ins last until you quit CloudStream.", text = true))
            scene.save("A11-code")
            scene.advance(4)
            assertEquals(0, client.polls, "not before the interval")
            assertNotNull(scene.find("Expires in 9:56", text = true), "it counts down")
            scene.advance(1)
            assertEquals(1, client.polls)
            scene.click(scene.bounds("Open simkl.com/pin").center)
            assertEquals(listOf("https://simkl.com/pin"), opened)

            client.approved = true
            scene.advance(5)
            assertNotNull(scene.find("Sync for Alex", text = true), "back to A9")
            assertNotNull(scene.find("Connected · signed in on this device", text = true))
            assertEquals(TOKEN, profiles.signIns.get(0, DesktopService.Simkl)?.token)
            assertNull(profiles.signIns.get(1, DesktopService.Simkl), "Sam's are Sam's")
            assertEquals(listOf("Simkl"), profiles.cards("Default").first { it.id == 0 }.syncedWith, "the card's line names it")
        }
    }

    @Test
    fun `Cancel ends the wait at once - no poll runs after it, an approval after it stores nothing, and nothing reads as an error`() {
        val profiles = household()
        val client = FakeClient()
        window(profiles, client).use { scene ->
            scene.openSync()
            scene.click(scene.signIn(0))
            scene.advance(5)
            assertEquals(1, client.polls)
            scene.click(scene.bounds("Cancel", text = true).center)
            assertNotNull(scene.find("Sync for Alex", text = true), "A12: back to A9")
            assertTrue(scene.allText().none { it.startsWith("Couldn't") }, "a cancel is not a failure")
            val simkl = scene.signIn(0)
            assertTrue(
                scene.nodes().any { it.config.getOrNull(SemanticsProperties.Focused) == true && it.boundsInRoot.contains(simkl) },
                "focus is back on Simkl's row",
            )
            client.approved = true
            scene.advance(30)
            assertEquals(1, client.polls, "no poll keeps running")
            assertNull(profiles.signIns.get(0, DesktopService.Simkl))
            scene.save("A12-cancelled")
        }
    }

    @Test
    fun `an expired code is struck through, nothing polls, and Get a new code asks for another`() {
        val profiles = household()
        val client = FakeClient().apply { code = SignInResult.Done(PairCode(CODE, "https://simkl.com/pin", expiresInSeconds = 10, intervalSeconds = 5)) }
        window(profiles, client).use { scene ->
            scene.openSync()
            scene.click(scene.signIn(0))
            scene.advance(10)
            assertNotNull(scene.find("This code expired. Get a new one to try again.", text = true), "A11b")
            scene.save("A11b-expired")
            val polls = client.polls
            scene.advance(20)
            assertEquals(polls, client.polls, "nothing polls once it expired")
            scene.click(scene.bounds("Get a new code", text = true).center)
            assertEquals(2, client.codes)
            assertNotNull(scene.find("Expires in 0:10", text = true))
        }
    }

    @Test
    fun `without a client id in the build Simkl is not available here, with no button`() {
        val profiles = household()
        window(profiles, FakeClient(simklAvailable = false)).use { scene ->
            scene.openSync()
            assertNotNull(scene.find("Not available here", text = true))
            assertEquals(2, scene.signInButtons().size, "only the two subtitle services")
        }
    }

    @Test
    fun `a subtitle service signs in with an email and password - a refusal keeps the email and clears the password`() {
        val profiles = household()
        window(profiles, FakeClient()).use { scene ->
            scene.openSync()
            scene.click(scene.signIn(1))
            assertNotNull(scene.find("Sign in to OpenSubtitles", text = true), "A16")
            assertNotNull(scene.find("OpenSubtitles has no browser sign-in, so it is typed here. It is kept on this device only and never backed up.", text = true))
            assertNotNull(scene.find("Sign-ins last until you quit CloudStream.", text = true))
            assertEquals("", scene.field("Password").second, "never prefilled")
            scene.type("Email", EMAIL)
            scene.type("Password", "wrong-fixture")
            assertTrue(scene.field("Password").third, "masked")
            scene.save("A16-sign-in")
            scene.click(scene.bounds("Sign in", text = true).center)
            assertNotNull(scene.find("That email and password didn't match. Check them and try again.", text = true), "A16b")
            assertEquals(EMAIL, scene.field("Email").second, "the email stays")
            assertEquals("", scene.field("Password").second, "the password clears")
            scene.save("A16b-mismatch")

            scene.type("Password", PASSWORD)
            assertTrue(scene.field("Password").third, "masked again after the refusal")
            scene.click(scene.bounds("Show").center)
            assertEquals(PASSWORD, scene.field("Password").second, "Show unmasks it")
            scene.click(scene.bounds("Sign in", text = true).center)
            assertNotNull(scene.find("Sync for Alex", text = true))
            val kept = checkNotNull(profiles.signIns.get(0, DesktopService.OpenSubtitles))
            assertEquals(PASSWORD, kept.password, "OpenSubtitles keeps it to renew its token (the owner's ruling)")
            assertEquals(emptyList(), profiles.cards("Default").first { it.id == 0 }.syncedWith, "a subtitle service is not sync")

            scene.click(scene.signIn(1))
            scene.type("Email", EMAIL)
            scene.type("Password", PASSWORD)
            scene.press(Key.Enter) // Return in Email moves to Password
            scene.press(Key.Enter) // and in Password, signs in
            assertNull(checkNotNull(profiles.signIns.get(0, DesktopService.SubDl)).password, "SubDL keeps no password")
        }
    }

    @Test
    fun `offline and failed sign-ins say so, with no detail from the service, and Try again starts afresh`() {
        val profiles = household()
        val client = FakeClient().apply { code = SignInResult.Offline }
        window(profiles, client).use { scene ->
            scene.openSync()
            scene.click(scene.signIn(0))
            assertNotNull(scene.find("You are offline", text = true), "A13b")
            assertNotNull(scene.find("Signing in to Simkl needs a connection. Try again when this device is back online.", text = true))
            scene.save("A13b-offline")
            client.code = SignInResult.Failed
            scene.click(scene.bounds("Try again", text = true).center)
            assertEquals(2, client.codes, "afresh")
            assertNotNull(scene.find("Couldn't sign in to Simkl", text = true), "A13")
            assertNotNull(scene.find("Simkl didn't confirm the sign-in. Nothing was saved.", text = true))
            scene.save("A13-failed")
            scene.press(Key.Escape)
            assertNotNull(scene.find("Sync for Alex", text = true), "Cancel goes back to A9")
        }
    }

    @Test
    fun `removing a sign-in asks first - Return keeps it, and only a click removes it`() {
        val profiles = household()
        profiles.signIns.put(0, DesktopService.Simkl, Credential(TOKEN))
        window(profiles, FakeClient()).use { scene ->
            scene.openSync()
            scene.click(scene.bounds("Remove from this device", text = true).center)
            assertNotNull(scene.find("Remove Simkl from this device?", text = true), "A14")
            assertNotNull(scene.find("Alex's Simkl sign-in is deleted from this device. Your Simkl account and its lists stay as they are.", text = true))
            scene.save("A14-remove")
            scene.press(Key.Enter)
            assertNotNull(profiles.signIns.get(0, DesktopService.Simkl), "Return is Cancel")
            scene.click(scene.bounds("Remove from this device", text = true).center)
            scene.click(scene.bounds("Remove", text = true).center)
            assertNull(profiles.signIns.get(0, DesktopService.Simkl))
            assertEquals(3, scene.allText().count { it == "Not connected" })
        }
    }

    @Test
    fun `deleting a profile takes its sign-ins, and a restart has none`() {
        val profiles = household()
        profiles.signIns.put(1, DesktopService.Simkl, Credential(TOKEN))
        profiles.signIns.put(0, DesktopService.SubDl, Credential(TOKEN, EMAIL))
        assertTrue(profiles.delete(1))
        assertNull(profiles.signIns.get(1, DesktopService.Simkl))
        assertNotNull(profiles.signIns.get(0, DesktopService.SubDl))
        val restarted = DesktopProfiles(file, libraryDirectory = directory)
        assertNull(restarted.signIns.get(0, DesktopService.SubDl), "in memory only: quitting forgets them")
    }

    @Test
    fun `signing in writes no file, and no output or log line carries the email, password, token or code`() {
        val home = Files.createTempDirectory("sync-home").toFile()
        val oldHome = System.getProperty("user.home")
        val out = ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        val logged = StringBuilder()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                logged.append(record.message).append(' ').append(record.parameters?.joinToString().orEmpty()).append(record.thrown).append('\n')
            }

            override fun flush() = Unit
            override fun close() = Unit
        }
        val root = Logger.getLogger("")
        try {
            val profiles = household()
            val client = FakeClient()
            window(profiles, client).use { scene ->
                scene.openSync()
                val before = snapshot(directory) + snapshot(home)
                System.setProperty("user.home", home.path)
                System.setOut(PrintStream(out, true))
                System.setErr(PrintStream(out, true))
                root.addHandler(handler)

                scene.click(scene.signIn(0))
                client.approved = true
                scene.advance(5)
                scene.click(scene.signIn(0))
                scene.type("Email", EMAIL)
                scene.type("Password", PASSWORD)
                scene.press(Key.Enter) // Return in Email moves to Password
                scene.press(Key.Enter) // and in Password, signs in
                scene.click(scene.signIn(0))
                scene.type("Email", EMAIL)
                scene.type("Password", PASSWORD)
                scene.press(Key.Enter) // Return in Email moves to Password
                scene.press(Key.Enter) // and in Password, signs in
                assertEquals(3, scene.allText().count { it == "Connected · signed in on this device" })

                assertEquals(before, snapshot(directory) + snapshot(home), "no file was written, changed or removed")
                assertTrue(home.listFiles().orEmpty().isEmpty())
            }
            val said = out.toString() + logged
            listOf(EMAIL, PASSWORD, TOKEN, CODE).forEach { secret -> assertFalse(secret in said, "nothing printed or logged carries a secret") }
            val states = listOf(
                Credential(TOKEN, EMAIL, PASSWORD).toString(),
                PairCode(CODE, "https://simkl.com/pin", 600, 5).toString(),
                SyncStep.Password(DesktopService.SubDl, email = EMAIL).toString(),
                profiles.signIns.toString(),
            )
            listOf(EMAIL, PASSWORD, TOKEN, CODE).forEach { secret ->
                assertTrue(states.none { secret in it }, "a sign-in logged by accident carries nothing")
            }
        } finally {
            root.removeHandler(handler)
            System.setOut(oldOut)
            System.setErr(oldErr)
            System.setProperty("user.home", oldHome)
            home.deleteRecursively()
        }
    }

    /** Every file under [root], with its bytes and time, so any write shows. */
    private fun snapshot(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }.associate { file ->
        file.relativeTo(root).path to "${file.length()}:${file.lastModified()}:${file.readBytes().contentHashCode()}"
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

    private fun ImageComposeScene.allText(): List<String> =
        nodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    /** A9's Sign in buttons, top to bottom. */
    private fun ImageComposeScene.signInButtons(): List<Rect> = nodes()
        .filter { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Sign in" } }
        .map { it.boundsInRoot }
        .distinct()
        .sortedBy { it.top }

    private fun ImageComposeScene.signIn(index: Int): Offset = signInButtons()[index].center

    /** The field named [label] that edits text: its node, its text, and whether what it draws is masked. */
    private fun ImageComposeScene.field(label: String): Triple<SemanticsNode, String, Boolean> {
        val node = checkNotNull(
            nodes().lastOrNull { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == label } &&
                    node.config.getOrNull(SemanticsActions.SetText) != null
            },
        ) { "no field named $label" }
        val text = node.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
        // The field's text as it is drawn: a masked password reads as dots, never its characters.
        return Triple(node, text, text.isNotEmpty() && text.all { it == '\u2022' })
    }

    private fun ImageComposeScene.type(label: String, text: String) {
        checkNotNull(field(label).first.config.getOrNull(SemanticsActions.SetText)?.action).invoke(AnnotatedString(text))
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

        const val CODE = "FAKE00"
        const val TOKEN = "token-fixture-7c1e"
        const val EMAIL = "robin@example.com"
        const val PASSWORD = "pass-fixture-91x"
    }
}

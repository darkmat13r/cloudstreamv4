package com.lagradost.cloudstream4.extensions.repositories

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
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopTypography
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.LocalCloudStreamTypography
import com.lagradost.cloudstream4.feature.extensions.ExtensionStatus
import com.lagradost.cloudstream4.feature.settings.desktop.SettingsWindowDefaults
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `KMP-110c2`: the Extensions pane drawn from `DS-27`'s states, through real pointer and key events in a scene. Each
 * control is found by its words or what a screen reader is told. Every repository and extension here is made up.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ExtensionsPaneDesktopTest {
    private val actions = mutableListOf<ExtensionsPaneAction>()
    private var locale: Locale? = null

    @BeforeTest
    fun english() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun restore() {
        locale?.let(Locale::setDefault)
    }

    private fun pane(state: ExtensionsPaneState, installedFrom: Int = 0, height: Int = HEIGHT) =
        ImageComposeScene(WIDTH, height, Density(1f)) {
            CloudStreamTheme {
                CompositionLocalProvider(LocalCloudStreamTypography provides desktopTypography()) {
                    Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                        ExtensionsPaneDesktop(state, onAction = { actions += it }, installedFrom = { installedFrom }, focusLink = false)
                    }
                }
            }
        }

    private fun repository(name: String = "Harbor Mirror", reachable: Boolean = true) =
        RepositoryRow(key = "k1", name = name, extensions = 24, installed = 6, readAtMillis = READ_AT, reachable = reachable)

    private fun extension(name: String, state: RowState, offered: Boolean = true) = ExtensionRow(
        internalName = name, name = name, version = 7, language = "en", tvTypes = listOf("Movie", "TvSeries"),
        sizeBytes = 1_200_000, state = state, offered = offered,
    )

    @Test
    fun `with nothing added, the pane says the one rule, and nothing is suggested (X1)`() {
        pane(ExtensionsPaneState()).use { scene ->
            val text = scene.allText()
            assertTrue("No repositories" in text)
            assertTrue("Each extension comes from a repository you add, and runs only after you allow it." in text)
            assertTrue("A link that starts with https:// or cloudstreamrepo://" in text)
            assertNull(scene.find("Extensions waiting for you: 0", text = true), "the waiting row is hidden at 0")
        }
    }

    @Test
    fun `every add failure says its own sentence, under the field (X2c)`() {
        val sentences = mapOf(
            AddFailure.NotHttps to "Only https links can be added. Check that it starts with https://.",
            AddFailure.PrivateAddress to "This link points to an address on your local network, which CloudStream doesn't open.",
            AddFailure.TooLarge to "This repository is larger than CloudStream reads. Nothing was added.",
            AddFailure.NewerFormat to "This repository uses a newer format. Update CloudStream to add it.",
            AddFailure.NotARepository to "This link doesn't lead to a repository.",
            AddFailure.Unreachable to "Couldn't reach this repository. Check the link and your connection.",
            AddFailure.AlreadyAdded("Harbor Mirror") to "Harbor Mirror is already in your list.",
            AddFailure.Shortcode to "Short codes don't work on the Mac. Paste the full link instead.",
        )
        sentences.forEach { (failure, sentence) ->
            pane(ExtensionsPaneState(add = AddState.Failed(failure))).use { scene ->
                assertTrue(sentence in scene.allText(), "$failure: ${scene.allText()}")
            }
        }
    }

    /** `#349` review: the link a person typed can carry a token, so once it is added the field no longer shows it. */
    @Test
    fun `after a repository is added, the field is empty and the link is drawn nowhere`() {
        val link = "https://files.example/harbour/repo.json?token=tok_made_up_8f2c"
        val state = androidx.compose.runtime.mutableStateOf(ExtensionsPaneState())
        val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
            CloudStreamTheme {
                CompositionLocalProvider(LocalCloudStreamTypography provides desktopTypography()) {
                    Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                        ExtensionsPaneDesktop(state.value, onAction = { actions += it }, installedFrom = { 0 }, focusLink = false)
                    }
                }
            }
        }
        scene.use {
            val field = checkNotNull(scene.find("Repository link"))
            checkNotNull(field.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.SetText)).action
                ?.invoke(androidx.compose.ui.text.AnnotatedString(link))
            scene.settle()
            assertTrue(scene.allText().any { link in it }, "the link is in the field while it is typed")
            scene.click(scene.bounds("Add", text = true).center)
            assertTrue(actions.contains(ExtensionsPaneAction.Add(link)))
            // The model reads it, adds it, and says so.
            state.value = ExtensionsPaneState(repositories = listOf(repository()), added = 1)
            scene.settle()
            assertTrue(scene.allText().none { "tok_made_up" in it || "files.example" in it }, "${scene.allText()}")
        }
    }

    @Test
    fun `while reading, Add becomes Cancel, and Cancel ends it (X2a)`() {
        pane(ExtensionsPaneState(add = AddState.Reading)).use { scene ->
            assertTrue("Reading the repository" in scene.allText())
            scene.click(scene.bounds("Cancel", text = true).center)
            assertEquals(listOf<ExtensionsPaneAction>(ExtensionsPaneAction.CancelAdd), actions)
        }
    }

    @Test
    fun `a repository is its own name and its count, with no link, and a name cannot reverse what follows (X3)`() {
        val name = "Harbor${Char(0x202E)} Mirror"
        pane(ExtensionsPaneState(repositories = listOf(repository(name)))).use { scene ->
            val text = scene.allText()
            assertTrue("Harbor Mirror" in text, "$text")
            assertTrue(text.none { Char(0x202E) in it }, "a format character was drawn")
            assertTrue(text.any { it.startsWith("24 extensions · updated ") }, "$text")
            assertTrue(text.none { "https://" in it && it != "A link that starts with https:// or cloudstreamrepo://" })
            scene.click(scene.bounds("Harbor Mirror", text = true).center)
            assertEquals(listOf<ExtensionsPaneAction>(ExtensionsPaneAction.Open("k1")), actions)
        }
    }

    @Test
    fun `Remove asks first, Cancel is the default, and only a click removes (X3b)`() {
        pane(ExtensionsPaneState(repositories = listOf(repository())), installedFrom = 6).use { scene ->
            scene.hover(scene.bounds("Harbor Mirror", text = true).center)
            scene.click(scene.bounds("Remove").center)
            assertTrue("Remove Harbor Mirror?" in scene.allText())
            assertTrue("Its 6 installed extensions are removed from this Mac too. You can add the repository again later." in scene.allText())
            scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown))
            scene.settle()
            assertFalse("Remove Harbor Mirror?" in scene.allText(), "Return lands on Cancel")
            assertEquals(emptyList(), actions)
            scene.hover(scene.bounds("Harbor Mirror", text = true).center)
            scene.click(scene.bounds("Remove").center)
            scene.click(scene.bounds("Remove", text = true).center)
            assertEquals(listOf<ExtensionsPaneAction>(ExtensionsPaneAction.RemoveRepository("k1")), actions)
        }
    }

    @Test
    fun `held extensions are counted at the top, and Review opens them (X3c)`() {
        pane(ExtensionsPaneState(repositories = listOf(repository()), waiting = 2)).use { scene ->
            assertNotNull(scene.find("Extensions waiting for you: 2", text = true))
            scene.click(scene.bounds("Review", text = true).center)
            assertEquals(listOf<ExtensionsPaneAction>(ExtensionsPaneAction.Review), actions)
        }
    }

    @Test
    fun `each extension says its state and offers its one verb (X4a, X4b)`() {
        val rows = listOf(
            extension("Lumen", RowState.Available),
            extension("Driftwood", RowState.Installing(700_000, 1_200_000)),
            extension("Pebble", RowState.Installed(verified = true)),
            extension("Juniper", RowState.Installed(verified = false)),
            extension("Saltmarsh", RowState.UpdateAvailable(4, 9)),
            extension("Northlight", RowState.Held),
            extension("Copperleaf", RowState.KeptOff),
            extension("Fernway", RowState.Blocked),
            extension("Kestrel", RowState.NotOffered, offered = false),
            extension("Marlow", RowState.Failed(InstallFailure.Fetch(RepositoryFailure.Unreachable))),
            extension("Tidewater", RowState.Failed(InstallFailure.HashMismatch)),
        )
        val state = ExtensionsPaneState(open = OpenRepository("k1", "Harbor Mirror", READ_AT, reachable = true, rows = rows))
        pane(state, height = TALL).use { scene ->
            val text = scene.allText()
            listOf(
                "v7 · English · Movies, TV Series · 1.2 MB",
                "Installing · 0.7 MB of 1.2 MB",
                "Unverified · its repository publishes no hash",
                "Update available · v4 → v9 · 1.2 MB",
                "Off until you choose",
                "Kept off",
                "Blocked by the scan. This cannot be allowed.",
                "Turned off by its repository",
                "Install failed · the file couldn't be downloaded",
                "Its file didn't match the published hash. Nothing was installed.",
                "Install", "Update", "Installed", "Review", "Retry",
            ).forEach { assertTrue(it in text, "\"$it\" is not drawn: $text") }
            scene.click(scene.bounds("Install", text = true).center)
            scene.click(scene.bounds("Cancel", text = true).center)
            assertEquals(
                listOf<ExtensionsPaneAction>(
                    ExtensionsPaneAction.Install("k1", "Lumen"),
                    ExtensionsPaneAction.CancelInstall("k1", "Driftwood"),
                ),
                actions,
            )
        }
    }

    @Test
    fun `Back and Escape return to the list (X4)`() {
        val state = ExtensionsPaneState(open = OpenRepository("k1", "Harbor Mirror", READ_AT, reachable = true, rows = emptyList()))
        pane(state).use { scene ->
            scene.click(scene.bounds("Go back").center)
            scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown))
            scene.settle()
            assertEquals(listOf<ExtensionsPaneAction>(ExtensionsPaneAction.Close, ExtensionsPaneAction.Close), actions)
        }
    }

    @Test
    fun `an unreachable repository shows its cached list with Retry, and installs nothing from it (X5a)`() {
        val rows = listOf(extension("Lumen", RowState.Available))
        val state = ExtensionsPaneState(open = OpenRepository("k1", "Example Repository", READ_AT, reachable = false, rows = rows))
        pane(state).use { scene ->
            assertTrue("Can't reach Example Repository" in scene.allText())
            scene.click(scene.bounds("Install", text = true).center)
            assertEquals(emptyList(), actions, "Install is off while it cannot be reached")
            scene.click(scene.bounds("Retry", text = true).center)
            assertEquals(listOf<ExtensionsPaneAction>(ExtensionsPaneAction.Retry), actions)
        }
    }

    @Test
    fun `offline, the pane says so at the top, and the lists stay (X5b)`() {
        val state = ExtensionsPaneState(repositories = listOf(repository()), offline = true, listsFromMillis = READ_AT)
        pane(state).use { scene ->
            assertTrue("You are offline" in scene.allText())
            assertTrue(scene.allText().any { it.startsWith("Repositories can't be checked. These lists are from ") })
            assertNotNull(scene.find("Harbor Mirror", text = true))
        }
    }

    @Test
    fun `at the minimum size, nothing runs off the side (X8)`() {
        val long = "A Community Collection With A Very Long Name That Will Not Fit On One Line"
        val rows = listOf(extension(long, RowState.Installing(700_000, 1_200_000)), extension("Saltmarsh", RowState.UpdateAvailable(4, 9)))
        listOf(
            ExtensionsPaneState(repositories = listOf(repository(long)), waiting = 3),
            ExtensionsPaneState(open = OpenRepository("k1", long, READ_AT, reachable = true, rows = rows)),
        ).forEach { state ->
            pane(state, height = SettingsWindowDefaults.MinHeight.value.toInt()).use { scene ->
                scene.nodes().forEach { node ->
                    val box = node.boundsInRoot
                    assertTrue(box.left >= 0f && box.right <= WIDTH, "runs off the side: $box")
                }
            }
        }
    }

    // ---- the scene, as `SettingsWindowTest` drives it ----------------------------------------------------------------

    private fun ImageComposeScene.settle() = repeat(SETTLE_FRAMES) { render(it * FRAME_NANOS) }

    private fun ImageComposeScene.hover(at: Offset) {
        sendPointerEvent(PointerEventType.Move, at)
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
        checkNotNull(find(label, text)) { "nothing is named \"$label\": ${allText()}" }.boundsInRoot

    /** What is drawn as text, and what a field holds. */
    private fun ImageComposeScene.allText(): List<String> = nodes().flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            listOfNotNull(node.config.getOrNull(SemanticsProperties.EditableText)?.text)
    }

    private fun SemanticsNode.all(): List<SemanticsNode> = listOf(this) + children.flatMap { it.all() }

    private inline fun ImageComposeScene.use(block: (ImageComposeScene) -> Unit) {
        try {
            settle()
            block(this)
        } finally {
            close()
        }
    }

    private companion object {
        /** The pane's column: the window less its sidebar. */
        val WIDTH = (SettingsWindowDefaults.Width - SettingsWindowDefaults.SidebarWidth).value.toInt()
        val HEIGHT = SettingsWindowDefaults.Height.value.toInt()
        const val TALL = 1600
        const val READ_AT = 1_700_000_000_000L
        const val SETTLE_FRAMES = 30
        const val FRAME_NANOS = 50_000_000L
    }
}

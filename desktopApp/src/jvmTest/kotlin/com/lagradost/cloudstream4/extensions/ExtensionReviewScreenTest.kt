package com.lagradost.cloudstream4.extensions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.lagradost.cloudstream4.DesktopApp
import com.lagradost.cloudstream4.EscapeHandler
import com.lagradost.cloudstream4.core.designsystem.desktop.forDesktop
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.LocalCloudStreamTypography
import com.lagradost.cloudstream4.feature.extensions.sandbox.ScanFinding
import com.lagradost.cloudstream4.feature.extensions.sandbox.ScanReport
import com.lagradost.cloudstream4.feature.extensions.sandbox.ScanSeverity
import com.lagradost.cloudstream4.feature.home.HomePageReply
import com.lagradost.cloudstream4.feature.home.HomeSource
import com.lagradost.cloudstream4.feature.home.HomeSourceId
import com.lagradost.cloudstream4.feature.home.HomeSources
import com.lagradost.cloudstream4.library.InMemoryLibraryStore
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat

/**
 * The list under real pointer and key events, plus a PNG of each state when `cloudstream.home.renders` names a
 * directory. `:core:screenshot` does not render on this toolchain, so these PNGs are looked at, and guard nothing.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ExtensionReviewScreenTest {

    private val telemetry = ReviewFinding("telemetry", listOf("com/x/Ping names a device identifier: \"installId\""), 0)
    private val reflection = ReviewFinding(
        "reflection",
        listOf("com/x/Loader calls java/lang/Class.forName", "com/x/Other uses java/lang/reflect/Method", "com/x/Third calls java/lang/Class.forName"),
        hidden = 12,
    )
    private val filesystem = ReviewFinding("filesystem", listOf("com/x/Cache uses java/io/File"), 0)

    private fun review(name: String, status: ReviewStatus, vararg findings: ReviewFinding) =
        ExtensionReview(name, name.untrustedText(), "a".repeat(64), status, findings.toList())

    private fun scene(width: Int = WIDTH, height: Int = HEIGHT, content: @Composable () -> Unit) =
        ImageComposeScene(width, height, Density(1f)) {
            CloudStreamTheme {
                CompositionLocalProvider(LocalCloudStreamTypography provides CloudStreamTheme.typography.forDesktop()) {
                    Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) { content() }
                }
            }
        }

    private fun ImageComposeScene.settle() = repeat(SETTLE_FRAMES) { render(it * FRAME_NANOS) }

    private fun ImageComposeScene.click(at: Offset) {
        sendPointerEvent(PointerEventType.Move, at)
        sendPointerEvent(PointerEventType.Press, at)
        sendPointerEvent(PointerEventType.Release, at)
        settle()
    }

    private fun ImageComposeScene.press(key: Key) {
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
        settle()
    }

    private fun ImageComposeScene.save(name: String) {
        val directory = System.getProperty("cloudstream.home.renders") ?: return
        settle()
        val png = checkNotNull(render(SETTLE_FRAMES * FRAME_NANOS).encodeToData(EncodedImageFormat.PNG)).bytes
        File(directory, "$name.png").writeBytes(png)
    }

    private fun screen(
        state: ExtensionReviewState,
        width: Int = WIDTH,
        height: Int = HEIGHT,
        onAction: (ReviewAction) -> Unit = {},
        onBack: () -> Unit = {},
    ) = scene(width, height) { ExtensionReviewScreen(state, onAction, onBack) }

    private fun held() = review("BingeCloud.jar", ReviewStatus.Held, telemetry, reflection)

    /** First-row buttons sit against the right edge of the column, which is 640 wide and centred. */
    private fun firstRowButton(buttonFromRight: Int, y: Float) =
        Offset(WIDTH / 2f + COLUMN_WIDTH / 2f - buttonFromRight, y)

    @Test
    fun `a pointer chooses, per extension, and what is chosen is named by file`() {
        val actions = mutableListOf<ReviewAction>()
        val scene = screen(ExtensionReviewState(listOf(held())), onAction = { actions += it })
        scene.settle()
        scene.click(firstRowButton(ALLOW_FROM_RIGHT, FIRST_ROW_Y))
        assertEquals(listOf<ReviewAction>(ReviewAction.Allow("BingeCloud.jar")), actions)
        actions.clear()
        scene.click(firstRowButton(KEEP_OFF_FROM_RIGHT, FIRST_ROW_Y))
        assertEquals(listOf<ReviewAction>(ReviewAction.KeepOff("BingeCloud.jar")), actions)
        scene.close()
    }

    /** Back first, then Keep off, then Allow: the safe answer comes before the other one. */
    @Test
    fun `the keyboard reaches both choices and back, in that order`() {
        val actions = mutableListOf<ReviewAction>()
        var backs = 0
        val scene = screen(ExtensionReviewState(listOf(held())), onAction = { actions += it }, onBack = { backs++ })
        scene.settle()
        scene.press(Key.Tab)
        scene.press(Key.Enter)
        assertEquals(1, backs)
        scene.press(Key.Tab)
        scene.press(Key.Enter)
        assertEquals(listOf<ReviewAction>(ReviewAction.KeepOff("BingeCloud.jar")), actions)
        scene.press(Key.Tab)
        scene.press(Key.Enter)
        assertEquals(ReviewAction.Allow("BingeCloud.jar"), actions.last())
        scene.close()
    }

    @Test
    fun `a blocked or mismatched extension has no choice on it at all`() {
        listOf(ReviewStatus.Blocked, ReviewStatus.HashMismatch).forEach { status ->
            val actions = mutableListOf<ReviewAction>()
            val scene = screen(ExtensionReviewState(listOf(review("Bad.jar", status))), onAction = { actions += it })
            scene.settle()
            scene.click(firstRowButton(ALLOW_FROM_RIGHT, FIRST_ROW_Y))
            scene.click(firstRowButton(KEEP_OFF_FROM_RIGHT, FIRST_ROW_Y))
            scene.press(Key.Tab)
            scene.press(Key.Tab)
            scene.press(Key.Enter)
            assertTrue(actions.isEmpty(), "$status: $actions")
            scene.close()
        }
    }

    @Test
    fun `a change of mind is on the same row, and offers only the other answer`() {
        val actions = mutableListOf<ReviewAction>()
        val state = ExtensionReviewState(listOf(review("Kept.jar", ReviewStatus.KeptOff, filesystem)))
        val scene = screen(state, onAction = { actions += it })
        scene.settle()
        scene.click(firstRowButton(LONE_BUTTON_FROM_RIGHT, FIRST_ROW_Y))
        assertEquals(listOf<ReviewAction>(ReviewAction.Allow("Kept.jar")), actions)
        scene.close()

        actions.clear()
        val allowed = ExtensionReviewState(listOf(review("Ok.jar", ReviewStatus.Allowed, filesystem)))
        val second = screen(allowed, onAction = { actions += it })
        second.settle()
        second.click(firstRowButton(LONE_BUTTON_FROM_RIGHT, FIRST_ROW_Y))
        assertEquals(listOf<ReviewAction>(ReviewAction.KeepOff("Ok.jar")), actions)
        second.close()
    }

    @Test
    fun `the last of several can be scrolled to at the smallest window, and answers`() {
        val actions = mutableListOf<ReviewAction>()
        val many = List(4) { review("Plugin$it.jar", ReviewStatus.Held, telemetry, reflection, filesystem) }
        val scene = screen(ExtensionReviewState(many), width = 800, height = 520, onAction = { actions += it })
        scene.settle()
        fun frame() = Bitmap.makeFromImage(scene.render(SETTLE_FRAMES * FRAME_NANOS))
        val top = frame()
        repeat(SCROLLS) {
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(400f, 260f), scrollDelta = Offset(0f, SCROLL_STEP))
            scene.settle()
        }
        val scrolled = frame()
        assertFalse(
            (0 until 520 step 8).all { y -> top.getColor(100, y) == scrolled.getColor(100, y) && top.getColor(200, y) == scrolled.getColor(200, y) },
            "the wheel moved nothing",
        )
        scene.save("review-07-scrolled-800x520")
        // Back, then two choices on each of four rows: focus brings the last one on screen.
        repeat(1 + 2 * many.size) { scene.press(Key.Tab) }
        scene.press(Key.Enter)
        assertEquals(listOf<ReviewAction>(ReviewAction.Allow("Plugin3.jar")), actions)
        scene.close()
    }

    /** The whole window: where the notice is, what opens, and Back and Escape from there. */
    @Test
    fun `the notice is on home and search and opens the list, and Escape comes back`() {
        val escape = EscapeHandler()
        var at = ""
        val report = SandboxReport(
            File("/nowhere"),
            listOf(
                load("BingeCloud.jar", LoadState.Held, finding("telemetry", "com/x/Ping names a device identifier: \"installId\"")),
                load("Second.jar", LoadState.Held, finding("reflection", "com/x/Y calls java/lang/Class.forName")),
            ),
        )
        val approvals = ExtensionApprovals(RecordingControl(), report, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val scene = scene { DesktopApp(escape, EmptyHome, approvals, InMemoryLibraryStore(), onScreen = { at = it }) }
        scene.settle()
        assertEquals("Home", at)
        scene.save("review-08-notice-on-home")
        scene.click(Offset(NOTICE_X, NOTICE_Y))
        assertEquals("Extensions", at)
        scene.settle()
        assertTrue(escape.handle(), "Escape goes back from the list")
        scene.settle()
        assertEquals("Home", at)
        assertFalse(escape.handle(), "and home has nothing under it")

        scene.click(Offset(SIDEBAR_ITEM_X, SEARCH_ITEM_Y))
        assertEquals("Search", at)
        scene.save("review-09-notice-on-search")
        scene.click(Offset(NOTICE_X, NOTICE_Y))
        assertEquals("Extensions", at)
        scene.close()
    }

    @Test
    fun `there is no notice when nothing has warnings`() {
        val escape = EscapeHandler()
        var at = ""
        val report = SandboxReport(File("/nowhere"), emptyList())
        val approvals = ExtensionApprovals(RecordingControl(), report, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val scene = scene { DesktopApp(escape, EmptyHome, approvals, InMemoryLibraryStore(), onScreen = { at = it }) }
        scene.settle()
        scene.click(Offset(NOTICE_X, NOTICE_Y))
        assertEquals("Home", at)
        scene.close()
    }

    @Test
    fun `when all are decided a quieter way in remains, so a mind can be changed`() {
        val escape = EscapeHandler()
        var at = ""
        val report = SandboxReport(File("/nowhere"), listOf(load("Kept.jar", LoadState.KeptOff, finding("filesystem", "x"))))
        val approvals = ExtensionApprovals(RecordingControl(), report, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val scene = scene { DesktopApp(escape, EmptyHome, approvals, InMemoryLibraryStore(), onScreen = { at = it }) }
        scene.settle()
        scene.save("review-10-quiet-entry")
        scene.click(Offset(NOTICE_X, NOTICE_Y))
        assertEquals("Extensions", at)
        scene.close()
    }

    /** An end-to-end press on the real holder: the row changes in place, and the notice's count follows. */
    @Test
    fun `allowing from the window takes the row out of waiting and tells the screens`() {
        val control = RecordingControl()
        val report = SandboxReport(File("/nowhere"), listOf(load("One.jar", LoadState.Held, finding("filesystem", "x"))))
        val approvals = ExtensionApprovals(control, report, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val scene = screenOn(approvals)
        scene.settle()
        scene.click(firstRowButton(ALLOW_FROM_RIGHT, FIRST_ROW_Y))
        waitUntil(scene) { approvals.state.value.waiting == 0 }
        assertEquals(listOf("allow One.jar"), control.calls)
        assertEquals(ReviewStatus.Allowed, approvals.state.value.reviews.single().status)
        assertEquals(1, approvals.state.value.providerChanges)
        scene.close()
    }

    @Test
    fun `when saving fails, it says so, and nothing is shown as allowed`() {
        val control = RecordingControl(failing = true)
        val report = SandboxReport(File("/nowhere"), listOf(load("One.jar", LoadState.Held, finding("filesystem", "x"))))
        val approvals = ExtensionApprovals(control, report, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val scene = screenOn(approvals)
        scene.settle()
        scene.click(firstRowButton(ALLOW_FROM_RIGHT, FIRST_ROW_Y))
        waitUntil(scene) { approvals.state.value.notSaved != null }
        assertEquals("One.jar", approvals.state.value.notSaved)
        assertEquals(ReviewStatus.Held, approvals.state.value.reviews.single().status)
        assertEquals(0, approvals.state.value.providerChanges)
        scene.close()
    }

    /** Scan details come from the plugin: a class name can hold U+202E, a newline, and any length at all. */
    @Test
    fun `a hostile file name and class name reach the state cleaned and bounded`() {
        val hostile = "com/evil/‮exe.txt\n\u0007" + "A".repeat(100_000)
        val report = SandboxReport(
            File("/nowhere"),
            listOf(load("‮daolnwod.jar", LoadState.HashMismatch), load("One.jar", LoadState.Held, finding("reflection", hostile))),
        )
        val state = ExtensionApprovals(RecordingControl(), report, CoroutineScope(SupervisorJob() + Dispatchers.Default)).state.value
        val shown = state.reviews.flatMap { review -> listOf(review.displayName) + review.findings.flatMap { it.details } }
        shown.forEach { text ->
            assertFalse(text.any { Character.getType(it).toByte() in setOf(Character.CONTROL, Character.FORMAT) }, text.take(40))
            assertTrue(text.length <= MAX_UNTRUSTED_CODE_POINTS + 1, "${text.length}")
        }
        assertTrue("daolnwod.jar" in shown, "$shown")
        assertEquals("‮daolnwod.jar", state.reviews.single { it.status == ReviewStatus.HashMismatch }.fileName, "the real name, for asking")
    }

    @Test
    fun `renders`() {
        val several = listOf(
            review("BingeCloud.jar", ReviewStatus.Held, telemetry, reflection),
            review("AnimeUnity.cs3", ReviewStatus.Held, ReviewFinding("exit", listOf("com/x/Restart calls java/lang/Runtime.exit"), 0), filesystem),
            review("Kept.jar", ReviewStatus.KeptOff, ReviewFinding("webview", listOf("com/x/Page uses android/webkit/WebView"), 0)),
            review("Allowed.jar", ReviewStatus.Allowed, ReviewFinding("raw-socket", listOf("com/x/Net uses java/net/Socket"), 0)),
        )
        val everyRule = com.lagradost.cloudstream4.feature.extensions.sandbox.ExtensionScanner.warnRules.map { rule ->
            ReviewFinding(rule, listOf("com/x/Thing does what $rule looks for"), 0)
        }
        val states = mapOf(
            "review-01-several-held" to ExtensionReviewState(several),
            "review-02-empty" to ExtensionReviewState(),
            "review-03-failures" to ExtensionReviewState(
                reviews = listOf(
                    review("Crashes.jar", ReviewStatus.StartFailed("not loaded: NoClassDefFoundError: com/google/gson/Gson | plugin host: exit 1"), filesystem),
                    review("Blocked.jar", ReviewStatus.Blocked, ReviewFinding("forbidden-api", listOf("com/x/Run uses java/lang/ProcessBuilder"), 0)),
                    review("Tampered.jar", ReviewStatus.HashMismatch),
                ),
                decisionsUnreadable = true,
                notSaved = "BingeCloud.jar",
            ),
            "review-04-every-rule" to ExtensionReviewState(listOf(review("EveryRule.jar", ReviewStatus.Held, *everyRule.toTypedArray()))),
            "review-05-hostile-text" to ExtensionReviewState(
                listOf(
                    review("‮raj.daolnwod", ReviewStatus.Held, ReviewFinding("reflection", listOf(("com/evil/‮cod.exe\n<b>%1\$s</b> " + "A".repeat(400)).untrustedText()), 0)),
                ),
            ),
            "review-06-starting" to ExtensionReviewState(listOf(review("One.jar", ReviewStatus.Starting, filesystem))),
        )
        states.forEach { (name, state) ->
            listOf(WIDTH to HEIGHT, 800 to 520).forEach { (width, height) ->
                val scene = screen(state, width, height)
                scene.save("$name-${width}x$height")
                scene.close()
            }
        }
        // The longest button labels, and an alphabet that runs the other way, at the smallest window.
        listOf("el", "ja").forEach { language ->
            inLanguage(language) {
                val scene = screen(ExtensionReviewState(several), 800, 520)
                scene.save("review-11-$language-800x520")
                scene.close()
            }
        }
        inLanguage("ar") {
            val scene = scene(800, 520) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    ExtensionReviewScreen(ExtensionReviewState(several), {}, {})
                }
            }
            scene.save("review-11-ar-rtl-800x520")
            scene.close()
        }
        // The longest notice of any locale, beside the tabs, in the smallest window.
        inLanguage("ml") {
            val report = SandboxReport(File("/nowhere"), listOf(load("One.jar", LoadState.Held, finding("filesystem", "x"))))
            val approvals = ExtensionApprovals(RecordingControl(), report, CoroutineScope(SupervisorJob() + Dispatchers.Default))
            val scene = scene(800, 520) { DesktopApp(EscapeHandler(), EmptyHome, approvals, InMemoryLibraryStore()) }
            scene.save("review-12-longest-notice-800x520")
            scene.close()
        }
    }

    @Test
    fun `the fixture jar for the real run, when asked`() {
        val directory = System.getProperty("cloudstream.home.renders") ?: return
        writeProbeJar(File(directory, "ProbeFixture.jar"))
    }

    private fun screenOn(approvals: ExtensionApprovals) = scene {
        val state by approvals.state.collectAsState()
        ExtensionReviewScreen(state, approvals::onAction, {})
    }

    private fun waitUntil(scene: ImageComposeScene, condition: () -> Boolean) {
        repeat(WAIT_ROUNDS) {
            if (condition()) return
            scene.settle()
            Thread.sleep(WAIT_MILLIS)
        }
        assertTrue(condition(), "never settled")
    }

    private fun <T> inLanguage(language: String, block: () -> T): T {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag(language))
        try {
            return block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    private fun finding(rule: String, detail: String) = ScanFinding(ScanSeverity.Warn, rule, detail)

    private fun load(name: String, state: LoadState, vararg findings: ScanFinding) = SandboxedLoad(
        fileName = name,
        verification = null,
        scan = if (findings.isEmpty()) null else ScanReport(findings.toList(), 1, emptySet()),
        providers = 0,
        outcome = "",
        sha256 = "a".repeat(64),
        state = state,
    )

    private class RecordingControl(private val failing: Boolean = false) : ExtensionControl {
        val calls = mutableListOf<String>()

        private fun outcome(name: String, state: LoadState) = SandboxedLoad(
            name, null, ScanReport(listOf(ScanFinding(ScanSeverity.Warn, "filesystem", "x")), 1, emptySet()),
            if (state == LoadState.Running) 1 else 0, "", "a".repeat(64), state,
        )

        override suspend fun allow(fileName: String, sha256: String): SandboxedLoad {
            if (failing) throw IOException("disk full")
            calls += "allow $fileName"
            return outcome(fileName, LoadState.Running)
        }

        override suspend fun keepOff(fileName: String, sha256: String): SandboxedLoad {
            if (failing) throw IOException("disk full")
            calls += "keep off $fileName"
            return outcome(fileName, LoadState.KeptOff)
        }
    }

    private object EmptyHome : HomeSource {
        override suspend fun sources() = HomeSources(emptyList(), 0)
        override suspend fun page(source: HomeSourceId, section: Int, page: Int) = HomePageReply.Failed("none")
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 800
        const val SETTLE_FRAMES = 30
        const val FRAME_NANOS = 50_000_000L
        const val WAIT_ROUNDS = 40
        const val WAIT_MILLIS = 50L
        const val SCROLLS = 30
        const val SCROLL_STEP = 1f
        const val COLUMN_WIDTH = 640
        /** The margin, the 36 header, the two lines of the intro, then a rule and half of the 36 button. */
        const val FIRST_ROW_Y = 169f
        const val ALLOW_FROM_RIGHT = 37
        const val KEEP_OFF_FROM_RIGHT = 130
        const val LONE_BUTTON_FROM_RIGHT = 37
        /** The notice sits at the foot of the sidebar (`KMP-53`): 24 up from the bottom, a 36 button. */
        const val NOTICE_X = 60f
        const val NOTICE_Y = 758f
        const val SIDEBAR_ITEM_X = 100f
        const val SEARCH_ITEM_Y = 76f
    }
}

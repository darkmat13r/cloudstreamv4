package com.lagradost.cloudstream4.player

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.view.KeyEvent
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.plugins.PLUGINS_KEY
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.ui.player.CSPlayerEvent
import com.lagradost.cloudstream3.ui.player.ExtractorUri
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.VideoGenerator
import com.lagradost.cloudstream3.ui.setup.HAS_DONE_SETUP_KEY
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.devicetest.TouchModeReset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 7e's Source panel on the real player (`KMP-66`): one episode offered by two sources, each a silent local copy of
 * `hud-silent.mp4` (see `TvPlayerHudNavigationTest`), media volume 0. No provider is asked anything.
 *
 * What is checked is what a switch must not lose: the position, and whether it was playing. 7e's panel commits on the
 * press, so the switch happens on OK on the other source's row.
 */
@RunWith(AndroidJUnit4::class)
class TvPlaybackPanelsNavigationTest {

    /** `BUG-59`: starts and ends out of touch mode, whatever ran before it. */
    @get:Rule
    val touchMode = TouchModeReset()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val dpad = DpadDriver()
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var volumeBefore = 0
    private lateinit var first: ExtractorUri
    private lateinit var second: ExtractorUri

    /** The one episode, from two sources. */
    private class TwoSources(private val a: ExtractorUri, private val b: ExtractorUri) : VideoGenerator<ExtractorUri>(listOf(a)) {
        override val hasCache = false
        override val canSkipLoading = false
        override fun getId(index: Int): Int? = a.id
        override suspend fun generateLinks(
            clearCache: Boolean,
            sourceTypes: Set<ExtractorLinkType>,
            callback: (Pair<ExtractorLink?, ExtractorUri?>) -> Unit,
            subtitleCallback: (SubtitleData) -> Unit,
            offset: Int,
            isCasting: Boolean,
        ): Boolean {
            callback(null to a)
            callback(null to b)
            return true
        }
    }

    private fun copy(name: String, display: String): ExtractorUri {
        val file = File(context.cacheDir, "$MARKER/$name").apply {
            parentFile?.mkdirs()
            instrumentation.context.assets.open("hud-silent.mp4").use { input -> outputStream().use { input.copyTo(it) } }
        }
        return ExtractorUri(uri = Uri.fromFile(file), name = file.name, displayName = display, id = 4243, headerName = TITLE, tvType = TvType.Movie)
    }

    @Before
    fun twoSilentCopies() {
        volumeBefore = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        first = copy("a.mp4", FIRST)
        second = copy("b.mp4", SECOND)
        instrumentation.runOnMainSync {
            setKey(HAS_DONE_SETUP_KEY, true)
            setKey(PLUGINS_KEY, arrayOf(PluginData("NoSuchPlugin", null, true, "/data/local/tmp/no-such-plugin.cs3", 1)))
        }
        TvPlayerHudSwitch.useComposeHud = true
    }

    @After
    fun restore() {
        instrumentation.runOnMainSync { removeKey(PLUGINS_KEY) }
        File(context.cacheDir, MARKER).deleteRecursively()
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, volumeBefore, 0)
    }

    private fun ActivityScenario<MainActivity>.play() {
        device.wait(Until.hasObject(By.desc(context.getString(R.string.title_home))), WAIT_MS)
        onActivity { it.navigate(R.id.global_to_navigation_player, GeneratorPlayer.newInstance(TwoSources(first, second), 0)) }
        assertTrue("No HUD", device.wait(Until.hasObject(By.text(SOURCE)), WAIT_MS))
    }

    private fun ActivityScenario<MainActivity>.player(): GeneratorPlayer {
        var fragment: GeneratorPlayer? = null
        onActivity { activity ->
            val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            fragment = host.childFragmentManager.fragments.filterIsInstance<GeneratorPlayer>().firstOrNull()
        }
        return fragment ?: error("No player")
    }

    private fun <T> ActivityScenario<MainActivity>.onPlayer(read: (GeneratorPlayer) -> T): T {
        val fragment = player()
        var value: T? = null
        instrumentation.runOnMainSync { value = read(fragment) }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    /** Which copy plays, read off the player's own field: the test's only look behind the screen. */
    private fun ActivityScenario<MainActivity>.playing(): ExtractorUri? = onPlayer { fragment ->
        val field = GeneratorPlayer::class.java.getDeclaredField("currentSelectedLink").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(fragment) as Pair<ExtractorLink?, ExtractorUri?>?)?.second
    }

    private fun ActivityScenario<MainActivity>.position(): Long = onPlayer { it.player.getPosition() ?: 0L }
    private fun ActivityScenario<MainActivity>.isPlaying(): Boolean = onPlayer { it.player.getIsPlaying() }

    private fun waitFor(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (!check() && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        assertTrue(what, check())
    }

    /** Reveal if hidden, then SOURCE (Down from play/pause), then OK: 7e's Source panel with focus on its first row. */
    private fun openSourcePanel() {
        if (!device.hasObject(By.text(SOURCE))) device.pressKeyCode(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue(device.wait(Until.hasObject(By.text(SOURCE)), WAIT_MS))
        dpad.press(Dpad.Down)
        dpad.center()
        assertTrue("No panel", device.wait(Until.hasObject(By.text(context.getString(R.string.player_panel_quality_profile))), WAIT_MS))
    }

    /** Down to the row of the copy that is not playing; OK is the caller's, so it can read the position just before. */
    private fun focusTheOther(now: ExtractorUri) {
        val other = if (now == first) SECOND else FIRST
        val seen = generateSequence { dpad.press(Dpad.Down) }.take(MAX_ROWS).firstOrNull { it.orEmpty().startsWith(other) }
        assertTrue("Never reached $other's row", seen != null)
    }

    @Test
    fun switchingWhilePlayingKeepsPlayingAtThePosition() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.play()
            waitFor("It did not start playing") { scenario.isPlaying() && scenario.position() > SETTLED_MS }
            val before = scenario.playing() ?: error("Nothing playing")
            // The clip is 30 s and every D-pad press waits on the screen: start the walk near its beginning.
            scenario.onPlayer { it.player.seekTo(REWIND_TO_MS) }

            openSourcePanel()
            focusTheOther(before)
            val at = scenario.position()
            dpad.center()

            waitFor("The switch did not happen") { scenario.playing() != before }
            waitFor("It did not play again after the switch, at ${scenario.position()} of a 30 s clip") { scenario.isPlaying() }
            assertTrue("Position lost: was $at, now ${scenario.position()}", abs(scenario.position() - at) < TOLERANCE_MS)
            assertTrue("The panel stayed", device.wait(Until.gone(By.text(context.getString(R.string.player_panel_quality_profile))), WAIT_MS))
        }
    }

    @Test
    fun switchingWhilePausedStaysPausedAtThePosition() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.play()
            waitFor("It did not start playing") { scenario.isPlaying() && scenario.position() > SETTLED_MS }
            scenario.onPlayer { it.player.handleEvent(CSPlayerEvent.Pause) }
            waitFor("It did not pause") { !scenario.isPlaying() }
            val before = scenario.playing() ?: error("Nothing playing")
            val at = scenario.position()

            openSourcePanel()
            focusTheOther(before)
            dpad.center()

            waitFor("The switch did not happen") { scenario.playing() != before }
            Thread.sleep(LOAD_SETTLE_MS)
            assertFalse("A paused player started playing after the switch", scenario.isPlaying())
            assertTrue("Position lost: was $at, now ${scenario.position()}", abs(scenario.position() - at) < TOLERANCE_MS)
        }
    }

    @Test
    fun backClosesThePanelAndStaysInThePlayer() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.play()
            openSourcePanel()
            device.pressBack()
            assertTrue("Back left the panel up", device.wait(Until.gone(By.text(context.getString(R.string.player_panel_quality_profile))), WAIT_MS))
            assertTrue("Back left the player", runCatching { scenario.player() }.isSuccess)
        }
    }

    /**
     * Two BACKs faster than the panel's close reaches the callback's collector: the first closes the panel, and the second
     * leaves the player rather than being swallowed by a callback still enabled for a panel that is gone.
     *
     * Both are sent from one coroutine on `Main.immediate`. That is what makes the collector late: measured, two BACKs
     * sent from plain main-thread code let it run in between, and the old callback passed. Inside a coroutine the
     * collector's resume waits for the coroutine to finish, so the second BACK meets a callback still enabled.
     */
    @Test
    fun twoFastBacksCloseThePanelThenLeaveThePlayer() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.play()
            openSourcePanel()
            scenario.onActivity { activity ->
                activity.lifecycleScope.launch(Dispatchers.Main.immediate) {
                    activity.onBackPressedDispatcher.onBackPressed()
                    activity.onBackPressedDispatcher.onBackPressed()
                }
            }
            waitFor("The second BACK was swallowed: still in the player") { runCatching { scenario.player() }.isFailure }
        }
    }

    @Test
    fun noPathReachesTheSourcePanel() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.play()
            openSourcePanel()
            assertTrue(device.hasObject(By.textStartsWith(FIRST)))
            // The HUD's times redraw under the read, so a node can go stale between finding it and reading it.
            val screen = device.findObjects(By.pkg(context.packageName))
                .flatMap { node -> runCatching { listOfNotNull(node.text, node.contentDescription) }.getOrDefault(emptyList()) }
            for (leak in listOf(MARKER, "file:", context.cacheDir.path, "a.mp4", "b.mp4")) {
                assertTrue("`$leak` is on screen: $screen", screen.none { leak in it })
            }
        }
    }

    private companion object {
        const val MARKER = "PANELPATH42"
        const val TITLE = "Panel Test Film"
        const val FIRST = "Mirror One"
        const val SECOND = "Mirror Two"
        const val SOURCE = "SOURCE"
        const val WAIT_MS = 15_000L
        const val POLL_MS = 200L
        const val SETTLED_MS = 2_000L
        const val LOAD_SETTLE_MS = 2_000L
        /** A reload rebuffers: allow its few hundred milliseconds and the keys' own time, not a restart from zero. */
        const val TOLERANCE_MS = 3_000L
        const val MAX_ROWS = 4
        const val REWIND_TO_MS = 1_000L
    }
}

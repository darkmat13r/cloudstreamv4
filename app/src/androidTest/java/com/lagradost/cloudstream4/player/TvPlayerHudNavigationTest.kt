package com.lagradost.cloudstream4.player

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.view.KeyEvent
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
import com.lagradost.cloudstream3.ui.player.DownloadFileGenerator
import com.lagradost.cloudstream3.ui.player.ExtractorUri
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.setup.HAS_DONE_SETUP_KEY
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The real player on a TV with 7d's HUD over it (`KMP-64`): a downloaded file opened the way the downloads page opens
 * one, `GeneratorPlayer` over `DownloadFileGenerator`.
 *
 * **Silent, and local.** The file is `hud-silent.mp4` from the test's assets: 30 s of a grey frame and a digitally silent
 * AAC track (-91 dB), made with `ffmpeg -f lavfi -i color=c=0x3a3a3a:s=320x180:r=10:d=30 -f lavfi -i anullsrc=r=48000:cl=stereo
 * -t 30 -c:v libx264 -crf 40 -c:a aac -b:a 16k`. The media volume is also put to 0 for the test and restored after. No
 * provider is asked anything.
 *
 * Its path carries a marker, so the test can check the path reaches nothing on screen, in text or descriptions.
 */
@RunWith(AndroidJUnit4::class)
class TvPlayerHudNavigationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val dpad = DpadDriver()
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var volumeBefore = 0
    private lateinit var file: File

    @Before
    fun aSilentFileAndAPersonWhoHasSetUp() {
        volumeBefore = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        file = File(context.cacheDir, "$MARKER/hud-silent.mp4").apply {
            parentFile?.mkdirs()
            instrumentation.context.assets.open("hud-silent.mp4").use { input -> outputStream().use { input.copyTo(it) } }
        }
        instrumentation.runOnMainSync {
            setKey(HAS_DONE_SETUP_KEY, true)
            // A saved plugin that does not exist keeps the extension setup page from opening over Home.
            setKey(PLUGINS_KEY, arrayOf(PluginData("NoSuchPlugin", null, true, "/data/local/tmp/no-such-plugin.cs3", 1)))
        }
        TvPlayerHudSwitch.useComposeHud = true
    }

    @After
    fun restore() {
        TvPlayerHudSwitch.useComposeHud = true
        instrumentation.runOnMainSync { removeKey(PLUGINS_KEY) }
        file.parentFile?.deleteRecursively()
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, volumeBefore, 0)
    }

    private fun ActivityScenario<MainActivity>.play() {
        // Home is up once the rail is; the player is opened on top of it.
        device.wait(Until.hasObject(By.desc(context.getString(R.string.title_home))), WAIT_MS)
        onActivity { activity ->
            val episode = ExtractorUri(
                uri = Uri.fromFile(file),
                name = file.name,
                id = 4242,
                headerName = TITLE,
                tvType = TvType.Movie,
            )
            activity.navigate(R.id.global_to_navigation_player, GeneratorPlayer.newInstance(DownloadFileGenerator(listOf(episode)), 0))
        }
    }

    private fun ActivityScenario<MainActivity>.playerFragment(): GeneratorPlayer? {
        var fragment: GeneratorPlayer? = null
        onActivity { activity ->
            val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            fragment = host.childFragmentManager.fragments.filterIsInstance<GeneratorPlayer>().firstOrNull()
        }
        return fragment
    }

    /** Whether the old Media3 controller is in use on the player's view. */
    private fun ActivityScenario<MainActivity>.controllerOn(): Boolean {
        val fragment = playerFragment() ?: error("No player")
        var on = false
        instrumentation.runOnMainSync {
            on = fragment.view?.findViewById<androidx.media3.ui.PlayerView>(R.id.player_view)?.useController ?: error("No player view")
        }
        return on
    }

    /** Every text and description on screen, for the checks that nothing leaks. */
    private fun everythingOnScreen(): List<String> =
        device.findObjects(By.pkg(context.packageName)).flatMap { listOfNotNull(it.text, it.contentDescription) }

    @Test
    fun theHudDrawsOverTheRealPlayerAndTheOldControllerIsOff() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.play()
            assertTrue("No HUD", device.wait(Until.hasObject(By.text(TITLE)), WAIT_MS))
            assertTrue(device.wait(Until.hasObject(By.text(SOURCE)), WAIT_MS))
            assertTrue("The DOWNLOADED badge", device.hasObject(By.text(DOWNLOADED)))
            assertFalse("The old controller is still on", scenario.controllerOn())
        }
    }

    @Test
    fun theFilesPathReachesNothingOnScreen() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.play()
            assertTrue(device.wait(Until.hasObject(By.text(SOURCE)), WAIT_MS))
            val screen = everythingOnScreen()
            for (leak in listOf(MARKER, "file:", context.cacheDir.path, "hud-silent")) {
                assertTrue("`$leak` is on screen: $screen", screen.none { leak in it })
            }
        }
    }

    /** 7d's `HIDDEN`: it hides itself while playing, Right scrubs without revealing it, and Up brings it back. */
    @Test
    fun itHidesThenRightScrubsHiddenAndUpReveals() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.play()
            assertTrue(device.wait(Until.hasObject(By.text(SOURCE)), WAIT_MS))
            assertTrue("It did not hide while playing", device.wait(Until.gone(By.text(SOURCE)), HIDE_WAIT_MS))

            // Sent and checked at once: the readout lives 1500 ms, and DpadDriver.press waits on the accessibility tree.
            device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue("No readout", device.wait(Until.hasObject(By.textStartsWith("+00:10")), READOUT_WAIT_MS))
            assertFalse("A hidden scrub revealed the HUD", device.hasObject(By.text(SOURCE)))
            assertTrue("The readout stayed", device.wait(Until.gone(By.textStartsWith("+00:10")), WAIT_MS))

            device.pressKeyCode(KeyEvent.KEYCODE_DPAD_UP)
            assertTrue("Up did not reveal", device.wait(Until.hasObject(By.text(SOURCE)), WAIT_MS))
            assertEquals(PAUSE, dpad.focused())
        }
    }

    /** The rollback: with the switch off, the old controller is the player's, and no HUD is drawn. */
    @Test
    fun withTheSwitchOffTheOldControllerIsBack() {
        TvPlayerHudSwitch.useComposeHud = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.play()
            assertTrue(device.wait(Until.hasObject(By.res(context.packageName, "player_view")), WAIT_MS))
            assertFalse("The HUD drew with the switch off", device.wait(Until.hasObject(By.text(SOURCE)), NO_HUD_WAIT_MS))
            assertTrue("The old controller is off", scenario.controllerOn())
        }
    }

    private companion object {
        const val MARKER = "PATHMARKER42"
        const val TITLE = "HUD Test Film"
        const val SOURCE = "SOURCE"
        const val DOWNLOADED = "DOWNLOADED"
        const val PAUSE = "Pause"
        const val WAIT_MS = 15_000L
        const val HIDE_WAIT_MS = 8_000L
        const val NO_HUD_WAIT_MS = 4_000L
        const val READOUT_WAIT_MS = 1_400L
    }
}

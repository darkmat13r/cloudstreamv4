package com.lagradost.cloudstream4.home

import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.plugins.PLUGINS_KEY
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.ui.result.ResultFragmentTv
import com.lagradost.cloudstream3.ui.setup.HAS_DONE_SETUP_KEY
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A real plugin in the real app, on a throwaway emulator: skipped unless someone has pushed one to [PLUGIN_ON_DEVICE],
 * which `scripts/device-tests.sh` never does, because it runs a third party's code and needs the network. `KMP-35` has the
 * script and what it measured.
 *
 * It goes through a person's path: a saved plugin, `MainActivity`'s real loading stages, and the remembered source named
 * in [REMEMBERED_ON_DEVICE] if there is one. What it finds goes to logcat under [TAG] and a png beside the plugin.
 */
@RunWith(AndroidJUnit4::class)
class TvHomeRealPluginProof {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val dpad = DpadDriver()

    private fun log(line: String) {
        android.util.Log.i(TAG, line)
    }

    private fun capture(name: String) {
        device.executeShellCommand("screencap -p /data/local/tmp/tv-home-plugin-$name.png")
    }

    /** The app cannot read `/data/local/tmp` itself, and the shell can. */
    private fun shell(command: String): String = device.executeShellCommand(command)

    private fun installPlugin(): File {
        val copy = File(File(context.filesDir, "proof-plugins").apply { mkdirs() }, File(PLUGIN_ON_DEVICE).name)
        copy.delete()
        instrumentation.uiAutomation.executeShellCommand("cat $PLUGIN_ON_DEVICE").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                copy.outputStream().use(input::copyTo)
            }
        }
        return copy
    }

    @Test
    fun aRealPluginsHomePageLandsOnTheNewScreen() {
        assumeTrue("no plugin pushed", shell("ls $PLUGIN_ON_DEVICE").trim() == PLUGIN_ON_DEVICE)
        val remembered = shell("cat $REMEMBERED_ON_DEVICE").trim().takeIf { it.isNotEmpty() && "No such" !in it }
        val plugin = installPlugin()
        log("plugin copied: ${plugin.absolutePath} ${plugin.length()} bytes, remembered=$remembered")
        assertTrue("the plugin reached the app", plugin.length() > 0)

        instrumentation.runOnMainSync {
            setKey(HAS_DONE_SETUP_KEY, true)
            setKey(PLUGINS_KEY, arrayOf(PluginData(plugin.nameWithoutExtension, null, true, plugin.absolutePath, 1)))
            DataStoreHelper.currentHomePage = remembered
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun fragment(): Class<*>? {
                var value: Class<*>? = null
                scenario.onActivity { activity ->
                    val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                    value = host.childFragmentManager.primaryNavigationFragment?.javaClass
                }
                return value
            }

            val deadline = System.currentTimeMillis() + PLUGIN_WAIT_MS
            while (System.currentTimeMillis() < deadline && APIHolder.apis.none { it.hasMainPage }) Thread.sleep(POLL_MS)
            log("providers: ${APIHolder.apis.map { "${it.name}[home=${it.hasMainPage},sections=${it.mainPage.size}]" }}")
            assertEquals("the new home is on screen", HomeTvFragment::class.java, fragment())
            assertTrue("the plugin registered something with a home page", APIHolder.apis.any { it.hasMainPage })

            // Rows or a stated failure, each of which is a real answer. A network that is off is reported, not hidden.
            val landing = System.currentTimeMillis()
            var focused: String? = null
            while (System.currentTimeMillis() < landing + ROWS_WAIT_MS) {
                focused = dpad.focused()
                if (focused != null && focused != context.getString(R.string.switch_account)) break
            }
            log("focus after landing: $focused, after ${System.currentTimeMillis() - landing} ms")
            val chrome = device.findObjects(By.descContains("Source ·")).map { it.contentDescription }
            log("source control: $chrome")
            capture("landed")

            val onDown = dpad.press(Dpad.Down)
            log("DOWN: $onDown")
            val onRight = dpad.press(Dpad.Right)
            log("RIGHT: $onRight")
            capture("moved")

            dpad.center()
            val opening = System.currentTimeMillis()
            while (System.currentTimeMillis() < opening + OPEN_WAIT_MS && fragment() != ResultFragmentTv::class.java) {
                Thread.sleep(POLL_MS)
            }
            log("after OK: ${fragment()?.simpleName}")
            Thread.sleep(OPEN_SETTLE_MS)
            capture("opened")

            repeat(BACK_PRESSES) {
                if (fragment() != HomeTvFragment::class.java) {
                    device.pressBack()
                    Thread.sleep(OPEN_SETTLE_MS)
                }
            }
            log("after Back: ${fragment()?.simpleName}, focus ${dpad.focused()}")
            capture("back")
            assertEquals("Back comes home", HomeTvFragment::class.java, fragment())
        }
    }

    private companion object {
        const val TAG = "TvHomeProof"
        const val PLUGIN_ON_DEVICE = "/data/local/tmp/proof-plugin.cs3"
        const val REMEMBERED_ON_DEVICE = "/data/local/tmp/proof-source.txt"
        const val POLL_MS = 250L
        const val PLUGIN_WAIT_MS = 60_000L
        const val ROWS_WAIT_MS = 60_000L
        const val OPEN_WAIT_MS = 30_000L
        const val OPEN_SETTLE_MS = 4_000L
        const val BACK_PRESSES = 5
    }
}

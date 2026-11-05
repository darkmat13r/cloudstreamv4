package com.lagradost.cloudstream4.home

import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.edit
import androidx.core.view.children
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.plugins.PLUGINS_KEY
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.ui.home.HomeFragment
import com.lagradost.cloudstream3.ui.result.ResultFragmentTv
import com.lagradost.cloudstream3.ui.settings.Globals.updateTv
import com.lagradost.cloudstream3.ui.setup.HAS_DONE_SETUP_KEY
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The real app on a TV: a cold start lands on the new home through real navigation, with focus on a card, and a remote can
 * open a title on its real page and come back. The providers are fakes registered where plugins register, because a screen
 * test can show the screen but only this shows `MainActivity`, the plugin stages and the window around it.
 *
 * A saved plugin that does not exist keeps the extension setup page from opening over Home, so this is a person's normal
 * launch. No extension is loaded, so what a real plugin does is `TvHomeRealPluginProof`'s.
 */
@RunWith(AndroidJUnit4::class)
class TvHomeNavigationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val density = context.resources.displayMetrics.density
    private val dpad = DpadDriver()
    private val one = FakeProvider(ONE)
    private val two = FakeProvider(TWO)
    private val sources = context.getString(R.string.pick_source)

    @Before
    fun aPersonWhoHasSetUp() {
        instrumentation.runOnMainSync {
            setKey(HAS_DONE_SETUP_KEY, true)
            DataStoreHelper.currentHomePage = null
        }
    }

    @After
    fun restore() {
        TvHomeSwitch.useComposeHome = true
        setLayoutPreference(null)
        listOf(one, two).forEach {
            APIHolder.allProviders.remove(it)
            APIHolder.removePluginMapping(it)
        }
        instrumentation.runOnMainSync {
            removeKey(PLUGINS_KEY)
            DataStoreHelper.currentHomePage = null
        }
    }

    private fun setLayoutPreference(value: Int?) {
        PreferenceManager.getDefaultSharedPreferences(context).edit(commit = true) {
            val key = context.getString(R.string.app_layout_key)
            if (value == null) remove(key) else putInt(key, value)
        }
        instrumentation.runOnMainSync { context.updateTv() }
    }

    /** Where `BasePlugin.registerMainAPI` puts a provider, so this goes through what a plugin goes through. */
    private fun install(vararg providers: FakeProvider) {
        providers.forEach {
            APIHolder.allProviders.add(it)
            APIHolder.addPluginMapping(it)
        }
        instrumentation.runOnMainSync {
            setKey(
                PLUGINS_KEY,
                arrayOf(PluginData("NoSuchPlugin", null, true, "/data/local/tmp/no-such-plugin.cs3", 1)),
            )
        }
    }

    private fun launch(block: Page.() -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { Page(it).block() }
    }

    private fun dp(value: Float): Int = (value * density).roundToInt()

    private inner class Page(private val scenario: ActivityScenario<MainActivity>) {
        private fun <T : Any> onMain(read: (MainActivity) -> T?): T? {
            var value: T? = null
            scenario.onActivity { value = read(it) }
            return value
        }

        private fun navController(activity: MainActivity) =
            (activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController

        private fun railView(activity: MainActivity): ComposeView? =
            activity.findViewById<ViewGroup>(R.id.homeRoot).children.filterIsInstance<ComposeView>().singleOrNull()

        val railHasFocus get() = onMain { railView(it)?.hasFocus() } == true
        val railWidth get() = onMain { railView(it)?.width } ?: -1
        val destination get() = onMain { navController(it).currentDestination?.id }
        val fragmentClass
            get() = onMain { activity ->
                val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                host.childFragmentManager.primaryNavigationFragment?.javaClass
            }

        fun waitFor(what: String, wanted: () -> Boolean) {
            val deadline = System.currentTimeMillis() + SCREEN_WAIT_MS
            while (!wanted() && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
            assertTrue(what, wanted())
        }

        fun waitForHome() = waitFor("the new home is on screen, was $fragmentClass") {
            fragmentClass == HomeTvFragment::class.java
        }

        fun waitForFocus(label: String) {
            val deadline = System.currentTimeMillis() + SCREEN_WAIT_MS
            var now = dpad.focused()
            while (now != label && System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_MS)
                now = dpad.focused()
            }
            assertEquals(label, now)
        }

        fun pressUntil(direction: Dpad, label: String) {
            val seen = generateSequence { dpad.press(direction) }.take(MAX_PRESSES).firstOrNull { it == label }
            assertEquals("pressing $direction", label, seen)
        }

        /** The control's name in the tree is its description in some builds and its words in others, so either. */
        fun pressToTheSourceControl(name: String) {
            val seen = generateSequence { dpad.press(Dpad.Up) }.take(MAX_PRESSES)
                .firstOrNull { it == name || it == "$SOURCE · $name" }
            assertTrue("pressing Up reached the source control, last $seen", seen != null)
        }

        /** With no extensions the app opens on the extension setup page; Back is how a person leaves it. */
        fun leaveSetup() {
            repeat(SETUP_BACK_PRESSES) {
                if (device.wait(Until.hasObject(By.desc(RAIL_HOME)), RAIL_WAIT_MS)) return
                device.pressBack()
            }
        }
    }

    // ---- The cold start ------------------------------------------------------------------------------------------

    @Test
    fun aColdStartLandsOnTheNewHomeWithFocusOnTheFirstCardAndTheRailClosed() {
        install(one)
        launch {
            waitForHome()
            assertEquals(R.id.navigation_home, destination)
            waitForFocus("$ONE A 0")
            assertFalse("the rail stays closed", railHasFocus)
            assertTrue(
                "and collapsed, was $railWidth",
                abs(dp(CloudStreamDimens.NavRailWidth.value) - railWidth) <= dp(1f),
            )
            assertNotNull("the rail's home item shows", device.findObject(By.desc(RAIL_HOME)))
            device.executeShellCommand("screencap -p /data/local/tmp/tv-home-cold-start.png")
        }
    }

    /**
     * `MainActivity` also builds the old screen's view model, which fetches the whole page on every plugin stage. That would
     * be every section twice, which a rate-limited site notices, and which a screen test cannot see.
     */
    @Test
    fun aColdStartAsksForEachSectionOnce() {
        install(one)
        instrumentation.runOnMainSync { DataStoreHelper.currentHomePage = ONE }
        launch {
            waitForHome()
            waitForFocus("$ONE A 0")
            // Long enough for the plugin stages after the first to have fired.
            Thread.sleep(PLUGIN_STAGES_MS)
            assertEquals(listOf("0/1", "1/1"), one.asked.sorted())
        }
    }

    @Test
    fun theLastChosenSourceOpensAtAColdStartAndAChoiceSurvivesARestart() {
        install(one, two)
        instrumentation.runOnMainSync { DataStoreHelper.currentHomePage = TWO }
        launch {
            waitForHome()
            waitForFocus("$TWO A 0")
            assertTrue("the other source was never fetched", one.asked.isEmpty())

            pressToTheSourceControl(TWO)
            dpad.center()
            waitFor("the panel opens") { device.hasObject(By.text(sources)) }
            waitForFocus(TWO)
            pressUntil(Dpad.Up, ONE)
            dpad.center()
            waitFor("the choice applies at once") { device.hasObject(By.desc("$SOURCE · $ONE")) }
            assertEquals(ONE, DataStoreHelper.currentHomePage)
        }
        one.asked.clear()
        two.asked.clear()
        launch {
            waitForHome()
            waitForFocus("$ONE A 0")
            assertTrue("the other source was not fetched", two.asked.isEmpty())
        }
    }

    /**
     * Closed before its view has finished coming up, its timer is not cancelled with it. It used to reach a detached
     * fragment and end the process, past the end of the test that did this, so this waits it out.
     */
    @Test
    fun aPageClosedAsSoonAsItOpensDoesNotCrashWhenItsTimerEnds() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { } }
        Thread.sleep(PLUGIN_WAIT_MS + TIMER_MARGIN_MS)
        launch { waitFor("the app is still alive to open") { fragmentClass != null } }
    }

    // ---- Back ----------------------------------------------------------------------------------------------------

    @Test
    fun backOnHomeAsksToExitAndBackAgainStaysOnTheCard() {
        install(one)
        launch {
            waitForHome()
            waitForFocus("$ONE A 0")
            device.pressBack()
            val no = context.getString(R.string.no)
            assertTrue("Back asks to exit", device.wait(Until.hasObject(By.text(no)), SCREEN_WAIT_MS))
            device.pressBack()
            assertTrue("and Back closes that", device.wait(Until.gone(By.text(no)), SCREEN_WAIT_MS))
            waitForFocus("$ONE A 0")
        }
    }

    /** On API 36 Back may never arrive as a key, in which case only the fragment's own callback can close the panel. */
    @Test
    fun backClosesThePickerAndNotTheApp() {
        install(one, two)
        launch {
            waitForHome()
            waitForFocus("$ONE A 0")
            pressToTheSourceControl(ONE)
            dpad.center()
            waitFor("the panel opens") { device.hasObject(By.text(sources)) }

            device.pressBack()

            val no = context.getString(R.string.no)
            waitFor("the panel closes") { !device.hasObject(By.text(sources)) }
            waitFor("focus is back on the control") { dpad.focused().let { it == ONE || it == "$SOURCE · $ONE" } }
            assertFalse("Back did not ask to exit", device.hasObject(By.text(no)))
            device.pressBack()
            assertTrue("Back is back to asking to exit", device.wait(Until.hasObject(By.text(no)), SCREEN_WAIT_MS))
            device.pressBack()
        }
    }

    @Test
    fun aCardOpensTheTitlesRealPageAndBackPutsFocusBackOnIt() {
        install(one)
        launch {
            waitForHome()
            waitForFocus("$ONE A 0")
            pressUntil(Dpad.Down, "$ONE B 0")
            pressUntil(Dpad.Right, "$ONE B 2")
            dpad.center()

            waitFor("the title's page, was $fragmentClass") { fragmentClass == ResultFragmentTv::class.java }
            assertEquals(R.id.navigation_results_tv, destination)
            waitFor("it asked the provider for that title") { one.loaded.isNotEmpty() }
            assertEquals("https://example.invalid/$ONE B 2", one.loaded.single())

            // Waits for each step: a page still coming up can swallow a Back.
            repeat(BACK_PRESSES) {
                if (fragmentClass != HomeTvFragment::class.java) {
                    device.pressBack()
                    device.waitForIdle()
                    Thread.sleep(SETTLE_MS)
                }
            }
            waitForHome()
            waitForFocus("$ONE B 2")
            assertFalse("on the card and not on the rail", railHasFocus)
            assertEquals("nothing was fetched again", 2, one.asked.size)
        }
    }

    // ---- The nav rail --------------------------------------------------------------------------------------------

    @Test
    fun leftFromTheFirstCardOpensTheRailAndRightReturnsToThatCard() {
        install(one)
        launch {
            waitForHome()
            waitForFocus("$ONE A 0")
            pressUntil(Dpad.Down, "$ONE B 0")

            assertEquals(RAIL_HOME, dpad.press(Dpad.Left))
            assertTrue(railHasFocus)
            assertEquals(device.displayWidth, railWidth)

            assertEquals("$ONE B 0", dpad.press(Dpad.Right))
            assertFalse(railHasFocus)
        }
    }

    @Test
    fun goingToAnotherPageByTheRailAndBackDoesNotReloadAndFocusIsOnTheFirstCard() {
        install(one)
        launch {
            waitForHome()
            waitForFocus("$ONE A 0")
            pressUntil(Dpad.Left, RAIL_HOME)
            pressUntil(Dpad.Down, RAIL_SEARCH)
            dpad.center()
            waitFor("search") { destination == R.id.navigation_search }

            // Search's own empty state takes focus when it opens, so a person goes back to the rail for this.
            pressUntil(Dpad.Left, RAIL_SEARCH)
            pressUntil(Dpad.Up, RAIL_HOME)
            dpad.center()
            waitFor("home") { destination == R.id.navigation_home }

            Thread.sleep(SETTLE_MS)
            // The page's view was rebuilt, and the window had no focus for it to lose. Search does the same on arrival.
            assertEquals("$ONE A 0", dpad.focused())
            assertFalse(railHasFocus)
            assertEquals("nothing was fetched again", 2, one.asked.size)
        }
    }

    // ---- With nothing installed ----------------------------------------------------------------------------------

    @Test
    fun withNothingInstalledHomesActionIsReachableAndOpensExtensions() {
        launch {
            leaveSetup()
            waitForHome()
            val extensions = context.getString(R.string.extensions)
            waitForFocus(extensions)
            dpad.center()
            waitFor("the extensions page") { destination == R.id.navigation_settings_extensions }
            device.pressBack()
            waitFor("back on home") { destination == R.id.navigation_home }
        }
    }

    // ---- What must not change ------------------------------------------------------------------------------------

    @Test
    fun theSwitchPutsTheOldHomeBack() {
        TvHomeSwitch.useComposeHome = false
        install(one)
        launch {
            waitFor("the old home, was $fragmentClass") { fragmentClass == HomeFragment::class.java }
        }
    }

    @Test
    fun thePhoneLayoutKeepsTheOldHome() {
        setLayoutPreference(LAYOUT_PHONE)
        install(one)
        launch {
            waitFor("the old home, was $fragmentClass") { fragmentClass == HomeFragment::class.java }
        }
    }

    @Test
    fun theEmulatorLayoutKeepsTheOldHome() {
        setLayoutPreference(LAYOUT_EMULATOR)
        install(one)
        launch {
            waitFor("the old home, was $fragmentClass") { fragmentClass == HomeFragment::class.java }
        }
    }

    /** Two rows, three titles each, named by the provider so that no two stops read the same. */
    private class FakeProvider(override var name: String) : MainAPI() {
        override var mainUrl = "https://example.invalid"
        override var lang = "en"
        override val hasMainPage = true
        override val supportedTypes = setOf(TvType.Movie)
        override val mainPage = listOf(MainPageData("A", "0"), MainPageData("B", "1"))
        val asked = CopyOnWriteArrayList<String>()
        val loaded = CopyOnWriteArrayList<String>()

        override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
            asked += "${request.data}/$page"
            val titles = List(TITLES) { index ->
                val title = "$name ${request.name} $index"
                newMovieSearchResponse(title, "$mainUrl/$title", TvType.Movie, fix = false)
            }
            return newHomePageResponse(request, titles, hasNext = false)
        }

        override suspend fun load(url: String): LoadResponse {
            loaded += url
            return newMovieLoadResponse(url.substringAfterLast('/'), url, TvType.Movie, url)
        }
    }

    private companion object {
        const val ONE = "Fake One"
        const val TWO = "Fake Two"
        const val SOURCE = "Source"
        const val RAIL_HOME = "Home"
        const val RAIL_SEARCH = "Search"
        const val TITLES = 3
        const val LAYOUT_PHONE = 0
        const val LAYOUT_EMULATOR = 2
        const val RAIL_WAIT_MS = 5_000L
        const val SCREEN_WAIT_MS = 15_000L
        const val POLL_MS = 200L
        const val SETTLE_MS = 1_500L
        const val TIMER_MARGIN_MS = 2_000L
        const val PLUGIN_STAGES_MS = 4_000L
        const val MAX_PRESSES = 6
        const val BACK_PRESSES = 4
        const val SETUP_BACK_PRESSES = 3
    }
}

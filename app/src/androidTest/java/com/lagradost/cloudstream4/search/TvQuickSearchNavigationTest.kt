package com.lagradost.cloudstream4.search

import androidx.fragment.app.Fragment
import androidx.navigation.FloatingWindow
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.plugins.PLUGINS_KEY
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment
import com.lagradost.cloudstream3.ui.setup.HAS_DONE_SETUP_KEY
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.home.HomeTvFragment
import com.lagradost.cloudstream4.shell.TvRailSwitch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Quick Search on the real app on a TV, artboards **8d**: opened the way every caller opens it,
 * `QuickSearchFragment.pushSearch`, from Home. Two fake providers are registered where plugins register: one answers
 * with two titles and one fails, so the panel counts both and draws real rows from the real fan-out.
 *
 * What is checked is what the board is about: the panel is a window **over** Home, which stays the same fragment,
 * attached and drawn, and every way out leaves the person where they were or where they asked to go.
 */
@RunWith(AndroidJUnit4::class)
class TvQuickSearchNavigationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val dpad = DpadDriver()
    private val answers = FakeProvider(ANSWERS, fails = false)
    private val fails = FakeProvider(FAILS, fails = true)
    private val carriesNothing = FakeProvider(CARRIES_NOTHING, fails = false, empty = true)
    private val context = instrumentation.targetContext
    private val railHome = context.getString(R.string.title_home)
    private val railSearch = context.getString(R.string.title_search)
    private val railLabels = listOf(
        R.string.title_home, R.string.title_search, R.string.library, R.string.title_downloads, R.string.title_settings,
    ).map(context::getString).toSet()

    @Before
    fun aPersonWhoHasSetUp() {
        answers.install()
        fails.install()
        carriesNothing.install()
        TvRailSwitch.useComposeRail = true
        instrumentation.runOnMainSync {
            setKey(HAS_DONE_SETUP_KEY, true)
            // A saved plugin that does not exist keeps the extension setup page from opening over Home.
            setKey(PLUGINS_KEY, arrayOf(PluginData("NoSuchPlugin", null, true, "/data/local/tmp/no-such-plugin.cs3", 1)))
        }
    }

    @After
    fun restore() {
        TvRailSwitch.useComposeRail = true
        listOf(answers, fails, carriesNothing).forEach {
            APIHolder.allProviders.remove(it)
            APIHolder.removePluginMapping(it)
        }
        instrumentation.runOnMainSync { removeKey(PLUGINS_KEY) }
    }

    private fun ActivityScenario<MainActivity>.navController(): NavController {
        var controller: NavController? = null
        onActivity { activity ->
            controller = (activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController
        }
        return controller!!
    }

    private fun ActivityScenario<MainActivity>.page(): Fragment? {
        var fragment: Fragment? = null
        onActivity { activity ->
            val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            fragment = host.childFragmentManager.fragments.firstOrNull { it !is QuickSearchTvFragment && it.isVisible }
        }
        return fragment
    }

    private fun ActivityScenario<MainActivity>.onHome(): Fragment {
        val deadline = System.currentTimeMillis() + SCREEN_WAIT_MS
        while (page() !is HomeTvFragment && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        return page() as? HomeTvFragment ?: error("Not on the new home, on ${page()?.javaClass}")
    }

    private fun ActivityScenario<MainActivity>.openQuickSearch(query: String, providers: Array<String> = arrayOf(ANSWERS, FAILS)) {
        onActivity { QuickSearchFragment.pushSearch(it, query, providers) }
        assertTrue("No panel", device.wait(Until.hasObject(By.text(TITLE)), SCREEN_WAIT_MS))
    }

    private fun waitForFocus(label: String) {
        val deadline = System.currentTimeMillis() + SCREEN_WAIT_MS
        var now = dpad.focused()
        while (now != label && System.currentTimeMillis() < deadline) now = dpad.focused()
        assertEquals(label, now)
    }

    @Test
    fun thePanelOpensOverHomeWhichStaysPutAndBackClosesIt() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val home = scenario.onHome()
            scenario.openQuickSearch("$FIRST (DUB)")

            val current = scenario.navController().currentDestination
            assertEquals(R.id.navigation_quick_search, current?.id)
            assertTrue("A page, not a window over one: $current", current is FloatingWindow)
            assertSame("Home was replaced", home, scenario.page())
            var drawn = false
            scenario.onActivity { drawn = home.view?.isShown == true }
            assertTrue("Home is not drawn under the panel", drawn)

            assertTrue(device.wait(Until.hasObject(By.text("1 OF 2 ANSWERED · 1 FAILED")), SCREEN_WAIT_MS))
            waitForFocus(FIRST)
            assertEquals("The suffix was not trimmed", listOf(FIRST), answers.searched.toList())

            device.pressBack()
            assertTrue("Back left the panel up", device.wait(Until.gone(By.text(TITLE)), SCREEN_WAIT_MS))
            assertEquals(R.id.navigation_home, scenario.navController().currentDestination?.id)
            assertSame(home, scenario.page())
        }
    }

    @Test
    fun aRowOpensItsTitleAndBackFromTheTitleReturnsHome() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val home = scenario.onHome()
            scenario.openQuickSearch(FIRST)
            waitForFocus(FIRST)
            assertEquals(SECOND, dpad.press(Dpad.Down))

            dpad.center()
            val deadline = System.currentTimeMillis() + SCREEN_WAIT_MS
            while (answers.loaded.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
            assertEquals(listOf("${answers.mainUrl}/$SECOND"), answers.loaded.toList())
            assertTrue("The panel stayed over the title", device.wait(Until.gone(By.text(TITLE)), SCREEN_WAIT_MS))

            device.pressBack()
            assertEquals(home, scenario.onHome())
            assertEquals(R.id.navigation_home, scenario.navController().currentDestination?.id)
        }
    }

    @Test
    fun doneClosesItAndTheRemoteCannotLeaveThePanel() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val home = scenario.onHome()
            scenario.openQuickSearch(FIRST)
            waitForFocus(FIRST)
            assertEquals("Left leaves the panel", FIRST, dpad.press(Dpad.Left))
            assertEquals(SECOND, dpad.press(Dpad.Down))
            assertEquals(DONE, dpad.press(Dpad.Down))
            assertEquals("Down past Done", DONE, dpad.press(Dpad.Down))

            dpad.center()
            assertTrue("Done left the panel up", device.wait(Until.gone(By.text(TITLE)), SCREEN_WAIT_MS))
            assertSame(home, scenario.page())
        }
    }

    /**
     * 8e on the real path: nobody carries it, and `Open full search` takes the query to the real search screen, with the
     * Compose rail (`TvRailSwitch`) marking Search, as the intent handler leaves it.
     */
    @Test
    fun openFullSearchTakesTheQueryToTheSearchScreenAndTheRailMarksSearch() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onHome()
            waitForMark(railHome)
            scenario.openQuickSearch(NOBODY, arrayOf(CARRIES_NOTHING))
            assertTrue("No 8e", device.wait(Until.hasObject(By.text("No matches")), SCREEN_WAIT_MS))
            waitForFocus(OPEN_FULL)

            dpad.center()
            assertTrue("The panel stayed up", device.wait(Until.gone(By.text(TITLE)), SCREEN_WAIT_MS))
            val deadline = System.currentTimeMillis() + SCREEN_WAIT_MS
            while (scenario.page() !is SearchTvFragment && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
            assertTrue("Not on search, on ${scenario.page()?.javaClass}", scenario.page() is SearchTvFragment)
            assertEquals(R.id.navigation_search, scenario.navController().currentDestination?.id)
            assertTrue("The field does not show the query", device.wait(Until.hasObject(By.text(NOBODY)), SCREEN_WAIT_MS))
            waitForMark(railSearch)
        }
    }

    /** The rail item marked as the current page, read as `TvRailNavigationTest` reads it. */
    private fun currentMark(): String? =
        device.findObjects(By.selected(true)).firstNotNullOfOrNull { node -> labelOf(node)?.takeIf { it in railLabels } }

    private fun labelOf(node: UiObject2): String? =
        node.text?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.takeIf { it.isNotBlank() }
            ?: node.children.firstNotNullOfOrNull(::labelOf)

    private fun waitForMark(label: String) {
        val deadline = System.currentTimeMillis() + SCREEN_WAIT_MS
        while (currentMark() != label && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        assertEquals("the rail's mark", label, currentMark())
    }

    /** Answers `search` with two titles, or fails it as a dead site does. Registered where a plugin's provider goes. */
    private class FakeProvider(
        override var name: String,
        private val fails: Boolean,
        private val empty: Boolean = false,
    ) : MainAPI() {
        override var mainUrl = "https://example.invalid/${name.replace(' ', '-')}"
        override var lang = "en"
        override val hasMainPage = false
        override val supportedTypes = setOf(TvType.Movie)
        val searched = CopyOnWriteArrayList<String>()
        val loaded = CopyOnWriteArrayList<String>()

        fun install() {
            APIHolder.allProviders.add(this)
            APIHolder.addPluginMapping(this)
        }

        override suspend fun search(query: String): List<SearchResponse> {
            searched += query
            if (fails) throw IOException("TIMEOUT")
            if (empty) return emptyList()
            return listOf(FIRST, SECOND).map { newMovieSearchResponse(it, "$mainUrl/$it", TvType.Movie, fix = false) }
        }

        override suspend fun load(url: String): LoadResponse {
            loaded += url
            return newMovieLoadResponse(url.substringAfterLast('/'), url, TvType.Movie, url)
        }
    }

    private companion object {
        const val ANSWERS = "Fake Quick Answers"
        const val FAILS = "Fake Quick Fails"
        const val CARRIES_NOTHING = "Fake Quick Carries Nothing"
        const val NOBODY = "zxqvbnmkjh"
        const val OPEN_FULL = "Open full search"
        const val TITLE = "Quick search"
        const val DONE = "Done"
        const val FIRST = "Dune Part Two"
        const val SECOND = "Dune Prophecy"
        const val SCREEN_WAIT_MS = 15_000L
        const val POLL_MS = 200L
    }
}

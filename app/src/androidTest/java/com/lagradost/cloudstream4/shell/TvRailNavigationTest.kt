package com.lagradost.cloudstream4.shell

import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.edit
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.settings.Globals.updateTv
import com.lagradost.cloudstream3.ui.setup.HAS_DONE_SETUP_KEY
import com.lagradost.cloudstream4.core.designsystem.target.TextFieldDefaults
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.home.TvHomeSwitch
import com.lagradost.cloudstream4.search.SearchTvFragment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The real app on a TV, driven by remote: the Compose rail is what shows, every item goes where it says, the current
 * mark follows navigation, and focus can always leave. No extension is installed, so pages have their empty states.
 */
@RunWith(AndroidJUnit4::class)
class TvRailNavigationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val density = context.resources.displayMetrics.density
    private val dpad = DpadDriver()

    private val labels = listOf(
        R.string.title_home,
        R.string.title_search,
        R.string.library,
        R.string.title_downloads,
        R.string.title_settings,
    ).map(context::getString)
    private val destinations = listOf(
        R.id.navigation_home,
        R.id.navigation_search,
        R.id.navigation_library,
        R.id.navigation_downloads,
        R.id.navigation_settings,
    )

    @Before
    fun skipFirstRunSetup() {
        instrumentation.runOnMainSync { setKey(HAS_DONE_SETUP_KEY, true) }
    }

    @After
    fun restore() {
        TvRailSwitch.useComposeRail = true
        TvHomeSwitch.useComposeHome = true
        setLayoutPreference(null)
    }

    /** The App layout setting: 0 phone, 2 emulator, unset for auto, which is TV on this device. */
    private fun setLayoutPreference(value: Int?) {
        PreferenceManager.getDefaultSharedPreferences(context).edit(commit = true) {
            val key = context.getString(R.string.app_layout_key)
            if (value == null) remove(key) else putInt(key, value)
        }
        instrumentation.runOnMainSync { context.updateTv() }
    }

    private fun launch(block: Page.() -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Page(scenario).apply {
                leaveSetup()
                block()
            }
        }
    }

    private fun dp(value: Float): Int = (value * density).roundToInt()

    /** Leaves a png in `/data/local/tmp`, which survives the test APK's uninstall. Asserts nothing. */
    private fun capture(name: String) {
        device.executeShellCommand("screencap -p /data/local/tmp/tv-rail-$name.png")
    }

    private fun assertNear(what: String, expected: Int, actual: Int, tolerance: Int = dp(1f)) =
        assertTrue("$what: expected $expected px, got $actual px", abs(expected - actual) <= tolerance)

    private inner class Page(private val scenario: ActivityScenario<MainActivity>) {

        private fun <T : Any> onMain(read: (MainActivity) -> T?): T? {
            var value: T? = null
            scenario.onActivity { value = read(it) }
            return value
        }

        private fun navController(activity: MainActivity) =
            (activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController

        /** The Compose rail's view, or `null` when there is none. Nothing else in `homeRoot` is a `ComposeView`. */
        private fun railView(activity: MainActivity): ComposeView? =
            activity.findViewById<ViewGroup>(R.id.homeRoot).children.filterIsInstance<ComposeView>().singleOrNull()

        val hasComposeRail get() = onMain { railView(it) } != null
        val railHasFocus get() = onMain { railView(it)?.hasFocus() } == true
        val railWidth get() = onMain { railView(it)?.width } ?: -1
        val railShown get() = onMain { railView(it)?.isVisible } == true
        val destination get() = onMain { navController(it).currentDestination?.id }
        val fragmentClass
            get() = onMain { activity ->
                val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                host.childFragmentManager.primaryNavigationFragment?.javaClass
            }
        val xmlRailVisible get() = onMain { it.binding?.navRailView?.isVisible } == true
        val xmlRailWidth get() = onMain { it.binding?.navRailView?.width } ?: -1
        val pageLeft
            get() = onMain { activity ->
                IntArray(2).also { activity.findViewById<View>(R.id.nav_host_fragment).getLocationOnScreen(it) }[0]
            } ?: -1

        fun navigate(id: Int) = scenario.onActivity { navController(it).navigate(id) }

        /** With no extensions the app opens on the extension setup page; Back is how a person leaves it. */
        fun leaveSetup() {
            repeat(SETUP_BACK_PRESSES) {
                if (device.wait(Until.hasObject(By.desc(labels.first())), RAIL_WAIT_MS)) return
                device.pressBack()
            }
        }

        fun railNode(label: String): UiObject2? =
            device.findObject(By.desc(label)) ?: device.findObject(By.text(label))

        /** The item the screen marks as the current page. */
        fun currentMark(): String? {
            val node = device.findObjects(By.selected(true)).firstOrNull { labelOf(it) in labels }
            return node?.let(::labelOf)
        }

        private fun labelOf(node: UiObject2): String? =
            node.text?.takeIf { it.isNotBlank() }
                ?: node.contentDescription?.takeIf { it.isNotBlank() }
                ?: node.children.firstNotNullOfOrNull(::labelOf)

        fun waitForMark(label: String) {
            val deadline = System.currentTimeMillis() + SCREEN_WAIT_MS
            while (currentMark() != label && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
            assertEquals("the current mark", label, currentMark())
        }

        fun waitForDestination(id: Int) {
            val deadline = System.currentTimeMillis() + SCREEN_WAIT_MS
            while (destination != id && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
            assertEquals("the destination", id, destination)
        }

        /** LEFT until focus is in the rail, as a person would, and returns the stop before it. */
        fun openRail(): String? {
            dpad.closeKeyboard()
            var last = dpad.focused()
            repeat(RAIL_PRESSES) {
                if (railHasFocus) return last
                val now = dpad.press(Dpad.Left)
                dpad.closeKeyboard()
                if (!railHasFocus) last = now
            }
            assertTrue("LEFT never reached the rail, stuck on $last", railHasFocus)
            return last
        }

        fun commit(index: Int) {
            openRail()
            val from = labels.indexOf(dpad.focused())
            assertTrue("focus entered on an item", from >= 0)
            repeat(abs(index - from)) { dpad.press(if (index > from) Dpad.Down else Dpad.Up) }
            assertEquals(labels[index], dpad.focused())
            dpad.center()
            waitForDestination(destinations[index])
            waitForMark(labels[index])
        }

        /** The mean brightness of what is past the rail and its shadow: a page dim shows here and nothing else does. */
        fun pageBrightness(): Float {
            val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot()).copy(Bitmap.Config.ARGB_8888, false)
            val from = dp(SHADOW_END_DP)
            var sum = 0L
            var count = 0
            for (x in from until shot.width step SAMPLE_STEP) {
                for (y in 0 until shot.height step SAMPLE_STEP) {
                    val pixel = shot.getPixel(x, y)
                    sum += Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)
                    count++
                }
            }
            return sum.toFloat() / count
        }
    }

    // ---- It is the rail on screen ----------------------------------------------------------------------------

    @Test
    fun theComposeRailReplacesTheXmlRailAndThePageStartsAt76() = launch {
        waitForDestination(R.id.navigation_home)
        capture("home-at-rest")
        assertTrue("the Compose rail exists", hasComposeRail)
        assertTrue("and shows", railShown)
        assertFalse("the XML rail is hidden", xmlRailVisible)
        assertFalse("launching leaves focus off the rail, so it opens collapsed", railHasFocus)
        assertNear("rail width", dp(CloudStreamDimens.NavRailWidth.value), railWidth)
        assertNear("page origin", dp(CloudStreamDimens.NavRailWidth.value), pageLeft)
        labels.forEach { assertNotNull("rail item $it", railNode(it)) }
        waitForMark(labels[HOME])
    }

    @Test
    fun theSearchScreenItsFieldLandsOnTheArtboardsContentOrigin() = launch {
        commit(SEARCH)
        val field = requireNotNull(device.wait(Until.findObject(By.clazz(EDIT_TEXT)), SCREEN_WAIT_MS))
        assertNear(
            "the field's text",
            dp(CloudStreamDimens.ContentOriginX.value + TextFieldDefaults.HorizontalPadding.value),
            field.visibleBounds.left,
        )
    }

    // ---- Each item, and the current mark -----------------------------------------------------------------------

    @Test
    fun everyItemOpensItsDestinationAndTheMarkFollows() = launch {
        listOf(SEARCH, LIBRARY, DOWNLOADS, SETTINGS, HOME).forEach { commit(it) }
    }

    @Test
    fun searchIsTheNewFragment() = launch {
        commit(SEARCH)
        assertEquals(SearchTvFragment::class.java, fragmentClass)
    }

    @Test
    fun theMarkFollowsBack() = launch {
        commit(SEARCH)
        device.pressBack()
        waitForDestination(R.id.navigation_home)
        waitForMark(labels[HOME])

        commit(SETTINGS)
        navigate(R.id.navigation_settings_general)
        waitForDestination(R.id.navigation_settings_general)
        waitForMark(labels[SETTINGS])
        assertTrue("a page under Settings keeps the rail", railShown)
        device.pressBack()
        waitForDestination(R.id.navigation_settings)
        waitForMark(labels[SETTINGS])
        device.pressBack()
        waitForDestination(R.id.navigation_home)
        waitForMark(labels[HOME])
    }

    // ---- Focus, RIGHT and the edges --------------------------------------------------------------------------

    /** @param moveToStop gets from where RIGHT lands to the stop under test. */
    private fun rightReturnsToTheStopThatLeft(index: Int, moveToStop: () -> Unit = ::moveOneDown) = launch {
        if (index != HOME) commit(index)
        openRail()
        assertEquals("entry lands on the current page", labels[index], dpad.focused())

        val landed = dpad.press(Dpad.Right)
        dpad.closeKeyboard()
        assertFalse("RIGHT from the rail reached the page, got $landed", railHasFocus)
        moveToStop()
        dpad.closeKeyboard()
        capture("page-${labels[index]}")

        val yielded = openRail()
        assertEquals("entry lands on the current page", labels[index], dpad.focused())
        capture("rail-${labels[index]}")
        assertEquals("the rail is the whole screen while open", device.displayWidth, railWidth)

        assertEquals("RIGHT returns to the exact stop", yielded, dpad.press(Dpad.Right))
        dpad.closeKeyboard()
        assertFalse("and focus has left the rail", railHasFocus)
        assertNear("and it collapsed", dp(CloudStreamDimens.NavRailWidth.value), railWidth)
    }

    private fun moveOneDown() {
        dpad.press(Dpad.Down)
    }

    /** `Extensions` is the page's only stop with nothing installed that is not the account cell above it. */
    @Test
    fun rightReturnsToTheExactStopOnHome() = rightReturnsToTheStopThatLeft(HOME, moveToStop = {})

    @Test
    fun rightReturnsToTheExactStopOnSearch() = rightReturnsToTheStopThatLeft(SEARCH)

    @Test
    fun rightReturnsToTheExactStopOnLibrary() = rightReturnsToTheStopThatLeft(LIBRARY)

    @Test
    fun rightReturnsToTheExactStopOnDownloads() = rightReturnsToTheStopThatLeft(DOWNLOADS)

    /**
     * Settings' own page cannot show this: from its search field DOWN goes nowhere, and the field expands when it takes
     * focus and collapses when it loses it, so RIGHT from the rail measured `Default`, the stop above it. Below that
     * page, a Compose list has plain rows.
     */
    @Test
    fun rightReturnsToTheExactStopOnAComposePage() = launch {
        commit(SETTINGS)
        navigate(R.id.navigation_settings_ui)
        waitForDestination(R.id.navigation_settings_ui)
        waitForMark(labels[SETTINGS])
        openRail()
        dpad.press(Dpad.Right)
        val first = dpad.focused()
        val stop = dpad.press(Dpad.Down)
        assertTrue("DOWN moved along the page from $first", stop != first && !railHasFocus)

        assertEquals("LEFT leaves the row for the rail", labels[SETTINGS], dpad.press(Dpad.Left))
        assertEquals("RIGHT returns to that row and not the first", stop, dpad.press(Dpad.Right))
    }

    /** The old home, because only its provider button has a DOWN that loses focus. */
    @Test
    fun rightFindsThePageWhenTheStopThatLeftWasNeverOnScreen() {
        TvHomeSwitch.useComposeHome = false
        launch {
            waitForDestination(R.id.navigation_home)
            openRail()
            val stop = dpad.press(Dpad.Right)
            dpad.press(Dpad.Down)
            assertNull("the stop that loses focus", dpad.focused())
            assertEquals("LEFT still reaches the rail", labels[HOME], dpad.press(Dpad.Left))
            assertEquals("and RIGHT finds the page's real stop", stop, dpad.press(Dpad.Right))
        }
    }

    @Test
    fun aPageTheRailLeftForHasForgottenItsStop() = launch {
        commit(SETTINGS)
        dpad.press(Dpad.Right)
        openRail()
        repeat(SETTINGS - DOWNLOADS) { dpad.press(Dpad.Up) }
        dpad.center()
        waitForDestination(R.id.navigation_downloads)
        waitForMark(labels[DOWNLOADS])
        dpad.press(Dpad.Right)
        assertFalse("RIGHT after a commit goes to the new page", railHasFocus)
    }

    @Test
    fun openingTheRailDimsThePageWithoutMovingIt() = launch {
        commit(SETTINGS)
        dpad.press(Dpad.Right)
        val left = pageLeft
        val atRest = pageBrightness()
        assertTrue("the page has something on it to dim", atRest > 0f)
        openRail()
        val dimmed = pageBrightness()
        assertTrue("the page dims: $atRest to $dimmed", dimmed <= atRest * MAX_DIMMED_RATIO)
        assertTrue("and still shows: $dimmed", dimmed > 0f)
        assertEquals("the page never reflows", left, pageLeft)
        val expanded = requireNotNull(device.findObject(By.text(labels[HOME]))) { "labels show once it is open" }
        assertTrue("the item reaches past the collapsed rail", expanded.visibleBounds.right > dp(CloudStreamDimens.NavRailWidth.value))
    }

    @Test
    fun theRailsEdgesAreStopsNotExits() = launch {
        waitForDestination(R.id.navigation_home)
        openRail()
        assertEquals("the first item", labels[HOME], dpad.focused())
        assertEquals("UP from the first stays", labels[HOME], dpad.press(Dpad.Up))
        assertEquals("LEFT does nothing", labels[HOME], dpad.press(Dpad.Left))
        labels.drop(1).forEach { assertEquals(it, dpad.press(Dpad.Down)) }
        assertEquals("DOWN from the last stays", labels.last(), dpad.press(Dpad.Down))
        assertEquals("LEFT does nothing", labels.last(), dpad.press(Dpad.Left))
        assertTrue(railHasFocus)
    }

    @Test
    fun backFromTheRailIsNeverATrap() = launch {
        waitForDestination(R.id.navigation_home)
        openRail()
        device.pressBack()
        val no = context.getString(R.string.no)
        assertTrue("Back on Home asks to exit", device.wait(Until.hasObject(By.text(no)), SCREEN_WAIT_MS))
        device.pressBack()
        assertTrue("and Back closes that", device.wait(Until.gone(By.text(no)), SCREEN_WAIT_MS))
        waitForDestination(R.id.navigation_home)
        openRail()
        assertEquals(labels[HOME], dpad.focused())

        commit(LIBRARY)
        device.pressBack()
        waitForDestination(R.id.navigation_home)
        waitForMark(labels[HOME])
        openRail()
        assertEquals("and the rail still opens on the current page", labels[HOME], dpad.focused())
    }

    /**
     * @param lost what the walk must find losing focus: nothing, or the two stops the XML rail loses it on too.
     * @param expected the rail items the walk can tell apart on this page.
     */
    private fun noDeadEnds(index: Int, lost: List<String> = emptyList(), expected: List<String> = labels) = launch {
        if (index != HOME) commit(index)
        val graph = dpad.walk(recoverWith = Dpad.Left)
        assertEquals(graph.toString(), emptyList<String>(), graph.unreachable(expected))
        assertEquals(graph.toString(), emptyList<String>(), graph.deadEnds())
        assertEquals(graph.toString(), lost, graph.traps().map { it.replace(BOUNDS, "") })
    }

    @Test
    fun noDeadEndsOnHome() = noDeadEnds(HOME)

    /** What this suite pinned before `KMP-35`, kept while the old home is the rollback. */
    @Test
    fun noDeadEndsOnTheOldHome() {
        TvHomeSwitch.useComposeHome = false
        noDeadEnds(HOME, lost = listOf("$NO_PROVIDER -> Down lost focus"))
    }

    @Test
    fun noDeadEndsOnLibrary() = noDeadEnds(LIBRARY, lost = listOf("$LIBRARY_PAGER -> Right lost focus"))

    @Test
    fun noDeadEndsOnDownloads() = noDeadEnds(DOWNLOADS)

    /** Settings' search field is also named `Search`, so the walk reads it and the rail's item as one stop, and that
     * stop's UP is the field's. `Home` is then out of its sight; [theRailsEdgesAreStopsNotExits] reaches it by key. */
    @Test
    fun noDeadEndsOnSettings() = noDeadEnds(SETTINGS, expected = labels - context.getString(R.string.title_home))

    // ---- What must not change --------------------------------------------------------------------------------

    @Test
    fun theSwitchPutsTheXmlRailBackAt62() {
        TvRailSwitch.useComposeRail = false
        launch {
            waitForDestination(R.id.navigation_home)
            assertFalse("no Compose rail", hasComposeRail)
            assertTrue("the XML rail shows", xmlRailVisible)
            assertNear("XML rail width", dp(XML_RAIL_DP), xmlRailWidth)
            assertNear("page origin", dp(XML_RAIL_DP), pageLeft)
            navigate(R.id.navigation_search)
            waitForDestination(R.id.navigation_search)
            val field = requireNotNull(device.wait(Until.findObject(By.clazz(EDIT_TEXT)), SCREEN_WAIT_MS))
            assertNear(
                "the 14dp miss the audit inferred",
                dp(
                    XML_RAIL_DP + CloudStreamDimens.ContentOriginX.value - CloudStreamDimens.NavRailWidth.value +
                        TextFieldDefaults.HorizontalPadding.value,
                ),
                field.visibleBounds.left,
            )
        }
    }

    /** Measures that this loss is the page's, not the Compose rail's. It is not fixed here. */
    @Test
    fun theXmlRailLosesFocusFromHomesProviderButtonToo() {
        TvRailSwitch.useComposeRail = false
        TvHomeSwitch.useComposeHome = false
        launch {
            waitForDestination(R.id.navigation_home)
            assertEquals("Home's one stop", NO_PROVIDER, dpad.enter())
            assertNull("DOWN from it", dpad.press(Dpad.Down))
        }
    }

    /** Measures that this loss is the page's, not the Compose rail's. It is not fixed here. */
    @Test
    fun theXmlRailLosesFocusFromTheLibrarysPagerToo() {
        TvRailSwitch.useComposeRail = false
        launch {
            navigate(R.id.navigation_library)
            waitForDestination(R.id.navigation_library)
            val visited = mutableListOf(dpad.enter())
            repeat(RAIL_PRESSES) {
                val at = visited.last()
                if (!at.startsWith(VIEW_PAGER)) {
                    // The XML rail's RIGHT lands inside the pager, and only the tabs above it go down onto the pager.
                    val direction = when {
                        at in labels -> Dpad.Right
                        at.startsWith(RECYCLER_VIEW) -> Dpad.Up
                        else -> Dpad.Down
                    }
                    visited += dpad.press(direction).orEmpty()
                }
            }
            assertTrue("the Library's pager, by way of $visited", visited.last().startsWith(VIEW_PAGER))
            assertNull("RIGHT from it", dpad.press(Dpad.Right))
        }
    }

    @Test
    fun thePhoneLayoutHasNoComposeRail() {
        setLayoutPreference(LAYOUT_PHONE)
        launch {
            waitForDestination(R.id.navigation_home)
            assertFalse(hasComposeRail)
            assertTrue("landscape shows the XML rail", xmlRailVisible)
            assertNear("the phone layout's page starts after its rail", xmlRailWidth, pageLeft)
        }
    }

    @Test
    fun theEmulatorLayoutHasNoComposeRail() {
        setLayoutPreference(LAYOUT_EMULATOR)
        launch {
            waitForDestination(R.id.navigation_home)
            assertFalse(hasComposeRail)
            assertTrue(xmlRailVisible)
            assertNear("XML rail width", dp(XML_RAIL_DP), xmlRailWidth)
            assertNear("page origin", dp(XML_RAIL_DP), pageLeft)
        }
    }

    private companion object {
        const val HOME = 0
        const val SEARCH = 1
        const val LIBRARY = 2
        const val DOWNLOADS = 3
        const val SETTINGS = 4

        const val LAYOUT_PHONE = 0
        const val LAYOUT_EMULATOR = 2

        /** `@dimen/nav_rail_view_width`. Written out, because the point is to see that it did not move. */
        const val XML_RAIL_DP = 62f

        /** The shadow has faded out by 236dp (`NavRailHostDefaults`). */
        const val SHADOW_END_DP = 240f
        const val SAMPLE_STEP = 4

        /** 46% dim leaves 54%. Margin, because focus moves off a filled row when the rail opens too. */
        const val MAX_DIMMED_RATIO = 0.75f

        const val VIEW_PAGER = "androidx.viewpager"
        const val RECYCLER_VIEW = "androidx.recyclerview"

        /** The provider button on Home with no extension installed. */
        const val NO_PROVIDER = "None"

        /** How the driver names the Library's pager, which has no text, with its bounds taken off. */
        const val LIBRARY_PAGER = "androidx.viewpager.widget.ViewPager"
        val BOUNDS = Regex("@\\S+")
        const val EDIT_TEXT = "android.widget.EditText"
        const val RAIL_WAIT_MS = 5_000L
        const val SCREEN_WAIT_MS = 10_000L
        const val POLL_MS = 200L
        const val SETUP_BACK_PRESSES = 3
        const val RAIL_PRESSES = 6
    }
}

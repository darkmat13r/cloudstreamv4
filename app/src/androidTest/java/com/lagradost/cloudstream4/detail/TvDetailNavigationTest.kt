package com.lagradost.cloudstream4.detail

import androidx.fragment.app.Fragment
import androidx.navigation.fragment.FragmentNavigator
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.setup.HAS_DONE_SETUP_KEY
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.devicetest.TouchModeReset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real app on a TV: the runtime repoint of `navigation_results_tv` took effect, the one entry every
 * caller uses lands on it, and a remote can get out again.
 *
 * **No extension is installed on the emulator**, so there is no provider, no title to load and no
 * network traffic — which is exactly what makes this test honest about what it can show. The content
 * states are `:feature:detail`'s, with a fake at the module's seam
 * (`DetailScreenTvDeviceTest`); what only the real app can show is navigation and the shell around it,
 * and that is all that is here.
 *
 * `AndroidDetailSource.provider` throws `NOT_LOADED` when no API answers to the id's `apiName`, so the
 * page's one reachable state here is its failure — and a failure state that a remote cannot escape is
 * the defect `StateView` exists to prevent, so that is what is asserted.
 *
 * The default [DpadDriver] focus reader is used, not `rule.focusedLabel()`: there is no compose rule in
 * the real app, so focus is read from the accessibility tree with the driver's own wait for it to catch
 * up (`INF-10`).
 */
@RunWith(AndroidJUnit4::class)
class TvDetailNavigationTest {

    /** `BUG-59`: starts and ends out of touch mode, whatever ran before it. */
    @get:Rule
    val touchMode = TouchModeReset()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val dpad = DpadDriver()
    private val homeLabel = instrumentation.targetContext.getString(R.string.title_home)

    /** The five nav-rail items, by the content descriptions the rail gives them. */
    private val railLabels = listOf(
        R.string.title_home,
        R.string.title_search,
        R.string.library,
        R.string.title_downloads,
        R.string.title_settings,
    ).map(instrumentation.targetContext::getString)

    @Before
    fun skipFirstRunSetup() {
        instrumentation.runOnMainSync { setKey(HAS_DONE_SETUP_KEY, true) }
    }

    /** With no extensions the app opens on the extension setup page; Back is how a person leaves it. */
    private fun leaveSetup() {
        repeat(SETUP_BACK_PRESSES) {
            if (device.wait(Until.hasObject(By.desc(homeLabel)), RAIL_WAIT_MS)) return
            device.pressBack()
        }
    }

    private fun ActivityScenario<MainActivity>.currentFragment(): Fragment? {
        var fragment: Fragment? = null
        onActivity { activity ->
            val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            fragment = host.childFragmentManager.primaryNavigationFragment
        }
        return fragment
    }

    /** The class the inflated graph's TV result node points at, which is what the repoint rewrites. */
    private fun ActivityScenario<MainActivity>.resultDestinationClass(): String? {
        var name: String? = null
        onActivity { activity ->
            val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            val node = host.navController.graph.findNode(R.id.navigation_results_tv)
            name = (node as? FragmentNavigator.Destination)?.className
        }
        return name
    }

    /** The way every caller opens a title: `loadResult`, which picks the destination for the layout. */
    private fun ActivityScenario<MainActivity>.openTheTitle() {
        onActivity { activity -> activity.loadResult(url = URL, apiName = API_NAME, name = NAME) }
        awaitFragment(DetailTvFragment::class.java)
    }

    private fun ActivityScenario<MainActivity>.awaitFragment(expected: Class<*>) {
        val deadline = System.currentTimeMillis() + SCREEN_WAIT_MS
        while (currentFragment()?.javaClass != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
        }
        assertEquals("the destination's fragment", expected, currentFragment()?.javaClass)
    }

    private fun ActivityScenario<MainActivity>.awaitSomethingElseThan(gone: Class<*>) {
        val deadline = System.currentTimeMillis() + SCREEN_WAIT_MS
        while (currentFragment()?.javaClass == gone && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
        }
        assertNotEquals("BACK left the screen", gone, currentFragment()?.javaClass)
    }

    /**
     * The repoint happened: `MainActivity` rewrites the node's class name on the inflated graph under
     * `if (isLayout(TV))`, and `mobile_navigation.xml` still says `ResultFragmentTv`. Reading the XML
     * would therefore prove the opposite of what this asserts.
     */
    @Test
    fun theTvResultDestinationResolvesToTheNewFragment() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            leaveSetup()
            assertEquals(DetailTvFragment::class.java.name, scenario.resultDestinationClass())
        }
    }

    /** And the one entry everything uses gets there, rather than the fragment being built by hand. */
    @Test
    fun loadResultOpensTheNewDetailFragment() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            leaveSetup()
            scenario.openTheTitle()
        }
    }

    /**
     * With no provider the page can only fail — so this is where the failure state is worth asserting
     * on the live path: it is drawn, its Retry is reachable by remote from the other stop on the
     * screen, pressing it asks again, and BACK leaves without stranding focus.
     *
     * It also pins the rail on this destination (`KMP-38`, the owner on 2026-11-05, reversing what this
     * test asserted before): the page is laid out beside the rail, LEFT off its leading edge enters the
     * rail, and RIGHT returns to the stop that left. How many presses that takes is deliberately not
     * pinned — it depends on where the state view sits — only that LEFT reaches the rail and RIGHT
     * comes back to the same stop.
     */
    @Test
    fun withNoProviderThePageFailsAndItsRetryIsReachableAndBackLeaves() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            leaveSetup()
            scenario.openTheTitle()

            assertTrue(
                "the failure state is drawn",
                device.wait(Until.hasObject(By.text(FAILED_HEADLINE)), SCREEN_WAIT_MS),
            )

            assertTrue(
                "the nav rail is on this destination",
                railLabels.any { device.findObject(By.desc(it)) != null },
            )

            assertEquals("the failure rests focus on its action", RETRY, dpad.enter())
            // LEFT walks off the page's leading edge into the rail. The first press may land on the
            // page's own back cell, which is up and to the left of the centred failure, so the stop
            // that yields focus to the rail is whichever of the two it was.
            val firstLeft = dpad.press(Dpad.Left)
            val leftTheRail = firstLeft !in railLabels
            val stopThatLeft = if (leftTheRail) firstLeft else RETRY
            val inRail = if (leftTheRail) dpad.press(Dpad.Left) else firstLeft
            assertTrue("LEFT never reached the nav rail, stopped on $inRail", inRail in railLabels)
            assertEquals("RIGHT returns to the stop that left", stopThatLeft, dpad.press(Dpad.Right))
            if (stopThatLeft != RETRY) {
                assertEquals("and DOWN comes back to Retry", RETRY, dpad.press(Dpad.Down))
            }

            dpad.center()
            assertTrue(
                "Retry asked again and failed again, which is all it can do with no provider",
                device.wait(Until.hasObject(By.text(FAILED_HEADLINE)), SCREEN_WAIT_MS),
            )
            assertEquals(
                "the page is still the new fragment",
                DetailTvFragment::class.java,
                scenario.currentFragment()?.javaClass,
            )

            device.pressBack()
            scenario.awaitSomethingElseThan(DetailTvFragment::class.java)
            // LEFT, because the page behind this one is Home and DOWN from its one stop loses focus
            // there — measured by `TvRailNavigationTest`, and not this test's business.
            val landed = dpad.focused() ?: dpad.enter(Dpad.Left)
            assertTrue("BACK left the app with nothing focused", landed.isNotBlank())
        }
    }

    private companion object {
        /** `.invalid` is reserved, so no provider can claim it by URL either. */
        const val URL = "https://example.invalid/the-last-signal"
        const val API_NAME = "NoSuchProvider"
        const val NAME = "The Last Signal"

        const val RAIL_WAIT_MS = 5_000L
        const val SCREEN_WAIT_MS = 15_000L
        const val POLL_MS = 200L
        const val SETUP_BACK_PRESSES = 3

        // `values/strings.xml`, as the screen resolves them.
        const val BACK = "Go Back"
        const val RETRY = "Try again"
        const val FAILED_HEADLINE = "Couldn't open this title"
    }
}

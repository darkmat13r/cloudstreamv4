package com.lagradost.cloudstream4.search

import androidx.fragment.app.Fragment
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
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real app on a TV: search opens the new screen through the nav rail, by remote, and a remote can
 * move around it and get back out. No extension is installed, so there are no sources and no network
 * traffic from a search; what the fakes in `:feature:search`'s tests stand in for is not exercised here.
 */
@RunWith(AndroidJUnit4::class)
class TvSearchNavigationTest {

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val dpad = DpadDriver()

    @Before
    fun skipFirstRunSetup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { setKey(HAS_DONE_SETUP_KEY, true) }
    }

    private fun ActivityScenario<MainActivity>.currentFragment(): Fragment? {
        var fragment: Fragment? = null
        onActivity { activity ->
            val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            fragment = host.childFragmentManager.primaryNavigationFragment
        }
        return fragment
    }

    /** With no extensions the app opens on the extension setup page; Back is how a person leaves it. */
    private fun openSearchFromTheRail() {
        val rail = By.desc(RAIL_SEARCH)
        repeat(SETUP_BACK_PRESSES) {
            if (device.wait(Until.hasObject(rail), RAIL_WAIT_MS)) return@repeat
            device.pressBack()
        }
        dpad.enter(Dpad.Left)
        generateSequence { dpad.press(Dpad.Left) }.take(RAIL_PRESSES).firstOrNull { it == RAIL_SEARCH }
            ?: generateSequence { dpad.press(Dpad.Down) }.take(RAIL_PRESSES).first { it == RAIL_SEARCH }
        dpad.center()
        device.wait(Until.hasObject(By.text(SOURCES)), SCREEN_WAIT_MS)
    }

    @Test
    fun theRailOpensTheNewSearchScreen() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSearchFromTheRail()
            assertEquals(SearchTvFragment::class.java, scenario.currentFragment()?.javaClass)
        }
    }

    @Test
    fun aRemoteCanReachTheScreenAndLeaveIt() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openSearchFromTheRail()
            val graph = dpad.walk()
            assertEquals(graph.toString(), emptyList<String>(), graph.unreachable(listOf(SOURCES, RAIL_SEARCH)))
            assertEquals(graph.toString(), emptyList<String>(), graph.deadEnds())
            assertEquals(graph.toString(), emptyList<String>(), graph.traps())
            assertEquals(graph.toString(), emptySet<String>(), graph.keyboardOpenedOn)
        }
    }

    /** `BUG-30` on the live path: landing on the field only highlights it, and OK opens the keyboard. */
    @Test
    fun okOpensTheKeyboardAndBackKeepsTheScreen() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSearchFromTheRail()

            // Right leaves the rail for the content region, whose action can sit below the field.
            var field = dpad.press(Dpad.Right)
            repeat(RAIL_PRESSES) { if (!field.isField()) field = dpad.press(Dpad.Up) }
            assertTrue("Never reached the field, last on $field", field.isField())
            assertFalse("Landing on the field opened the keyboard", dpad.keyboardOpens(NO_KEYBOARD_WAIT_MS))

            dpad.center()
            assertTrue("OK did not open the keyboard", dpad.keyboardOpens(KEYBOARD_WAIT_MS))

            dpad.closeKeyboard()
            assertFalse("The keyboard came back", dpad.keyboardOpens(NO_KEYBOARD_WAIT_MS))
            assertEquals(SearchTvFragment::class.java, scenario.currentFragment()?.javaClass)
            assertEquals(field, dpad.focused())
        }
    }

    /** The empty field has no text to be named by, so the driver names it by class and bounds. */
    private fun String?.isField() = this?.startsWith(EDIT_TEXT_CLASS) == true

    private companion object {
        const val RAIL_SEARCH = "Search"
        const val SOURCES = "Sources"
        const val RAIL_WAIT_MS = 5_000L
        const val SCREEN_WAIT_MS = 10_000L
        const val SETUP_BACK_PRESSES = 3
        const val RAIL_PRESSES = 6
        const val NO_KEYBOARD_WAIT_MS = 3_000L
        const val KEYBOARD_WAIT_MS = 10_000L
        const val EDIT_TEXT_CLASS = "android.widget.EditText"
    }
}

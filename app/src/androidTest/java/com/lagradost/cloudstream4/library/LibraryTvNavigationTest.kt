package com.lagradost.cloudstream4.library

import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream4.core.devicetest.TouchModeReset
import com.lagradost.cloudstream4.detail.DetailTvFragment
import com.lagradost.cloudstream3.ui.setup.HAS_DONE_SETUP_KEY
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real app on a TV: the rail opens the new library through real navigation, a bookmark stored under the
 * real keys is on it, a remote can open it, and Back puts focus back on it.
 *
 * No extension is installed, so a provider that answers nothing stands in for one. The title's real page opens
 * and shows its failure, with no network: what a loaded page shows is not this test's concern.
 */
@RunWith(AndroidJUnit4::class)
class LibraryTvNavigationTest {

    /** `BUG-59`: starts and ends out of touch mode, whatever ran before it. */
    @get:Rule
    val touchMode = TouchModeReset()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val dpad = DpadDriver()
    private val provider = object : MainAPI() {
        override var name = PROVIDER
    }

    @Before
    fun seedALocalBookmark() {
        APIHolder.allProviders.withLock { APIHolder.allProviders.add(provider) }
        instrumentation.runOnMainSync {
            setKey(HAS_DONE_SETUP_KEY, true)
            DataStoreHelper.setResultWatchState(STORED_ID, WatchType.WATCHING.internalId)
            DataStoreHelper.setBookmarkedData(
                STORED_ID,
                DataStoreHelper.BookmarkedData(
                    bookmarkedTime = 1L,
                    id = STORED_ID,
                    latestUpdatedTime = 1L,
                    name = TITLE,
                    url = "https://example.invalid/title",
                    apiName = PROVIDER,
                    type = null,
                    posterUrl = null,
                    year = null,
                ),
            )
        }
    }

    @After
    fun removeIt() {
        APIHolder.allProviders.withLock { APIHolder.allProviders.remove(provider) }
        instrumentation.runOnMainSync { DataStoreHelper.deleteBookmarkedData(STORED_ID) }
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
    private fun openLibraryFromTheRail() {
        val rail = By.desc(RAIL_LIBRARY)
        repeat(SETUP_BACK_PRESSES) {
            if (device.wait(Until.hasObject(rail), RAIL_WAIT_MS)) return@repeat
            device.pressBack()
        }
        dpad.enter(Dpad.Left)
        generateSequence { dpad.press(Dpad.Left) }.take(RAIL_PRESSES).firstOrNull { it == RAIL_LIBRARY }
            ?: generateSequence { dpad.press(Dpad.Down) }.take(RAIL_PRESSES).first { it == RAIL_LIBRARY }
        dpad.center()
        device.wait(Until.hasObject(By.textContains(WATCHING)), SCREEN_WAIT_MS)
    }

    private fun pressToTheTitle() {
        dpad.press(Dpad.Right)
        generateSequence { dpad.press(Dpad.Down) }.take(RAIL_PRESSES).first { it == TITLE }
    }

    @Test
    fun theRailOpensTheNewLibraryAndBackRestoresFocusOnTheOpenedTitle() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openLibraryFromTheRail()
            assertEquals(LibraryTvFragment::class.java, scenario.currentFragment()?.javaClass)

            pressToTheTitle()
            dpad.center()
            device.wait(Until.gone(By.text(WATCHING)), SCREEN_WAIT_MS)
            // KMP-38: TV opens the new detail page at this destination.
            assertEquals(DetailTvFragment::class.java, scenario.currentFragment()?.javaClass)

            repeat(BACK_PRESSES) {
                if (scenario.currentFragment()?.javaClass != LibraryTvFragment::class.java) {
                    device.pressBack()
                    device.waitForIdle()
                }
            }
            assertEquals(LibraryTvFragment::class.java, scenario.currentFragment()?.javaClass)
            device.wait(Until.hasObject(By.text(TITLE)), SCREEN_WAIT_MS)
            assertEquals(TITLE, dpad.focused())

            // To the rail and back, to the same card and not to the first stop on the page.
            assertEquals(RAIL_LIBRARY, dpad.press(Dpad.Left))
            assertEquals(TITLE, dpad.press(Dpad.Right))
        }
    }

    private companion object {
        const val STORED_ID = 987_654_321
        const val TITLE = "A Bookmarked Title"
        const val PROVIDER = "LibraryTestProvider"
        const val RAIL_LIBRARY = "Library"
        const val WATCHING = "Watching"
        const val RAIL_WAIT_MS = 5_000L
        const val SCREEN_WAIT_MS = 10_000L
        const val SETUP_BACK_PRESSES = 3
        const val RAIL_PRESSES = 6
        const val BACK_PRESSES = 4
    }
}

package com.lagradost.cloudstream4.library

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.ui.library.LAST_SYNC_API_KEY
import com.lagradost.cloudstream3.ui.library.ListSorting
import com.lagradost.cloudstream3.ui.setup.HAS_DONE_SETUP_KEY
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.devicetest.TouchModeReset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `KMP-49` in the real app on a TV: the library's provider button over the real `AccountManager.syncApis`, the panel
 * listing them, and the field's Left reaching the rail (`BUG-34`).
 *
 * No account is signed in, and none is made: every tracking service is listed as not connected, and the local list is
 * the one shown. A service's lists are covered with a fake `SyncAPI` in `AndroidLibrarySourcesTest`.
 */
@RunWith(AndroidJUnit4::class)
class LibrarySourcesTvNavigationTest {

    /** `BUG-59`: starts and ends out of touch mode, whatever ran before it. */
    @get:Rule
    val touchMode = TouchModeReset()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val dpad = DpadDriver()
    private var chosenBefore: String? = null
    private var sortBefore: Int? = null

    @Before
    fun startFromTheLocalList() {
        instrumentation.runOnMainSync {
            setKey(HAS_DONE_SETUP_KEY, true)
            chosenBefore = getKey<String>(CHOSEN_KEY)
            removeKey(CHOSEN_KEY)
            sortBefore = getKey<Int>(SORT_KEY)
        }
    }

    @After
    fun putTheChoiceBack() {
        instrumentation.runOnMainSync {
            chosenBefore?.let { setKey(CHOSEN_KEY, it) } ?: removeKey(CHOSEN_KEY)
            removeKey("$currentAccount/library_synced_at", RECORD_NAME)
            sortBefore?.let { setKey(SORT_KEY, it) } ?: removeKey(SORT_KEY)
        }
    }

    private fun openLibraryFromTheRail() {
        val rail = By.desc(RAIL_LIBRARY)
        repeat(SETUP_BACK_PRESSES) {
            if (device.wait(Until.hasObject(rail), RAIL_WAIT_MS)) return@repeat
            device.pressBack()
        }
        dpad.enter(Dpad.Left)
        generateSequence { dpad.press(Dpad.Left) }.take(PRESSES).firstOrNull { it == RAIL_LIBRARY }
            ?: generateSequence { dpad.press(Dpad.Down) }.take(PRESSES).first { it == RAIL_LIBRARY }
        dpad.center()
        assertTrue(device.wait(Until.hasObject(By.text(LOCAL)), SCREEN_WAIT_MS))
    }

    /** Right from the rail onto the page, then Up to the chrome row and Left along it to the button. */
    private fun toTheProviderButton() {
        dpad.press(Dpad.Right)
        generateSequence { dpad.press(Dpad.Up) }.take(PRESSES).first { it == LOCAL || it.isTheField() }
        if (dpad.focused().isTheField()) assertEquals(LOCAL, dpad.press(Dpad.Left))
    }

    @Test
    fun theButtonOpensEveryRealSourceAndOnlyTheLocalListIsConnected() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openLibraryFromTheRail()
            toTheProviderButton()
            dpad.center()
            assertTrue(device.wait(Until.hasObject(By.text(PANEL)), SCREEN_WAIT_MS))
            assertEquals("the panel opens on the shown source", LOCAL, dpad.focused())
            val names = AccountManager.syncApis.map { it.name }
            assertEquals(listOf("MAL", "Kitsu", "AniList", "Simkl", LOCAL), names)
            names.forEach { name -> assertTrue(name, device.hasObject(By.text(name))) }
            assertEquals(names.size - 1, device.findObjects(By.text(NOT_CONNECTED)).size)

            // A service that is not signed in is a stop, and pressing it leaves the panel open and the choice alone.
            generateSequence { dpad.press(Dpad.Down) }.take(PRESSES).first { it in names && it != LOCAL }
            dpad.center()
            device.waitForIdle()
            assertTrue(device.hasObject(By.text(PANEL)))
            instrumentation.runOnMainSync { assertNull(getKey<String>(CHOSEN_KEY)) }

            pressRemoteBack()
            assertTrue(device.wait(Until.gone(By.text(PANEL)), SCREEN_WAIT_MS))
            assertEquals(LOCAL, dpad.focused())
        }
    }

    /** `BUG-34`: Left from the field used to stop on the field. Now it reaches the button, and the button's Left the rail. */
    @Test
    fun leftFromTheFieldReachesTheRail() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openLibraryFromTheRail()
            toTheProviderButton()
            assertTrue("Right from the button is the field", dpad.press(Dpad.Right).isTheField())
            assertEquals(LOCAL, dpad.press(Dpad.Left))
            assertEquals(RAIL_LIBRARY, dpad.press(Dpad.Left))
        }
    }

    /** The two records under the real keys: the old screen's choice, and the new time of a read. */
    @Test
    fun theChoiceAndTheTimeOfAReadAreKeptUnderTheRealKeys() {
        instrumentation.runOnMainSync {
            DataStoreLibrarySourceRecords.choose(LOCAL)
            assertEquals(LOCAL, getKey<String>(CHOSEN_KEY))
            assertEquals(LOCAL, DataStoreLibrarySourceRecords.chosen())
            assertNull(DataStoreLibrarySourceRecords.syncedAt(RECORD_NAME))
            DataStoreLibrarySourceRecords.synced(RECORD_NAME, 1_234_567L)
            assertEquals(1_234_567L, DataStoreLibrarySourceRecords.syncedAt(RECORD_NAME))
        }
    }

    /** `KMP-50`: the sort under the old screen's own key, as its ordinal, so the old screen reads the same choice. */
    @Test
    fun theSortIsKeptUnderTheOldScreensKeyAsItsOrdinal() {
        instrumentation.runOnMainSync {
            removeKey(SORT_KEY)
            assertNull("nothing stored is the default, not the delegate's A to Z", DataStoreLibrarySourceRecords.sortingMode())
            DataStoreLibrarySourceRecords.sortBy(ListSorting.ReleaseDateOld.ordinal)
            assertEquals(ListSorting.ReleaseDateOld.ordinal, getKey<Int>(SORT_KEY))
            assertEquals(ListSorting.ReleaseDateOld.ordinal, DataStoreHelper.librarySortingMode)
            assertEquals(ListSorting.ReleaseDateOld.ordinal, DataStoreLibrarySourceRecords.sortingMode())
        }
    }

    /**
     * BACK as a remote sends it. `UiDevice.pressBack()` is not that key (`DetailScreenTvDeviceTest`), and the panel
     * takes BACK as a key event.
     */
    private fun pressRemoteBack() {
        val time = SystemClock.uptimeMillis()
        listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
            val event = KeyEvent(
                time, time, action, KeyEvent.KEYCODE_BACK, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_HDMI,
            )
            check(instrumentation.uiAutomation.injectInputEvent(event, true)) { "BACK was not injected" }
        }
    }

    /** The field reads as its view, not its placeholder, in the real app's accessibility tree. */
    private fun String?.isTheField() = this == FILTER || this?.startsWith("android.widget.EditText") == true

    private companion object {
        val CHOSEN_KEY get() = "$currentAccount/$LAST_SYNC_API_KEY"
        val SORT_KEY get() = "$currentAccount/library_sorting_mode"
        const val RECORD_NAME = "KMP49TestSource"
        const val LOCAL = "Local"
        const val PANEL = "Library source"
        const val NOT_CONNECTED = "Not connected"
        const val FILTER = "Filter Bookmarks"
        const val RAIL_LIBRARY = "Library"
        const val RAIL_WAIT_MS = 5_000L
        const val SCREEN_WAIT_MS = 10_000L
        const val SETUP_BACK_PRESSES = 3
        const val PRESSES = 8
    }
}

package com.lagradost.cloudstream4.account

import android.app.Activity
import android.view.KeyEvent
import androidx.preference.PreferenceManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.CredentialStore
import com.lagradost.cloudstream3.ui.account.AccountSelectActivity
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream4.core.devicetest.TouchModeReset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `KMP-73` on the real path: `AccountSelectActivity` on the TV layout, its view model, and `MainActivity` after it.
 *
 * The sign-in here is a marker in the credential store, never a real token, and the test asserts it never reaches the
 * screen. The device's own profiles, selection and skip setting are put back afterwards.
 *
 * **Leaves nothing running.** These launches are not `ActivityScenario`s, so nothing closes what they start: the picker,
 * and the `MainActivity` it hands over to. `MainActivity` is `singleTask`, so one left alive is reused by the next
 * test's launch, with this test's profile and screens in it (train 8: `TvDetailNavigationTest` after this one found
 * "Go Back" focused, not "Try again"). Every activity started here is finished and waited out before the state is put
 * back, and the state includes the process's own copies: the sign-in IDs cached for the selected profile and
 * [AccountSelectActivity.hasLoggedIn]. It also includes **touch mode**, which is the device's: the edit test's one tap
 * turns it on, and that alone is what failed the detail test (measured: of the six tests, only the edit test paired
 * with it failed it, and it changes no stored preference).
 */
@RunWith(AndroidJUnit4::class)
class AccountSelectTvNavigationTest {

    /** `BUG-59`: starts and ends out of touch mode, whatever ran before it. */
    @get:Rule
    val touchMode = TouchModeReset()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val skipKey = "skip_startup_account_select_key"
    private lateinit var profilesBefore: Array<DataStoreHelper.Account>
    private var selectedBefore = 0
    private var skipBefore: Boolean? = null
    private var loggedInBefore = false
    private var activitiesBefore = emptySet<Activity>()
    private val signIn = "${AccountManager.ACCOUNT_TOKEN}/simkl/0"

    @Before
    fun saveDevice() = instrumentation.runOnMainSync {
        activitiesBefore = alive()
        loggedInBefore = AccountSelectActivity.hasLoggedIn
        profilesBefore = DataStoreHelper.accounts
        selectedBefore = DataStoreHelper.selectedKeyIndex
        skipBefore = if (prefs.contains(skipKey)) prefs.getBoolean(skipKey, false) else null
        prefs.edit().putBoolean(skipKey, false).commit()
        DataStoreHelper.selectedKeyIndex = 0
        AccountSelectActivity.hasLoggedIn = false
    }

    @After
    fun restoreDevice() {
        finishWhatThisStarted()
        instrumentation.runOnMainSync {
            CredentialStore.remove(signIn)
            DataStoreHelper.accounts = profilesBefore
            DataStoreHelper.selectedKeyIndex = selectedBefore
            AccountManager.updateAccountIds() // the cache still holds the IDs of the profile the test switched to
            prefs.edit().apply { skipBefore?.let { putBoolean(skipKey, it) } ?: remove(skipKey) }.commit()
            AccountSelectActivity.hasLoggedIn = loggedInBefore
        }
    }

    /** Every activity of this process that has not been destroyed. Main thread only. */
    private fun alive(): Set<Activity> = LIVE_STAGES.flatMapTo(mutableSetOf()) {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)
    }

    /**
     * Finishes every activity this test started and waits until each is destroyed, then for a quiet second: the hand-over
     * sets [AccountSelectActivity.hasLoggedIn] before it starts `MainActivity`, so that one can still be on its way.
     * Fails if one outlives the wait.
     */
    private fun finishWhatThisStarted() {
        val deadline = System.currentTimeMillis() + 10_000
        var left: Set<Activity> = emptySet()
        var quietSince = 0L
        while (System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                left = alive() - activitiesBefore
                left.filterNot { it.isFinishing }.forEach { it.finish() }
            }
            val now = System.currentTimeMillis()
            if (left.isNotEmpty()) quietSince = 0L
            else if (quietSince == 0L) quietSince = now
            else if (now - quietSince >= QUIET_MS) return
            Thread.sleep(100)
        }
        throw AssertionError("Still running after the test: ${left.map { it.javaClass.simpleName }}")
    }

    private fun profiles(vararg extra: DataStoreHelper.Account) = instrumentation.runOnMainSync {
        DataStoreHelper.accounts = arrayOf(*extra)
    }

    private fun screenShown(): Boolean = device.wait(Until.hasObject(By.text(TITLE)), 10_000)

    /**
     * As the launcher starts it. Not `ActivityScenario`: skipping finishes the activity inside `onCreate`, which a
     * scenario reports as a launch that never reached a state.
     */
    private fun launch(fromMainToEdit: Boolean = false) {
        context.startActivity(
            Intent(context, AccountSelectActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .apply { if (fromMainToEdit) putExtra("isEditingFromMainActivity", true) },
        )
    }

    /** `navigateToMainActivity` sets this as it hands over, whichever way the activity got there. */
    private fun handedOver(): Boolean {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            if (AccountSelectActivity.hasLoggedIn) return true
            Thread.sleep(100)
        }
        return false
    }

    /** Behaviour change: one added profile plus the default is two profiles, and the picker now shows. */
    @Test
    fun oneAddedProfileShowsThePicker() {
        profiles(DataStoreHelper.Account(TESTER, "Tester", defaultImageIndex = 0))
        launch()
        assertTrue("8d did not show with two profiles", screenShown())
        assertTrue("It handed over without a choice", !AccountSelectActivity.hasLoggedIn)
    }

    @Test
    fun aSingleProfileStillSkipsThePicker() {
        profiles()
        launch()
        assertTrue("It never handed over to MainActivity", handedOver())
        assertNull("8d showed with only the default profile", device.findObject(By.text(TITLE)))
    }

    /** Focus starts on the current profile (the default); RIGHT and OK pick the next one, and MainActivity follows. */
    @Test
    fun choosingAProfileOpensTheAppAsThatProfile() {
        profiles(DataStoreHelper.Account(TESTER, "Tester", defaultImageIndex = 0))
        launch()
        assertTrue(screenShown())
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)
        device.waitForIdle()
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue("It never handed over to MainActivity", handedOver())
        assertEquals(TESTER, DataStoreHelper.selectedKeyIndex)
    }

    /** A sign-in shows as the service's name on the profile's card, read by presence; the stored value never shows. */
    @Test
    fun aSignInShowsAsItsServiceAndNeverAsItsValue() {
        profiles(DataStoreHelper.Account(TESTER, "Tester", defaultImageIndex = 0))
        CredentialStore.write(signIn, arrayOf(MARKER))
        launch()
        assertTrue(screenShown())
        assertNotNull("The current profile's card does not say Simkl",
            device.wait(Until.findObject(By.text("Current · Simkl")), 5_000))
        assertNull("A stored sign-in value reached the screen", device.findObject(By.textContains(MARKER)))
    }

    /** A sign-out leaves an empty array behind; that is signed out, not a sync. */
    @Test
    fun anEmptySignInCountsAsSignedOut() {
        profiles(DataStoreHelper.Account(TESTER, "Tester", defaultImageIndex = 0))
        CredentialStore.write(signIn, emptyArray<String>())
        launch()
        assertTrue(screenShown())
        assertNotNull("The current profile's card does not say No sync",
            device.wait(Until.findObject(By.text("Current · No sync")), 5_000))
        assertNull("An empty sign-in reads as Simkl", device.findObject(By.text("Current · Simkl")))
    }

    /** As the old grid: editing a profile when opened from MainActivity switches to it and goes back to MainActivity. */
    @Test
    fun anEditFromMainSwitchesToThatProfileAndReturns() {
        profiles(DataStoreHelper.Account(TESTER, "Tester", defaultImageIndex = 0))
        launch(fromMainToEdit = true)
        assertTrue(screenShown())
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)
        device.waitForIdle()
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_CENTER)
        val apply = device.wait(Until.findObject(By.res(context.packageName, "apply_btt")), 5_000)
        assertNotNull("The profile editor did not open", apply)
        apply.click()
        assertTrue("It did not go back to MainActivity", handedOver())
        assertEquals(TESTER, DataStoreHelper.selectedKeyIndex)
    }

    private companion object {
        const val TITLE = "Who's watching?"
        const val TESTER = 4730
        const val MARKER = "NOT-A-REAL-TOKEN"
        const val QUIET_MS = 1_000L
        val LIVE_STAGES = listOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED,
            Stage.STOPPED, Stage.RESTARTED)
    }
}

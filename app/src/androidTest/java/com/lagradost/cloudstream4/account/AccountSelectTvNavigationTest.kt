package com.lagradost.cloudstream4.account

import android.view.KeyEvent
import androidx.preference.PreferenceManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.CredentialStore
import com.lagradost.cloudstream3.ui.account.AccountSelectActivity
import com.lagradost.cloudstream3.utils.DataStoreHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `KMP-73` on the real path: `AccountSelectActivity` on the TV layout, its view model, and `MainActivity` after it.
 *
 * The sign-in here is a marker in the credential store, never a real token, and the test asserts it never reaches the
 * screen. The device's own profiles, selection and skip setting are put back afterwards.
 */
@RunWith(AndroidJUnit4::class)
class AccountSelectTvNavigationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val skipKey = "skip_startup_account_select_key"
    private lateinit var profilesBefore: Array<DataStoreHelper.Account>
    private var selectedBefore = 0
    private var skipBefore: Boolean? = null
    private val signIn = "${AccountManager.ACCOUNT_TOKEN}/simkl/0"

    @Before
    fun saveDevice() = instrumentation.runOnMainSync {
        profilesBefore = DataStoreHelper.accounts
        selectedBefore = DataStoreHelper.selectedKeyIndex
        skipBefore = if (prefs.contains(skipKey)) prefs.getBoolean(skipKey, false) else null
        prefs.edit().putBoolean(skipKey, false).commit()
        DataStoreHelper.selectedKeyIndex = 0
        AccountSelectActivity.hasLoggedIn = false
    }

    @After
    fun restoreDevice() = instrumentation.runOnMainSync {
        CredentialStore.remove(signIn)
        DataStoreHelper.accounts = profilesBefore
        DataStoreHelper.selectedKeyIndex = selectedBefore
        prefs.edit().apply { skipBefore?.let { putBoolean(skipKey, it) } ?: remove(skipKey) }.commit()
        AccountSelectActivity.hasLoggedIn = false
    }

    private fun profiles(vararg extra: DataStoreHelper.Account) = instrumentation.runOnMainSync {
        DataStoreHelper.accounts = arrayOf(*extra)
    }

    private fun screenShown(): Boolean = device.wait(Until.hasObject(By.text(TITLE)), 10_000)

    /**
     * As the launcher starts it. Not `ActivityScenario`: skipping finishes the activity inside `onCreate`, which a
     * scenario reports as a launch that never reached a state.
     */
    private fun launch() {
        context.startActivity(
            Intent(context, AccountSelectActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
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

    private companion object {
        const val TITLE = "Who's watching?"
        const val TESTER = 4730
        const val MARKER = "NOT-A-REAL-TOKEN"
    }
}

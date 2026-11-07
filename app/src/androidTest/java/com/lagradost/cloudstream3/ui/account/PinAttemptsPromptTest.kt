package com.lagradost.cloudstream3.ui.account

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.BackupUtils
import com.lagradost.cloudstream3.utils.BackupUtils.restoreAfterPins
import com.lagradost.cloudstream3.utils.DataStoreHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * `BUG-55` on the device: the profile-switch prompt and the restore prompt both stop comparing after five wrong PINs,
 * show the wait, keep a right PIN out during it, and take it once the wait is over. The count is in the app's own file,
 * and nothing in that file is a PIN.
 *
 * The limit's clock is faked, so a 30-second wait passes at once. Every PIN is a marker (`4971`, wrong `4970`).
 */
@RunWith(AndroidJUnit4::class)
class PinAttemptsPromptTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val now = AtomicLong(SystemClock.elapsedRealtime())
    private val attempts get() = context.getSharedPreferences(PinAttempts.FILE, Context.MODE_PRIVATE)
    private lateinit var profilesBefore: Array<DataStoreHelper.Account>
    private var selectedBefore = 0

    private val kids = DataStoreHelper.Account(KIDS, "Kids", defaultImageIndex = 0, lockPin = PIN)
    private val dad = DataStoreHelper.Account(DAD, "Dad", defaultImageIndex = 0, lockPin = null)

    @Before
    fun save() = instrumentation.runOnMainSync {
        profilesBefore = DataStoreHelper.accounts
        selectedBefore = DataStoreHelper.selectedKeyIndex
        DataStoreHelper.accounts = arrayOf(dad, kids)
        DataStoreHelper.selectedKeyIndex = DAD
        AccountSelectActivity.hasLoggedIn = false
        PinAttempts.clock = { now.get() }
        PinAttempts.right(KIDS)
    }

    @After
    fun restore() = instrumentation.runOnMainSync {
        PinAttempts.right(KIDS)
        PinAttempts.clock = { SystemClock.elapsedRealtime() }
        DataStoreHelper.accounts = profilesBefore
        DataStoreHelper.selectedKeyIndex = selectedBefore
        AccountSelectActivity.hasLoggedIn = false
    }

    private fun field() = device.wait(Until.findObject(By.res(context.packageName, "pinEditText")), WAIT_MS)
        .also { assertNotNull("No PIN field", it) }

    private fun type(pin: String) {
        field().text = pin
        device.waitForIdle()
    }

    /** Five wrong PINs, then the wait: shown, the field off, the right PIN kept out, and the record holds no PIN. */
    private fun fiveWrongThenTheWaitHolds(gotIn: () -> Boolean) {
        repeat(PinAttempts.FREE_WRONG) { type(WRONG) }
        val waiting = context.getString(R.string.pin_error_wait, "").trimEnd()
        assertNotNull("No wait shown", device.wait(Until.findObject(By.textStartsWith(waiting)), WAIT_MS))
        assertFalse("The field takes input during the wait", field().isEnabled)
        runCatching { field().text = PIN } // a disabled field may refuse it; either way it must not get in
        device.waitForIdle()
        Thread.sleep(SETTLE_MS)
        assertFalse("The right PIN got in during the wait", gotIn())

        assertEquals("The count is not stored", PinAttempts.FREE_WRONG, attempts.getInt("$KIDS/wrong", -1))
        assertTrue("A PIN is stored with the count", attempts.all.values.none { it.toString() == PIN })
        PinAttempts.forgetHeld()
        assertTrue("A restart lifted the wait", PinAttempts.waitMs(KIDS) > 0L)
    }

    private fun waitIsOverThenTheRightPinGetsIn(gotIn: () -> Boolean) {
        now.addAndGet(PinAttempts.FIRST_WAIT_MS)
        assertTrue("The field did not come back", device.wait(Until.hasObject(By.res(context.packageName, "pinEditText").enabled(true)), WAIT_MS))
        type(PIN)
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (!gotIn() && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        assertTrue("The right PIN did not get in after the wait", gotIn())
        assertFalse("A right PIN left the count", attempts.contains("$KIDS/wrong"))
    }

    @Test
    fun theProfileSwitchPromptWaitsAfterFiveWrongPins() {
        ActivityScenario.launch(AccountSelectActivity::class.java).use { scenario ->
            // The production call, on a view model the activity doesn't observe: a right PIN switches the profile
            // without the activity going on to MainActivity, which would outlive the scenario.
            scenario.onActivity { AccountViewModel().handleAccountSelect(kids, it) }
            val switched = { DataStoreHelper.selectedKeyIndex == KIDS }
            fiveWrongThenTheWaitHolds(switched)
            waitIsOverThenTheRightPinGetsIn(switched)
        }
    }

    @Test
    fun theRestorePromptWaitsAfterFiveWrongPins() {
        val restored = AtomicBoolean(false)
        val backup = BackupUtils.BackupFile(
            datastore = BackupUtils.BackupVars(null, null, mapOf(PROFILES_KEY to arrayOf(dad).toJson()), null, null, null),
            settings = BackupUtils.BackupVars(null, null, null, null, null, null),
        )
        ActivityScenario.launch(AccountSelectActivity::class.java).use { scenario ->
            scenario.onActivity { it.restoreAfterPins(backup) { restored.set(true) } }
            fiveWrongThenTheWaitHolds { restored.get() }
            waitIsOverThenTheRightPinGetsIn { restored.get() }
        }
    }

    private companion object {
        const val PROFILES_KEY = "${DataStoreHelper.TAG}/account"
        const val KIDS = 4970
        const val DAD = 4969
        const val PIN = "4971"
        const val WRONG = "4970"
        const val WAIT_MS = 10_000L
        const val POLL_MS = 100L
        const val SETTLE_MS = 1_000L
    }
}

package com.lagradost.cloudstream3.utils

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.syncproviders.CredentialStore
import com.lagradost.cloudstream3.ui.account.AccountSelectActivity
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.BackupUtils.restoreAfterPins
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `BUG-54`: a restore that replaces the profile list asks for the PIN of every locked profile first, and writes nothing
 * unless every one is given.
 *
 * Every PIN is a marker (`4961`, `4962`, wrong `4960`), never a real one. The device's profiles, selection and PINs are
 * put back afterwards.
 */
@RunWith(AndroidJUnit4::class)
class RestorePinGateTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val credentials get() = context.getSharedPreferences(CredentialStore.FILE, Context.MODE_PRIVATE)
    private lateinit var profilesBefore: Array<DataStoreHelper.Account>
    private var selectedBefore = 0

    private fun profile(index: Int, name: String, pin: String?) =
        DataStoreHelper.Account(index, name, defaultImageIndex = 0, lockPin = pin)

    /** A backup whose profile list holds one profile, "Dad", at the index "Kids" is locked on. */
    private val backup = BackupUtils.BackupFile(
        datastore = BackupUtils.BackupVars(
            null, null, mapOf(PROFILES_KEY to arrayOf(profile(KIDS, "Dad", null)).toJson()), null, null, null,
        ),
        settings = BackupUtils.BackupVars(null, null, null, null, null, null),
    )

    @Before
    fun save() = instrumentation.runOnMainSync {
        profilesBefore = DataStoreHelper.accounts
        selectedBefore = DataStoreHelper.selectedKeyIndex
        DataStoreHelper.selectedKeyIndex = 0
        AccountSelectActivity.hasLoggedIn = false
    }

    @After
    fun restore() = instrumentation.runOnMainSync {
        DataStoreHelper.accounts = profilesBefore
        DataStoreHelper.selectedKeyIndex = selectedBefore
        AccountSelectActivity.hasLoggedIn = false
    }

    /** A FragmentActivity that stays up with more than one profile: the profile picker. */
    private fun withActivity(block: (ActivityScenario<AccountSelectActivity>) -> Unit) =
        ActivityScenario.launch(AccountSelectActivity::class.java).use(block)

    private fun prompt(name: String) =
        device.wait(Until.findObject(By.text(context.getString(R.string.enter_pin_with_name, name))), WAIT_MS)

    private fun type(pin: String) {
        val field = device.wait(Until.findObject(By.res(context.packageName, "pinEditText")), WAIT_MS)
        assertNotNull("No PIN field", field)
        field.text = pin
        device.waitForIdle()
    }

    @Test
    fun aWrongPinThenCancelWritesNothingAndBothRightRestores() {
        instrumentation.runOnMainSync {
            DataStoreHelper.accounts = arrayOf(profile(KIDS, "Kids", "4961"), profile(TEENS, "Teens", "4962"))
        }
        val restored = AtomicBoolean(false)

        withActivity { scenario ->
            // Kids right, Teens wrong, then Cancel.
            scenario.onActivity { it.restoreAfterPins(backup) { restored.set(true) } }
            assertNotNull("No prompt for Kids", prompt("Kids"))
            type("4961")
            assertNotNull("No prompt for Teens", prompt("Teens"))
            type("4960")
            assertNotNull("A wrong PIN closed the prompt", prompt("Teens"))
            cancelButton().click()
            device.waitForIdle()
            Thread.sleep(SETTLE_MS)

            assertFalse("A restore ran without every PIN", restored.get())
            forgetCachedList()
            val now = DataStoreHelper.accounts
            assertEquals("The profile list changed", listOf("Kids", "Teens"), now.map { it.name })
            assertEquals("4961", now.single { it.keyIndex == KIDS }.lockPin)
            assertEquals("4962", now.single { it.keyIndex == TEENS }.lockPin)

            // Both right: the restore runs, and its profile comes back unlocked.
            scenario.onActivity { it.restoreAfterPins(backup) { restored.set(true) } }
            assertNotNull(prompt("Kids"))
            type("4961")
            assertNotNull(prompt("Teens"))
            type("4962")
            val deadline = System.currentTimeMillis() + WAIT_MS
            while (!restored.get() && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
            assertTrue("The restore did not run with both PINs", restored.get())

            forgetCachedList()
            val after = DataStoreHelper.accounts
            assertEquals(listOf("Dad"), after.map { it.name })
            assertNull("A restored profile inherited a PIN", after.single().lockPin)
            assertFalse(credentials.all.keys.any { it.startsWith("${CredentialStore.PIN_FOLDER}/") })
        }
    }

    @Test
    fun withNoLockedProfileNothingIsAsked() {
        instrumentation.runOnMainSync {
            DataStoreHelper.accounts = arrayOf(profile(KIDS, "Kids", null), profile(TEENS, "Teens", null))
        }
        val restored = AtomicBoolean(false)
        withActivity { scenario ->
            scenario.onActivity { it.restoreAfterPins(backup) { restored.set(true) } }
            val deadline = System.currentTimeMillis() + WAIT_MS
            while (!restored.get() && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
            assertTrue("The restore did not run", restored.get())
            assertNull("A PIN was asked with no locked profile", device.findObject(By.res(context.packageName, "pinEditText")))
        }
    }

    /**
     * The prompt's Cancel button, found by the dialog's negative-button id. If the keyboard covers it, one BACK closes the
     * keyboard. If that BACK closed the prompt instead, there is no button and this fails, naming what is on screen,
     * so the test can't pass through a different way out.
     */
    private fun cancelButton(): UiObject2 {
        val negative = By.res("android", "button2")
        val found = device.wait(Until.findObject(negative), SHORT_WAIT_MS)
            ?: run {
                device.pressBack()
                device.wait(Until.findObject(negative), WAIT_MS)
            }
        if (found == null) {
            val screen = ByteArrayOutputStream().also { device.dumpWindowHierarchy(it) }.toString()
                .lineSequence().mapNotNull { Regex("""text="([^"]+)"|resource-id="([^"]+)"""").find(it)?.value }.toList()
            fail("No Cancel button; on screen: $screen")
        }
        return found!!
    }

    /** Drops `DataStoreHelper`'s in-memory copy of the list, so the next read sees what is on disk. Test-only reflection. */
    private fun forgetCachedList() {
        val holder = DataStoreHelper::class.java.getDeclaredField("storedAccounts\$delegate").apply { isAccessible = true }
        val delegate = holder.get(null) as PreferenceDelegate<*>
        PreferenceDelegate::class.java.getDeclaredField("cache").apply { isAccessible = true }.set(delegate, null)
    }

    private companion object {
        const val PROFILES_KEY = "${DataStoreHelper.TAG}/account"
        const val KIDS = 4960
        const val TEENS = 4961
        const val WAIT_MS = 10_000L
        const val SHORT_WAIT_MS = 2_000L
        const val POLL_MS = 100L
        const val SETTLE_MS = 1_000L
    }
}

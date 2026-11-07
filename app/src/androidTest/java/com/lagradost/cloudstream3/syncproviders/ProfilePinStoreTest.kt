package com.lagradost.cloudstream3.syncproviders

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.PreferenceDelegate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * `BUG-51`: profile PINs live in `CredentialStore`'s file, which Android's backup and device transfer exclude, and
 * never in the backed-up profile list; and a sign-in move that could not be written is retried, not marked done.
 *
 * Every PIN is a marker (`4951`, `4952`), never a real one, and assertions name keys only. The profile list, both
 * stores and the preferences folder's permissions are put back afterwards.
 */
@RunWith(AndroidJUnit4::class)
class ProfilePinStoreTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val main get() = context.getSharedPrefs()
    private val credentials get() = context.getSharedPreferences(CredentialStore.FILE, Context.MODE_PRIVATE)
    private val touched = mutableSetOf<String>()
    private lateinit var profilesBefore: Array<DataStoreHelper.Account>

    private fun pinKey(index: Int) = "${CredentialStore.PIN_FOLDER}/$index"
    private fun locked(index: Int, pin: String?) =
        DataStoreHelper.Account(index, "Locked", defaultImageIndex = 0, lockPin = pin)

    /** The profile list exactly as the backed-up store holds it. */
    private fun storedList(): String = main.getString(PROFILES_KEY, null).orEmpty()

    @Before
    fun save() = instrumentation.runOnMainSync { profilesBefore = DataStoreHelper.accounts }

    @After
    fun restore() = instrumentation.runOnMainSync {
        prefsFolder().setWritable(true, true)
        main.edit().apply { touched.forEach { remove(it) } }.commit()
        credentials.edit().apply { touched.forEach { remove(it) } }.commit()
        DataStoreHelper.accounts = profilesBefore
    }

    @Test
    fun aLockedProfileKeepsItsPinOutOfTheBackedUpStoreAndStaysLocked() {
        instrumentation.runOnMainSync { DataStoreHelper.accounts = arrayOf(locked(INDEX, PIN)) }

        assertFalse("A PIN is in the backed-up profile list", storedList().contains(PIN))
        assertEquals(PIN, credentials.getString(pinKey(INDEX), null))
        // The lock is read from here (AccountViewModel, AccountSelectActivity, AccountHelper).
        assertEquals("The profile lost its lock", PIN, DataStoreHelper.accounts.single { it.keyIndex == INDEX }.lockPin)
        assertEquals(PIN, DataStoreHelper.getAccounts(context).single { it.keyIndex == INDEX }.lockPin)
    }

    @Test
    fun aListStillHoldingAPinIsMovedOnFirstRead() {
        // As the store was before this change: the PIN inside the profile list.
        main.edit().putString(PROFILES_KEY, arrayOf(locked(INDEX, PIN)).toJson()).commit()
        forgetCachedList()

        val read = DataStoreHelper.accounts

        assertEquals("The PIN was lost in the move", PIN, read.single { it.keyIndex == INDEX }.lockPin)
        assertEquals(PIN, credentials.getString(pinKey(INDEX), null))
        assertFalse("The PIN was left in the backed-up profile list", storedList().contains(PIN))
        forgetCachedList()
        assertEquals("The move did not reach disk", PIN, DataStoreHelper.accounts.single { it.keyIndex == INDEX }.lockPin)
    }

    /** A removed profile's PIN goes with it, so a later profile on the same index is not locked by it. */
    @Test
    fun aReusedIndexDoesNotInheritAnOldLock() {
        instrumentation.runOnMainSync {
            DataStoreHelper.accounts = arrayOf(locked(INDEX, PIN), locked(INDEX + 1, PIN_2))
            DataStoreHelper.accounts = arrayOf(locked(INDEX + 1, PIN_2))
        }
        assertFalse(credentials.contains(pinKey(INDEX)))

        instrumentation.runOnMainSync { DataStoreHelper.accounts = arrayOf(locked(INDEX + 1, PIN_2), locked(INDEX, null)) }

        val accounts = DataStoreHelper.accounts
        assertNull("A new profile inherited an old PIN", accounts.single { it.keyIndex == INDEX }.lockPin)
        assertEquals(PIN_2, accounts.single { it.keyIndex == INDEX + 1 }.lockPin)
    }

    /**
     * The review's case: a sign-in move whose copy cannot be written is not marked done. The old key stays readable
     * and in place, and the move is retried in the same process once writing works again.
     */
    @Test
    fun aFailedMoveIsRetriedNotMarkedDone() {
        val key = "${AccountManager.ACCOUNT_TOKEN}/pin-test/$INDEX"
        main.edit().putString(key, "\"NOT-A-REAL-TOKEN-51\"").commit(); touched += key
        setMigrated(false)

        assertTrue(prefsFolder().setWritable(false, false))
        try {
            assertEquals("\"NOT-A-REAL-TOKEN-51\"", CredentialStore.readRaw(key))
            assertFalse("A move that was not written was marked done", migrated())
            assertTrue("The old key went before its copy was written", main.contains(key))
        } finally {
            prefsFolder().setWritable(true, true)
        }

        assertEquals("\"NOT-A-REAL-TOKEN-51\"", CredentialStore.readRaw(key))
        assertTrue("The move was not retried", migrated())
        assertFalse("The old key was left in the backed-up store", main.contains(key))
    }

    private fun prefsFolder() = File(context.applicationInfo.dataDir, "shared_prefs")

    // Test-only reflection on private state. A rename makes these throw, which fails the test rather than passing it.

    /** Drops `DataStoreHelper`'s in-memory copy of the list, so the next read sees what is on disk, as a new process would. */
    private fun forgetCachedList() {
        val holder = DataStoreHelper::class.java.getDeclaredField("storedAccounts\$delegate").apply { isAccessible = true }
        val delegate = holder.get(null) as PreferenceDelegate<*>
        PreferenceDelegate::class.java.getDeclaredField("cache").apply { isAccessible = true }.set(delegate, null)
    }

    private fun migratedField() = CredentialStore::class.java.getDeclaredField("migrated").apply { isAccessible = true }
    private fun migrated() = migratedField().getBoolean(null)
    private fun setMigrated(value: Boolean) = migratedField().setBoolean(null, value)

    private companion object {
        const val PROFILES_KEY = "${DataStoreHelper.TAG}/account"
        const val INDEX = 4950
        const val PIN = "4951"
        const val PIN_2 = "4952"
    }
}

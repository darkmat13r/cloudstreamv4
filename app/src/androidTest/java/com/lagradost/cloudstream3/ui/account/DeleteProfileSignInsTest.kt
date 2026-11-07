package com.lagradost.cloudstream3.ui.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.utils.DataStoreHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `BUG-48`: deleting a profile left its sign-ins behind. A profile's data lives under `<keyIndex>/…`, which delete
 * removes, but its sign-ins live under `auth_tokens/<provider>/<keyIndex>` and `auth_ids/<provider>/<keyIndex>`, which it
 * did not. A new profile took `keyIndex = max + 1`, so deleting the newest and adding one gave the new profile the
 * deleted one's logged-in services.
 *
 * Every value here is a marker, never a real token, and no value is printed: assertions name keys only. The device's own
 * profile list, selection and any sign-in keys this test writes are restored afterwards.
 */
@RunWith(AndroidJUnit4::class)
class DeleteProfileSignInsTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var accountsBefore: Array<DataStoreHelper.Account>
    private var selectedBefore = 0
    private val written = mutableSetOf<String>()

    private fun account(index: Int) = DataStoreHelper.Account(keyIndex = index, name = "P$index", defaultImageIndex = 0)
    private fun token(provider: String, index: Int) = "${AccountManager.ACCOUNT_TOKEN}/$provider/$index"
    private fun id(provider: String, index: Int) = "${AccountManager.ACCOUNT_IDS}/$provider/$index"

    private fun signIn(vararg keys: String) = instrumentation.runOnMainSync {
        keys.forEach { setKey(it, MARKER); written += it }
    }

    private fun profiles(vararg indices: Int, selected: Int) = instrumentation.runOnMainSync {
        DataStoreHelper.accounts = indices.map(::account).toTypedArray()
        DataStoreHelper.selectedKeyIndex = selected
    }

    private fun assertGone(key: String) = assertNull("A sign-in survived at $key", getKey<String>(key))
    private fun assertKept(key: String) = assertEquals("A sign-in was removed at $key", MARKER, getKey<String>(key))

    @Before
    fun saveDevice() = instrumentation.runOnMainSync {
        accountsBefore = DataStoreHelper.accounts
        selectedBefore = DataStoreHelper.selectedKeyIndex
    }

    @After
    fun restoreDevice() = instrumentation.runOnMainSync {
        written.forEach { removeKey(it) }
        DataStoreHelper.accounts = accountsBefore
        DataStoreHelper.selectedKeyIndex = selectedBefore
    }

    @Test
    fun deletingAProfileRemovesItsSignInsAndNoOneElses() {
        profiles(KEPT, DELETED, NEIGHBOUR, selected = KEPT)
        signIn(token("simkl", DELETED), token("mal", DELETED), id("simkl", DELETED))
        signIn(token("simkl", KEPT), id("simkl", KEPT), token("simkl", NEIGHBOUR))

        instrumentation.runOnMainSync { AccountViewModel().handleAccountDelete(account(DELETED), context) }

        assertGone(token("simkl", DELETED)); assertGone(token("mal", DELETED)); assertGone(id("simkl", DELETED))
        assertKept(token("simkl", KEPT)); assertKept(id("simkl", KEPT)); assertKept(token("simkl", NEIGHBOUR))
    }

    /** The guard: a sign-in at an index above every profile pushes the next profile past it. */
    @Test
    fun aNewProfileNeverTakesAnIndexThatStillHasASignIn() {
        profiles(KEPT, selected = KEPT)
        signIn(token("simkl", STRANDED))

        assertEquals(STRANDED + 1, ProfileSignIns.nextKeyIndex(DataStoreHelper.accounts.toList()))
    }

    /** The startup cleanup removes only what no profile reaches: never 0, a listed profile, or the selected one. */
    @Test
    fun theStartupCleanupRemovesOnlyUnreachableSignIns() {
        // 0 is not stored (a fresh install synthesizes it); SELECTED is selected but not listed.
        profiles(KEPT, NEIGHBOUR, selected = SELECTED)
        signIn(token("simkl", 0), id("simkl", 0))
        signIn(token("simkl", KEPT), token("simkl", NEIGHBOUR), token("simkl", SELECTED))
        signIn(token("simkl", DELETED), token("mal", DELETED), id("simkl", DELETED))

        instrumentation.runOnMainSync { ProfileSignIns.removeOrphans() }

        assertKept(token("simkl", 0)); assertKept(id("simkl", 0))
        assertKept(token("simkl", KEPT)); assertKept(token("simkl", SELECTED))
        assertKept(token("simkl", NEIGHBOUR)) // 14242 stays when 4242 goes
        assertGone(token("simkl", DELETED)); assertGone(token("mal", DELETED)); assertGone(id("simkl", DELETED))
    }

    private companion object {
        const val KEPT = 4241
        const val DELETED = 4242
        const val NEIGHBOUR = 14242
        const val SELECTED = 4243
        const val STRANDED = 90001
        const val MARKER = "NOT-A-REAL-TOKEN"
    }
}

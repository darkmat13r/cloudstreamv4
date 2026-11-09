package com.lagradost.cloudstream3.syncproviders

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.account.PinAttempts
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.BackupUtils
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import com.lagradost.cloudstream3.utils.DataStoreHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/**
 * `BUG-50`: sign-ins live in their own file, excluded from Android's backup and device transfer, and the app's own
 * export carries no PIN and no sign-in.
 *
 * Every value is a marker (`NOT-A-REAL-TOKEN-…`, PIN `4950`), never a real credential, and assertions name keys only.
 * Both stores and the profile list are put back afterwards.
 */
@RunWith(AndroidJUnit4::class)
class CredentialStoreBackupTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val main get() = context.getSharedPrefs()
    private val credentials get() = context.getSharedPreferences(CredentialStore.FILE, Context.MODE_PRIVATE)
    private val touched = mutableSetOf<String>()
    private lateinit var profilesBefore: Array<DataStoreHelper.Account>

    private fun token(provider: String, index: Int) = "${AccountManager.ACCOUNT_TOKEN}/$provider/$index"
    private fun id(provider: String, index: Int) = "${AccountManager.ACCOUNT_IDS}/$provider/$index"

    /** As the main store writes a value: a JSON string. */
    private fun legacy(key: String, marker: String) {
        main.edit().putString(key, "\"$marker\"").commit(); touched += key
    }

    @Before
    fun save() = instrumentation.runOnMainSync { profilesBefore = DataStoreHelper.accounts }

    @After
    fun restore() = instrumentation.runOnMainSync {
        main.edit().apply { touched.forEach { remove(it) } }.commit()
        credentials.edit().apply { touched.forEach { remove(it) } }.commit()
        DataStoreHelper.accounts = profilesBefore
    }

    @Test
    fun migrationMovesEverySignInAndRemovesTheOldKeys() {
        legacy(token("simkl", INDEX), "NOT-A-REAL-TOKEN-1")
        legacy(token("mal", INDEX), "NOT-A-REAL-TOKEN-2")
        legacy(id("simkl", INDEX), "NOT-A-REAL-TOKEN-3")
        legacy(NEIGHBOUR, "NOT-A-REAL-TOKEN-4")

        CredentialStore.migrate(context)

        for ((key, marker) in listOf(token("simkl", INDEX) to 1, token("mal", INDEX) to 2, id("simkl", INDEX) to 3)) {
            assertEquals("Lost in the move: $key", "\"NOT-A-REAL-TOKEN-$marker\"", credentials.getString(key, null))
            assertFalse("Left in the backed-up store: $key", main.contains(key))
        }
        assertTrue("A key that is not a sign-in moved", main.contains(NEIGHBOUR))
        assertFalse(credentials.contains(NEIGHBOUR))
    }

    @Test
    fun aValueAlreadyInTheNewFileWinsOverAStaleOne() {
        val key = token("simkl", INDEX)
        credentials.edit().putString(key, "\"NOT-A-REAL-TOKEN-NEW\"").commit(); touched += key
        legacy(key, "NOT-A-REAL-TOKEN-OLD")

        CredentialStore.migrate(context)

        assertEquals("\"NOT-A-REAL-TOKEN-NEW\"", credentials.getString(key, null))
        assertFalse(main.contains(key))
    }

    @Test
    fun signInsAreWrittenToTheNewFileOnly() {
        val profile = DataStoreHelper.selectedKeyIndex
        val key = token(PREFIX, profile)
        touched += key; touched += id(PREFIX, profile)

        AccountManager.updateAccounts(PREFIX, emptyArray())
        AccountManager.updateAccountsId(PREFIX, 4950)

        assertTrue(credentials.contains(key))
        assertTrue(credentials.contains(id(PREFIX, profile)))
        assertFalse("A sign-in reached the backed-up store", main.contains(key))
    }

    @Test
    fun theExportHoldsNoPinAndNoSignIn() {
        instrumentation.runOnMainSync {
            DataStoreHelper.accounts = arrayOf(DataStoreHelper.Account(INDEX, "Locked", defaultImageIndex = 0, lockPin = PIN))
        }
        CredentialStore.write(token("simkl", INDEX), "NOT-A-REAL-TOKEN-5"); touched += token("simkl", INDEX)
        legacy(token("mal", INDEX), "NOT-A-REAL-TOKEN-6") // not yet migrated: still never exported

        val backup = BackupUtils.exportForTest(context)
        val json = backup.toJson()

        assertFalse("A sign-in is in the export", json.contains("NOT-A-REAL-TOKEN"))
        val maps = listOf(backup.datastore.bool, backup.datastore.int, backup.datastore.string, backup.datastore.float,
            backup.datastore.long, backup.datastore.stringSet)
        assertFalse(maps.any { m -> m.orEmpty().keys.any { it.startsWith("auth_") } })
        val profiles = backup.datastore.string?.get(PROFILES_KEY)
        assertNotNull("The profiles were dropped from the export", profiles)
        val exported = parseJson<Array<DataStoreHelper.Account>>(profiles!!)
        assertEquals("Locked", exported.single { it.keyIndex == INDEX }.name)
        assertTrue("A PIN is in the export", exported.all { it.lockPin == null })
    }

    @Test
    fun restoringAnOldExportWithAPinBringsTheProfileBackUnlocked() {
        val old = arrayOf(DataStoreHelper.Account(INDEX, "Locked", defaultImageIndex = 0, lockPin = PIN)).toJson()
        val file = BackupUtils.BackupFile(
            datastore = BackupUtils.BackupVars(null, null, mapOf(PROFILES_KEY to old), null, null, null),
            settings = BackupUtils.BackupVars(null, null, null, null, null, null),
        )

        BackupUtils.restore(context, file, restoreSettings = false, restoreDataStore = true)

        val restored = context.getKey(PROFILES_KEY, Array<DataStoreHelper.Account>::class.java)
        assertNotNull(restored)
        assertEquals("Locked", restored!!.single { it.keyIndex == INDEX }.name)
        assertNull("A PIN came back from the export", restored.single { it.keyIndex == INDEX }.lockPin)
    }

    /** The rules name the file `CredentialStore` writes, in all three places Android reads them. */
    @Test
    fun androidBackupAndTransferExcludeTheCredentialFile() {
        val path = "${CredentialStore.FILE}.xml"
        assertTrue(excludes(R.xml.backup_descriptor).contains("full-backup-content" to path))
        val rules = excludes(R.xml.data_extraction_rules)
        assertTrue("Cloud backup carries the sign-ins", rules.contains("cloud-backup" to path))
        assertTrue("Device transfer carries the sign-ins", rules.contains("device-transfer" to path))
    }

    /** `BUG-55`: the wrong-PIN counts stay on this device too, so a wait never follows a profile elsewhere. */
    @Test
    fun androidBackupAndTransferExcludeThePinAttemptsFile() {
        val path = "${PinAttempts.FILE}.xml"
        assertTrue(excludes(R.xml.backup_descriptor).contains("full-backup-content" to path))
        val rules = excludes(R.xml.data_extraction_rules)
        assertTrue("Cloud backup carries the PIN counts", rules.contains("cloud-backup" to path))
        assertTrue("Device transfer carries the PIN counts", rules.contains("device-transfer" to path))
    }

    /** (parent tag, path) for every `<exclude domain="sharedpref">`. */
    private fun excludes(xml: Int): Set<Pair<String, String>> {
        val parser = context.resources.getXml(xml)
        val found = mutableSetOf<Pair<String, String>>()
        val parents = ArrayDeque<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "exclude" && parser.getAttributeValue(null, "domain") == "sharedpref") {
                        found += parents.last() to parser.getAttributeValue(null, "path")
                    }
                    parents.addLast(parser.name)
                }
                XmlPullParser.END_TAG -> parents.removeLast()
            }
        }
        return found
    }

    private companion object {
        const val INDEX = 4950
        const val PIN = "4950"
        const val PREFIX = "bug50fixture"
        const val NEIGHBOUR = "bug50/not_a_sign_in"
        const val PROFILES_KEY = "${DataStoreHelper.TAG}/account"
    }
}

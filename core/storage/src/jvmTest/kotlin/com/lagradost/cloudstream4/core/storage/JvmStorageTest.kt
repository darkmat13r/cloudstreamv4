package com.lagradost.cloudstream4.core.storage

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [JvmStorage] — the wiring, and the one guarantee it adds over the constructors: a file has one
 * store.
 */
class JvmStorageTest {

    private val directory = TemporaryStorageDirectory()

    @AfterTest
    fun deleteDirectory() = directory.delete()

    @Test
    fun `the same directory hands back the same store`() {
        // Not a nicety: two instances over one file drop each other's writes, so this is the
        // guarantee Context.getSharedPreferences gives on Android and nothing gives here.
        assertSame(JvmStorage.settings(directory.path), JvmStorage.settings(directory.path))
        assertSame(JvmStorage.jsonRecords(directory.path), JvmStorage.jsonRecords(directory.path))
    }

    @Test
    fun `a path spelled differently is still the same store`() {
        val roundabout = directory.path.resolve("elsewhere").resolve("..")

        assertSame(JvmStorage.jsonRecords(directory.path), JvmStorage.jsonRecords(roundabout))
    }

    @Test
    fun `another directory is another store`() {
        val other = TemporaryStorageDirectory()
        try {
            assertNotEquals(JvmStorage.jsonRecords(directory.path), JvmStorage.jsonRecords(other.path))
        } finally {
            other.delete()
        }
    }

    @Test
    fun `settings and records are two files, and neither is written until it is used`() {
        val settings = JvmStorage.settings(directory.path)
        val records = JvmStorage.jsonRecords(directory.path)

        assertEquals(emptyList(), directory.path.toFile().list()!!.sorted())

        settings.getBoolean("subtitles", false).set(true)
        records.set("data_store_helper/account", "[]")

        assertEquals(
            listOf(JvmStorage.JSON_RECORDS_FILE, JvmStorage.SETTINGS_FILE).sorted(),
            directory.path.toFile().list()!!.sorted(),
        )
        // Separate files: a settings key is not a record key.
        assertEquals(emptySet(), records.keys() intersect setOf("subtitles"))
    }

    @Test
    fun `the record file is named after the file app already uses`() {
        assertTrue(JvmStorage.JSON_RECORDS_FILE.startsWith(StorageFiles.JSON_RECORDS))
    }

    @Test
    fun `secrets go into the record store, under the prefix, in plain text`() {
        val secrets = JvmStorage.secrets(prefix = "auth_tokens", directory = directory.path)
        val records = JvmStorage.jsonRecords(directory.path)

        secrets.set("MAL/0", "[{\"accessToken\":\"abc\"}]")

        assertEquals(SecretProtection.PLAINTEXT, secrets.protection)
        // The same flat key AccountManager.updateAccounts writes on Android.
        assertEquals(setOf("auth_tokens/MAL/0"), records.keys())
        // And "plaintext" is not a figure of speech: the token is in the file, readable.
        val onDisk = Files.readString(directory.path.resolve(JvmStorage.JSON_RECORDS_FILE))
        assertTrue(onDisk.contains("abc"), "the KDoc says plaintext; this is what that means")
    }

    @Test
    fun `a secret store shares the record store, so it sees what the app wrote`() {
        val records = JvmStorage.jsonRecords(directory.path)
        records.set("auth_tokens/AniList/0", "[{\"accessToken\":\"existing\"}]")

        val secrets = JvmStorage.secrets(prefix = "auth_tokens", directory = directory.path)

        assertEquals("[{\"accessToken\":\"existing\"}]", secrets.get("AniList/0"))
    }
}

/**
 * [JvmStorageDirectory] — where the stores land on each desktop platform.
 *
 * Every input is a parameter, so all three platforms are testable from whichever one is running
 * this. That is the reason [JvmStorageDirectory.resolve] has an internal overload at all.
 */
class JvmStorageDirectoryTest {

    private fun resolve(
        osName: String,
        override: String? = null,
        appData: String? = null,
        xdgDataHome: String? = null,
        userHome: String = "/home/user",
    ): String = JvmStorageDirectory.resolve(osName, override, appData, xdgDataHome, userHome).toString()

    @Test
    fun `macOS uses Application Support`() {
        assertEquals(
            "/home/user/Library/Application Support/CloudStream",
            resolve("Mac OS X"),
        )
    }

    @Test
    fun `windows uses APPDATA, and the profile when it is not set`() {
        assertEquals(
            Paths.get("C:\\Users\\me\\AppData\\Roaming", "CloudStream").toString(),
            resolve("Windows 11", appData = "C:\\Users\\me\\AppData\\Roaming"),
        )
        assertEquals(
            "/home/user/AppData/Roaming/CloudStream",
            resolve("Windows 11", appData = null),
        )
    }

    @Test
    fun `everything else follows XDG, lower case`() {
        assertEquals("/home/user/.local/share/cloudstream", resolve("Linux"))
        assertEquals("/data/xdg/cloudstream", resolve("Linux", xdgDataHome = "/data/xdg"))
        assertEquals("/home/user/.local/share/cloudstream", resolve("FreeBSD"))
    }

    @Test
    fun `a blank environment variable means unset, not the current directory`() {
        assertEquals("/home/user/.local/share/cloudstream", resolve("Linux", xdgDataHome = "  "))
        assertEquals("/home/user/AppData/Roaming/CloudStream", resolve("Windows 10", appData = ""))
    }

    @Test
    fun `the system property overrides every platform`() {
        // What a portable install on a stick, and a second profile, both need.
        assertEquals("/media/stick/cs", resolve("Mac OS X", override = "/media/stick/cs"))
        assertEquals("/media/stick/cs", resolve("Windows 11", override = "/media/stick/cs", appData = "C:\\x"))
        assertEquals("/media/stick/cs", resolve("Linux", override = "/media/stick/cs", xdgDataHome = "/data"))
        // The override is the directory itself: no application name is appended to it.
        assertEquals(
            "/media/stick/cs/${JvmStorage.SETTINGS_FILE}",
            JvmStorageDirectory.resolve("Linux", "/media/stick/cs", null, null, "/home/user")
                .resolve(JvmStorage.SETTINGS_FILE)
                .toString(),
        )
    }

    @Test
    fun `the property name is the one the KDoc documents`() {
        assertEquals("cloudstream.storage.dir", JvmStorageDirectory.OVERRIDE_PROPERTY)
        // Matches :desktopApp's nativeDistributions.packageName, so an installed build and a build
        // from source read the same directory.
        assertEquals("CloudStream", JvmStorageDirectory.APPLICATION_NAME)
    }
}

package com.lagradost.cloudstream4.core.storage

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the file backing adds on top of the port contracts: the value actually survives the process,
 * a crash cannot truncate it, a damaged file does not take the app down with it, and the file is
 * not readable by the rest of the machine.
 *
 * The contract itself — keys, folders, defaults, encodings — is not restated here; it is in
 * `FileStoreContractTest`, which re-runs the in-memory suites against these same classes.
 */
class FileStorageTest {

    private val directory = TemporaryStorageDirectory()

    @AfterTest
    fun deleteDirectory() = directory.delete()

    // --- persistence: the reason this module needed a JVM implementation at all ---

    @Test
    fun `records survive a new store instance over the same file`() {
        val file = directory.file()
        FileKeyValueJsonStore(file).apply {
            set("data_store_helper/account", "[{\"keyIndex\":0,\"name\":\"Default\"}]")
            set("result_resume_watching/1", "{\"position\":1234}")
        }

        // A new instance, nothing shared with the first: this reads the file or it reads nothing.
        val reopened = FileKeyValueJsonStore(file)

        assertEquals("[{\"keyIndex\":0,\"name\":\"Default\"}]", reopened.get("data_store_helper/account"))
        assertEquals("{\"position\":1234}", reopened.get("result_resume_watching/1"))
        assertEquals(setOf("data_store_helper/account", "result_resume_watching/1"), reopened.keys())
    }

    @Test
    fun `every settings type survives a new store instance, as its own type`() {
        val file = directory.file()
        FilePreferenceStore(file).apply {
            getString("theme", "").set("oled")
            getLong("last_sync", 0).set(Long.MAX_VALUE)
            getInt("quality", 0).set(-5)
            getFloat("speed", 0f).set(1.25f)
            getBoolean("subtitles", false).set(true)
            getStringSet("providers", emptySet()).set(setOf("a", "b"))
        }

        val reopened = FilePreferenceStore(file)

        assertEquals("oled", reopened.getString("theme", "").get())
        assertEquals(Long.MAX_VALUE, reopened.getLong("last_sync", 0).get())
        assertEquals(-5, reopened.getInt("quality", 0).get())
        assertEquals(1.25f, reopened.getFloat("speed", 0f).get(), absoluteTolerance = 0f)
        assertTrue(reopened.getBoolean("subtitles", false).get())
        assertEquals(setOf("a", "b"), reopened.getStringSet("providers", emptySet()).get())
        // The types come back as themselves, not as the strings they were stored as.
        assertEquals(
            mapOf<String, Any>(
                "theme" to "oled",
                "last_sync" to Long.MAX_VALUE,
                "quality" to -5,
                "speed" to 1.25f,
                "subtitles" to true,
                "providers" to setOf("a", "b"),
            ),
            reopened.getAll(),
        )
    }

    @Test
    fun `deleting a key persists as an absence`() {
        val file = directory.file()
        FileKeyValueJsonStore(file).apply {
            set("a", "1")
            set("b", "2")
            remove("a")
        }

        val reopened = FileKeyValueJsonStore(file)

        assertFalse(reopened.contains("a"))
        assertEquals(setOf("b"), reopened.keys())
    }

    @Test
    fun `folders survive a reload`() {
        val file = directory.file()
        FileKeyValueJsonStore(file).edit {
            set("auth_tokens/MAL/0", "[]")
            set("auth_tokens/AniList/0", "[]")
            set("auth_ids/MAL/0", "1")
        }

        val reopened = FileKeyValueJsonStore(file)
        assertEquals(listOf("auth_tokens/AniList/0", "auth_tokens/MAL/0"), reopened.keysIn("auth_tokens").sorted())
        assertEquals(2, reopened.removeIn("auth_tokens"))

        assertEquals(setOf("auth_ids/MAL/0"), FileKeyValueJsonStore(file).keys())
    }

    // --- the size limit that ruled out java.util.prefs.Preferences ---

    @Test
    fun `a record far past the java prefs value limit round-trips`() {
        // Preferences.MAX_VALUE_LENGTH is 8192 and `put` throws past it. CloudStream's account
        // array and its cached sync lists are routinely larger, which is the whole reason this is
        // a file and not a Preferences node.
        val huge = buildString {
            append("[")
            repeat(2000) { index ->
                if (index > 0) append(",")
                append("{\"id\":$index,\"name\":\"An episode title with some length to it\"}")
            }
            append("]")
        }
        assertTrue(huge.length > 100_000, "the fixture must dwarf the 8192 limit, was ${huge.length}")

        val file = directory.file()
        FileKeyValueJsonStore(file).set("sync_cache/mal", huge)

        assertEquals(huge, FileKeyValueJsonStore(file).get("sync_cache/mal"))
    }

    // --- verbatim, through an escaping layer and a filesystem ---

    @Test
    fun `values with newlines, quotes, delimiters and astral characters round-trip exactly`() {
        val awkward = mapOf(
            "newlines" to "line one\nline two\r\nline three",
            "quotes" to "\"quoted\" and 'single' and `back`",
            // The format's own reserved characters. A value full of them is the case that breaks a
            // naive `key=value` file.
            "delimiters" to "a=b,c\\d\\=e\\,f",
            "unicode" to "日本語 — Ærøskøbing — Ω",
            "astral" to "🎬📺",
            "control" to "tab\there, vertical\u000Bthere",
            "empty" to "",
        )

        val file = directory.file()
        FileKeyValueJsonStore(file).edit { awkward.forEach { (key, value) -> set(key, value) } }

        val reopened = FileKeyValueJsonStore(file)
        awkward.forEach { (key, value) ->
            assertEquals(value, reopened.get(key), "'$key' did not survive the round trip")
        }
    }

    @Test
    fun `keys containing the format's delimiters round-trip too`() {
        val file = directory.file()
        val key = "folder/a=b,c\\d\nnewline"
        FileKeyValueJsonStore(file).set(key, "{}")

        val reopened = FileKeyValueJsonStore(file)
        assertEquals(setOf(key), reopened.keys())
        assertEquals("{}", reopened.get(key))
    }

    @Test
    fun `settings values with delimiters and set members with commas round-trip`() {
        val file = directory.file()
        FilePreferenceStore(file).apply {
            getString("weird", "").set("a=b,c\\d\ne")
            getStringSet("members", emptySet()).set(setOf("plain", "with,comma", "with\\backslash", ""))
        }

        val reopened = FilePreferenceStore(file)
        assertEquals("a=b,c\\d\ne", reopened.getString("weird", "").get())
        assertEquals(
            setOf("plain", "with,comma", "with\\backslash", ""),
            reopened.getStringSet("members", emptySet()).get(),
        )
    }

    @Test
    fun `an empty string set and a set holding one empty string are not the same stored value`() {
        val file = directory.file()
        FilePreferenceStore(file).apply {
            getStringSet("none", setOf("default")).set(emptySet())
            getStringSet("oneEmpty", setOf("default")).set(setOf(""))
        }

        val reopened = FilePreferenceStore(file)
        assertEquals(emptySet(), reopened.getStringSet("none", setOf("default")).get())
        assertEquals(setOf(""), reopened.getStringSet("oneEmpty", setOf("default")).get())
    }

    // --- atomicity ---

    @Test
    fun `a failure after the temp file is written leaves the previous content intact`() {
        val file = directory.file()
        FileKeyValueJsonStore(file).set("account", "[{\"keyIndex\":0}]")
        val before = Files.readAllBytes(file)

        // The seam is the one instant where a naive writer has already destroyed the old file: the
        // new content is on disk and the old file has not been replaced yet.
        val failing = FileKeyValueJsonStore(
            TextRecordFile(file, StorageCorruptionListener.STDERR) { throw IOException("simulated crash") },
        )

        assertFailsWith<IOException> { failing.set("account", "[{\"keyIndex\":1}]") }

        assertContentEquals(before, Files.readAllBytes(file), "the previous file was modified")
        assertEquals("[{\"keyIndex\":0}]", FileKeyValueJsonStore(file).get("account"))
        // In-memory state did not run ahead of the disk either.
        assertEquals("[{\"keyIndex\":0}]", failing.get("account"))
        assertEquals(emptyList(), temporaryFiles(), "a temp file was left behind")
    }

    @Test
    fun `a successful write leaves no temp file behind`() {
        val file = directory.file()
        val store = FileKeyValueJsonStore(file)
        repeat(5) { store.set("key$it", "$it") }

        assertEquals(listOf(file.fileName.toString()), directory.path.toFile().list()!!.sorted())
        assertEquals(emptyList(), temporaryFiles())
    }

    @Test
    fun `a temp file left behind by a crashed process is ignored`() {
        val file = directory.file()
        FileKeyValueJsonStore(file).set("a", "1")
        // What a process killed between the temp write and the move leaves on disk.
        Files.write(
            directory.path.resolve("${file.fileName}1234${TextRecordFile.TEMP_SUFFIX}"),
            "#cloudstream-storage 1\na=999\n".toByteArray(StandardCharsets.UTF_8),
        )

        val reopened = FileKeyValueJsonStore(file)

        assertEquals("1", reopened.get("a"), "a stray temp file was read as the store")
    }

    @Test
    fun `a batch is one write, however many keys it touches`() {
        // The write seam doubles as a counter: it is invoked once per file write.
        var writes = 0
        val store = FileKeyValueJsonStore(
            TextRecordFile(directory.file(), StorageCorruptionListener.STDERR) { writes++ },
        )

        store.edit {
            repeat(50) { set("auth_tokens/MAL/$it", "[]") }
        }
        assertEquals(1, writes, "the batch should be one write")

        assertEquals(50, store.removeIn("auth_tokens"))
        assertEquals(2, writes, "removeIn should be one more write")
    }

    @Test
    fun `a write that changes nothing writes nothing`() {
        var writes = 0
        val store = FileKeyValueJsonStore(
            TextRecordFile(directory.file(), StorageCorruptionListener.STDERR) { writes++ },
        )
        store.set("a", "1")

        store.set("a", "1")
        store.remove("absent")
        store.edit { }

        assertEquals(1, writes)
    }

    // --- corruption ---

    @Test
    fun `a corrupt file reads as empty, is kept aside and is reported`() {
        val file = directory.file()
        val damaged = "this is not a store file\nnot even slightly\n".toByteArray(StandardCharsets.UTF_8)
        Files.write(file, damaged)
        val reports = mutableListOf<Triple<Path, Path?, Throwable>>()

        val store = FileKeyValueJsonStore(file) { corrupt, quarantined, cause ->
            reports += Triple(corrupt, quarantined, cause)
        }

        assertEquals(emptySet(), store.keys(), "a corrupt file must not be read as data")
        assertEquals(1, reports.size, "the corruption was not reported")
        val (reported, quarantined, _) = reports.single()
        assertEquals(file, reported)
        assertNotNull(quarantined, "the damaged file was not kept aside")
        assertContentEquals(damaged, Files.readAllBytes(quarantined), "the damaged file was altered")
        assertFalse(Files.exists(file), "the damaged file was left in the store's place")

        // And the store is usable: the next write starts a clean file.
        store.set("a", "1")
        assertEquals("1", FileKeyValueJsonStore(file).get("a"))
    }

    @Test
    fun `bytes that are not utf-8 are corruption, not data`() {
        val file = directory.file()
        // 0xC3 starts a two-byte sequence; 0x28 cannot continue one.
        Files.write(file, byteArrayOf(0xC3.toByte(), 0x28, 0x00, 0xFF.toByte()))
        var quarantined: Path? = null

        val store = FileKeyValueJsonStore(file) { _, aside, _ -> quarantined = aside }

        assertEquals(emptySet(), store.keys())
        assertNotNull(quarantined)
    }

    @Test
    fun `a zero-length file is corruption, not an empty store`() {
        // The writer never produces one: an empty file is what a truncating writer leaves behind,
        // and treating it as "no records" would silently accept exactly the loss this design
        // exists to prevent.
        val file = directory.file()
        Files.createFile(file)
        var reported = false

        FileKeyValueJsonStore(file) { _, _, _ -> reported = true }.keys()

        assertTrue(reported)
    }

    @Test
    fun `a truncated line is corruption`() {
        val file = directory.file()
        Files.write(
            file,
            "#cloudstream-storage 1\na=1\nb_with_no_separator\n".toByteArray(StandardCharsets.UTF_8),
        )
        var reported = false

        val store = FileKeyValueJsonStore(file) { _, _, _ -> reported = true }

        assertTrue(reported)
        // Not "the records up to the bad line": a partially read file is a quietly wrong app.
        assertEquals(emptySet(), store.keys())
    }

    @Test
    fun `a corrupt settings file recovers to the defaults`() {
        val file = directory.file()
        Files.write(file, "nonsense".toByteArray(StandardCharsets.UTF_8))

        val store = FilePreferenceStore(file) { _, _, _ -> }

        assertEquals("dark", store.getString("theme", "dark").get())
        assertFalse(store.getString("theme", "dark").isSet())
        assertEquals(emptyMap<String, Any>(), store.getAll())
    }

    // --- permissions ---

    @Test
    fun `the file and its directory are owner-only where the filesystem has posix permissions`() {
        val nested = directory.path.resolve("nested")
        val file = nested.resolve("secrets.records")
        FileKeyValueJsonStore(file).set("auth_tokens/MAL/0", "[{\"accessToken\":\"secret\"}]")

        if (Files.getFileAttributeView(file, PosixFileAttributeView::class.java) == null) {
            // Windows, exFAT, SMB: documented on JvmStorage.secrets as the case where the file
            // inherits the directory's ACL instead. Nothing to assert.
            return
        }
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(nested)))
    }

    // --- change notification ---

    // UnconfinedTestDispatcher: the collector must be running before the write, or the test would
    // pass on the initial emission alone and prove nothing.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a collector sees a later write`() = runTest {
        val store = FilePreferenceStore(directory.file())
        val entry = store.getString("theme", "dark")

        // The current value first, before anything happens — the contract PreferenceData states.
        assertEquals("dark", entry.changes().first())

        val later = async(UnconfinedTestDispatcher(testScheduler)) {
            entry.changes().first { it != "dark" }
        }
        entry.set("light")

        assertEquals("light", later.await())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a delete notifies as well, and the value read back is the default`() = runTest {
        val store = FilePreferenceStore(directory.file())
        val entry = store.getString("theme", "dark")
        entry.set("light")

        val later = async(UnconfinedTestDispatcher(testScheduler)) {
            entry.changes().first { it != "light" }
        }
        entry.delete()

        assertEquals("dark", later.await())
        assertFalse(entry.isSet())
    }

    // --- the store's own file ---

    @Test
    fun `nothing is written until something is stored`() {
        val file = directory.file()
        val store = FileKeyValueJsonStore(file)

        assertNull(store.get("anything"))
        assertFalse(Files.exists(file), "reading an absent store created a file")
    }

    @Test
    fun `the directory is created on the first write`() {
        val file = directory.path.resolve("one").resolve("two").resolve("store.records")

        FileKeyValueJsonStore(file).set("a", "1")

        assertTrue(Files.isRegularFile(file))
    }

    /** Whatever `.tmp` files are lying around, which after a complete write is none. */
    private fun temporaryFiles(): List<String> =
        directory.path.toFile().list()!!.filter { it.endsWith(TextRecordFile.TEMP_SUFFIX) }.sorted()
}

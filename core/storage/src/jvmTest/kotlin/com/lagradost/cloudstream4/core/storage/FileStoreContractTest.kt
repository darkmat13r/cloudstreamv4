package com.lagradost.cloudstream4.core.storage

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest

/**
 * The JVM file-backed stores, held to the **same contract** as the in-memory ones.
 *
 * Each class here is the corresponding contract suite with one method overridden, so every case in
 * `KeyValueJsonStoreTest`, `PreferenceStoreTest`, `SecretStoreTest` and `JsonRecordEncodingTest`
 * runs a second time against a real file in a real temporary directory. Nothing is restated: if a
 * case is added to a contract suite it lands here too, and if the file-backed store ever diverges
 * from the in-memory one the difference is a failure rather than a gap.
 *
 * What the file backing adds on top of the contract — persistence across instances, atomicity,
 * corruption recovery, permissions, the size limit that motivated the design — is in
 * [FileStorageTest], because none of it has an in-memory counterpart to share.
 */
class FileKeyValueJsonStoreTest : KeyValueJsonStoreTest() {

    private val directory = TemporaryStorageDirectory()

    @AfterTest
    fun deleteDirectory() = directory.delete()

    override fun newStore(initial: Map<String, String>): KeyValueJsonStore =
        FileKeyValueJsonStore(directory.file()).apply {
            // One batch, so a seeded store is one write — and so an empty seed writes no file at
            // all, which is how the "absent key" cases see a store whose file does not exist yet.
            edit { initial.forEach { (key, json) -> set(key, json) } }
        }
}

/** [FilePreferenceStore] against the full [PreferenceStoreTest] contract. */
class FilePreferenceStoreTest : PreferenceStoreTest() {

    private val directory = TemporaryStorageDirectory()

    @AfterTest
    fun deleteDirectory() = directory.delete()

    override fun newStore(initial: Map<String, Any>): PreferenceStore =
        FilePreferenceStore(directory.file()).also { store ->
            initial.forEach { (key, value) -> store.seed(key, value) }
        }

    /**
     * Writes a raw seed value through the typed accessor for its type.
     *
     * The in-memory store takes the map as-is; a file has no "as-is" — a value has to be written as
     * *some* type — so the seed's runtime type chooses the accessor. That is what makes the
     * wrong-type case meaningful here: `mapOf("n" to "not an int")` really is stored as a string,
     * and `getInt` really does have to fall back.
     */
    private fun PreferenceStore.seed(key: String, value: Any) {
        when (value) {
            is String -> getString(key, "").set(value)
            is Long -> getLong(key, 0).set(value)
            is Int -> getInt(key, 0).set(value)
            is Float -> getFloat(key, 0f).set(value)
            is Boolean -> getBoolean(key, false).set(value)
            is Set<*> -> getStringSet(key, emptySet()).set(value.map { it as String }.toSet())
            else -> error("no accessor seeds a ${value::class.simpleName}")
        }
    }
}

/**
 * [KeyValueJsonSecretStore] over a file — the JVM secret store, which needed no implementation of
 * its own.
 *
 * That is the point of this class: the secret store is `commonMain` code over a
 * [KeyValueJsonStore], so pointing it at [FileKeyValueJsonStore] is the whole JVM implementation,
 * and this runs the existing contract over it to confirm it.
 */
class FileSecretStoreTest : SecretStoreTest() {

    private val directory = TemporaryStorageDirectory()

    @AfterTest
    fun deleteDirectory() = directory.delete()

    /** Only the file-backed one: the in-memory store is already covered by the superclass. */
    override fun stores(): List<SecretStore> =
        listOf(KeyValueJsonSecretStore(newRecords(), prefix = "auth_tokens"))

    override fun newRecords(initial: Map<String, String>): KeyValueJsonStore =
        FileKeyValueJsonStore(directory.file()).apply {
            edit { initial.forEach { (key, json) -> set(key, json) } }
        }
}

/**
 * The JSON-record fixtures against the file store.
 *
 * "Verbatim" costs something here that it does not cost in a map: every value is escaped on the way
 * to disk and unescaped on the way back, so the escaped string, the quoted string, the one with a
 * `\u` escape and the CJK one each exercise the format.
 */
class FileJsonRecordEncodingTest : JsonRecordEncodingTest() {

    private val directory = TemporaryStorageDirectory()

    @AfterTest
    fun deleteDirectory() = directory.delete()

    override fun newStore(): KeyValueJsonStore = FileKeyValueJsonStore(directory.file())
}

/**
 * A temporary directory, and a fresh file path inside it per call.
 *
 * Per **call**, not per directory: a contract case may build two stores, and two file-backed stores
 * over one path would drop each other's writes — the hazard [JvmStorage] exists to prevent. Giving
 * each one its own file keeps the contract suites testing the contract rather than that.
 */
internal class TemporaryStorageDirectory {

    val path: Path = Files.createTempDirectory("cloudstream-storage-test")

    private var created = 0

    fun file(name: String = "store"): Path = path.resolve("$name-${created++}.records")

    fun delete() {
        path.toFile().deleteRecursively()
    }
}

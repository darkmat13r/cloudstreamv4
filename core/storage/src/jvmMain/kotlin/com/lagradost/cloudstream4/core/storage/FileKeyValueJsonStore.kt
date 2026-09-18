package com.lagradost.cloudstream4.core.storage

import java.nio.file.Path

/**
 * [KeyValueJsonStore] in a file, for the JVM (Compose for Desktop).
 *
 * The counterpart of `AndroidKeyValueJsonStore`: same keys, same values, same folder semantics,
 * a different place to put the bytes. Android has `SharedPreferences`; the JVM has
 * [TextRecordFile], because the JDK's own key/value store cannot hold a CloudStream record — see
 * [TextRecordFile] for why `java.util.prefs.Preferences` is not an option.
 *
 * ## Parity with Android, item by item
 *
 * | contract | Android | here |
 * |---|---|---|
 * | key strings | flat, `folder/path` | identical — the algebra is [folderKey], in `commonMain` |
 * | values | the caller's JSON string, verbatim | the caller's JSON string, verbatim |
 * | `keysIn` / `removeIn` | the interface defaults | the same interface defaults |
 * | missing key | `null` | `null` |
 * | unreadable value | `null` (caught `ClassCastException`) | `null` (nothing is unreadable here: every
 *   stored value is a string) |
 * | `edit` | one `apply()` | one file write |
 *
 * The one behavioural difference worth knowing is **where a wrong-typed value can come from**.
 * `SharedPreferences` can hold a `Boolean` under a record key, written by `Editor.setKeyRaw` in
 * `:app`; this file holds strings only, so [get] has nothing to fail on and [keys] lists exactly
 * the records.
 *
 * ## Durability and concurrency
 *
 * Both come from [TextRecordFile] and are documented there: writes are atomic (temp file, `fsync`,
 * `ATOMIC_MOVE`), a corrupt file recovers to empty and is kept aside, one instance is thread-safe,
 * and **two instances over one file lose each other's writes**. Get instances from [JvmStorage],
 * which keeps one per file, rather than constructing them per call site.
 *
 * @param file the store's own file. [JvmStorage.jsonRecords] resolves the usual one.
 * @param onCorruption told when the file could not be read; see [StorageCorruptionListener].
 */
class FileKeyValueJsonStore internal constructor(
    private val records: TextRecordFile,
) : KeyValueJsonStore {

    constructor(
        file: Path,
        onCorruption: StorageCorruptionListener = StorageCorruptionListener.STDERR,
    ) : this(TextRecordFile(file, onCorruption))

    /** Where the records are, for diagnostics and for tests. */
    val file: Path get() = records.path

    override fun get(key: String): String? = records.get(key)

    override fun set(key: String, json: String) {
        records.edit { it[key] = json }
    }

    override fun remove(key: String) {
        records.edit { it.remove(key) }
    }

    override fun contains(key: String): Boolean = records.contains(key)

    override fun keys(): Set<String> = records.keys()

    /** One file write for the whole batch, which is what [KeyValueJsonStore.removeIn] relies on. */
    override fun edit(block: KeyValueJsonEditor.() -> Unit) {
        records.edit { working ->
            object : KeyValueJsonEditor {
                override fun set(key: String, json: String) {
                    working[key] = json
                }

                override fun remove(key: String) {
                    working.remove(key)
                }
            }.block()
        }
    }
}

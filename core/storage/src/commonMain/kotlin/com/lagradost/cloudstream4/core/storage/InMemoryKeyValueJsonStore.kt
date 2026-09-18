package com.lagradost.cloudstream4.core.storage

/**
 * A [KeyValueJsonStore] in a map: the reference implementation of the contract, and what the
 * jvmTest suite runs against.
 *
 * It stores the JSON strings verbatim, exactly as the `SharedPreferences` implementation does, so
 * a test that pins the encoding here pins it for both. Not thread-safe, not persistent.
 */
class InMemoryKeyValueJsonStore(
    initialRecords: Map<String, String> = emptyMap(),
) : KeyValueJsonStore {

    private val records: MutableMap<String, String> = initialRecords.toMutableMap()

    override fun get(key: String): String? = records[key]

    override fun set(key: String, json: String) {
        records[key] = json
    }

    override fun remove(key: String) {
        records.remove(key)
    }

    override fun contains(key: String): Boolean = records.containsKey(key)

    override fun keys(): Set<String> = records.keys.toSet()

    override fun edit(block: KeyValueJsonEditor.() -> Unit) {
        // A map has nothing to batch; the interface's contract is "these land together", and for a
        // single-threaded map they do.
        object : KeyValueJsonEditor {
            override fun set(key: String, json: String) {
                records[key] = json
            }

            override fun remove(key: String) {
                records.remove(key)
            }
        }.block()
    }
}

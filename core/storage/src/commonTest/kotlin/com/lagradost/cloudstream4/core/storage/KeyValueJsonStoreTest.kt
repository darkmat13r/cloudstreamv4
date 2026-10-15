package com.lagradost.cloudstream4.core.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The behavioural contract of [KeyValueJsonStore], run against [InMemoryKeyValueJsonStore].
 *
 * [KeyValueJsonStore.keysIn] and [KeyValueJsonStore.removeIn] are interface defaults, so what is
 * pinned here is what every implementation inherits, the `SharedPreferences` one included.
 *
 * Every case goes through [newStore], so an implementation that can run on the JVM is held to this
 * same contract by subclassing rather than by a second copy of these assertions —
 * `FileKeyValueJsonStoreTest` does exactly that.
 *
 * ## Why this suite is in `commonTest`
 *
 * Nothing here touches the JVM — `kotlin.test` and this module's own `commonMain` types, and no
 * `java.*` import in the file or in anything it calls. So it belongs in `commonTest`, where
 * `iosSimulatorTest` (`INF-04`) runs it on an `iosSimulatorArm64` simulator as well as on the JVM.
 * The file-backed suites that subclass these cases stay in `jvmTest` and still inherit them:
 * `commonTest` is a parent source set of `jvmTest`, so `FileStoreContractTest` is unaffected.
 *
 * **A backtick name here may not contain a comma.** Kotlin/Native rejects it —
 * `Name contains illegal characters: ","` — and the JVM compiler accepts it silently, so the first
 * thing that breaks when a suite moves is its own name. Names that held one were reworded, not
 * re-scoped; see `docs/tasks/infrastructure.md` under `INF-04`.
 */
open class KeyValueJsonStoreTest {

    /**
     * The store under test, holding [initial] before the case starts.
     *
     * A subclass overrides this and inherits every case below. It must return a **fresh** store per
     * call: some cases build more than one.
     */
    protected open fun newStore(initial: Map<String, String> = emptyMap()): KeyValueJsonStore =
        InMemoryKeyValueJsonStore(initial)

    @Test
    fun `absent key reads as null`() {
        assertNull(newStore().get("nothing"))
    }

    @Test
    fun `set then get returns the value`() {
        val store = newStore()
        store.set("a", "{\"x\":1}")
        assertEquals("{\"x\":1}", store.get("a"))
    }

    @Test
    fun `contains distinguishes absent from stored`() {
        val store = newStore()
        assertFalse(store.contains("a"))
        store.set("a", "null")
        assertTrue(store.contains("a"))
        // `null` is a valid JSON record and is not the same as "no record".
        assertEquals("null", store.get("a"))
    }

    @Test
    fun `remove deletes the key`() {
        val store = newStore(mapOf("a" to "1"))
        store.remove("a")
        assertFalse(store.contains("a"))
        assertNull(store.get("a"))
    }

    @Test
    fun `removing an absent key is a no-op`() {
        val store = newStore(mapOf("a" to "1"))
        store.remove("b")
        assertEquals(setOf("a"), store.keys())
    }

    @Test
    fun `keys lists every record`() {
        val store = newStore(mapOf("a" to "1", "b/c" to "2"))
        assertEquals(setOf("a", "b/c"), store.keys())
    }

    @Test
    fun `edit applies every write in the batch`() {
        val store = newStore(mapOf("keep" to "1", "drop" to "2"))
        store.edit {
            set("added", "3")
            remove("drop")
        }
        assertEquals("3", store.get("added"))
        assertFalse(store.contains("drop"))
        assertEquals("1", store.get("keep"))
    }

    // --- folder semantics: byte-compatible with Context.getKeys / removeKeys in :app ---

    @Test
    fun `keysIn matches only keys one level under the folder prefix`() {
        val store = newStore(
            mapOf(
                "result/1" to "a",
                "result/2" to "b",
                // A sibling folder sharing a prefix must not match — this is the reason
                // Context.getKeys appends the slash instead of using startsWith(folder).
                "resultat/1" to "c",
                "result" to "d",
                "other/1" to "e",
            ),
        )
        assertEquals(listOf("result/1", "result/2"), store.keysIn("result").sorted())
    }

    @Test
    fun `keysIn normalises trailing slashes like DataStore does`() {
        val store = newStore(mapOf("a/1" to "x", "a/b/2" to "y", "ab/3" to "z"))
        val expected = listOf("a/1", "a/b/2")
        assertEquals(expected, store.keysIn("a").sorted())
        assertEquals(expected, store.keysIn("a/").sorted())
        assertEquals(expected, store.keysIn("a///").sorted())
    }

    @Test
    fun `keysIn is recursive as a flat prefix scan must be`() {
        // "auth_tokens/MAL/0" is under "auth_tokens" as well as under "auth_tokens/MAL":
        // there are no real folders, only key prefixes.
        val store = newStore(mapOf("auth_tokens/MAL/0" to "[]"))
        assertEquals(listOf("auth_tokens/MAL/0"), store.keysIn("auth_tokens"))
        assertEquals(listOf("auth_tokens/MAL/0"), store.keysIn("auth_tokens/MAL"))
    }

    @Test
    fun `removeIn removes the folder and returns the count`() {
        val store = newStore(
            mapOf("f/1" to "a", "f/2" to "b", "g/1" to "c", "f" to "d"),
        )
        assertEquals(2, store.removeIn("f"))
        assertEquals(setOf("g/1", "f"), store.keys())
    }

    @Test
    fun `removeIn commits once whatever the folder holds`() {
        // The Android store turns one `edit` into one `apply()`. If `removeIn` fell back to the
        // store's own `remove` it would commit per key, which is the cost `DataStore.Editor` in
        // `:app` exists to avoid ("apply for every key is very expensive on memory").
        val store = CountingKeyValueJsonStore(mapOf("f/1" to "a", "f/2" to "b", "f/3" to "c"))

        assertEquals(3, store.removeIn("f"))

        assertEquals(1, store.batches, "expected one batch")
        assertEquals(0, store.unbatchedRemovals, "expected no per-key commits")
        assertEquals(emptySet<String>(), store.keys())
    }

    /**
     * Records how a caller reached the store, so the batching contract can be asserted.
     *
     * Written out rather than delegated with `by`: interface delegation would forward `removeIn`
     * and `keysIn` to the delegate too, and the defaults under test would never run here.
     */
    private class CountingKeyValueJsonStore(initial: Map<String, String>) : KeyValueJsonStore {
        private val records = initial.toMutableMap()

        var batches = 0
            private set
        var unbatchedRemovals = 0
            private set

        override fun get(key: String): String? = records[key]

        override fun set(key: String, json: String) {
            records[key] = json
        }

        override fun remove(key: String) {
            unbatchedRemovals++
            records.remove(key)
        }

        override fun contains(key: String): Boolean = records.containsKey(key)

        override fun keys(): Set<String> = records.keys.toSet()

        override fun edit(block: KeyValueJsonEditor.() -> Unit) {
            batches++
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

    @Test
    fun `removeIn on an empty folder returns zero`() {
        val store = newStore(mapOf("g/1" to "c"))
        assertEquals(0, store.removeIn("f"))
        assertEquals(setOf("g/1"), store.keys())
    }

    // --- folder key algebra ---

    @Test
    fun `folderKey joins with a single slash matching DataStore getFolderName`() {
        assertEquals("auth_tokens/MAL/0", folderKey("auth_tokens", "MAL/0"))
        assertEquals("data_store_helper/account", folderKey("data_store_helper", "account"))
        // getFolderName is "${folder}/${path}" with no normalisation: a trailing slash on the
        // folder produces a double slash, and :app has always written it that way.
        assertEquals("a//b", folderKey("a/", "b"))
        assertEquals("/b", folderKey("", "b"))
    }

    @Test
    fun `the folder-scoped extensions address the same flat key`() {
        val store = newStore()
        store.set("auth_tokens", "MAL/0", "[]")
        assertEquals("[]", store.get("auth_tokens/MAL/0"))
        assertEquals("[]", store.get("auth_tokens", "MAL/0"))
        assertTrue(store.contains("auth_tokens", "MAL/0"))
        store.remove("auth_tokens", "MAL/0")
        assertFalse(store.contains("auth_tokens/MAL/0"))
    }
}

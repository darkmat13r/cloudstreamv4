package com.lagradost.cloudstream4.core.storage

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The behavioural contract of [PreferenceStore], run against [InMemoryPreferenceStore].
 *
 * The shape is the one `com.mihon.common.preference.PreferenceStore` in `:shared` already has, so
 * these are also the cases the Phase 5 consolidation has to keep passing.
 *
 * Every case goes through [newStore], so an implementation that can run on the JVM is held to this
 * same contract by subclassing rather than by a second copy of these assertions —
 * [FilePreferenceStoreTest] does exactly that.
 */
open class PreferenceStoreTest {

    /**
     * The store under test, holding [initial] before the case starts.
     *
     * The seed map is raw primitives, the same shape [PreferenceStore.getAll] returns, because two
     * cases below seed a value of the *wrong* type on purpose. A subclass that cannot seed a type
     * writes it through the typed API instead; it must return a **fresh** store per call.
     */
    protected open fun newStore(initial: Map<String, Any> = emptyMap()): PreferenceStore =
        InMemoryPreferenceStore(initial)

    @Test
    fun `an unset entry reads as its default and says so`() {
        val entry = newStore().getString("theme", "dark")
        assertEquals("dark", entry.get())
        assertEquals("dark", entry.defaultValue())
        assertFalse(entry.isSet())
        assertEquals("theme", entry.key())
    }

    @Test
    fun `a set value is visible to a later handle on the same key`() {
        val store = newStore()
        store.getString("theme", "dark").set("light")
        // A second handle, not the one that wrote: the store holds the value, not the entry.
        assertEquals("light", store.getString("theme", "dark").get())
        assertTrue(store.getString("theme", "dark").isSet())
    }

    @Test
    fun `setting a value equal to the default still counts as set`() {
        val store = newStore()
        val entry = store.getBoolean("enabled", false)
        entry.set(false)
        assertTrue(entry.isSet())
    }

    @Test
    fun `delete returns the entry to its default`() {
        val store = newStore()
        val entry = store.getInt("count", 7)
        entry.set(3)
        entry.delete()
        assertFalse(entry.isSet())
        assertEquals(7, entry.get())
    }

    @Test
    fun `every primitive type round-trips`() {
        val store = newStore()
        store.getString("s", "").set("x")
        store.getLong("l", 0).set(Long.MAX_VALUE)
        store.getInt("i", 0).set(-5)
        store.getFloat("f", 0f).set(1.5f)
        store.getBoolean("b", false).set(true)
        store.getStringSet("ss", emptySet()).set(setOf("a", "b"))

        assertEquals("x", store.getString("s", "").get())
        assertEquals(Long.MAX_VALUE, store.getLong("l", 0).get())
        assertEquals(-5, store.getInt("i", 0).get())
        assertEquals(1.5f, store.getFloat("f", 0f).get(), absoluteTolerance = 0f)
        assertEquals(true, store.getBoolean("b", false).get())
        assertEquals(setOf("a", "b"), store.getStringSet("ss", emptySet()).get())
    }

    @Test
    fun `a value stored under the wrong type reads as the default`() {
        // SharedPreferences throws ClassCastException here and both implementations fall back to
        // the default rather than propagating it; that is why a wrong-typed entry is survivable.
        val store = newStore(mapOf("n" to "not an int"))
        assertEquals(9, store.getInt("n", 9).get())
    }

    @Test
    fun `getAll exposes the raw stored values`() {
        val store = newStore()
        store.getString("s", "").set("x")
        store.getInt("i", 0).set(3)
        assertEquals(mapOf("s" to "x", "i" to 3), store.getAll())
    }

    // --- object codecs: the store holds the encoded form, the caller owns the codec ---

    @Test
    fun `getObjectFromString stores the serialized form`() {
        val store = newStore()
        val entry = store.getObjectFromString(
            key = "pair",
            defaultValue = 0 to 0,
            serializer = { "${it.first}x${it.second}" },
            deserializer = { it.split("x").let { p -> p[0].toInt() to p[1].toInt() } },
        )
        entry.set(16 to 9)
        assertEquals("16x9", store.getAll()["pair"])
        assertEquals(16 to 9, entry.get())
    }

    @Test
    fun `getObjectFromInt stores the serialized form`() {
        val store = newStore()
        val entry = store.getObjectFromInt(
            key = "level",
            defaultValue = Level.LOW,
            serializer = { it.ordinal },
            deserializer = { Level.entries[it] },
        )
        entry.set(Level.HIGH)
        assertEquals(1, store.getAll()["level"])
        assertEquals(Level.HIGH, entry.get())
    }

    @Test
    fun `getObjectSetFromStringSet drops members the deserializer rejects`() {
        val store = newStore(mapOf("levels" to setOf("LOW", "GONE", "HIGH")))
        val entry = store.getEnumSet("levels", defaultValue = emptySet<Level>())
        assertEquals(setOf(Level.LOW, Level.HIGH), entry.get())
    }

    @Test
    fun `getEnum falls back to the default for an unknown name`() {
        val store = newStore(mapOf("level" to "REMOVED_IN_A_LATER_VERSION"))
        assertEquals(Level.LOW, store.getEnum("level", Level.LOW).get())
    }

    @Test
    fun `getEnum round-trips by name, not by ordinal`() {
        val store = newStore()
        store.getEnum("level", Level.LOW).set(Level.HIGH)
        // By name: reordering the enum must not silently change what a stored value means.
        assertEquals("HIGH", store.getAll()["level"])
    }

    @Test
    fun `getLongArray round-trips through the comma form`() {
        val store = newStore()
        val entry = store.getLongArray("ids", emptyList())
        entry.set(listOf(1L, 2L, 3L))
        assertEquals("1,2,3", store.getAll()["ids"])
        assertEquals(listOf(1L, 2L, 3L), entry.get())
    }

    // --- helpers ---

    @Test
    fun `toggle flips and returns the new value`() {
        val entry = newStore().getBoolean("b", false)
        assertTrue(entry.toggle())
        assertFalse(entry.toggle())
    }

    @Test
    fun `set operators add and remove members`() {
        val entry = newStore().getStringSet("s", emptySet())
        entry += "a"
        entry += listOf("b", "c")
        entry -= "b"
        assertEquals(setOf("a", "c"), entry.get())
    }

    @Test
    fun `getAndSet derives the new value from the old`() {
        val entry = newStore().getInt("i", 1)
        entry.getAndSet { it + 41 }
        assertEquals(42, entry.get())
    }

    @Test
    fun `private and app-state prefixes are the ones already on disk`() {
        // Byte-compatible with :shared — these prefixes are part of existing key strings.
        assertEquals("__PRIVATE_token", PreferenceData.privateKey("token"))
        assertEquals("__APP_STATE_shown", PreferenceData.appStateKey("shown"))
        assertTrue(PreferenceData.isPrivate(PreferenceData.privateKey("token")))
        assertTrue(PreferenceData.isAppState(PreferenceData.appStateKey("shown")))
        assertFalse(PreferenceData.isPrivate("token"))
        assertFalse(PreferenceData.isAppState("__PRIVATE_token"))
    }

    // --- changes() ---

    @Test
    fun `changes emits the current value before anything happens`() = runTest {
        val entry = newStore().getString("theme", "dark")
        assertEquals("dark", entry.changes().first())
    }
}

/** Top-level so it can be a reified type argument of the `getEnum` / `getEnumSet` helpers. */
enum class Level { LOW, HIGH }

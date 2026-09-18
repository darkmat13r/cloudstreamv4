package com.lagradost.cloudstream4.core.storage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * A [PreferenceStore] in a map. It is the reference implementation of the contract — the jvmTest
 * suite is written against it — and it is what previews and desktop/iOS callers use until those
 * platforms have a real one.
 *
 * Unlike `:shared`'s `InMemoryPreferenceStore`, whose backing map is immutable so a `set` is
 * invisible to the next `get`, this one actually stores what you write and emits on [changes].
 *
 * Not thread-safe, and not persistent: nothing here survives the process.
 */
class InMemoryPreferenceStore(
    initialPreferences: Map<String, Any> = emptyMap(),
) : PreferenceStore {

    private val values: MutableMap<String, Any> = initialPreferences.toMutableMap()

    /** Emits the key that changed. `extraBufferCapacity` so a `set` never suspends. */
    private val keyChanges = MutableSharedFlow<String>(extraBufferCapacity = 64)

    override fun getString(key: String, defaultValue: String): PreferenceData<String> =
        primitive(key, defaultValue) { it as? String }

    override fun getLong(key: String, defaultValue: Long): PreferenceData<Long> =
        primitive(key, defaultValue) { it as? Long }

    override fun getInt(key: String, defaultValue: Int): PreferenceData<Int> =
        primitive(key, defaultValue) { it as? Int }

    override fun getFloat(key: String, defaultValue: Float): PreferenceData<Float> =
        primitive(key, defaultValue) { it as? Float }

    override fun getBoolean(key: String, defaultValue: Boolean): PreferenceData<Boolean> =
        primitive(key, defaultValue) { it as? Boolean }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defaultValue: Set<String>): PreferenceData<Set<String>> =
        primitive(key, defaultValue) { it as? Set<String> }

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): PreferenceData<T> = Entry(
        key = key,
        defaultValue = defaultValue,
        read = { stored -> (stored as? String)?.let(deserializer) },
        write = { value -> serializer(value) },
    )

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): PreferenceData<T> = Entry(
        key = key,
        defaultValue = defaultValue,
        read = { stored -> (stored as? Int)?.let(deserializer) },
        write = { value -> serializer(value) },
    )

    override fun <T> getObjectSetFromStringSet(
        key: String,
        defaultValue: Set<T>,
        serializer: (T) -> String,
        deserializer: (String) -> T?,
    ): PreferenceData<Set<T>> = Entry(
        key = key,
        defaultValue = defaultValue,
        read = { stored ->
            @Suppress("UNCHECKED_CAST")
            (stored as? Set<String>)?.mapNotNull(deserializer)?.toSet()
        },
        write = { value -> value.map(serializer).toSet() },
    )

    override fun getAll(): Map<String, *> = values.toMap()

    /**
     * The primitive case: stored as-is, read back with a type check. A value of the wrong type
     * reads as the default, which is what `SharedPreferences` effectively does once `:app` has
     * caught the `ClassCastException`.
     */
    private fun <T : Any> primitive(
        key: String,
        defaultValue: T,
        read: (Any) -> T?,
    ): PreferenceData<T> = Entry(key, defaultValue, read) { it }

    private inner class Entry<T>(
        private val key: String,
        private val defaultValue: T,
        private val read: (Any) -> T?,
        private val write: (T) -> Any,
    ) : PreferenceData<T> {

        override fun key(): String = key

        override fun get(): T = values[key]?.let(read) ?: defaultValue

        override fun set(value: T) {
            values[key] = write(value)
            keyChanges.tryEmit(key)
        }

        override fun isSet(): Boolean = values.containsKey(key)

        override fun delete() {
            values.remove(key)
            keyChanges.tryEmit(key)
        }

        override fun defaultValue(): T = defaultValue

        override fun changes(): Flow<T> = keyChanges
            .filter { it == key }
            .onStart { emit(key) }
            .map { get() }
            .conflate()

        override fun stateIn(scope: CoroutineScope): StateFlow<T> =
            changes().stateIn(scope, SharingStarted.Eagerly, get())
    }
}

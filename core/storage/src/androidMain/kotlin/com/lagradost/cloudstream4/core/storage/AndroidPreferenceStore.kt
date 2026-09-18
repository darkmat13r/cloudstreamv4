package com.lagradost.cloudstream4.core.storage

import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * [PreferenceStore] over a `SharedPreferences` file.
 *
 * ## It does not choose the file
 *
 * The constructor takes the `SharedPreferences` rather than a `Context`, so nothing here decides
 * what "the settings file" is. `:app` passes what `DataStore.getDefaultSharedPrefs()` already
 * returns — `androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)`, i.e. the
 * `"<packageName>_preferences"` file the XML preference screens and the 7 Compose settings screens
 * already use. Two reasons: this module then needs no `androidx.preference` dependency (a
 * dependency decision that would need the user's approval, and one this PR does not need to take);
 * and re-deriving that file name here would duplicate an `androidx` implementation detail that only
 * `androidx` gets to change.
 *
 * ## Byte compatibility
 *
 * Every accessor maps to the `SharedPreferences` primitive of the same name, with the same key
 * string and no encoding of its own, so it reads and writes what
 * `com.mihon.common.preference.AndroidPreferenceStore` in `:shared` reads and writes today — that
 * class is the model for this one.
 *
 * ## A note on `changes()`
 *
 * `OnSharedPreferenceChangeListener` is registered per collector, as in `:shared`. It fires on the
 * thread that committed the change and delivers `null` as the key when preferences are cleared,
 * which is why the filter accepts `null`.
 */
class AndroidPreferenceStore(
    private val preferences: SharedPreferences,
) : PreferenceStore {

    private val keyFlow: Flow<String?> = preferences.keyFlow

    override fun getString(key: String, defaultValue: String): PreferenceData<String> =
        Entry(
            key = key,
            defaultValue = defaultValue,
            read = { prefs, k, default -> prefs.getString(k, default) ?: default },
            write = { k, value -> { putString(k, value) } },
        )

    override fun getLong(key: String, defaultValue: Long): PreferenceData<Long> =
        Entry(
            key = key,
            defaultValue = defaultValue,
            read = { prefs, k, default -> prefs.getLong(k, default) },
            write = { k, value -> { putLong(k, value) } },
        )

    override fun getInt(key: String, defaultValue: Int): PreferenceData<Int> =
        Entry(
            key = key,
            defaultValue = defaultValue,
            read = { prefs, k, default -> prefs.getInt(k, default) },
            write = { k, value -> { putInt(k, value) } },
        )

    override fun getFloat(key: String, defaultValue: Float): PreferenceData<Float> =
        Entry(
            key = key,
            defaultValue = defaultValue,
            read = { prefs, k, default -> prefs.getFloat(k, default) },
            write = { k, value -> { putFloat(k, value) } },
        )

    override fun getBoolean(key: String, defaultValue: Boolean): PreferenceData<Boolean> =
        Entry(
            key = key,
            defaultValue = defaultValue,
            read = { prefs, k, default -> prefs.getBoolean(k, default) },
            write = { k, value -> { putBoolean(k, value) } },
        )

    override fun getStringSet(key: String, defaultValue: Set<String>): PreferenceData<Set<String>> =
        Entry(
            key = key,
            defaultValue = defaultValue,
            read = { prefs, k, default -> prefs.getStringSet(k, default) ?: default },
            write = { k, value -> { putStringSet(k, value) } },
        )

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): PreferenceData<T> = Entry(
        key = key,
        defaultValue = defaultValue,
        read = { prefs, k, default ->
            runCatching { prefs.getString(k, null)?.let(deserializer) }.getOrNull() ?: default
        },
        write = { k, value -> { putString(k, serializer(value)) } },
    )

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): PreferenceData<T> = Entry(
        key = key,
        defaultValue = defaultValue,
        read = { prefs, k, default ->
            runCatching {
                if (prefs.contains(k)) deserializer(prefs.getInt(k, 0)) else default
            }.getOrDefault(default)
        },
        write = { k, value -> { putInt(k, serializer(value)) } },
    )

    override fun <T> getObjectSetFromStringSet(
        key: String,
        defaultValue: Set<T>,
        serializer: (T) -> String,
        deserializer: (String) -> T?,
    ): PreferenceData<Set<T>> = Entry(
        key = key,
        defaultValue = defaultValue,
        read = { prefs, k, default ->
            runCatching {
                prefs.getStringSet(k, null)?.mapNotNull(deserializer)?.toSet()
            }.getOrNull() ?: default
        },
        write = { k, value -> { putStringSet(k, value.map(serializer).toSet()) } },
    )

    override fun getAll(): Map<String, *> = preferences.all ?: emptyMap<String, Any>()

    private inner class Entry<T>(
        private val key: String,
        private val defaultValue: T,
        private val read: (SharedPreferences, String, T) -> T,
        private val write: (String, T) -> SharedPreferences.Editor.() -> Unit,
    ) : PreferenceData<T> {

        override fun key(): String = key

        override fun get(): T = try {
            read(preferences, key, defaultValue)
        } catch (_: ClassCastException) {
            // Something wrote another type under this key. `:shared` deletes and falls back; keep
            // that behaviour so a corrupt entry self-heals instead of throwing on every read.
            delete()
            defaultValue
        }

        override fun set(value: T) {
            val editor = preferences.edit()
            write(key, value).invoke(editor)
            editor.apply()
        }

        override fun isSet(): Boolean = preferences.contains(key)

        override fun delete() {
            preferences.edit().remove(key).apply()
        }

        override fun defaultValue(): T = defaultValue

        override fun changes(): Flow<T> = keyFlow
            .filter { it == key || it == null }
            .onStart { emit("ignition") }
            .map { get() }
            .conflate()

        override fun stateIn(scope: CoroutineScope): StateFlow<T> =
            changes().stateIn(scope, SharingStarted.Eagerly, get())
    }
}

/**
 * Emits the key that changed, or `null` when the file was cleared. One registration per collector,
 * unregistered when collection stops.
 *
 * Deliberately not multicast: a shared flow would need a scope this class does not own, and the
 * listener is cheap.
 */
private val SharedPreferences.keyFlow: Flow<String?>
    get() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> trySend(key) }
        registerOnSharedPreferenceChangeListener(listener)
        awaitClose { unregisterOnSharedPreferenceChangeListener(listener) }
    }

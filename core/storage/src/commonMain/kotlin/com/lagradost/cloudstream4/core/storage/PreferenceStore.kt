package com.lagradost.cloudstream4.core.storage

/**
 * The **settings** store: typed, primitive-valued preferences, one entry per key.
 *
 * On Android this is the *default* `SharedPreferences` file — the one
 * `PreferenceManager.getDefaultSharedPreferences` returns and `DataStore.getDefaultSharedPrefs()`
 * in `:app` hands out — and it is the file the `androidx.preference` XML screens and the 7 Compose
 * settings screens already read and write. It is **not** `rebuild_preference`; that file holds JSON
 * records and has its own port, [KeyValueJsonStore].
 *
 * ## Why this is a second copy of an interface that already exists
 *
 * `com.mihon.common.preference.PreferenceStore` has exactly this shape, and the settings screens
 * are written against it. When this module was written that declaration was in `:shared`, which was
 * about to be split, so depending on it would have put this module's fate in that split's hands;
 * the shape was restated here instead, method for method and name for name.
 *
 * **The two are still not consolidated.** `:shared` is gone (`KMP-06d`): the Mihon declaration now
 * lives in `:core:mihon` and `AppSettings`, the one thing built on it, in `:core:settings`. Merging
 * them is an open decision rather than a pending plan step, because `:core:storage` taking the
 * `com.mihon.*` package would give it a vendored-Apache-2.0 root, and `AppSettings` adopting *this*
 * declaration retypes 62 `preference = settings.…` arguments whose receiving fields are declared in
 * vendored Mihon code. `docs/tasks/kmp.md` `KMP-06d` has the measurement.
 *
 * Either way, exactly one of the two declarations survives it: the `com.mihon` one becomes a
 * `typealias` to this one, or this one does. Keeping the signatures identical is what makes that a
 * rename rather than a rewrite — do not let them drift. Anything added here should be added there
 * too, or be additive enough to survive the merge.
 *
 * ## Encoding
 *
 * Values are stored in their primitive form, not as JSON. `getObjectFromString` /
 * `getObjectFromInt` / `getObjectSetFromStringSet` take the codec as parameters precisely so that
 * this module owns no encoding of its own: the caller decides, exactly as it does today.
 */
interface PreferenceStore {

    fun getString(key: String, defaultValue: String = ""): PreferenceData<String>

    fun getLong(key: String, defaultValue: Long = 0): PreferenceData<Long>

    fun getInt(key: String, defaultValue: Int = 0): PreferenceData<Int>

    fun getFloat(key: String, defaultValue: Float = 0f): PreferenceData<Float>

    fun getBoolean(key: String, defaultValue: Boolean = false): PreferenceData<Boolean>

    fun getStringSet(key: String, defaultValue: Set<String> = emptySet()): PreferenceData<Set<String>>

    fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): PreferenceData<T>

    fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): PreferenceData<T>

    fun <T> getObjectSetFromStringSet(
        key: String,
        defaultValue: Set<T>,
        serializer: (T) -> String,
        deserializer: (String) -> T?,
    ): PreferenceData<Set<T>>

    /**
     * Every stored key with its raw primitive value. Used by backup and by settings search;
     * the value types are whatever the platform stores (`String`, `Int`, `Set<String>`, …).
     */
    fun getAll(): Map<String, *>
}

fun PreferenceStore.getLongArray(
    key: String,
    defaultValue: List<Long>,
): PreferenceData<List<Long>> = getObjectFromString(
    key = key,
    defaultValue = defaultValue,
    serializer = { it.joinToString(",") },
    deserializer = { stored -> stored.split(",").mapNotNull { it.toLongOrNull() } },
)

inline fun <reified T : Enum<T>> PreferenceStore.getEnum(
    key: String,
    defaultValue: T,
): PreferenceData<T> = getObjectFromString(
    key = key,
    defaultValue = defaultValue,
    serializer = { it.name },
    deserializer = { stored -> runCatching { enumValueOf<T>(stored) }.getOrDefault(defaultValue) },
)

inline fun <reified T : Enum<T>> PreferenceStore.getEnumSet(
    key: String,
    defaultValue: Set<T>,
): PreferenceData<Set<T>> = getObjectSetFromStringSet(
    key = key,
    defaultValue = defaultValue,
    serializer = { it.name },
    deserializer = { stored -> runCatching { enumValueOf<T>(stored) }.getOrNull() },
)

package com.lagradost.cloudstream4.core.storage

import android.content.Context
import android.content.SharedPreferences

/**
 * [KeyValueJsonStore] over the `rebuild_preference` `SharedPreferences` file — the same file, with
 * the same keys and the same values, that `DataStore` in `:app` reads and writes today.
 *
 * ## Byte compatibility with `:app`
 *
 * | `:app` (`DataStore.kt`) | here |
 * |---|---|
 * | `context.getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)` | [forContext], with [StorageFiles.JSON_RECORDS] |
 * | `Context.setKey`: `edit { putString(path, value?.toJsonLiteral()) }` | [set]: `putString(key, json)`, the JSON produced by the caller |
 * | `Context.getKey`: `getString(path, null)` then `parseJson` | [get]: `getString(key, null)`, parsed by the caller |
 * | `Context.getKeys(folder)`: `all.keys.filter { it.startsWith(folder.trimEnd('/') + "/") }` | [KeyValueJsonStore.keysIn], the interface default, same expression |
 * | `Context.removeKey`: `if (contains) edit { remove }` | [remove] |
 * | `Context.removeKeys(folder)`: one `edit`, returns the count | [KeyValueJsonStore.removeIn], the interface default |
 * | `DataStore.editor(context).apply()` | [edit] |
 *
 * Nothing here transforms a value. `set` stores the exact string it is handed, so a record written
 * through this store is indistinguishable from one written by `Context.setKey`, and vice versa.
 *
 * ## Why swapping the backing store is ABI-safe
 *
 * `Context.getKey` and `Context.setKey` are `inline reified`: their bodies are copied into every
 * extension compiled against `app/build/classes.jar`. Disassembling that jar shows those bodies
 * calling `invokevirtual DataStore.getSharedPrefs` followed by `invokeinterface
 * android/content/SharedPreferences.getString` / `.putString`. The file name `"rebuild_preference"`
 * appears only inside `DataStore.getPreferences`, which is `private`. So an extension binds to the
 * `SharedPreferences` **interface** and to the JSON-string encoding, never to the XML file — which
 * is why a different implementation behind that interface would not break one. This class does not
 * even need that latitude: it uses the same file.
 *
 * ## Errors
 *
 * `SharedPreferences.getString` throws `ClassCastException` when the stored value is not a string
 * (`:app` can have written a `Boolean` under the same key through `Editor.setKeyRaw`). `:app`
 * catches it and returns `null`; so does [get].
 */
class AndroidKeyValueJsonStore(
    private val preferences: SharedPreferences,
) : KeyValueJsonStore {

    override fun get(key: String): String? =
        runCatching { preferences.getString(key, null) }.getOrNull()

    override fun set(key: String, json: String) {
        preferences.edit().putString(key, json).apply()
    }

    override fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }

    override fun contains(key: String): Boolean = preferences.contains(key)

    /**
     * Every key in the file, whatever the value's type — matching `Context.getKeys`, which also
     * reads `all.keys` and so sees keys written by `Editor.setKeyRaw` as well as JSON records.
     */
    override fun keys(): Set<String> = preferences.all.keys.toSet()

    /** One `apply()` for the whole batch, which is the point of `DataStore.Editor` in `:app`. */
    override fun edit(block: KeyValueJsonEditor.() -> Unit) {
        val editor = preferences.edit()
        object : KeyValueJsonEditor {
            override fun set(key: String, json: String) {
                editor.putString(key, json)
            }

            override fun remove(key: String) {
                editor.remove(key)
            }
        }.block()
        editor.apply()
    }

    companion object {
        /**
         * The store on `rebuild_preference`, opened exactly as `DataStore.getPreferences` opens it:
         * same name, same `MODE_PRIVATE`. Pass an application context — a `SharedPreferences`
         * instance outlives whatever `Context` opened it.
         */
        fun forContext(context: Context): AndroidKeyValueJsonStore = AndroidKeyValueJsonStore(
            context.getSharedPreferences(StorageFiles.JSON_RECORDS, Context.MODE_PRIVATE),
        )
    }
}

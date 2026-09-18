package com.lagradost.cloudstream4.core.storage

/**
 * The name of the JSON-record file CloudStream has always had. It is part of the on-disk layout,
 * not a choice this module gets to make.
 *
 * The **settings** file behind [PreferenceStore] has no name here on purpose: on Android it is the
 * platform default (`"<packageName>_preferences"`, chosen by `androidx.preference`), and elsewhere
 * it is whatever that platform's implementation picks. `:app` resolves it and passes the resulting
 * store in.
 */
object StorageFiles {
    /**
     * The file behind [KeyValueJsonStore].
     *
     * Mirrors `com.lagradost.cloudstream3.utils.PREFERENCES_NAME` in `:core:data`. It is restated
     * rather than referenced because `:core:data` is layer 2 and this module is layer 1 (plan
     * §4.1 rule 2), and because that constant is frozen extension API — extensions inline its
     * value, so it cannot change without breaking them anyway. **If it ever does change, change it
     * here in the same commit.**
     */
    const val JSON_RECORDS = "rebuild_preference"
}

/**
 * Joins a folder and a path into the single flat key the store actually holds.
 *
 * Byte-compatible with `DataStore.getFolderName` in `:app` (`"${folder}/${path}"`). There is no
 * folder structure on disk — "folders" are a prefix convention on flat keys, which is why
 * [KeyValueJsonStore.keysIn] is a `startsWith` scan.
 */
fun folderKey(folder: String, path: String): String = "$folder/$path"

/**
 * The **JSON record** store: the `rebuild_preference` file, where `:app` keeps everything that is
 * an object rather than a setting — watch positions, bookmarks, account rows, cached sync lists,
 * download metadata, auth tokens.
 *
 * ## Values are JSON strings, and this module does not encode them
 *
 * `:app` writes these with `Context.setKey`, which is `putString(path, value?.toJsonLiteral())` —
 * a kotlinx-serialization or Jackson encoding of the value, stored as a `String`. It reads them
 * back with `parseJson`. Both are `inline reified` and have been copied into every published
 * extension, so the encoding is frozen ABI and belongs to `:app`.
 *
 * This port therefore carries **opaque JSON strings** and nothing else. It does not parse, validate,
 * re-encode or pretty-print them; `get(key)` returns the exact string `set(key, json)` was given.
 * That is what makes a `:core:storage`-backed writer and `:app`'s `Context.getKey` interchangeable
 * on the same file, and it is why this module needs no kotlinx-serialization dependency.
 *
 * Note the encoding is `toJsonLiteral`, not `toJson`: a `String` value is stored **quoted**
 * (`"hello"`, 7 bytes), because `getKey` will `parseJson` it back. A caller that stores a bare
 * `hello` writes a record `:app` cannot read.
 *
 * ## Absent is not empty
 *
 * `get` returns `null` both when the key is absent and when the stored value cannot be read as a
 * string (`:app` swallows the same `ClassCastException` and returns `null`). Use [contains] when
 * the difference matters.
 */
interface KeyValueJsonStore {

    /** The stored JSON string, or `null` when the key is absent or unreadable. */
    fun get(key: String): String?

    /** Stores [json] verbatim. Must not transform it — see the note on encoding above. */
    fun set(key: String, json: String)

    fun remove(key: String)

    fun contains(key: String): Boolean

    /** Every key in the file, in no particular order. */
    fun keys(): Set<String>

    /**
     * Applies several writes as one batch. Implementations that can commit once should; the
     * Android one does, which is the whole point of `DataStore.Editor` in `:app`.
     */
    fun edit(block: KeyValueJsonEditor.() -> Unit)

    /**
     * The keys under [folder], i.e. those starting with `folder` plus a single `/`.
     *
     * Byte-compatible with `Context.getKeys` in `:app`, trailing-slash normalisation included: the
     * folder is `trimEnd('/')`-ed and one `/` appended, so `"a"`, `"a/"` and `"a///"` all scan the
     * prefix `"a/"`, and a sibling folder `"ab"` never matches. Defaulted on the interface so every
     * implementation shares one definition of "folder" — do not override it.
     */
    fun keysIn(folder: String): List<String> {
        val prefix = folder.trimEnd('/') + "/"
        return keys().filter { it.startsWith(prefix) }
    }

    /**
     * Removes every key under [folder] in one batch and returns how many were removed.
     * Matches `Context.removeKeys` in `:app`, which also returns the count.
     */
    fun removeIn(folder: String): Int {
        val keys = keysIn(folder)
        // `this.remove` is the *editor*'s remove, not this store's. Both are in scope inside the
        // lambda and they differ: the store's would commit once per key, which is exactly the
        // "apply for every key is very expensive" that `DataStore.Editor` exists to avoid.
        edit { for (key in keys) this.remove(key) }
        return keys.size
    }
}

/** The batch handed to [KeyValueJsonStore.edit]. Writes land when the block returns. */
interface KeyValueJsonEditor {
    fun set(key: String, json: String)
    fun remove(key: String)
}

/** Folder-scoped conveniences, mirroring the `(folder, path)` overloads of `DataStore` in `:app`. */
fun KeyValueJsonStore.get(folder: String, path: String): String? = get(folderKey(folder, path))

fun KeyValueJsonStore.set(folder: String, path: String, json: String) = set(folderKey(folder, path), json)

fun KeyValueJsonStore.remove(folder: String, path: String) = remove(folderKey(folder, path))

fun KeyValueJsonStore.contains(folder: String, path: String): Boolean = contains(folderKey(folder, path))

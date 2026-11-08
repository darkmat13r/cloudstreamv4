package com.lagradost.cloudstream3.syncproviders

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJsonLiteral
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs

/**
 * `BUG-50`: sign-ins (`auth_tokens/<provider>/<profile>`, `auth_ids/<provider>/<profile>`) live in their own
 * preferences file, [FILE], which `res/xml/backup_descriptor.xml` and `res/xml/data_extraction_rules.xml` exclude from
 * Android's backup and device transfer. They used to sit in the main store, which Android backed up whole: a restore
 * onto a new device brought every provider token with it. Now a new device signs in again.
 *
 * **Migration** runs once per process, on the first read or write: each sign-in key in the main store is copied as its
 * raw stored string, the copy is committed to disk, and only then are the old keys removed. A key the new file already
 * holds is newer and wins. Nothing is lost if the process dies part way: the old keys go only after the copy is written.
 *
 * Values are stored exactly as the main store stored them (JSON via `toJsonLiteral`), so nothing changes shape. Nothing
 * here logs a key's value.
 *
 * `BUG-51`: profile PINs live here too, under [PIN_FOLDER]`/<profile index>`, so they stay off Android's backup as the
 * sign-ins do. `DataStoreHelper.accounts` moves them out of the profile list; see [pins] and [replacePins].
 */
internal object CredentialStore {
    /** The preferences file name; the backup rules exclude `$FILE.xml`. */
    const val FILE = "cs_credentials"

    /** `BUG-51`: each profile's PIN, as `lock_pin/<profile index>`, stored as the plain PIN string. */
    const val PIN_FOLDER = "lock_pin"

    private val folders = listOf(AccountManager.ACCOUNT_TOKEN, AccountManager.ACCOUNT_IDS)

    @Volatile
    private var migrated = false

    private fun file(ctx: Context): SharedPreferences = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun isSignInKey(key: String) = folders.any { key.startsWith("$it/") }

    /** The store, migrated; null without a context, as the main store's helpers are. */
    private fun store(): SharedPreferences? {
        val ctx = context ?: return null
        if (!migrated) synchronized(this) {
            // A failed copy leaves the old keys in place, and the next call tries again; marking it done would read
            // an empty store for the rest of the process and look signed out.
            if (!migrated) migrated = migrate(ctx) >= 0
        }
        return file(ctx)
    }

    /**
     * Moves sign-in keys from the main store into [FILE]. Returns how many keys moved, 0 when there was nothing to
     * move, or -1 when the copy could not be written, in which case nothing was removed.
     */
    fun migrate(ctx: Context): Int {
        val main = ctx.getSharedPrefs()
        val moving = main.all.filter { (key, value) -> isSignInKey(key) && value is String }
        if (moving.isEmpty()) return 0
        val target = file(ctx)
        val copy = target.edit()
        moving.forEach { (key, value) -> if (!target.contains(key)) copy.putString(key, value as String) }
        if (!copy.commit()) return -1 // nothing is removed unless the copy is on disk
        main.edit().apply { moving.keys.forEach { remove(it) } }.commit()
        return moving.size
    }

    /** Until the move has succeeded, a sign-in still in the main store is read from there. */
    private fun unmoved(): SharedPreferences? = if (migrated) null else context?.getSharedPrefs()

    fun readRaw(key: String): String? =
        store()?.getString(key, null) ?: unmoved()?.takeIf { isSignInKey(key) }?.getString(key, null)

    /**
     * As the main store's `getKey`: null when absent or when the stored value does not parse. Named `getKey` on
     * purpose: callers' compiler-generated classes are named after the inline function, and are in `classes-jar.api`.
     */
    inline fun <reified T : Any> getKey(key: String): T? =
        readRaw(key)?.let { runCatching { parseJson<T>(it) }.getOrNull() }

    fun write(key: String, value: Any) {
        store()?.edit()?.putString(key, value.toJsonLiteral())?.apply()
    }

    fun remove(key: String) {
        store()?.edit()?.remove(key)?.apply()
        // A sign-out must not come back on the next move.
        unmoved()?.takeIf { isSignInKey(key) && it.contains(key) }?.edit()?.remove(key)?.apply()
    }

    /** Every key under [folder] (`auth_tokens` or `auth_ids`). */
    fun keys(folder: String): List<String> {
        val prefix = folder.trimEnd('/') + "/"
        val moved = store()?.all?.keys?.filter { it.startsWith(prefix) }.orEmpty()
        val left = unmoved()?.all?.keys?.filter { it.startsWith(prefix) && isSignInKey(it) }.orEmpty()
        return (moved + left).distinct()
    }

    /**
     * Every stored PIN by profile index, or null without a context. On null, `DataStoreHelper.accounts` returns the list
     * without PINs; that is only reachable with no context, when the list itself has nothing to read either.
     */
    fun pins(): Map<Int, String>? {
        val prefix = "$PIN_FOLDER/"
        return store()?.all?.mapNotNull { (key, value) ->
            val index = key.removePrefix(prefix).takeIf { key.startsWith(prefix) }?.toIntOrNull()
            if (index != null && value is String) index to value else null
        }?.toMap()
    }

    /**
     * Makes the stored PINs exactly [pins]: each one written, and every other profile's PIN removed, so a reused
     * profile index never inherits an old lock. Returns false, having changed nothing, when it could not be written.
     */
    fun replacePins(pins: Map<Int, String>): Boolean {
        val store = store() ?: return false
        val prefix = "$PIN_FOLDER/"
        val edit = store.edit()
        store.all.keys.filter { it.startsWith(prefix) }.forEach { edit.remove(it) }
        pins.forEach { (index, pin) -> edit.putString("$prefix$index", pin) }
        return edit.commit()
    }
}

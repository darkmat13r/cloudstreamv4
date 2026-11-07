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
 */
internal object CredentialStore {
    /** The preferences file name; the backup rules exclude `$FILE.xml`. */
    const val FILE = "cs_credentials"

    private val folders = listOf(AccountManager.ACCOUNT_TOKEN, AccountManager.ACCOUNT_IDS)

    @Volatile
    private var migrated = false

    private fun file(ctx: Context): SharedPreferences = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun isSignInKey(key: String) = folders.any { key.startsWith("$it/") }

    /** The store, migrated; null without a context, as the main store's helpers are. */
    private fun store(): SharedPreferences? {
        val ctx = context ?: return null
        if (!migrated) synchronized(this) {
            if (!migrated) {
                migrate(ctx)
                migrated = true
            }
        }
        return file(ctx)
    }

    /** Moves sign-in keys from the main store into [FILE]. Returns how many keys moved. */
    fun migrate(ctx: Context): Int {
        val main = ctx.getSharedPrefs()
        val moving = main.all.filter { (key, value) -> isSignInKey(key) && value is String }
        if (moving.isEmpty()) return 0
        val target = file(ctx)
        val copy = target.edit()
        moving.forEach { (key, value) -> if (!target.contains(key)) copy.putString(key, value as String) }
        if (!copy.commit()) return 0 // nothing is removed unless the copy is on disk
        main.edit().apply { moving.keys.forEach { remove(it) } }.commit()
        return moving.size
    }

    fun readRaw(key: String): String? = store()?.getString(key, null)

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
    }

    /** Every key under [folder] (`auth_tokens` or `auth_ids`). */
    fun keys(folder: String): List<String> {
        val prefix = folder.trimEnd('/') + "/"
        return store()?.all?.keys?.filter { it.startsWith(prefix) }.orEmpty()
    }
}

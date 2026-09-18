package com.lagradost.cloudstream4.core.storage

/**
 * The [SecretStore] CloudStream actually has today: secrets kept as plain JSON records in a
 * [KeyValueJsonStore], under a key prefix.
 *
 * This is **not** an encrypted store and [protection] says so. It exists to name the seam, not to
 * close it — see [SecretStore]'s KDoc for what an `EncryptedSharedPreferences` or Keychain
 * implementation would have to change.
 *
 * It lives in `commonMain` rather than `androidMain` because it has no platform surface of its own:
 * on Android you build it over the `SharedPreferences`-backed [KeyValueJsonStore] on
 * `rebuild_preference`, which puts the secrets exactly where `:app` already writes them. The
 * consequence is that its whole behaviour is covered by the jvmTest suite, over an in-memory
 * [KeyValueJsonStore] — the `SharedPreferences` layer underneath it is the only untested part.
 *
 * @param prefix the folder the secrets live under, e.g. `AccountManager.ACCOUNT_TOKEN`
 * (`"auth_tokens"`). Keys passed to [get] / [set] are relative to it and are joined with
 * [folderKey], so this store reads and writes the same flat keys `:app` does.
 */
class KeyValueJsonSecretStore(
    private val records: KeyValueJsonStore,
    private val prefix: String,
) : SecretStore {

    /**
     * Plain text. The bytes sit in the app's preference file unencrypted, exactly as
     * `Context.setKey` leaves them today.
     */
    override val protection: SecretProtection = SecretProtection.PLAINTEXT

    /** [prefix] without a trailing slash, so every member joins keys the same way [keysIn] splits them. */
    private val folder = prefix.trimEnd('/')

    override fun get(key: String): String? = records.get(folderKey(folder, key))

    override fun set(key: String, secret: String) = records.set(folderKey(folder, key), secret)

    override fun remove(key: String) = records.remove(folderKey(folder, key))

    override fun contains(key: String): Boolean = records.contains(folderKey(folder, key))

    /** The keys *relative to* [prefix], so they round-trip through [get] and [set]. */
    override fun keys(): Set<String> =
        records.keysIn(folder).map { it.removePrefix("$folder/") }.toSet()

    override fun clear() {
        records.removeIn(folder)
    }
}

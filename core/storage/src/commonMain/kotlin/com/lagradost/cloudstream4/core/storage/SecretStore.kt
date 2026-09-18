package com.lagradost.cloudstream4.core.storage

/**
 * How much the platform actually protects what a [SecretStore] holds.
 *
 * This exists so that callers, and anyone reading a call site, can see the answer instead of
 * assuming it from the type's name. Every value here is a statement about the *current*
 * implementation, not an aspiration.
 */
enum class SecretProtection {
    /**
     * No protection at all beyond the app sandbox: the value is readable by anything that can read
     * the app's data directory — a rooted or jailbroken device, an `adb backup` where one is
     * allowed, a full-device backup, a file manager with the right permissions, and any code in
     * this process.
     *
     * **This is what CloudStream does today**, on both files, for every secret listed in
     * [SecretStore]'s KDoc.
     */
    PLAINTEXT,

    /**
     * The value is encrypted at rest with a key held by the OS keystore (Android Keystore /
     * `EncryptedSharedPreferences`, iOS Keychain). Nothing returns this yet.
     */
    OS_KEYSTORE,
}

/**
 * The store for the values that would let someone act as the user: **OAuth tokens** and the
 * **profile PIN**.
 *
 * ## What it does not promise
 *
 * It does not promise encryption, and the name is not a claim that it provides any. Read
 * [protection] to find out what a given instance does; the Android implementation today returns
 * [SecretProtection.PLAINTEXT].
 *
 * This is a separate interface from [KeyValueJsonStore] even though the Android implementation is
 * a thin delegate to it, for two reasons. It names, in one place, which keys are sensitive — the
 * knowledge that is currently spread across `BackupUtils.nonTransferableKeys`. And it is the seam:
 * when an `EncryptedSharedPreferences` or Keychain implementation lands, only this interface's
 * implementation and [protection] change, and no call site does.
 *
 * ## What CloudStream stores here today, and how
 *
 * Both live in the `rebuild_preference` file as plain JSON strings, written by `Context.setKey`:
 *
 * - **OAuth tokens** — `auth_tokens/<prefix>/<accountIndex>`, a JSON array of `AuthData`
 *   (`AccountManager.ACCOUNT_TOKEN`). Access and refresh tokens for MAL, AniList, Simkl, Kitsu,
 *   OpenSubtitles and the rest, in clear text.
 * - **The profile PIN** — the `lockPin` field of an `Account` inside the JSON array at
 *   `data_store_helper/account`. It is not a key of its own, which is why this interface addresses
 *   secrets by key string and leaves the record shape to the caller: a `SecretStore` cannot today
 *   hold the PIN separately without changing what `:app` writes, and this PR changes nothing
 *   `:app` writes.
 *
 * `BackupUtils` already excludes `auth_tokens` and `auth_ids` from backups. That is the only
 * handling these values get.
 *
 * ## What a future encrypted implementation would change
 *
 * An `AndroidEncryptedSecretStore` over `EncryptedSharedPreferences` (or an iOS `KeychainSecretStore`)
 * would implement exactly this interface, return [SecretProtection.OS_KEYSTORE], and keep the same
 * key strings and the same JSON values. What it would additionally need, and what this PR
 * deliberately does not do:
 *
 * - **A dependency decision.** `androidx.security:security-crypto` is not in the catalog, its 1.0.0
 *   line is effectively unmaintained and the 1.1.0 alpha has its own caveats; a Keychain binding is
 *   a different decision again. Either goes through the four points in `dependency-security.md`.
 * - **A user-data migration.** Existing installs have plaintext tokens in `rebuild_preference`. A
 *   real implementation has to read them there, re-write them encrypted and delete the originals,
 *   once, idempotently, tolerating a kill mid-way. That is its own PR.
 * - **A key-loss story.** Keystore keys do not survive every restore, device change or user
 *   lockscreen change. Losing a token means a re-login, which is acceptable, but the code has to
 *   treat an undecryptable value as absent rather than crashing.
 * - **Failure semantics.** `EncryptedSharedPreferences` can throw where `SharedPreferences` does
 *   not. This interface would keep returning `null`, so the mapping happens inside the
 *   implementation.
 */
interface SecretStore {

    /** What this instance actually does. Not a configuration knob — a property of the implementation. */
    val protection: SecretProtection

    /** The stored secret, or `null` when absent or unreadable. */
    fun get(key: String): String?

    fun set(key: String, secret: String)

    fun remove(key: String)

    fun contains(key: String): Boolean

    /**
     * Every key this store holds. Keys are **not** secret — the values are — so this is safe to
     * enumerate; it is what a "sign out of everything" path needs.
     */
    fun keys(): Set<String>

    /** Removes every secret. Used by sign-out-of-everything and by profile deletion. */
    fun clear()
}

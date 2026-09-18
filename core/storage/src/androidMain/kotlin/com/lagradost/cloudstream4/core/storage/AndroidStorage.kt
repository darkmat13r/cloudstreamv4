package com.lagradost.cloudstream4.core.storage

import android.content.Context
import android.content.SharedPreferences

/**
 * The Android wiring of the three ports onto CloudStream's two existing preference files.
 *
 * Nothing in this module is wired into `:app` yet — that is B8 and the feature verticals. This
 * object exists so that the wiring is one call per store when it happens, and so that the file
 * each store lands on is stated in one place rather than rediscovered at each call site.
 *
 * ## What is and is not tested
 *
 * The behavioural contract of all three ports is covered by `jvmTest` against the in-memory
 * implementations, and [KeyValueJsonSecretStore] — the whole of the secret store's logic — is
 * covered there too, because it is `commonMain` code over a [KeyValueJsonStore].
 *
 * **The `SharedPreferences`-backed classes in this source set are not covered by an automated
 * test.** They need a real `SharedPreferences`, which means either an instrumented test on a device
 * or emulator, or Robolectric. **Robolectric is not available in this project and this PR does not
 * add it** (that is a dependency decision, per `dependency-security.md`). Their correctness rests
 * on: the key algebra and the folder semantics living in `commonMain` and being tested there; each
 * accessor mapping one-to-one onto the `SharedPreferences` primitive of the same name; and the
 * per-method comparison against `DataStore.kt` in [AndroidKeyValueJsonStore]'s KDoc. Treat a change
 * to either Android class as unverified until someone runs it on a device.
 */
object AndroidStorage {

    /**
     * The settings store, over the `SharedPreferences` the caller considers default.
     *
     * `:app` passes `DataStore.getDefaultSharedPrefs()` —
     * `PreferenceManager.getDefaultSharedPreferences(context)`, the `"<packageName>_preferences"`
     * file. This module does not call `PreferenceManager` itself; see [AndroidPreferenceStore].
     */
    fun settings(preferences: SharedPreferences): PreferenceStore =
        AndroidPreferenceStore(preferences)

    /**
     * The JSON-record store, on `rebuild_preference` — the file `DataStore` in `:app` calls
     * `getSharedPrefs()`. Pass an application context.
     */
    fun jsonRecords(context: Context): KeyValueJsonStore =
        AndroidKeyValueJsonStore.forContext(context)

    /**
     * A secret store over [jsonRecords], scoped to [prefix].
     *
     * **It is plaintext** — it reports [SecretProtection.PLAINTEXT], because that is what the file
     * is. For OAuth tokens, `prefix` is `AccountManager.ACCOUNT_TOKEN` (`"auth_tokens"`), which is
     * where `:app` already writes them, so this reads existing installs' tokens with no migration.
     * See [SecretStore] for what an encrypted implementation would change.
     */
    fun secrets(context: Context, prefix: String): SecretStore =
        KeyValueJsonSecretStore(jsonRecords(context), prefix)
}

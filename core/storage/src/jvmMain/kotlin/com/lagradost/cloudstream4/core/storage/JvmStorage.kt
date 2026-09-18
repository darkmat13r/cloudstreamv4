package com.lagradost.cloudstream4.core.storage

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The JVM wiring of the three ports onto files, mirroring `AndroidStorage`.
 *
 * Nothing in this module is wired into `:desktopApp` yet, exactly as nothing is wired into `:app`
 * yet. This object exists so that when it happens it is one call per store, and so that the file
 * each store lands on is stated once rather than rediscovered at each call site.
 *
 * ## One instance per file, and why that is this object's job
 *
 * Android gets this for free: `Context.getSharedPreferences` returns the same instance for the same
 * name, so every caller in the process shares one cache and one listener list. A file has no such
 * registry, and two [FilePreferenceStore]s over one path would quietly drop each other's writes
 * (see [TextRecordFile]'s concurrency note). So this object keeps one store per resolved path and
 * hands the same one back. **Prefer these functions to the constructors**; the constructors are
 * public for tests and for an app that wants a store somewhere unusual, and using one twice on the
 * same path is the mistake this registry exists to prevent.
 *
 * The cache is never emptied — a store is process-scoped state, like `SharedPreferences` — so a
 * test that wants a genuinely fresh instance over an existing file constructs one directly.
 */
object JvmStorage {

    /**
     * The settings file. Named for what it holds; unlike the record file below there is no existing
     * name to match, because Android's is `androidx.preference`'s choice and not part of any
     * contract.
     */
    const val SETTINGS_FILE: String = "settings.records"

    /**
     * The JSON-record file, named after [StorageFiles.JSON_RECORDS] so it is recognisably the same
     * store as Android's `rebuild_preference`. The content is *not* interchangeable — that one is
     * `SharedPreferences` XML, this one is [TextRecordFile] text — but the keys and the values
     * inside are, which is what a backup transferred between platforms would carry.
     */
    const val JSON_RECORDS_FILE: String = "${StorageFiles.JSON_RECORDS}.records"

    private val preferenceStores = ConcurrentHashMap<Path, FilePreferenceStore>()
    private val recordStores = ConcurrentHashMap<Path, FileKeyValueJsonStore>()

    /**
     * The settings store, in [directory]. Equivalent of `AndroidStorage.settings`, except that the
     * JVM has no `androidx.preference` to ask, so this module does name the file.
     *
     * One store per file, as `Context.getSharedPreferences` gives on Android — two instances over one
     * file would silently lose each other's writes (see [TextRecordFile]). [onCorruption] is therefore
     * bound by the **first** call for a directory and later ones are ignored; corruption is detected
     * while the store is being built, so a listener passed later could never have fired anyway.
     */
    fun settings(
        directory: Path = JvmStorageDirectory.resolve(),
        onCorruption: StorageCorruptionListener = StorageCorruptionListener.STDERR,
    ): PreferenceStore = preferenceStores.computeIfAbsent(canonical(directory, SETTINGS_FILE)) {
        FilePreferenceStore(it, onCorruption)
    }

    /**
     * The JSON-record store, in [directory]. Equivalent of `AndroidStorage.jsonRecords`. As with
     * [settings], the store is memoized per file and [onCorruption] is bound by the first call.
     */
    fun jsonRecords(
        directory: Path = JvmStorageDirectory.resolve(),
        onCorruption: StorageCorruptionListener = StorageCorruptionListener.STDERR,
    ): KeyValueJsonStore = recordStores.computeIfAbsent(canonical(directory, JSON_RECORDS_FILE)) {
        FileKeyValueJsonStore(it, onCorruption)
    }

    /**
     * A secret store over [jsonRecords], scoped to [prefix] — the same [KeyValueJsonSecretStore]
     * Android uses, over a different file. It needed no JVM implementation of its own: it is
     * `commonMain` code over a [KeyValueJsonStore], so giving the JVM a record store gave it a
     * secret store too, tested by the same contract.
     *
     * ## It is plaintext, and on this platform that is worth more words than on Android
     *
     * It reports [SecretProtection.PLAINTEXT]. The OAuth tokens are in the file in clear text,
     * exactly as they are on Android — but the surrounding protection is weaker, and a desktop
     * install should not be told otherwise:
     *
     * - There is no app sandbox. On Android the data directory is readable only by the app's uid;
     *   here every process running as this user can read the file. Malware does not need root, an
     *   unlocked bootloader or `adb`; it needs to be running as you.
     * - The file is created `rw-------` (0600) and its directory `rwx------` (0700) **where the
     *   filesystem has POSIX permissions** — Linux, macOS, and a JVM on WSL. That stops other
     *   *users*, not other processes of yours.
     * - **On Windows, and on any filesystem without POSIX permissions** (exFAT on a USB stick, a
     *   mounted SMB share, a FAT-formatted portable install), those permissions cannot be set and
     *   are silently not set. The file inherits the directory's ACL: inside `%APPDATA%` that is
     *   your profile, which is the normal case and roughly equivalent; on a removable drive it can
     *   be everyone. A portable install pointed at a stick with `-Dcloudstream.storage.dir` gives
     *   its tokens whatever that stick gives them.
     * - Nothing is encrypted at rest. A backup of the home directory, a synced folder, or a
     *   restored disk image carries the tokens with it.
     *
     * What an encrypted implementation would change is written up on [SecretStore] — the same three
     * problems (a dependency decision, a migration of existing plaintext, a key-loss story) with
     * different names on this platform: the macOS **Keychain** through `security`/JNA, **DPAPI**
     * (`CryptProtectData`) on Windows, and on Linux the Secret Service API where a keyring daemon
     * is running and nothing where one is not. That last gap is why this is a real decision and not
     * a missing line of code: "encrypted on two platforms and plaintext on the third" needs an
     * answer for the third before it is worth the dependency.
     */
    fun secrets(
        prefix: String,
        directory: Path = JvmStorageDirectory.resolve(),
        onCorruption: StorageCorruptionListener = StorageCorruptionListener.STDERR,
    ): SecretStore = KeyValueJsonSecretStore(jsonRecords(directory, onCorruption), prefix)

    /**
     * The registry key: an absolute, normalised path, so `dir`, `./dir` and `dir/../dir` are one
     * store. Not `toRealPath`, which needs the file to exist and would resolve symlinks differently
     * before and after the first write.
     */
    private fun canonical(directory: Path, fileName: String): Path =
        directory.resolve(fileName).toAbsolutePath().normalize()
}

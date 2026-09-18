package com.lagradost.cloudstream4.core.storage

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where the JVM build keeps its stores.
 *
 * Android has no equivalent of this: `Context` knows the app's data directory and nothing gets to
 * choose. On the desktop the convention is the platform's, and the three platforms disagree:
 *
 * | platform | directory |
 * |---|---|
 * | macOS | `~/Library/Application Support/CloudStream` |
 * | Windows | `%APPDATA%\CloudStream`, falling back to `~\AppData\Roaming\CloudStream` |
 * | anything else | `$XDG_DATA_HOME/cloudstream`, falling back to `~/.local/share/cloudstream` |
 *
 * `CloudStream` is the name `:desktopApp` already packages under
 * (`nativeDistributions.packageName`), so an installed build and a build from source land in the
 * same place. The XDG name is lower case because that is the convention there.
 *
 * ## Pointing it somewhere else
 *
 * Two ways, and the first wins:
 *
 * 1. `-Dcloudstream.storage.dir=...` ([OVERRIDE_PROPERTY]) — for a portable install on a stick, for
 *    running two profiles side by side, and for anyone who wants their data out of `$HOME`.
 * 2. Pass the directory to [JvmStorage.settings] / [JvmStorage.jsonRecords] / [JvmStorage.secrets],
 *    which is what the tests do.
 *
 * The directory is not created here; the first write creates it, owner-only where the filesystem
 * has POSIX permissions.
 */
object JvmStorageDirectory {

    /** `-Dcloudstream.storage.dir=/path/to/somewhere`. Absolute or relative to the working directory. */
    const val OVERRIDE_PROPERTY: String = "cloudstream.storage.dir"

    /** Matches `:desktopApp`'s `nativeDistributions.packageName`. */
    const val APPLICATION_NAME: String = "CloudStream"

    /** Lower case, per the XDG basedir convention. */
    const val XDG_APPLICATION_NAME: String = "cloudstream"

    /** The directory this JVM should use, from the system properties and the environment. */
    fun resolve(): Path = resolve(
        osName = System.getProperty("os.name").orEmpty(),
        override = System.getProperty(OVERRIDE_PROPERTY),
        appData = System.getenv("APPDATA"),
        xdgDataHome = System.getenv("XDG_DATA_HOME"),
        userHome = System.getProperty("user.home").orEmpty(),
    )

    /**
     * The resolution itself, with every input passed in so all three platforms can be tested on one.
     *
     * Blank is treated as absent throughout: an `APPDATA=` or `XDG_DATA_HOME=` in the environment
     * means "unset", not "the current directory".
     */
    internal fun resolve(
        osName: String,
        override: String?,
        appData: String?,
        xdgDataHome: String?,
        userHome: String,
    ): Path {
        override?.takeIf { it.isNotBlank() }?.let { return Paths.get(it) }

        val lowercaseOs = osName.lowercase()
        val home = Paths.get(userHome)
        return when {
            lowercaseOs.startsWith("mac") || lowercaseOs.startsWith("darwin") ->
                home.resolve("Library").resolve("Application Support").resolve(APPLICATION_NAME)

            lowercaseOs.startsWith("windows") ->
                appData?.takeIf { it.isNotBlank() }?.let { Paths.get(it).resolve(APPLICATION_NAME) }
                    ?: home.resolve("AppData").resolve("Roaming").resolve(APPLICATION_NAME)

            else ->
                xdgDataHome?.takeIf { it.isNotBlank() }?.let { Paths.get(it).resolve(XDG_APPLICATION_NAME) }
                    ?: home.resolve(".local").resolve("share").resolve(XDG_APPLICATION_NAME)
        }
    }
}

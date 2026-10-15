package com.lagradost.cloudstream4.feature.extensions

/**
 * An extension that is present on this device.
 *
 * `:app`'s `plugins.PluginData` is the persisted form and is **published extension API**
 * (`app/api/classes-jar.api`), so it stays. This is the portable contract for the same facts.
 *
 * [location] is deliberately an opaque `String` rather than a path or a file handle, for the reason
 * `StreamSource.Local` in `:media:model` is: on Android it is an absolute path under the app's files
 * directory, and there is no portable type that means the same thing. Whoever produced it is the only
 * thing that can resolve it.
 */
data class InstalledExtension(
    /** Matches [ExtensionListing.internalName] when this came from a repository. */
    val internalName: String,
    /** Opaque, platform-resolved location of the installed `.cs3`. */
    val location: String,
    /** The [ExtensionListing.version] this installation was built from. */
    val version: Int,
    /**
     * True when this was downloaded from a repository, false when side-loaded.
     *
     * It decides whether the extension takes part in auto-update: `:app` stores it as
     * `PluginData.isOnline` and skips local ones. [sourceUrl] is `null` exactly when this is false.
     */
    val isFromRepository: Boolean,
    /** The URL it was downloaded from, for update checks. `null` for a side-loaded extension. */
    val sourceUrl: String? = null,
)

/**
 * Where an extension is in the install → load lifecycle.
 *
 * This is the state a UI renders and a runtime reports, and it is one type because the transitions
 * are what a caller cares about. [Loaded] is the only state in which an extension's providers are
 * registered; everything else means they are not.
 */
sealed interface ExtensionState {
    /** In the repository index, not on this device. */
    data object Available : ExtensionState

    /** Bytes are being fetched. [progress] is `0f..1f`, or `null` when the size is unknown. */
    data class Downloading(val progress: Float? = null) : ExtensionState

    /** On disk, not loaded — either not loaded yet, or deliberately unloaded. */
    data object Installed : ExtensionState

    /** On disk and loaded; its providers are live. */
    data object Loaded : ExtensionState

    /**
     * Installed, and the repository advertises a higher [ExtensionListing.version].
     *
     * Separate from [Installed] because it is the only state that makes an update offer meaningful,
     * and the available version is part of the state rather than something the UI recomputes.
     */
    data class UpdateAvailable(val availableVersion: Int) : ExtensionState

    /**
     * The last attempt failed. [reason] is for the user; [cause] is for a log.
     *
     * Carrying a plain `String` reason rather than a `UiString` keeps this module free of
     * `:core:strings`; the feature layer decides how to word a failure.
     */
    data class Failed(val reason: String, val cause: Throwable? = null) : ExtensionState
}

/**
 * Whether a downloaded `.cs3` is what the repository said it would be.
 *
 * `:app` does not check this today — `ExtensionListing.fileHash` is carried through the index and
 * never compared — and this contract exists so that the check has somewhere to live when it is
 * written. **Nothing in this module performs verification**; it only names the outcomes, so a runtime
 * that skips the step has to say so with [Skipped] rather than by returning success.
 *
 * Extension code is third-party and arbitrary, and `.claude/rules/dependency-security.md` treats its
 * output as untrusted input. An unverified download is the step before that trust boundary.
 */
sealed interface ExtensionVerification {
    /** The file's hash matched [ExtensionListing.fileHash]. */
    data object Verified : ExtensionVerification

    /** The file's hash did not match. [expected] and [actual] are for the report, not for display. */
    data class HashMismatch(val expected: String, val actual: String) : ExtensionVerification

    /**
     * No check was possible, with the reason.
     *
     * The common case is a repository that publishes no `fileHash`, which most do not. This is not a
     * failure — it is the absence of a guarantee, and a caller that cannot tell the two apart will
     * eventually treat one as the other.
     */
    data class Skipped(val reason: String) : ExtensionVerification
}

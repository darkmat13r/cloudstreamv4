package com.lagradost.cloudstream4.feature.extensions

/**
 * The seam between "which extensions should be active" and "how this platform loads one".
 *
 * Plan §4.3 names this as `:feature:extensions`' commonMain contract, with `PathClassLoader` as the
 * Android side. It is an `interface`, not an `expect`: the implementations differ by more than a
 * platform — Android has `PathClassLoader`, iOS has no dynamic-code loading at all and that question
 * is still open (§10 O2) — and an interface lets a build supply a runtime that refuses rather than
 * one that cannot compile.
 *
 * ## What this is not
 *
 * **It is not a port of `PluginManager`.** That object is ~900 lines holding `Context`, `Activity`,
 * `File`, notification channels, Android OAT-file cleanup and the `___DO_NOT_CALL_FROM_A_PLUGIN_`
 * entry points, and it is reachable from published extension API. Nothing here replaces it or is
 * wired to it; `:app` is untouched by this module.
 *
 * ## Deliberately absent
 *
 * - **No `Context`, no `File`, no `Activity`.** Those are what kept this logic in `:app`.
 * - **No download or HTTP.** Fetching an index or a `.cs3` is `:core:http`'s job; this seam takes
 *   bytes-already-here ([install]) and a location ([load]).
 * - **No persistence.** What is installed is storage's business (`:core:storage`).
 * - **No verification.** [ExtensionVerification] names the outcomes; a runtime reports which one it
 *   reached. Making the seam perform the check would hide a skipped check inside a success.
 */
interface ExtensionRuntime {

    /** Extensions this runtime currently has loaded, with their providers live. */
    suspend fun loaded(): List<InstalledExtension>

    /**
     * Places [bytes] on this device as an extension and reports what it became.
     *
     * Takes the bytes rather than a URL so the seam owes nothing to an HTTP client, and returns the
     * [InstalledExtension] because only the runtime knows the opaque
     * [InstalledExtension.location] it chose.
     *
     * [verification] is the result of checking those bytes, passed in rather than performed here —
     * a runtime may refuse to install on [ExtensionVerification.HashMismatch], and that policy
     * belongs to the caller, not to the loader.
     */
    suspend fun install(
        listing: ExtensionListing,
        bytes: ByteArray,
        verification: ExtensionVerification,
    ): Result<InstalledExtension>

    /**
     * Loads an already-installed extension so its providers register.
     *
     * Separate from [install] because the two fail for unrelated reasons and happen at unrelated
     * times: everything installed is loaded at startup, and a hot reload during development loads
     * without installing.
     */
    suspend fun load(extension: InstalledExtension): Result<Unit>

    /**
     * Unregisters an extension's providers.
     *
     * Leaves the files in place — [uninstall] is the one that removes them. On a JVM, classes a
     * loader has defined are not truly reclaimable, so an implementation may legitimately mean "its
     * providers no longer answer" rather than "its code is gone".
     */
    suspend fun unload(extension: InstalledExtension): Result<Unit>

    /** Unloads if needed, then removes the installed files. */
    suspend fun uninstall(extension: InstalledExtension): Result<Unit>
}

/**
 * An [ExtensionRuntime] for a platform that cannot load extensions at all.
 *
 * Every call fails with the same stated reason, and [loaded] is empty. This exists so iOS and
 * `:desktopApp` can compile and run the feature's state and screens — showing a catalogue, and an
 * honest "not supported here" — while `DEC-04` and §10 O2 are still open. A build that silently had
 * no runtime would instead fail at the first install, somewhere far from the cause.
 */
class UnsupportedExtensionRuntime(
    private val reason: String = "This platform cannot load extensions.",
) : ExtensionRuntime {

    override suspend fun loaded(): List<InstalledExtension> = emptyList()

    override suspend fun install(
        listing: ExtensionListing,
        bytes: ByteArray,
        verification: ExtensionVerification,
    ): Result<InstalledExtension> = refuse()

    override suspend fun load(extension: InstalledExtension): Result<Unit> = refuse()

    override suspend fun unload(extension: InstalledExtension): Result<Unit> = refuse()

    override suspend fun uninstall(extension: InstalledExtension): Result<Unit> = refuse()

    private fun <T> refuse(): Result<T> = Result.failure(UnsupportedOperationException(reason))
}

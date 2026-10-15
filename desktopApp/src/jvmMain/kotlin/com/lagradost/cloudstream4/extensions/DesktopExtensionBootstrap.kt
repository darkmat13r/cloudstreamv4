package com.lagradost.cloudstream4.extensions

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream4.feature.extensions.ExtensionHash
import com.lagradost.cloudstream4.feature.extensions.ExtensionVerification
import com.lagradost.cloudstream4.feature.extensions.InstalledExtension
import com.lagradost.cloudstream4.feature.extensions.JvmExtensionRuntime
import java.io.File
import java.io.IOException

/**
 * Loads the extension jars sitting in the desktop extensions directory, at startup.
 *
 * ## Why a directory and not a catalogue screen
 *
 * `JvmExtensionRuntime` is the piece `KMP-13` set out to build and `DesktopSearchSource` was blocked
 * on; something has to call it, and desktop has **no catalogue UI** — no repository list, no install
 * button, no settings screen. Drawing one here would mean improvising a screen with no artboard,
 * which `docs/plans/screen-implementation-handover.md` §7 Phase B forbids. So the bootstrap is a
 * directory scan: whatever `*.jar` is in the directory is what gets loaded, which is the same
 * mechanism `:app` already offers for side-loaded plugins out of a local folder
 * (`PluginManager.kt:186-189`).
 *
 * This is deliberately the smallest honest wiring, and it is **not** the feature. When the catalogue
 * vertical is drawn and built, it owns `install`/`uninstall` and this file shrinks to whatever
 * remains of "load what is already here".
 *
 * ## Nothing is downloaded, and that is a decision rather than an omission
 *
 * An auto-installer would need two things this PR deliberately does not take: a JSON parser for the
 * index (none is on desktop's compile classpath — see [DesktopExtensionPins]) and a decision that
 * launching CloudStream may fetch and execute third-party code without being asked. The second is
 * the owner's call, not a side effect of a runtime PR. [instructions] prints the `curl` lines
 * instead, so a human performs the install.
 *
 * ## Verification, in precedence order
 *
 * 1. A `<name>.jar.sha256` sidecar beside the jar — the user pasting the `jarHash` the index
 *    publishes today. Always wins, because it is the current fact.
 * 2. [DesktopExtensionPins], for an official jar whose name is in the measured table.
 * 3. Neither: [ExtensionVerification.Skipped], and the jar still loads. A file a person put in their
 *    own directory is a side-load, which `:app` also performs unverified; what matters is that the
 *    report says `unverified` out loud rather than implying a guarantee nobody has.
 *
 * A mismatch in cases 1 or 2 **refuses to load**, and the runtime refuses again underneath
 * ([JvmExtensionRuntime] recomputes rather than trusting a verdict).
 */
class DesktopExtensionBootstrap(
    directory: File? = null,
) {

    private val resolved: ResolvedDirectory = resolveDirectory(directory)
    private val runtime = JvmExtensionRuntime(resolved.directory)

    /**
     * Verifies and loads every jar in the directory, in name order.
     *
     * Runs on the thread the caller provides and blocks it for as long as the plugins' own `load()`
     * takes: that is third-party code, and the honest bound on it is "none". A watchdog would have to
     * abandon a thread rather than cancel a coroutine — `withTimeout` cannot interrupt a blocking
     * socket read — and that is more machinery than a startup path with an empty directory in the
     * normal case deserves. Named here rather than discovered later.
     */
    suspend fun loadInstalled(): ExtensionBootstrapReport {
        val jars = resolved.directory
            .listFiles { file -> file.isFile && file.name.endsWith(JAR_SUFFIX) }
            ?.sortedBy { it.name }
            .orEmpty()
        return ExtensionBootstrapReport(
            directory = resolved.directory,
            notes = resolved.notes,
            loads = jars.map { jar -> loadOne(jar) },
        )
    }

    /**
     * One jar, verified then loaded.
     *
     * The `IOException` catch is narrow on purpose: `runtime.load` reports its own failures as a
     * `Result` and does not throw, so the only thing left that can is reading the file to hash it or
     * canonicalising its path — a jar that vanished between the listing and here, or one the user
     * cannot read. That is a report line, not a reason for the app not to start.
     */
    private suspend fun loadOne(jar: File): ExtensionLoad = try {
        verifyAndLoad(jar)
    } catch (unreadable: IOException) {
        ExtensionLoad(
            jarName = jar.name,
            verification = ExtensionVerification.Skipped("could not be read"),
            outcome = Result.failure(unreadable),
        )
    }

    private suspend fun verifyAndLoad(jar: File): ExtensionLoad {
        val verification = ExtensionHash.verify(jar, expectedHash(jar))
        if (verification is ExtensionVerification.HashMismatch) {
            return ExtensionLoad(
                jarName = jar.name,
                verification = verification,
                outcome = Result.failure(
                    IllegalStateException(
                        "refusing to load ${jar.name}: published hash ${verification.expected} " +
                            "but the file hashes to ${verification.actual}. If the repository " +
                            "republished it, put the current jarHash in ${jar.name}$SIDECAR_SUFFIX"
                    )
                ),
                providers = 0,
            )
        }

        val location = jar.canonicalPath
        val installed = InstalledExtension(
            internalName = jar.nameWithoutExtension,
            location = location,
            // The jar carries no version — only the `.cs3`'s manifest.json does, and that artefact is
            // unusable here. `Int.MIN_VALUE` is what `:app` means by "not set"
            // (`PluginManager.kt:109`), and a side-load takes no part in update checks anyway.
            version = DesktopExtensionPins.forJar(jar.name)?.version ?: VERSION_NOT_SET,
            // A file a person placed here, not a repository download: `sourceUrl` must be null when
            // this is false (`ExtensionInstall.kt:21-29`).
            isFromRepository = false,
        )
        val outcome = runtime.load(installed)
        return ExtensionLoad(
            jarName = jar.name,
            verification = verification,
            outcome = outcome,
            providers = APIHolder.apis.filter { it.sourcePlugin == location }.size,
        )
    }

    private fun expectedHash(jar: File): String? =
        sidecarHash(jar) ?: DesktopExtensionPins.forJar(jar.name)?.hash

    /**
     * The first line of `<jar>.sha256`, if there is one.
     *
     * Bounded before reading, because this is a file of unknown provenance in a directory the app
     * scans: a 2 GB "sidecar" must not be read into memory to be rejected. Anything that is not a
     * `sha256-…` string is handled downstream as [ExtensionVerification.Skipped] rather than being
     * guessed at.
     */
    private fun sidecarHash(jar: File): String? {
        val sidecar = File(jar.parentFile, jar.name + SIDECAR_SUFFIX)
        if (!sidecar.isFile || sidecar.length() > MAX_SIDECAR_BYTES) return null
        return runCatching { sidecar.readText() }.getOrNull()
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private class ResolvedDirectory(val directory: File, val notes: List<String>)

    private companion object {
        const val JAR_SUFFIX = ".jar"
        const val SIDECAR_SUFFIX = ".sha256"
        const val MAX_SIDECAR_BYTES = 1024L
        const val DIRECTORY_ENV = "CLOUDSTREAM4_EXTENSIONS_DIR"
        const val VERSION_NOT_SET = Int.MIN_VALUE

        /**
         * The directory, with every decision taken about it reported rather than silently applied.
         *
         * The environment override exists so the owner can point a run at a scratch directory
         * without touching `$HOME`. It is external input, so it is validated: a relative path is
         * ignored with a note, because resolving one against the process's working directory means
         * "wherever Gradle happened to start the app", which is not a location a user chose.
         */
        fun resolveDirectory(explicit: File?): ResolvedDirectory {
            val notes = mutableListOf<String>()
            val override = System.getenv(DIRECTORY_ENV)?.trim()
            val directory = when {
                explicit != null -> explicit
                override.isNullOrEmpty() -> defaultDirectory()
                !File(override).isAbsolute -> {
                    notes += "$DIRECTORY_ENV is not an absolute path; using the default directory"
                    defaultDirectory()
                }
                else -> File(override)
            }
            if (!directory.isDirectory && !directory.mkdirs()) {
                notes += "could not create ${directory.path}"
            }
            return ResolvedDirectory(directory, notes)
        }

        fun defaultDirectory(): File =
            File(System.getProperty("user.home") ?: ".", ".cloudstream4/extensions")
    }
}

/** One jar's outcome: what it was, whether it was provably itself, and what loading it did. */
data class ExtensionLoad(
    val jarName: String,
    val verification: ExtensionVerification,
    val outcome: Result<Unit>,
    val providers: Int = 0,
)

/**
 * What the bootstrap did, as data, so the caller decides where it goes.
 *
 * The runtime itself logs nothing by design; this type is where the words live, and `Main.kt` prints
 * them. When desktop grows a catalogue screen this is what it renders instead.
 */
data class ExtensionBootstrapReport(
    val directory: File,
    val notes: List<String>,
    val loads: List<ExtensionLoad>,
) {

    /** Providers now registered by the extensions this bootstrap loaded. */
    val providers: Int get() = loads.sumOf { it.providers }

    /** One line per fact, ready for `println`, including the install hint when there is nothing. */
    fun lines(): List<String> = buildList {
        add("extensions: ${directory.path}")
        addAll(notes.map { note -> "extensions: $note" })
        if (loads.isEmpty()) {
            addAll(instructions(directory))
            return@buildList
        }
        for (load in loads) {
            add("extensions: ${load.jarName} — ${describe(load.verification)} · ${describe(load.outcome, load)}")
        }
        add("extensions: ${loads.count { it.outcome.isSuccess }} of ${loads.size} loaded, $providers providers")
    }

    private fun describe(verification: ExtensionVerification): String = when (verification) {
        ExtensionVerification.Verified -> "hash verified"
        is ExtensionVerification.HashMismatch -> "HASH MISMATCH"
        is ExtensionVerification.Skipped -> "unverified (${verification.reason})"
    }

    private fun describe(outcome: Result<Unit>, load: ExtensionLoad): String = when {
        outcome.isSuccess -> "loaded ${load.providers} provider(s)"
        else -> "not loaded: ${outcome.exceptionOrNull()?.message ?: outcome.exceptionOrNull()}"
    }
}

/**
 * How to put an extension in [directory], printed when there is none.
 *
 * It names the jar rather than the `.cs3` on purpose: a `.cs3` is dex-only and cannot be loaded off
 * Android at all (`KMP-13` §1), and the two artefacts have confusingly similar names in the same
 * branch. The sidecar line is what turns an unverified side-load into a verified one.
 */
private fun instructions(directory: File): List<String> = buildList {
    add("extensions: no *.jar here. Desktop loads the cross-platform jar, never the .cs3 (dex-only).")
    add("extensions: 5 of the 235 catalogue entries surveyed publish one; these are those 5:")
    for (pin in DesktopExtensionPins.all()) {
        add("extensions:   curl -sSLo ${directory.path}/${pin.jarName} ${pin.url}")
    }
    add(
        "extensions: optional, and what makes a load verified: write the index's jarHash into " +
            "<name>.jar.sha256 beside it."
    )
}

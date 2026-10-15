package com.lagradost.cloudstream4.feature.extensions

import com.lagradost.cloudstream3.plugins.BasePlugin
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * An [ExtensionRuntime] that loads **cross-platform extension jars** on a JVM, for `:desktopApp`.
 *
 * ## It cannot load a `.cs3`, and that is the artefact's fault, not the loader's
 *
 * A `.cs3` holds `manifest.json` + `classes.dex` and nothing else — read out of the producer
 * (`recloudstream/gradle`, `tasks/Tasks.kt:143-171`, where `make` is a plain `Zip` that adds the
 * manifest and `compileDex`'s output) and confirmed by `unzip -l` on a published one. That is Dalvik
 * bytecode; no JVM `ClassLoader` can load it, so [install] and [load] here are about the *other*
 * artefact: the plain jar that a cross-platform extension publishes beside it, which the index
 * already advertises as `jarUrl` / `jarHash` / `jarFileSize`
 * ([ExtensionListing.jarUrl] and friends). `KMP-13` §1-2 has both measurements.
 *
 * **The cost is coverage, and a host should say so rather than imply a full catalogue**: 5 of the
 * 235 surveyed catalogue entries publish a jar, and all 5 are the official repository. This is not
 * a `PluginManager` replacement — it is the only loader that works off Android at all.
 *
 * ## Two things that are easy to get wrong and silent when you do
 *
 * - **The parent loader must be this module's own.** `BasePlugin`, `MainAPI` and `APIHolder` have to
 *   be *one* identity across the host and every jar; a loader with a different parent defines its
 *   own `BasePlugin`, `pluginClass.newInstance() as BasePlugin` throws `ClassCastException`, and
 *   `APIHolder.apis` would be a second, invisible list. Hence [parentLoader] below, taken from this
 *   class rather than from a parameter: a caller choosing it wrong is the one failure mode that
 *   cannot be checked for at the point of the mistake.
 * - **`filename` is set before `load()`.** `registerMainAPI` stamps `element.sourcePlugin =
 *   this.filename` (`BasePlugin.kt:22`), and a plugin registers from inside `load()`. Set it after
 *   and every provider carries `sourcePlugin = null`, which registers fine, searches fine, and can
 *   never be unloaded — the ownership key is gone (see [ProviderRegistry]).
 *
 * ## Refusing a hash mismatch, and recomputing rather than trusting the verdict
 *
 * [ExtensionRuntime.install]'s contract says the caller performs the check and may have a policy of
 * its own. This runtime's policy is fixed and not configurable: **a mismatch never reaches disk**.
 * Loading an extension is third-party code execution in this process
 * (`.claude/rules/dependency-security.md`), and "the host asked me to" is not a reason to execute
 * bytes that are provably not the bytes the repository published.
 *
 * It also re-runs the comparison itself whenever [ExtensionListing.jarHash] is present, instead of
 * believing the [ExtensionVerification] it was handed. That is not re-litigating the caller's
 * policy — it is declining to make "did anyone actually check?" a property of the call site. One
 * SHA-256 over at most a few hundred KB is not a cost worth reasoning about.
 *
 * ## What it does not do
 *
 * No downloading (the seam takes bytes), no persistence of what is installed (`:core:storage`'s
 * job), no logging — failures come back as [Result], because a library that prints has decided
 * where a host's output goes. `Plugin`-style extensions, with Android resources and a settings
 * screen, are out of scope and unreachable: `Plugin` is an `:app` class.
 *
 * @param directory where installed jars live, and the only directory this runtime will load or
 *   delete inside. It is created on first [install].
 * @param io the dispatcher the file and class-loading work runs on, injected rather than hardcoded
 *   as `.claude/rules/kotlin-compose-quality.md` asks. The default is the right one — every
 *   operation here is blocking file I/O, plus a third-party `load()` that may do anything — and a
 *   test that wants the work on its own thread can say so.
 */
class JvmExtensionRuntime(
    private val directory: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ExtensionRuntime {

    private class Live(
        val extension: InstalledExtension,
        val plugin: BasePlugin,
        val loader: URLClassLoader,
    )

    /**
     * Serialises the whole lifecycle, keyed by canonical path in [live].
     *
     * Not reentrant, which is a real constraint rather than a note: [uninstall] therefore calls
     * [unloadLocked] directly instead of `unload()`, which would deadlock on itself.
     */
    private val mutex = Mutex()
    private val live = mutableMapOf<String, Live>()

    override suspend fun loaded(): List<InstalledExtension> =
        mutex.withLock { live.values.map { it.extension } }

    override suspend fun install(
        listing: ExtensionListing,
        bytes: ByteArray,
        verification: ExtensionVerification,
    ): Result<InstalledExtension> = catching {
        val fileName = jarFileName(listing.internalName)
        check(verification !is ExtensionVerification.HashMismatch) {
            "refusing to install ${safeName(listing.internalName)}: $verification"
        }
        require(bytes.isNotEmpty()) { "refusing to install ${safeName(listing.internalName)}: no bytes" }
        // Fail closed, deliberately, and note that this is *narrower* than ExtensionHash's own
        // verdicts. `ExtensionHash` distinguishes "I cannot read this hash" (Skipped) from "this is
        // the wrong file" (HashMismatch), and it is right to: they are different facts and the
        // caller owns the policy. **This runtime's policy is that a listing which publishes a hash
        // must verify against it**, whichever way it fails — because the next statement writes
        // third-party executable code to disk, and "the index claimed a guarantee I could not
        // check" is not a state worth writing bytes for.
        //
        // The cost is forward compatibility: if upstream ever publishes `sha512-…`, every install
        // of a listing carrying one fails here rather than silently installing unverified bytes.
        // That is the correct direction to fail in, and the fix is to teach `ExtensionHash` the new
        // algorithm — not to loosen this check. A listing with **no** hash at all is unaffected and
        // installs as Skipped, which is the common case: 230 of 235 surveyed entries publish none.
        listing.jarHash?.let { published ->
            val recomputed = ExtensionHash.verify(bytes, published)
            check(recomputed is ExtensionVerification.Verified) {
                "refusing to install ${safeName(listing.internalName)}: published hash is present " +
                    "but did not verify ($recomputed)"
            }
        }

        mutex.withLock {
            val target = File(directory, fileName)
            check(live[target.canonicalPathOrAbsolute()] == null) {
                "${safeName(listing.internalName)} is loaded; unload it before replacing its jar"
            }
            withContext(io) {
                check(directory.isDirectory || directory.mkdirs()) { "cannot create $directory" }
                // Written beside the target and moved, so a truncated download or a crash mid-write
                // cannot leave something at a path that `load` would happily open.
                val temp = File(directory, "$fileName$TEMP_SUFFIX")
                try {
                    temp.writeBytes(bytes)
                    Files.move(
                        temp.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } finally {
                    // A failed write would otherwise leave the partial file in the directory a host
                    // scans. Already gone after a successful move, where this is a no-op.
                    runCatching { temp.delete() }
                }
                InstalledExtension(
                    internalName = listing.internalName,
                    location = target.canonicalPathOrAbsolute(),
                    version = listing.version,
                    isFromRepository = true,
                    // `sourceUrl` feeds update checks, so it is the URL these bytes came from — the
                    // jar's, not the `.cs3`'s, which this runtime can never use.
                    sourceUrl = listing.jarUrl ?: listing.downloadUrl,
                )
            }
        }
    }

    /**
     * Opens the jar, registers its providers, and keeps the loader so [unload] can close it.
     *
     * Idempotent: loading something already loaded succeeds without touching it, the way
     * `PluginManager.kt:639-642` returns `true` for a plugin it already has. A failure is total —
     * the loader is closed and anything the plugin managed to register before throwing is purged, so
     * a failed load leaves no half-registered provider behind.
     */
    override suspend fun load(extension: InstalledExtension): Result<Unit> = catching {
        mutex.withLock {
            val file = managedFile(extension.location)
            val key = file.path
            if (live.containsKey(key)) return@withLock
            withContext(io) {
                check(file.isFile) { "no jar at ${file.name}" }
                val loader = URLClassLoader(arrayOf(file.toURI().toURL()), parentLoader)
                try {
                    val plugin = ExtensionJar.findPluginClass(file, loader)
                        .getDeclaredConstructor()
                        .newInstance()
                    // Before load(). See the class KDoc; BasePlugin.kt:22 is the line that reads it.
                    plugin.filename = key
                    plugin.load()
                    live[key] = Live(extension.copy(location = key), plugin, loader)
                } catch (failure: Throwable) {
                    ProviderRegistry.purge(key)
                    loader.close()
                    throw failure
                }
            }
        }
    }

    /**
     * Unregisters [extension]'s providers and closes its loader.
     *
     * Succeeds when the extension was not loaded: the postcondition is "its providers do not answer",
     * and [ProviderRegistry.purge] enforces that by `filename` whether this runtime remembers the
     * extension or not — so an unload also cleans up after a host that lost track of one.
     *
     * The classes stay defined, as [ExtensionRuntime.unload]'s own KDoc concedes. Closing the
     * `URLClassLoader` releases the jar's file handle, which is what makes [uninstall] able to delete
     * it on Windows, and nothing more.
     */
    override suspend fun unload(extension: InstalledExtension): Result<Unit> = catching {
        mutex.withLock { unloadLocked(extension) }
    }

    override suspend fun uninstall(extension: InstalledExtension): Result<Unit> = catching {
        mutex.withLock {
            unloadLocked(extension)
            // managedFile() after the unload, not before: a location outside `directory` should
            // still have its providers purged, and only the delete is refused.
            val file = managedFile(extension.location)
            withContext(io) {
                check(!file.exists() || file.delete()) { "could not delete ${file.name}" }
            }
        }
    }

    private fun unloadLocked(extension: InstalledExtension) {
        val key = File(extension.location).canonicalPathOrAbsolute()
        val entry = live.remove(key)
        // `beforeUnload` is third-party and declared `@Throws` (BasePlugin.kt:40). Its failure does
        // not stop the unload and does not fail the call, which is what `:app` settled on
        // (PluginManager.kt:697-701): the providers still have to go, and they do.
        entry?.let { runCatching { it.plugin.beforeUnload() } }
        ProviderRegistry.purge(key)
        entry?.let { runCatching { it.loader.close() } }
    }

    /**
     * [location] as a file inside [directory], or a refusal.
     *
     * [InstalledExtension.location] is opaque by contract and arrives from whatever produced it — a
     * repository index, a stored record, a host's directory scan. Resolving it against the managed
     * directory is what stops `../../…` from turning [load] into "run any jar on this disk" and
     * [uninstall] into "delete any file". Canonicalising both sides first is the part that matters:
     * comparing strings would be fooled by a symlink or a `.` segment.
     */
    private fun managedFile(location: String): File {
        val file = File(location).canonicalFileOrSelf()
        val root = directory.canonicalFileOrSelf()
        require(file.parentFile == root) {
            "refusing ${file.name}: not inside the managed extension directory"
        }
        return file
    }

    /**
     * [internalName] as a jar file name, or a refusal.
     *
     * The name comes from a third-party index and becomes a path, so it is whitelisted rather than
     * escaped: ASCII word characters, dot, dash, underscore, no leading dot, bounded length. The
     * five official names (`YoutubeProvider`, `TwitchProvider`, …) are well inside it.
     */
    private fun jarFileName(internalName: String): String {
        require(internalName.matches(SAFE_NAME) && !internalName.startsWith('.')) {
            "refusing an internalName that is not a safe file name: '${safeName(internalName)}'"
        }
        return "$internalName$JAR_SUFFIX"
    }

    /**
     * [name] with anything a terminal might act on removed.
     *
     * The strings in these messages come from a repository index and end up in a host's log. A
     * rejected name is exactly the case where it is not a tame identifier, so the message carrying
     * it must not be able to smuggle an escape sequence or a forged line into the log it lands in.
     */
    private fun safeName(name: String): String =
        name.take(MAX_NAME_LENGTH).filter { it.isLetterOrDigit() || it in SAFE_PUNCTUATION }

    private companion object {
        /**
         * The loader that defines `BasePlugin` for this process.
         *
         * `Class.getClassLoader()` is null for a class the bootstrap loader defined, which cannot
         * happen for this one; the fallback is there because `URLClassLoader(urls, null)` would
         * silently mean "bootstrap only" and strand every `:library` type.
         */
        private val parentLoader: ClassLoader =
            JvmExtensionRuntime::class.java.classLoader ?: ClassLoader.getSystemClassLoader()

        private const val JAR_SUFFIX = ".jar"
        private const val TEMP_SUFFIX = ".part"
        private const val MAX_NAME_LENGTH = 64
        private const val SAFE_PUNCTUATION = "._- "
        private val SAFE_NAME = Regex("[A-Za-z0-9._-]{1,$MAX_NAME_LENGTH}")

        /** Canonicalisation touches the filesystem and can fail; the absolute path is the fallback. */
        private fun File.canonicalFileOrSelf(): File = runCatching { canonicalFile }.getOrDefault(absoluteFile)

        private fun File.canonicalPathOrAbsolute(): String = canonicalFileOrSelf().path
    }
}

/**
 * [runCatching] without swallowing cancellation.
 *
 * Every member here is `suspend`, and `runCatching` would turn a cancelled coroutine into a
 * `Result.failure(CancellationException)` — a caller would read a cancelled install as a failed one
 * and the scope would never learn it was cancelled.
 */
private inline fun <T> catching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: Throwable) {
    Result.failure(failure)
}

package com.lagradost.cloudstream4.extensions

import android.content.Context
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream4.feature.extensions.ExtensionListing
import com.lagradost.cloudstream4.feature.extensions.ExtensionRuntime
import com.lagradost.cloudstream4.feature.extensions.ExtensionVerification
import com.lagradost.cloudstream4.feature.extensions.InstalledExtension
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ExtensionRuntime] over `PluginManager`, through its existing public members only.
 *
 * `PluginManager.kt` is not edited. Any member added to it, `internal` included, changes its block
 * in `app/api/classes-jar.api`, and it is live on every Android startup. So nothing calls this class
 * yet, and adding it cannot change how plugins load today.
 *
 * ## Where the seam and `PluginManager` differ
 *
 * `KMP-11` in `docs/tasks/kmp.md` has each of these with the options.
 *
 * - [install] refuses. `PluginManager` has no way to take bytes, and a listing lacks the repository
 *   index URL that the install path is derived from.
 * - [load] can refuse a plugin that `PluginManager` could load, when another plugin's name selects
 *   first ([apiNameForLoad]). `loadPlugin` is private; the public route selects by name.
 * - [load]'s failure has no cause, because `loadPlugin` returns a `Boolean`. It has already logged
 *   and shown the user a toast by then.
 * - A plugin whose own `load` throws stays in `PluginManager.plugins`, with whatever it registered,
 *   so [loaded] goes on listing it after [load] returned a failure. `JvmExtensionRuntime` purges in
 *   that case; `PluginManager` does not, and this reports what is true rather than correcting it.
 * - [uninstall] of a plugin whose file is already gone fails, and leaves the saved record, because
 *   `PluginManager.deletePlugin` only removes the record after a successful delete.
 *
 * ## Safe mode
 *
 * `PluginManager` does not enforce safe mode: `MainActivity` checks before calling it. This is a new
 * way in, so [load] checks for itself. [unload] and [uninstall] do not, because removing a plugin
 * is how a user gets out of safe mode.
 *
 * ## The Context is what a plugin receives
 *
 * `Plugin.load(Context)` gets whatever [context] returns, and the caller decides what that is:
 * `MainActivity` passes itself, `SubscriptionWorkManager` passes a non-Activity `Context`. It is a
 * provider so that this class never holds an `Activity`. `null` refuses the load.
 *
 * ## Not held: `PluginManager.lock`
 *
 * `loadPlugin` and `deletePlugin` take that `Mutex` themselves and it is not reentrant, so holding
 * it around either call would deadlock.
 */
class AndroidExtensionRuntime internal constructor(
    private val port: PluginManagerPort,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ExtensionRuntime {

    constructor(context: () -> Context?) : this(LivePluginManagerPort(context))

    override suspend fun loaded(): List<InstalledExtension> = withContext(io) {
        loadedExtensions(port.loadedPaths(), port.savedOnline(), port.savedLocal())
    }

    /** Always a failure, and nothing is written. See the class KDoc. */
    override suspend fun install(
        listing: ExtensionListing,
        bytes: ByteArray,
        verification: ExtensionVerification,
    ): Result<InstalledExtension> = Result.failure(
        UnsupportedOperationException(
            "AndroidExtensionRuntime cannot install: use PluginManager.downloadPlugin. The install " +
                "path needs the repository index URL, which a listing does not carry, and saving " +
                "the record is private to PluginManager."
        )
    )

    override suspend fun load(extension: InstalledExtension): Result<Unit> = catching {
        withContext(io) {
            val location = extension.location
            check(!port.isSafeMode()) { "safe mode is on, so no extension may load" }
            require(isManagedLocation(location, port.managedRoots())) {
                "refusing ${safeForLog(File(location).name)}: not in a managed extension directory"
            }
            // `loadPlugin` answers `true` for a plugin it already has, but only after building a
            // new loader and running the plugin's constructor. There is no need to do that here.
            if (location in port.loadedPaths()) return@withContext

            val apiName = apiNameForLoad(location, port.savedOnline(), port.savedLocal())
            check(port.loadByApiName(apiName)) {
                "PluginManager could not load ${safeForLog(apiName)}; the reason is in its log"
            }
            check(location in port.loadedPaths()) {
                "PluginManager reported loading ${safeForLog(apiName)}, but not from this location"
            }
        }
    }

    /** Succeeds when not loaded: `unloadPlugin` logs that and returns. The path is passed as given. */
    override suspend fun unload(extension: InstalledExtension): Result<Unit> = catching {
        withContext(io) { port.unload(extension.location) }
    }

    /**
     * Unloads first, as the seam says, although `deletePlugin` on its own deletes first and leaves
     * the plugin loaded if that fails. Its later unload is then a logged no-op.
     */
    override suspend fun uninstall(extension: InstalledExtension): Result<Unit> = catching {
        withContext(io) {
            val location = extension.location
            port.unload(location)
            // After the unload: an unmanaged location still loses its providers, only the delete
            // is refused, the same order as JvmExtensionRuntime.
            require(isManagedLocation(location, port.managedRoots())) {
                "refusing to delete ${safeForLog(File(location).name)}: " +
                    "not in a managed extension directory"
            }
            check(port.delete(File(location))) {
                "could not delete ${safeForLog(File(location).name)}"
            }
        }
    }

    /** [runCatching] without turning a cancelled coroutine into a failed operation. */
    private inline fun <T> catching(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        Result.failure(failure)
    }
}

/** The members of `PluginManager` this runtime uses, so its logic can be tested without Android. */
internal interface PluginManagerPort {
    fun savedOnline(): List<PluginData>
    fun savedLocal(): List<PluginData>
    fun loadedPaths(): List<String>
    fun isSafeMode(): Boolean
    fun managedRoots(): List<File>
    suspend fun loadByApiName(apiName: String): Boolean
    fun unload(absolutePath: String)
    suspend fun delete(file: File): Boolean
}

private class LivePluginManagerPort(private val context: () -> Context?) : PluginManagerPort {
    override fun savedOnline() = PluginManager.getPluginsOnline().toList()

    override fun savedLocal() = PluginManager.getPluginsLocal().toList()

    // `loadPlugin` writes this map under its own monitor, from parallel coroutines.
    override fun loadedPaths() = synchronized(PluginManager.plugins) {
        PluginManager.plugins.keys.toList()
    }

    override fun isSafeMode() = PluginManager.isSafeMode()

    /** Where `getPluginPath` and `loadAllLocalPlugins` put plugins. Empty without a [context]. */
    override fun managedRoots(): List<File> {
        val host = context() ?: return emptyList()
        return listOfNotNull(
            File(host.filesDir, RepositoryManager.ONLINE_PLUGINS_FOLDER),
            host.getExternalFilesDir(null)?.let { File(it, "plugins") },
        )
    }

    override suspend fun loadByApiName(apiName: String): Boolean {
        val host = checkNotNull(context()) { "no Context to hand a plugin" }
        return PluginManager.loadSinglePlugin(host, apiName)
    }

    override fun unload(absolutePath: String) = PluginManager.unloadPlugin(absolutePath)

    override suspend fun delete(file: File) = PluginManager.deletePlugin(file)
}

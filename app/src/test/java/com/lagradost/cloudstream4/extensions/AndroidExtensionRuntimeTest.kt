package com.lagradost.cloudstream4.extensions

import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream4.feature.extensions.ExtensionListing
import com.lagradost.cloudstream4.feature.extensions.ExtensionStatus
import com.lagradost.cloudstream4.feature.extensions.ExtensionVerification
import com.lagradost.cloudstream4.feature.extensions.InstalledExtension
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * The runtime's decisions, against a fake of the `PluginManager` members it uses.
 *
 * `PluginManager` itself needs a device, so nothing here shows that a real plugin loads.
 */
class AndroidExtensionRuntimeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class FakePort(val root: File) : PluginManagerPort {
        val calls = mutableListOf<String>()
        var online = listOf<PluginData>()
        var local = listOf<PluginData>()
        var loaded = mutableListOf<String>()
        var safeMode = false
        var loadResult: (String) -> Boolean = { true }
        var deleteResult = true
        var unloadFailure: Throwable? = null

        override fun savedOnline() = online
        override fun savedLocal() = local
        override fun loadedPaths() = loaded.toList()
        override fun isSafeMode() = safeMode
        override fun managedRoots() = listOf(root)

        override suspend fun loadByApiName(apiName: String): Boolean {
            calls += "load:$apiName"
            return loadResult(apiName)
        }

        override fun unload(absolutePath: String) {
            calls += "unload:$absolutePath"
            unloadFailure?.let { throw it }
            loaded.remove(absolutePath)
        }

        override suspend fun delete(file: File): Boolean {
            calls += "delete:${file.path}"
            return deleteResult
        }
    }

    private fun port() = FakePort(temp.root)
    private fun FakePort.runtime() = AndroidExtensionRuntime(this, Dispatchers.Unconfined)
    private fun path(name: String) = File(temp.root, name).path

    private fun installed(location: String, name: String = "Foo") =
        InstalledExtension(name, location, 1, true, "https://x/$name")

    private fun record(location: String, name: String = "Foo") =
        PluginData(name, "https://x/$name", true, location, 1)

    /** A load that behaves like `loadPlugin`: on success the path appears in `plugins`. */
    private fun FakePort.loadsInto(location: String) {
        loadResult = { loaded += location; true }
    }

    @Test
    fun loadSelectsByApiNameAndChecksTheLocationArrived() = runBlocking {
        val location = path("repo/foo.cs3")
        val port = port().apply {
            online = listOf(record(location, "FooProvider"))
            loadsInto(location)
        }
        assertTrue(port.runtime().load(installed(location)).isSuccess)
        assertEquals(listOf("load:Foo"), port.calls)
    }

    /**
     * Startup's gate is in `MainActivity`, not `PluginManager`, so this is the only thing between a
     * caller of the seam and the plugin that safe mode exists to keep out.
     */
    @Test
    fun safeModeRefusesLoadBeforeAnyPluginCodeCanRun() = runBlocking {
        val location = path("foo.cs3")
        val port = port().apply {
            online = listOf(record(location))
            safeMode = true
        }
        assertTrue(port.runtime().load(installed(location)).isFailure)
        assertEquals(emptyList<String>(), port.calls)
    }

    @Test
    fun safeModeDoesNotStopUnloadOrUninstall() = runBlocking {
        val location = path("foo.cs3")
        val port = port().apply { safeMode = true }
        assertTrue(port.runtime().unload(installed(location)).isSuccess)
        assertTrue(port.runtime().uninstall(installed(location)).isSuccess)
    }

    @Test
    fun loadRefusesALocationOutsideTheManagedRoots() = runBlocking {
        val outside = "/somewhere/else/foo.cs3"
        val port = port().apply { online = listOf(record(outside)) }
        assertTrue(port.runtime().load(installed(outside)).isFailure)
        assertEquals(emptyList<String>(), port.calls)
    }

    @Test
    fun loadRefusesWhenTheNameWouldSelectAnotherRepositorysPlugin() = runBlocking {
        val first = path("repoA/foo.cs3")
        val second = path("repoB/foo.cs3")
        val port = port().apply { online = listOf(record(first), record(second)) }
        assertTrue(port.runtime().load(installed(second)).isFailure)
        assertEquals(emptyList<String>(), port.calls)
    }

    @Test
    fun loadOfAnAlreadyLoadedPluginSucceedsWithoutCallingPluginManager() = runBlocking {
        val location = path("foo.cs3")
        val port = port().apply {
            online = listOf(record(location))
            loaded += location
        }
        assertTrue(port.runtime().load(installed(location)).isSuccess)
        assertEquals(emptyList<String>(), port.calls)
    }

    @Test
    fun loadFailsWhenPluginManagerAnswersFalse() = runBlocking {
        val location = path("foo.cs3")
        val port = port().apply {
            online = listOf(record(location))
            loadResult = { false }
        }
        assertTrue(port.runtime().load(installed(location)).isFailure)
    }

    @Test
    fun loadFailsWhenTrueButTheLocationIsNotLoaded() = runBlocking {
        val location = path("foo.cs3")
        val port = port().apply { online = listOf(record(location)) }
        assertTrue(port.runtime().load(installed(location)).isFailure)
    }

    /** `loadPlugin` adds to `plugins` before calling the plugin's `load`, and keeps it if that throws. */
    @Test
    fun aPluginWhoseLoadThrewIsAFailureAndIsStillReportedAsLoaded() = runBlocking {
        val location = path("foo.cs3")
        val port = port().apply {
            online = listOf(record(location))
            loadResult = { loaded += location; false }
        }
        val runtime = port.runtime()
        assertTrue(runtime.load(installed(location)).isFailure)
        assertEquals(listOf(location), runtime.loaded().map { it.location })
        assertEquals(listOf("load:Foo"), port.calls)
    }

    @Test
    fun unloadPassesTheLocationUnchanged() = runBlocking {
        val linked = File(temp.root, "linked")
        val real = temp.newFolder("real")
        Files.createSymbolicLink(linked.toPath(), real.toPath())
        val location = File(linked, "foo.cs3").path

        val port = port()
        assertTrue(port.runtime().unload(installed(location)).isSuccess)
        assertEquals(listOf("unload:$location"), port.calls)
    }

    @Test
    fun unloadCarriesAThrownFailure() = runBlocking {
        val port = port().apply { unloadFailure = IllegalStateException("boom") }
        assertEquals("boom", port.runtime().unload(installed(path("a"))).exceptionOrNull()?.message)
    }

    @Test
    fun uninstallUnloadsThenDeletes() = runBlocking {
        val location = path("foo.cs3")
        val port = port()
        assertTrue(port.runtime().uninstall(installed(location)).isSuccess)
        assertEquals(listOf("unload:$location", "delete:$location"), port.calls)
    }

    @Test
    fun aFailedDeleteIsAFailureAndThePluginIsAlreadyUnloaded() = runBlocking {
        val location = path("foo.cs3")
        val port = port().apply {
            deleteResult = false
            loaded += location
        }
        val runtime = port.runtime()
        assertTrue(runtime.uninstall(installed(location)).isFailure)
        assertEquals(emptyList<InstalledExtension>(), runtime.loaded())
    }

    /** `deletePlugin` takes any `File`, so this check is what stops the seam deleting anything. */
    @Test
    fun uninstallNeverDeletesOutsideTheManagedRoots() = runBlocking {
        val outside = "/somewhere/else/foo.cs3"
        val port = port()
        assertTrue(port.runtime().uninstall(installed(outside)).isFailure)
        assertEquals(listOf("unload:$outside"), port.calls)
    }

    @Test
    fun installRefusesAndTouchesNothing() = runBlocking {
        val port = port()
        val listing = ExtensionListing(
            internalName = "Foo",
            name = "Foo",
            downloadUrl = "https://x/foo.cs3",
            status = ExtensionStatus.Ok,
            version = 1,
            apiVersion = 1,
        )
        val result = port.runtime().install(listing, byteArrayOf(1), ExtensionVerification.Verified)
        assertTrue(result.exceptionOrNull() is UnsupportedOperationException)
        assertEquals(emptyList<String>(), port.calls)
        assertEquals(emptyList<String>(), temp.root.list()!!.toList())
    }

    @Test
    fun cancellationIsNotReportedAsFailure() {
        val port = port().apply { unloadFailure = CancellationException("cancelled") }
        assertThrows(CancellationException::class.java) {
            runBlocking { port.runtime().unload(installed(path("a"))) }
        }
    }
}

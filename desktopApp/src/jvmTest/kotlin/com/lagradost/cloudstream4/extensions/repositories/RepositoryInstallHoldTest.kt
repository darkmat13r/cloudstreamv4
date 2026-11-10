package com.lagradost.cloudstream4.extensions.repositories

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream4.extensions.ExtensionApprovals
import com.lagradost.cloudstream4.extensions.LoadState
import com.lagradost.cloudstream4.extensions.PlainApi
import com.lagradost.cloudstream4.extensions.PlainPlugin
import com.lagradost.cloudstream4.extensions.ReviewAction
import com.lagradost.cloudstream4.extensions.ReviewStatus
import com.lagradost.cloudstream4.extensions.SandboxedExtensions
import com.lagradost.cloudstream4.extensions.writeFixtureJar
import com.lagradost.cloudstream4.feature.extensions.ExtensionHash
import com.lagradost.cloudstream4.feature.extensions.ExtensionListing
import com.lagradost.cloudstream4.feature.extensions.ExtensionStatus
import com.lagradost.cloudstream4.feature.extensions.sandbox.ExtensionScanner
import com.lagradost.cloudstream4.feature.extensions.sandbox.HostLauncher
import com.lagradost.cloudstream4.extensions.ExtensionDecisions
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * `KMP-110b`, through the real pipeline: a file a repository installed is held even when the real scanner finds nothing,
 * and runs only once it is allowed. The plugin is the clean test fixture, built here; no real plugin is involved.
 * Skips where there is no `sandbox-exec`, because nothing is loaded on those platforms at all.
 */
class RepositoryInstallHoldTest {
    private lateinit var home: File
    private lateinit var directory: File
    private lateinit var extensions: SandboxedExtensions
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("install-hold").toFile().canonicalFile
        directory = File(home, "extensions").apply { mkdirs() }
        extensions = SandboxedExtensions(directory)
    }

    @AfterTest
    fun tearDown() {
        extensions.providers.forEach {
            APIHolder.allProviders.remove(it)
            APIHolder.removePluginMapping(it)
        }
        extensions.close()
        scope.cancel()
        home.deleteRecursively()
    }

    /** Skipped, and said to be, where there is no `sandbox-exec`: nothing is loaded on those platforms at all. */
    private fun needsSandbox() = assumeTrue("no sandbox-exec on this platform", HostLauncher.forThisProcess() != null)

    private val decisions get() = ExtensionDecisions.fileFor(directory)

    /** Damage the decisions file the way a bad restore or a disk error would: it fails its checksum. */
    private fun damageDecisions() = decisions.appendText("damaged\n")

    private fun restart() {
        extensions.providers.forEach {
            APIHolder.allProviders.remove(it)
            APIHolder.removePluginMapping(it)
        }
        extensions.close()
        extensions = SandboxedExtensions(directory)
    }

    /** The clean fixture, installed as a repository's jar under [internalName]. */
    private fun installPlain(internalName: String = "Plain"): String {
        val jar = File(home, "built.jar")
        // Padded by name, so two installs are two different files: two identical files in the folder race for one
        // store entry, which is not what these tests are about.
        writeFixtureJar(jar, PlainPlugin::class.java, PlainApi::class.java, padding = internalName)
        assertEquals(emptyList(), ExtensionScanner.scan(jar).findings, "the fixture must scan clean, or this proves nothing")
        val bytes = jar.readBytes()
        val listing = ExtensionListing(
            internalName = internalName, name = internalName, downloadUrl = "https://files.example/plain.cs3",
            status = ExtensionStatus.Ok, version = 1, apiVersion = 1,
            jarUrl = "https://files.example/plain.jar", jarHash = ExtensionHash.of(bytes), jarFileSizeBytes = bytes.size.toLong(),
        )
        val installer = RepositoryInstaller(directory, fetch = { _, _ -> Fetched.Bytes(bytes) })
        return assertIs<InstallResult.Installed>(runBlocking { installer.install("https://files.example/repo.json", listing) }).fileName
    }

    @Test
    fun `a clean file from a repository is held at launch, and runs once allowed`() {
        needsSandbox()
        val fileName = installPlain()
        val report = runBlocking { extensions.loadInstalled() }
        val load = report.loads.single()
        assertEquals(LoadState.Held, load.state, load.outcome)
        assertTrue(load.fromRepository)
        assertEquals(0, load.scan?.warnings)
        assertTrue(extensions.providers.none { it.name == "Plain" }, "it ran before it was allowed")

        val approvals = ExtensionApprovals(extensions, report, scope)
        assertEquals(ReviewStatus.Held, approvals.state.value.reviews.single().status)
        approvals.onAction(ReviewAction.Allow(fileName))
        runBlocking {
            withTimeout(TIMEOUT_MILLIS) {
                while (approvals.state.value.reviews.single().status != ReviewStatus.Allowed) delay(POLL_MILLIS)
            }
        }
        assertTrue(extensions.providers.any { it.name == "Plain" })
    }

    @Test
    fun `installed while the app runs, a refresh takes it through the pipeline and starts nothing`() {
        needsSandbox()
        runBlocking { extensions.loadInstalled() }
        val fileName = installPlain()
        val load = runBlocking { extensions.refresh(fileName) }
        assertEquals(LoadState.Held, load?.state, load?.outcome)
        assertTrue(extensions.providers.none { it.name == "Plain" })
    }

    @Test
    fun `with the decisions file damaged, a repository's file is held at launch`() {
        needsSandbox()
        installPlain()
        damageDecisions()
        val report = runBlocking { extensions.loadInstalled() }
        assertTrue(report.decisionsUnreadable)
        assertEquals(LoadState.Held, report.loads.single().state, report.loads.single().outcome)
        assertTrue(extensions.providers.none { it.name == "Plain" }, "it started without Allow")
    }

    /**
     * The second install replaces the damaged file (kept aside as `.damaged`), and with it the first install's Pending.
     * The first is still held, by its installed entry.
     */
    @Test
    fun `repairing a damaged file by choosing again leaves every other repository install held`() {
        needsSandbox()
        val first = installPlain("Plain")
        damageDecisions()
        installPlain("Second")
        assertTrue(ExtensionDecisions.damagedFileFor(decisions).isFile)
        assertTrue(!ExtensionDecisions(decisions).read().unreadable, "the second install repaired the file")
        restart()
        val loads = runBlocking { extensions.loadInstalled() }.loads
        assertEquals(LoadState.Held, loads.single { it.fileName == first }.state)
        assertTrue(loads.all { it.state == LoadState.Held }, loads.joinToString { "${it.fileName}=${it.state}" })
        assertTrue(extensions.providers.none { it.name == "Plain" })
    }

    @Test
    fun `with the decisions file damaged, a clean side-loaded file is held too`() {
        needsSandbox()
        writeFixtureJar(File(directory, "SideLoaded.jar"), PlainPlugin::class.java, PlainApi::class.java)
        assertEquals(LoadState.Running, runBlocking { extensions.loadInstalled() }.loads.single().state, "runs while readable")
        restart()
        damageDecisions()
        val load = runBlocking { extensions.loadInstalled() }.loads.single()
        assertEquals(LoadState.Held, load.state, load.outcome)
        assertTrue(extensions.providers.none { it.name == "Plain" })
    }

    /** `#336` review: the installed list unreadable holds everything not allowed, as the decisions file does. */
    @Test
    fun `with the installed list damaged, a repository's file and a clean side-loaded one are both held`() {
        needsSandbox()
        val fromRepository = installPlain()
        writeFixtureJar(File(directory, "SideLoaded.jar"), PlainPlugin::class.java, PlainApi::class.java, padding = "side-loaded")
        InstalledFromRepositories.fileFor(directory).writeText("garbage")
        val loads = runBlocking { extensions.loadInstalled() }.loads
        assertEquals(LoadState.Held, loads.single { it.fileName == fromRepository }.state)
        assertEquals(LoadState.Held, loads.single { it.fileName == "SideLoaded.jar" }.state)
        assertTrue(extensions.providers.none { it.name == "Plain" }, "something started")
    }

    private companion object {
        const val POLL_MILLIS = 50L
        const val TIMEOUT_MILLIS = 60_000L
    }
}

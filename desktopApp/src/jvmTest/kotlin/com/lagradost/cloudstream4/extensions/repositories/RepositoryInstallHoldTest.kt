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

    private val hasSandbox get() = HostLauncher.forThisProcess() != null

    /** The clean fixture, installed as a repository's jar. */
    private fun installPlain(): String {
        val jar = File(home, "built.jar")
        writeFixtureJar(jar, PlainPlugin::class.java, PlainApi::class.java)
        assertEquals(emptyList(), ExtensionScanner.scan(jar).findings, "the fixture must scan clean, or this proves nothing")
        val bytes = jar.readBytes()
        val listing = ExtensionListing(
            internalName = "Plain", name = "Plain", downloadUrl = "https://files.example/plain.cs3",
            status = ExtensionStatus.Ok, version = 1, apiVersion = 1,
            jarUrl = "https://files.example/plain.jar", jarHash = ExtensionHash.of(bytes), jarFileSizeBytes = bytes.size.toLong(),
        )
        val installer = RepositoryInstaller(directory, fetch = { _, _ -> Fetched.Bytes(bytes) })
        return assertIs<InstallResult.Installed>(runBlocking { installer.install("https://files.example/repo.json", listing) }).fileName
    }

    @Test
    fun `a clean file from a repository is held at launch, and runs once allowed`() {
        if (!hasSandbox) return
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
        if (!hasSandbox) return
        runBlocking { extensions.loadInstalled() }
        val fileName = installPlain()
        val load = runBlocking { extensions.refresh(fileName) }
        assertEquals(LoadState.Held, load?.state, load?.outcome)
        assertTrue(extensions.providers.none { it.name == "Plain" })
    }

    private companion object {
        const val POLL_MILLIS = 50L
        const val TIMEOUT_MILLIS = 60_000L
    }
}

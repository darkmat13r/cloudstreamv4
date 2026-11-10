package com.lagradost.cloudstream4.extensions

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream4.feature.extensions.sandbox.HostLauncher
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Writes what it should not, then fails to load with more of it in the message. Made up for `BUG-83`. */
@CloudstreamPlugin
class LeakyLoadPlugin : BasePlugin() {
    override fun load() {
        println("stdout GET https://viewer:hunter2@media.example/watch?q=SECRET_QUERY")
        System.err.println("stderr token Zq8Xv3Lm9Pt2Rk7Wd4Hs6Nb1Yc for viewer.one@mail.example from 192.0.2.44")
        throw IllegalStateException("load failed at https://media.example/?k=SECRET_QUERY in /Users/sample-user/x")
    }
}

/**
 * `BUG-83`: the app's report of a plugin that failed to start, which it prints at every launch, holds our words, the
 * failure's class and the stderr counts, and none of what the plugin wrote or said. A real host under the real
 * Seatbelt policy; skips where there is no `sandbox-exec`.
 */
class ExtensionOutputTest {
    private lateinit var home: File
    private lateinit var directory: File
    private lateinit var extensions: SandboxedExtensions
    private val leaky: File get() = File(directory, "Leaky.jar")

    private val leaked = listOf(
        "SECRET_QUERY", "hunter2", "/watch", "Zq8Xv3Lm9Pt2Rk7Wd4Hs6Nb1Yc", "viewer.one", "192.0.2.44", "sample-user",
        "load failed",
    )

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("output").toFile().canonicalFile
        directory = File(home, "extensions").apply { mkdirs() }
        extensions = SandboxedExtensions(directory)
        writeFixtureJar(leaky, LeakyLoadPlugin::class.java)
        ExtensionDecisions(ExtensionDecisions.fileFor(directory)).record(leaky.name, sha256Of(leaky), ExtensionChoice.Allow)
    }

    @AfterTest
    fun tearDown() {
        extensions.close()
        home.deleteRecursively()
    }

    @Test
    fun `a plugin that fails to start is reported without anything it wrote or said`() {
        if (HostLauncher.forThisProcess() == null) return
        runBlocking {
            withTimeout(60_000) {
                val report = extensions.loadInstalled()
                val load = report.loads.single { it.fileName == leaky.name }
                assertEquals(LoadState.Failed, load.state, load.outcome)
                // Positive control: this is the start failure's line, with our words and the counts.
                assertTrue("plugin failed: IllegalStateException" in load.outcome, load.outcome)
                assertTrue(Regex("stderr: \\d+ lines, \\d+ bytes").containsMatchIn(load.outcome), load.outcome)
                val printed = report.lines().joinToString("\n")
                leaked.forEach { secret ->
                    assertFalse(secret in load.outcome, "$secret in ${load.outcome}")
                    assertFalse(secret in printed, "$secret in $printed")
                }
            }
        }
    }
}

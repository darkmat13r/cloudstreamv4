package com.lagradost.cloudstream4.extensions

import com.lagradost.cloudstream4.feature.extensions.sandbox.HostLauncher
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * `BUG-97`: a file in the extensions directory that cannot be read. Its outcome is shown to the person and printed, so
 * it says what happened in fixed words and never repeats the exception's message, which names the file's path and so
 * the home directory. Only the test fixture is used, and nothing is started.
 */
class UnreadableExtensionTest {
    private lateinit var home: File
    private lateinit var directory: File
    private lateinit var unreadable: File

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("unreadable-extension").toFile().canonicalFile
        directory = File(home, "extensions").apply { mkdirs() }
        unreadable = File(directory, "Plain.jar")
    }

    @AfterTest
    fun tearDown() {
        unreadable.setReadable(true)
        home.deleteRecursively()
    }

    @Test
    fun `a file that cannot be read is refused in words that name no path, and the others still load`() {
        assumeTrue("no sandbox-exec on this platform", HostLauncher.forThisProcess() != null)
        writeFixtureJar(unreadable, PlainPlugin::class.java, PlainApi::class.java, padding = "unreadable")
        writeFixtureJar(File(directory, "Fine.jar"), PlainPlugin::class.java, PlainApi::class.java, padding = "fine")
        // No hash is published for it, so the first read of its bytes is the store's own hash.
        assertTrue(unreadable.setReadable(false, false), "could not make the file unreadable")
        assumeTrue("this user can read the file anyway", !unreadable.canRead())

        val loads = runBlocking { SandboxedExtensions(directory).scanInstalled() }.loads
        val refused = loads.single { it.fileName == "Plain.jar" }
        assertEquals(LoadState.Failed, refused.state, refused.outcome)
        assertEquals("not loaded: the extension could not be read", refused.outcome)
        assertFalse(home.name in refused.outcome, "the outcome names a path: ${refused.outcome}")
        assertEquals(LoadState.Scanned, loads.single { it.fileName == "Fine.jar" }.state)
    }
}

package com.lagradost.cloudstream4.extensions

import com.lagradost.cloudstream4.feature.extensions.sandbox.HostLauncher
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * `BUG-89`: the extension store under files that arrive at once. Two identical files share one `.store` entry, and
 * loading them together must not let one fail the other, or the launch. Only the test fixture is used, and nothing is
 * started: a scan stops before a host.
 */
class StoreRaceTest {
    private lateinit var home: File
    private lateinit var directory: File

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("store-race").toFile().canonicalFile
        directory = File(home, "extensions").apply { mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        File(directory, ".store").setWritable(true)
        home.deleteRecursively()
    }

    /** The store is only written where there is a sandbox to run what it holds. */
    private fun needsSandbox() = assumeTrue("no sandbox-exec on this platform", HostLauncher.forThisProcess() != null)

    @Test
    fun `two identical files loading at once both reach the scan`() {
        needsSandbox()
        val first = File(directory, "Plain.jar")
        writeFixtureJar(first, PlainPlugin::class.java, PlainApi::class.java)
        // Several names, one content: they all want the same store entry at the same moment.
        listOf("Copy", "Again", "Twin").forEach { first.copyTo(File(directory, "$it.jar")) }
        repeat(ROUNDS) { round ->
            File(directory, ".store").deleteRecursively()
            val loads = runBlocking { SandboxedExtensions(directory).scanInstalled() }.loads
            assertEquals(4, loads.size)
            assertTrue(loads.all { it.state == LoadState.Scanned }, "round $round: " + loads.joinToString { "${it.fileName}=${it.state} ${it.outcome}" })
        }
        assertEquals(1, File(directory, ".store").list()!!.count { it.endsWith(".jar") }, "one entry for one content")
    }

    @Test
    fun `a store that cannot be written refuses that file, and the launch goes on`() {
        needsSandbox()
        writeFixtureJar(File(directory, "Plain.jar"), PlainPlugin::class.java, PlainApi::class.java)
        val store = File(directory, ".store").apply { mkdirs() }
        assertTrue(store.setWritable(false), "could not make the store read-only")
        val load = runBlocking { SandboxedExtensions(directory).scanInstalled() }.loads.single()
        assertEquals(LoadState.Failed, load.state, load.outcome)
    }

    private companion object {
        /** The race is between file copies of a few kilobytes, so it is tried many times rather than once. */
        const val ROUNDS = 40
    }
}

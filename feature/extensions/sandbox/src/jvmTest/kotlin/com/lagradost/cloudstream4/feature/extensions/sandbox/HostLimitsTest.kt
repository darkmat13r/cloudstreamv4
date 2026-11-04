package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** A plugin that misbehaves, through a real host, against limits short enough to wait for. */
class HostLimitsTest {
    private lateinit var root: File
    private lateinit var data: File
    private lateinit var jar: File

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("limits").toFile().canonicalFile
        data = File(root, "data").apply { mkdirs() }
        jar = File(root, "store/hostile.jar").apply { parentFile.mkdirs() }
        JarOutputStream(jar.outputStream()).use { out ->
            listOf(HostilePlugin::class.java, HostileApi::class.java).forEach { type ->
                val path = type.name.replace('.', '/') + ".class"
                out.putNextEntry(JarEntry(path))
                type.classLoader.getResourceAsStream(path)!!.use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun hosted(limits: HostLimits = HostLimits(), block: suspend (PluginHostClient) -> Unit) {
        val launcher = HostLauncher.forThisProcess() ?: return
        runBlocking {
            val (client, providers) = PluginHostClient.start(launcher, jar, data, "hostile", limits)
            try {
                providers.getOrElse { throw AssertionError("host: ${client.hostOutput}", it) }
                block(client)
            } finally {
                client.close()
            }
        }
    }

    private suspend fun PluginHostClient.attempt(what: String) =
        search("Hostile", "attempt:$what", 1).items.single().name

    private suspend fun PluginHostClient.awaitEnd(millis: Long) = withTimeout(millis) {
        // Not by calling it: a call waiting is what lets a plugin keep working.
        while (endedBecause == null) delay(POLL_MILLIS)
    }

    @Test
    fun `a plugin that spins with nothing asked of it is stopped, and the caller just sees a failure`() =
        hosted(HostLimits(busyMillis = 4_000, periodMillis = 500)) { host ->
            val started = System.currentTimeMillis()
            assertEquals("spinning", host.attempt("spin|8"))
            host.awaitEnd(WAIT_MILLIS)
            println("LIMIT spin: stopped after ${System.currentTimeMillis() - started} ms: ${host.endedBecause}")
            assertTrue("kept working" in host.endedBecause.orEmpty(), host.endedBecause)
            assertFailsWith<Exception> { host.attempt("cwd|") }
        }

    @Test
    fun `filling the heap gets an error in the plugin, and its host still answers`() = hosted { host ->
        assertTrue("OutOfMemoryError" in host.attempt("heap|"))
        assertTrue(host.attempt("cwd|").isNotEmpty())
        println("LIMIT heap: the host survived")
    }

    @Test
    fun `direct memory has a cap too`() = hosted { host ->
        val outcome = host.attempt("direct|")
        println("LIMIT direct: $outcome")
        assertTrue("OutOfMemoryError" in outcome, outcome)
        assertTrue(host.attempt("cwd|").isNotEmpty())
    }

    @Test
    fun `memory past the limit is stopped by the watch`() =
        hosted(HostLimits(maxResidentBytes = 300L * 1024 * 1024, periodMillis = 250)) { host ->
            runCatching { host.attempt("heap|") }
            host.awaitEnd(WAIT_MILLIS)
            println("LIMIT memory: ${host.endedBecause}")
            assertTrue("memory" in host.endedBecause.orEmpty(), host.endedBecause)
        }

    @Test
    fun `thousands of threads cost the plugin and nobody else`() = hosted { host ->
        val outcome = runCatching { host.attempt("threads|20000") }
        println("LIMIT threads: ${outcome.getOrNull() ?: outcome.exceptionOrNull()?.message}, ended: ${host.endedBecause}")
    }

    @Test
    fun `one file cannot pass 256 MB`() = hosted { host ->
        val outcome = runCatching { host.attempt("big-file|${File(data, "big").path}") }
        val size = File(data, "big").length()
        println("LIMIT file: $outcome, $size bytes")
        assertTrue(size <= 256L * 1024 * 1024, "$size")
    }

    @Test
    fun `many files are stopped by the watch`() =
        hosted(HostLimits(maxDiskBytes = 50L * 1024 * 1024, periodMillis = 250)) { host ->
            runCatching { host.attempt("many-files|${File(data, "many").path}") }
            host.awaitEnd(WAIT_MILLIS)
            println("LIMIT disk: ${host.endedBecause}")
            assertTrue("disk" in host.endedBecause.orEmpty(), host.endedBecause)
        }

    @Test
    fun `the host runs at a lower priority`() {
        val launcher = HostLauncher.forThisProcess() ?: return
        runBlocking {
            val (client, providers) = PluginHostClient.start(launcher, jar, data, "hostile")
            try {
                providers.getOrThrow()
                val pid = client.pid
                val ps = ProcessBuilder("/bin/ps", "-o", "nice=", "-p", pid.toString()).start()
                val nice = ps.inputStream.bufferedReader().readText().trim().toInt()
                println("LIMIT nice: $nice")
                assertEquals(10, nice)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `cpu time as ps writes it`() {
        assertEquals(12_340L, parseCpuTime("0:12.34"))
        assertEquals(3_661_000L, parseCpuTime("1:01:01"))
        assertEquals(90_061_000L, parseCpuTime("1-01:01:01"))
    }

    @Test
    fun `the arithmetic`() {
        val limits = HostLimits(maxResidentBytes = 100, maxDiskBytes = 100, busyShare = 0.9, busyMillis = 10_000, periodMillis = 5_000)
        val watch = HostWatch(limits)
        assertEquals("used too much memory", HostWatch(limits).look(0, 0, 101, 0, false))
        assertEquals("wrote too much to disk", HostWatch(limits).look(0, 0, 0, 101, false))
        assertNull(watch.look(0, 0, 0, 0, false))
        assertNull(watch.look(5_000, 5_000, 0, 0, false), "busy, not for long enough")
        assertNull(watch.look(10_000, 10_000, 0, 0, false))
        assertTrue(watch.look(15_000, 15_000, 0, 0, false).orEmpty().isNotEmpty(), "busy for the whole window")
        val answering = HostWatch(limits)
        (0..10).forEach { assertNull(answering.look(it * 5_000L, it * 5_000L, 0, 0, callWaiting = true), "a call is waiting") }
        val rested = HostWatch(limits)
        assertNull(rested.look(0, 0, 0, 0, false))
        assertNull(rested.look(5_000, 5_000, 0, 0, false))
        assertNull(rested.look(10_000, 5_000, 0, 0, false), "idle resets it")
        assertNull(rested.look(15_000, 10_000, 0, 0, false))
        assertFalse(rested.look(20_000, 15_000, 0, 0, false) != null, "not yet again")
    }

    private companion object {
        const val POLL_MILLIS = 200L
        const val WAIT_MILLIS = 60_000L
    }
}

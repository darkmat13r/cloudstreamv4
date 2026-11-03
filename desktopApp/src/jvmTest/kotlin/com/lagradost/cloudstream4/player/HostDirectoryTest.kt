package com.lagradost.cloudstream4.player

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Real processes, because whether a process is alive and when it started are what this reads. */
class HostDirectoryTest {
    private val base: File = Files.createTempDirectory("media-host-base").toFile().canonicalFile
    private val started = mutableListOf<Process>()

    @AfterTest
    fun cleanUp() {
        started.forEach { it.destroyForcibly().waitFor() }
        deleteHostDirectory(base.toPath())
    }

    private fun sleeper(): Process = ProcessBuilder("/bin/sleep", "120").start().also { started += it }

    private fun dirNamed(name: String): File = File(base, name).apply { check(mkdirs()) }

    private fun nameFor(process: ProcessHandle): String =
        "$HOST_DIRECTORY_PREFIX${process.pid()}-${checkNotNull(process.startMillis())}-1"

    /** What another copy of the app does: its own process, the real call, and it stays until killed. */
    private fun anotherApp(): Pair<Process, File> {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"), AnotherApp::class.java.name, base.path,
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start().also { started += it }
        val directory = File(checkNotNull(process.inputStream.bufferedReader().readLine()) { "the other app said nothing" })
        assertTrue(directory.isDirectory, "the other app should have made its directory")
        return process to directory
    }

    @Test
    fun `a second run leaves a directory another running app is using`() {
        val (_, theirs) = anotherApp()
        val own = File(theirs, "file").apply { writeText("a host's") }

        val mine = newHostDirectory(base)

        assertTrue(theirs.isDirectory, "a live app's directory should stay")
        assertEquals("a host's", own.readText())
        assertNotEquals(theirs, mine)
    }

    @Test
    fun `an app that made a directory does not clear it when it makes the next`() {
        val first = newHostDirectory(base)
        val second = newHostDirectory(base)

        assertTrue(first.isDirectory)
        assertTrue(second.isDirectory)
        assertNotEquals(first, second)
    }

    @Test
    fun `an app that was killed has its directory cleared by the next run`() {
        val (process, theirs) = anotherApp()
        File(theirs, "file").writeText("left behind")
        process.destroyForcibly().waitFor(WAIT_SECONDS, TimeUnit.SECONDS)

        val mine = newHostDirectory(base)

        assertFalse(theirs.exists(), "a dead app's directory should be cleared")
        assertTrue(mine.isDirectory)
    }

    @Test
    fun `a pid now used by a process that started at another time does not keep a directory`() {
        val other = sleeper().toHandle()
        val realStart = checkNotNull(other.startMillis())
        val reused = dirNamed("$HOST_DIRECTORY_PREFIX${other.pid()}-${realStart - 1}-1")
        val live = dirNamed(nameFor(other))

        newHostDirectory(base)

        assertFalse(reused.exists(), "the pid is alive, but it is not the process that made this")
        assertTrue(live.exists(), "the same pid and start is the same process")
    }

    @Test
    fun `a start time reads the same from outside and over time`() {
        val other = sleeper().toHandle()
        val first = other.startMillis()
        Thread.sleep(SETTLE_MILLIS)
        assertEquals(first, other.startMillis())
        assertEquals(first, ProcessHandle.of(other.pid()).get().startMillis())
    }

    @Test
    fun `a directory from a build before this one, or a stray file, is cleared`() {
        val old = dirNamed("host6318881770900839163")
        val stray = File(base, "stray").apply { writeText("x") }
        val malformed = listOf("host-", "host-x-1-1", "host-1", "host-1-x-1", "host-99999999999999999999-1-1").map(::dirNamed)

        newHostDirectory(base)

        assertFalse(old.exists())
        assertFalse(stray.exists())
        malformed.forEach { assertFalse(it.exists(), it.name) }
    }

    @Test
    fun `a new directory is made even in a base with nothing to clear`() {
        val mine = newHostDirectory(base)

        assertEquals(base, mine.parentFile)
        assertTrue(hostDirectoryInUse(mine.name), "this process made it, and is running")
    }
}

/** The main of [HostDirectoryTest]'s other app: makes its directory, says where, and waits to be killed. */
internal object AnotherApp {
    @JvmStatic
    fun main(args: Array<String>) {
        println(newHostDirectory(File(args[0])).path)
        System.out.flush()
        Thread.sleep(Long.MAX_VALUE)
    }
}

private const val WAIT_SECONDS = 10L
private const val SETTLE_MILLIS = 50L

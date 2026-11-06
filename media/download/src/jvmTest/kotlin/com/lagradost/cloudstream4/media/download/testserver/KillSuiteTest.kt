package com.lagradost.cloudstream4.media.download.testserver

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A download in a child JVM is killed with `destroyForcibly()` (SIGKILL: no `finally`, no shutdown
 * hook, no flush), then this JVM resumes it. The server lives in this JVM and outlives the child.
 */
class KillSuiteTest : SuiteBase() {
    private companion object {
        const val SIZE = 32 * MIB
        const val MIN_PART = 256 * KIB
        const val PARTS = 4
        const val THROTTLE = 2 * MIB
        const val KILL_AT_FRACTION_PERCENT = 30L
        const val WAIT_MILLIS = 60_000L
        const val KILL_TARGET_CLASS = "com.lagradost.cloudstream4.media.download.testserver.KillTargetKt"
    }

    private class Child(val process: Process) {
        val bytes = AtomicLong(-1)
        val transcript = StringBuffer()

        init {
            Thread {
                process.inputStream.bufferedReader().forEachLine { line ->
                    transcript.append(line).append('\n')
                    if (line.startsWith("SNAP ")) bytes.set(line.split(' ')[1].toLong())
                }
            }.apply { isDaemon = true; start() }
        }
    }

    private fun startChild(name: String): Child {
        val java = ProcessHandle.current().info().command().orElseThrow { IllegalStateException("cannot find the java executable") }
        val classPath = System.getProperty("java.class.path")
        val marker = KILL_TARGET_CLASS.replace('.', '/') + ".class"
        assertTrue(
            classPath.split(File.pathSeparator).any { File(it, marker).exists() },
            "java.class.path does not reach the test classes, a child JVM cannot run the kill target: $classPath",
        )
        val process = ProcessBuilder(
            java, "-Xmx256m", "-cp", classPath, KILL_TARGET_CLASS,
            h.server.url("/f"), h.folderFile.path, name, PARTS.toString(), MIN_PART.toString(),
        ).redirectErrorStream(true).start()
        return Child(process)
    }

    /** Lets the child run until it has [percent] of [SIZE] downloaded, then kills it. */
    private fun killAt(child: Child, percent: Long) {
        val target = SIZE * percent / 100
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        while (child.bytes.get() < target && child.process.isAlive && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(child.process.isAlive, "the child ended before it could be killed:\n${child.transcript}")
        assertTrue(child.bytes.get() >= target, "child never reached $percent% in ${WAIT_MILLIS}ms:\n${child.transcript}")
        child.process.destroyForcibly()
        assertTrue(child.process.waitFor(15, TimeUnit.SECONDS), "child did not die")
        assertFalse(child.transcript.contains("FINAL"), "the child finished before it was killed:\n${child.transcript}")
    }

    @Test
    fun `a download resumes after its process was killed and does not refetch what is on disk`() {
        val res = h.resource(size = SIZE)
        h.server.throttle = { THROTTLE }

        killAt(startChild("out.bin"), KILL_AT_FRACTION_PERCENT)

        assertFalse(h.finalFile().exists(), "a killed download left a final file")
        assertTrue(File(h.tempDir(), "state").isFile, "no state file survived the kill")
        val before = runCatching { h.partsOnDisk() }.getOrNull()
        val onDisk = h.diskBytes()
        assertTrue(onDisk > 0, "nothing on disk after the kill")
        val mark = h.server.log.size
        h.server.throttle = { 0L }

        val run = h.run(h.request(options = suiteOptions(parts = PARTS, minPartBytes = MIN_PART)))

        h.assertCompleted(run, res.source)
        h.assertNoRefetch(checkNotNull(before), mark)
    }

    @Test
    fun `a download killed twice still resumes to a byte identical file`() {
        val res = h.resource(size = SIZE)
        h.server.throttle = { THROTTLE }

        killAt(startChild("out.bin"), 20)
        val afterFirst = h.diskBytes()
        killAt(startChild("out.bin"), 60)
        val afterSecond = h.diskBytes()
        val partsAfterSecond = h.partsOnDisk()
        assertTrue(afterSecond >= afterFirst, "the second run lost bytes: $afterFirst then $afterSecond")

        h.server.throttle = { 0L }
        val mark = h.server.log.size
        val run = h.run(h.request(options = suiteOptions(parts = PARTS, minPartBytes = MIN_PART)))

        h.assertCompleted(run, res.source)
        h.assertNoRefetch(partsAfterSecond, mark)
    }

    @Test
    fun `a download killed with no validators restarts rather than mixing versions`() {
        val res = h.resource(size = SIZE, etag = null)
        h.server.throttle = { THROTTLE }

        killAt(startChild("out.bin"), KILL_AT_FRACTION_PERCENT)
        res.content = Content(seed = 99, size = SIZE, etag = null)
        h.server.throttle = { 0L }

        val run = h.run(h.request(options = suiteOptions(parts = PARTS, minPartBytes = MIN_PART)))

        h.assertCompleted(run, res.source)
    }

    @Test
    fun `a final file never exists while the killed child was downloading`() {
        h.resource(size = SIZE)
        h.server.throttle = { THROTTLE }
        val child = startChild("out.bin")
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        var sawFinal = false
        while (child.bytes.get() < SIZE * KILL_AT_FRACTION_PERCENT / 100 && child.process.isAlive && System.currentTimeMillis() < deadline) {
            if (h.finalFile().exists()) sawFinal = true
            Thread.sleep(2)
        }
        killAt(child, KILL_AT_FRACTION_PERCENT)
        assertFalse(sawFinal, "a final file was visible while the download was in progress")
        assertEquals(false, h.finalFile().exists())
    }
}

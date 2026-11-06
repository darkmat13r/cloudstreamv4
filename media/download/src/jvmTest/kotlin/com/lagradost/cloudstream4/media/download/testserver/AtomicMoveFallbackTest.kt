package com.lagradost.cloudstream4.media.download.testserver

import com.lagradost.cloudstream4.media.download.DownloadStorage
import com.lagradost.cloudstream4.media.download.FileKitStorage
import com.lagradost.cloudstream4.media.download.renameFallback
import com.lagradost.cloudstream4.media.model.DownloadFailure
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [renameFallback], the move `FileKitStorage.atomicMove` falls back to on Android API 24/25 where
 * kotlinx-io's atomic move throws `UnsupportedOperationException`. A JVM has `java.nio.file`, so that
 * exception never happens here: the fallback is called directly, and wired into a storage for the engine.
 * `AtomicMoveDeviceTest` is the proof on a real API 25 device.
 */
class AtomicMoveFallbackTest : SuiteBase() {
    private val unsupported = UnsupportedOperationException("Atomic move not supported")

    private fun file(name: String, content: ByteArray?): File = File(h.outer, name).apply { content?.let { writeBytes(it) } }

    @Test
    fun `the rename moves the file and replaces an existing destination`() {
        val from = file("from", "new content".encodeToByteArray())
        val to = file("to", "old".encodeToByteArray())

        renameFallback(PlatformFile(from), PlatformFile(to), unsupported)

        assertFalse(from.exists())
        assertEquals("new content", to.readText())
    }

    @Test
    fun `a missing source is an IOException that names no path`() {
        val from = file("missing-source", null)
        val to = file("to", "old".encodeToByteArray())

        val error = assertFailsWith<IOException> { renameFallback(PlatformFile(from), PlatformFile(to), unsupported) }

        assertSame(unsupported, error.cause)
        assertFalse(error.message.orEmpty().contains(h.outer.path), "the message holds a path: ${error.message}")
        assertEquals("old", to.readText(), "the destination is untouched")
    }

    @Test
    fun `a destination whose directory is missing is an IOException and the source stays`() {
        val content = ByteArray(4096) { it.toByte() }
        val from = file("from", content)
        val to = File(h.outer, "no-such-dir/to")

        assertFailsWith<IOException> { renameFallback(PlatformFile(from), PlatformFile(to), unsupported) }

        assertContentEquals(content, from.readBytes())
        assertFalse(to.exists())
    }

    @Test
    fun `an error other than UnsupportedOperationException is not caught by FileKitStorage`() = runBlocking {
        val from = file("missing-source", null)
        val to = file("to", "old".encodeToByteArray())

        val error = assertFailsWith<Throwable> { FileKitStorage().atomicMove(PlatformFile(from), PlatformFile(to)) }

        // The fallback's IOException carries the UnsupportedOperationException as its cause; NIO's does not.
        assertFalse(error.cause is UnsupportedOperationException, "the fallback ran: $error")
        assertNotEquals("rename failed where an atomic move was not supported", error.message)
        assertEquals("old", to.readText())
    }

    /**
     * Runs a download whose move onto [failingTarget] goes through the real fallback into a directory that
     * does not exist, so the rename returns `false` exactly as it would on a device.
     */
    private fun runWithFailingRename(failingTarget: String): Pair<DownloadFailure?, File> {
        val inner = FileKitStorage()
        val storage = object : DownloadStorage by inner {
            override suspend fun atomicMove(from: PlatformFile, to: PlatformFile) {
                if (to.name != failingTarget) return inner.atomicMove(from, to)
                val unreachable = File(File(to.path).parentFile, "no-such-dir/${to.name}")
                renameFallback(from, PlatformFile(unreachable), UnsupportedOperationException("Atomic move not supported"))
            }
        }
        h.resource(size = 4 * MIB)
        val run = h.run(h.request(options = suiteOptions(parts = 4, minPartBytes = 256 * KIB)), downloader = h.downloader(storage))
        return h.assertFailed(run).failure to h.tempDir()
    }

    @Test
    fun `a failed rename of part 0 is StorageUnavailable and every part file is kept`() {
        val (failure, temp) = runWithFailingRename("joined")

        assertEquals(DownloadFailure.StorageUnavailable, failure)
        val parts = temp.listFiles().orEmpty().filter { it.name.startsWith("part-") }
        assertTrue(parts.size > 1, "expected several part files, found ${temp.list()?.toList()}")
        assertEquals(4 * MIB, parts.sumOf { it.length() }, "the part files still hold every byte")
        assertTrue(File(temp, "state").isFile, "the state file is kept")
    }

    @Test
    fun `a failed final rename is StorageUnavailable and the joined file is kept`() {
        val (failure, temp) = runWithFailingRename("out.bin")

        assertEquals(DownloadFailure.StorageUnavailable, failure)
        assertEquals(4 * MIB, File(temp, "joined").length(), "the joined file is kept whole")
    }
}

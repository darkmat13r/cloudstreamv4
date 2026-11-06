package com.lagradost.cloudstream4.media.download

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.atomicMove
import io.github.vinceglb.filekit.div
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The engine's atomic moves on a real device. On API 24 and 25 `java.nio.file` is absent, so kotlinx-io
 * 0.9.1 picks a mover that throws `UnsupportedOperationException("Atomic move not supported")`, and FileKit
 * 0.17.0's `PlatformFile.atomicMove` calls straight into it. The first two tests pin that down for both
 * layers; the rest show that [FileKitStorage] and the engine's own moves (the join's part 0, the state
 * file) work on the same device all the same.
 *
 * On API 26 and later the first two tests expect the move to succeed instead, so the suite says what it saw
 * on whatever device it runs on.
 */
@RunWith(AndroidJUnit4::class)
class AtomicMoveDeviceTest {
    private val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "atomic-move-test").apply {
        deleteRecursively()
        mkdirs()
    }
    private val dir = PlatformFile(root)
    private val storage = FileKitStorage()
    private val nioMissing = Build.VERSION.SDK_INT < 26

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun bytes(seed: Int, size: Int) = ByteArray(size) { (it * 31 + seed).toByte() }

    private fun file(name: String, content: ByteArray?): File = File(root, name).apply { content?.let { writeBytes(it) } }

    private fun assertNioMissing(error: Throwable?, layer: String) {
        assertNotNull("$layer: the move succeeded on API ${Build.VERSION.SDK_INT}, where java.nio.file is missing", error)
        assertEquals("$layer: exception class", UnsupportedOperationException::class.java, error!!::class.java)
        assertEquals("$layer: exception message", "Atomic move not supported", error.message)
    }

    @Test
    fun kotlinxIoAtomicMoveThrowsUnsupportedBelowApi26() {
        val content = bytes(1, 4096)
        val from = file("from", content)
        val to = file("to", null)

        val error = runCatching { SystemFileSystem.atomicMove(Path(from.path), Path(to.path)) }.exceptionOrNull()

        if (nioMissing) {
            assertNioMissing(error, "SystemFileSystem.atomicMove")
            assertArrayEquals("the source is untouched", content, from.readBytes())
            assertFalse("nothing arrived", to.exists())
        } else {
            if (error != null) throw error
            assertArrayEquals(content, to.readBytes())
        }
    }

    @Test
    fun fileKitAtomicMoveThrowsUnsupportedBelowApi26() {
        val content = bytes(2, 4096)
        val from = file("from", content)
        val to = file("to", null)

        val error = runCatching { runBlocking { PlatformFile(from).atomicMove(PlatformFile(to)) } }.exceptionOrNull()

        if (nioMissing) {
            assertNioMissing(error, "PlatformFile.atomicMove")
            assertArrayEquals("the source is untouched", content, from.readBytes())
            assertFalse("nothing arrived", to.exists())
        } else {
            if (error != null) throw error
            assertArrayEquals(content, to.readBytes())
        }
    }

    @Test
    fun fileKitStorageMovesAndReplacesTheDestination() = runBlocking {
        val content = bytes(3, 1 shl 20)
        val from = file("from", content)
        val to = file("to", bytes(4, 10))

        storage.atomicMove(PlatformFile(from), PlatformFile(to))

        assertFalse("the source is gone", from.exists())
        assertArrayEquals("the bytes arrived and replaced the old file", content, to.readBytes())
    }

    @Test
    fun fileKitStorageFailsWithIOExceptionAndKeepsTheSourceWhenTheMoveCannotHappen() = runBlocking {
        val content = bytes(8, 4096)
        val from = file("from", content)
        val to = File(root, "missing/to")

        val error = runCatching { storage.atomicMove(PlatformFile(from), PlatformFile(to)) }.exceptionOrNull()

        assertNotNull("the move into a missing directory succeeded", error)
        if (nioMissing) {
            assertEquals("the fallback's own failure", java.io.IOException::class.java, error!!::class.java)
            assertEquals(UnsupportedOperationException::class.java, error.cause?.let { it::class.java })
        }
        assertArrayEquals("the source is untouched", content, from.readBytes())
        assertFalse(to.exists())
    }

    @Test
    fun joinPartsMovesPartZeroAndAppendsTheRest() = runBlocking {
        val part0 = bytes(5, 300_000)
        val part1 = bytes(6, 200_000)
        val part2 = bytes(7, 123_457)
        file(partFileName(0), part0)
        file(partFileName(1), part1)
        file(partFileName(2), part2)
        val layout = listOf(
            PersistedPart(0, 0, 300_000, 300_000),
            PersistedPart(1, 300_000, 500_000, 200_000),
            PersistedPart(2, 500_000, 623_457, 123_457),
        )

        val outcome = joinParts(storage, dir, layout, bufferBytes = 64 * 1024)

        assertEquals(JoinOutcome.Done, outcome)
        assertArrayEquals(part0 + part1 + part2, File(root, JOINED_FILE_NAME).readBytes())
        assertEquals("only joined is left", listOf(JOINED_FILE_NAME), root.list()!!.sorted())
    }

    @Test
    fun stateFileWritesOverAnOldStateAndReadsBack() = runBlocking {
        val first = PersistedState(100, "\"a\"", "", PersistedPhase.DOWNLOAD, listOf(PersistedPart(0, 0, 100, 10)))
        val second = first.copy(phase = PersistedPhase.JOINING, parts = listOf(PersistedPart(0, 0, 100, 100)))

        storage.writeStateFile(dir, first)
        storage.writeStateFile(dir, second)

        assertEquals(second, storage.readStateFile(dir))
        assertFalse("state.tmp was moved, not left", File(root, StateFile.TEMP_FILE_NAME).exists())
    }
}

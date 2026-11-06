package com.lagradost.cloudstream4.media.download

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.div
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileKitStorageTest {
    private val root: File = Files.createTempDirectory("csdl-storage-test").toFile()
    private val storage = FileKitStorage()
    private val dir = PlatformFile(root)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun write(file: PlatformFile, text: String, append: Boolean) {
        storage.sink(file, append).buffered().use { it.write(text.encodeToByteArray()) }
    }

    private fun read(file: PlatformFile): String = storage.source(file).buffered().use { it.readByteArray().decodeToString() }

    @Test
    fun `append grows the file and a plain open truncates it`() {
        val file = dir / "part-0"
        assertNull(storage.size(file))
        assertFalse(storage.exists(file))
        write(file, "hello", append = true)
        assertEquals(5L, storage.size(file))
        write(file, " world", append = true)
        assertEquals(11L, storage.size(file))
        assertEquals("hello world", read(file))
        write(file, "x", append = false)
        assertEquals(1L, storage.size(file))
    }

    @Test
    fun `size is null for a missing file and zero for an empty one`() {
        val file = dir / "empty"
        write(file, "", append = false)
        assertEquals(0L, storage.size(file))
        assertNull(storage.size(dir / "missing"))
    }

    @Test
    fun `createDirectories makes the whole path and is idempotent`() {
        val nested = dir / "a" / "b" / "c"
        storage.createDirectories(nested)
        storage.createDirectories(nested)
        assertTrue(storage.exists(nested))
    }

    @Test
    fun `list returns the entries and an empty list for a missing directory`() {
        storage.createDirectories(dir / "d")
        write(dir / "d" / "one", "1", append = false)
        write(dir / "d" / "two", "22", append = false)
        assertEquals(listOf("one", "two"), storage.list(dir / "d").map { it.path.substringAfterLast('/') }.sorted())
        assertEquals(emptyList(), storage.list(dir / "nope"))
    }

    @Test
    fun `atomicMove moves the file and replaces an existing one`() = runBlocking {
        val from = dir / "joined"
        val to = dir / "final.mp4"
        write(from, "new content", append = false)
        write(to, "old", append = false)
        storage.atomicMove(from, to)
        assertFalse(storage.exists(from))
        assertEquals("new content", read(to))
        assertEquals(11L, storage.size(to))
    }

    @Test
    fun `delete removes a file, a directory tree and tolerates a missing path`() = runBlocking {
        write(dir / "f", "x", append = false)
        storage.delete(dir / "f")
        assertFalse(storage.exists(dir / "f"))
        storage.createDirectories(dir / "t" / "inner")
        write(dir / "t" / "inner" / "x", "x", append = false)
        write(dir / "t" / "y", "y", append = false)
        storage.delete(dir / "t")
        assertFalse(storage.exists(dir / "t"))
        storage.delete(dir / "never-existed")
    }

    @Test
    fun `delete unlinks a symlink without touching its target`() = runBlocking {
        val target = dir / "target-dir"
        storage.createDirectories(target)
        write(target / "keep", "keep", append = false)
        val link = File(root, "link")
        Files.createSymbolicLink(link.toPath(), File(root, "target-dir").toPath())
        storage.delete(PlatformFile(link))
        assertFalse(link.exists())
        assertEquals("keep", read(target / "keep"))
    }

    @Test
    fun `availableBytes answers for an existing directory and for one not yet created`() = runBlocking {
        assertNotNull(storage.availableBytes(dir))
        assertNotNull(storage.availableBytes(dir / "not" / "yet" / "here"))
        assertTrue(storage.availableBytes(dir)!! > 0L)
    }

    @Test
    fun `state file round trips through storage and a corrupt one reads as none`() = runBlocking {
        val state = PersistedState(
            10, "\"e\"", "", PersistedPhase.DOWNLOAD,
            listOf(PersistedPart(0, 0, 4, 4), PersistedPart(1, 4, 10, 0)),
        )
        assertNull(storage.readStateFile(dir))
        storage.writeStateFile(dir, state)
        assertEquals(state, storage.readStateFile(dir))
        assertFalse(storage.exists(dir / StateFile.TEMP_FILE_NAME))
        storage.writeStateFile(dir, state.copy(phase = PersistedPhase.JOINING))
        assertEquals(PersistedPhase.JOINING, storage.readStateFile(dir)?.phase)
        write(dir / StateFile.FILE_NAME, "garbage", append = false)
        assertNull(storage.readStateFile(dir))
        write(dir / StateFile.FILE_NAME, "x".repeat(StateFile.MAX_BYTES + 1), append = false)
        assertNull(storage.readStateFile(dir))
    }

    @Test
    fun `bytes written are bytes read, including every byte value`() {
        val file = dir / "binary"
        val data = ByteArray(1000) { it.toByte() }
        storage.sink(file, append = false).buffered().use { it.write(data) }
        assertContentEquals(data, storage.source(file).buffered().use { it.readByteArray() })
    }
}

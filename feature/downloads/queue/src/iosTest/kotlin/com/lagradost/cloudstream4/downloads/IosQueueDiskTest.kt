@file:OptIn(ExperimentalForeignApi::class)

package com.lagradost.cloudstream4.downloads

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSTemporaryDirectory
import platform.posix.lstat
import platform.posix.mkdtemp
import platform.posix.stat
import platform.posix.symlink
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `KMP-123`: the iPad's file operations, on the simulator, each against what the Mac's `java.nio` call does. */
class IosQueueDiskTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = memScoped {
            Path(checkNotNull(mkdtemp("${NSTemporaryDirectory()}queue-disk-XXXXXX".cstr.getPointer(this))?.toKString()))
        }
    }

    @AfterTest
    fun tearDown() {
        deleteTree(dir)
    }

    @Test
    fun `the downloads file is written whole - owner-only - and read back`() {
        val file = Path(dir, "data", "downloads.json")
        IosQueueDisk.writeAtomically(file, "first".encodeToByteArray())
        IosQueueDisk.writeAtomically(file, "second".encodeToByteArray())

        assertContentEquals("second".encodeToByteArray(), IosQueueDisk.readFile(file, 1024))
        assertEquals(0b110_000_000, permissionsOf(file), "rw------- only")
        assertEquals(listOf("downloads.json"), names(Path(dir, "data")), "no staging file is left behind")
    }

    @Test
    fun `a missing downloads file reads as none - and a large or linked one is refused`() {
        assertNull(IosQueueDisk.readFile(Path(dir, "none.json"), 1024))

        val big = Path(dir, "big.json")
        write(big, ByteArray(2048))
        assertFailsWith<IOException> { IosQueueDisk.readFile(big, 1024) }

        val link = Path(dir, "link.json")
        assertEquals(0, symlink(big.toString(), link.toString()))
        assertFailsWith<IOException> { IosQueueDisk.readFile(link, 4096) }
    }

    @Test
    fun `files - sizes and links read as the Mac reads them`() {
        val parts = Path(dir, ".film.mp4.csdl")
        SystemFileSystem.createDirectories(parts)
        write(Path(parts, "part-0"), ByteArray(10))
        write(Path(parts, "joined"), ByteArray(5))
        SystemFileSystem.createDirectories(Path(parts, "sub"))

        assertEquals(mapOf("part-0" to 10L, "joined" to 5L), IosQueueDisk.files(parts)?.associate { it.name to it.length })
        assertNull(IosQueueDisk.files(Path(dir, "missing")))
        assertTrue(IosQueueDisk.isFile(Path(parts, "part-0")))
        assertFalse(IosQueueDisk.isFile(Path(parts, "sub")))
        assertEquals(10L, IosQueueDisk.length(Path(parts, "part-0")))
        assertEquals(0L, IosQueueDisk.length(Path(parts, "nothing")))

        val dangling = Path(dir, "dangling")
        assertEquals(0, symlink(Path(dir, "gone").toString(), dangling.toString()))
        assertFalse(IosQueueDisk.exists(dangling), "exists follows the link")
        assertTrue(IosQueueDisk.existsNoFollow(dangling), "existsNoFollow sees the link itself")
    }

    @Test
    fun `a deleted link goes and its target stays - and only an empty folder is removed`() {
        val target = Path(dir, "target.mp4")
        write(target, ByteArray(3))
        val link = Path(dir, "link.mp4")
        assertEquals(0, symlink(target.toString(), link.toString()))

        IosQueueDisk.deleteIfExists(link)
        IosQueueDisk.deleteIfExists(Path(dir, "never-there"))
        assertFalse(IosQueueDisk.existsNoFollow(link))
        assertTrue(IosQueueDisk.isFile(target), "the link's target is not touched")

        val full = Path(dir, "full")
        SystemFileSystem.createDirectories(full)
        write(Path(full, "keep"), ByteArray(1))
        IosQueueDisk.deleteIfEmptyDirectory(full)
        assertTrue(IosQueueDisk.exists(full))

        val empty = Path(dir, "empty")
        SystemFileSystem.createDirectories(empty)
        IosQueueDisk.deleteIfEmptyDirectory(empty)
        assertFalse(IosQueueDisk.exists(empty))
    }

    @Test
    fun `a real path resolves links - and a volume has room`() {
        val real = Path(dir, "real")
        SystemFileSystem.createDirectories(real)
        val link = Path(dir, "alias")
        assertEquals(0, symlink(real.toString(), link.toString()))

        assertEquals(IosQueueDisk.realPath(real), IosQueueDisk.realPath(link))
        assertFailsWith<IOException> { IosQueueDisk.realPath(Path(dir, "missing")) }
        val space = IosQueueDisk.volumeSpace(dir)
        assertTrue(space.totalBytes > 0L && space.usableBytes in 1L..space.totalBytes)
    }

    private fun write(path: Path, bytes: ByteArray) = SystemFileSystem.sink(path).buffered().use { it.write(bytes) }

    private fun names(directory: Path): List<String> = SystemFileSystem.list(directory).map { it.name }

    private fun permissionsOf(path: Path): Int = memScoped {
        val st = alloc<stat>()
        check(lstat(path.toString(), st.ptr) == 0)
        st.st_mode.toInt() and 0b111_111_111
    }

    private fun deleteTree(path: Path) {
        val isDirectory = memScoped {
            val st = alloc<stat>()
            lstat(path.toString(), st.ptr) == 0 && (st.st_mode.toInt() and platform.posix.S_IFMT.toInt()) == platform.posix.S_IFDIR.toInt()
        }
        if (isDirectory) SystemFileSystem.list(path).forEach(::deleteTree)
        platform.posix.remove(path.toString())
    }
}

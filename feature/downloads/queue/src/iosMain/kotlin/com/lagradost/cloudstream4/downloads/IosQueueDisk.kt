@file:OptIn(ExperimentalForeignApi::class)

package com.lagradost.cloudstream4.downloads

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.io.IOException
import kotlinx.io.files.Path
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSFileSystemSize
import platform.posix.EINTR
import platform.posix.ENOENT
import platform.posix.F_FULLFSYNC
import platform.posix.O_NOFOLLOW
import platform.posix.O_RDONLY
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.close
import platform.posix.closedir
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.free
import platform.posix.fsync
import platform.posix.lstat
import platform.posix.mkstemps
import platform.posix.open
import platform.posix.opendir
import platform.posix.read
import platform.posix.readdir
import platform.posix.realpath
import platform.posix.remove
import platform.posix.rename
import platform.posix.rmdir
import platform.posix.stat
import platform.posix.unlink
import platform.posix.write

/**
 * The iPad's [QueueDisk] (`KMP-123`), over POSIX: each call is the one the Mac's `java.nio` call makes underneath.
 *
 * - [readFile] opens with `O_NOFOLLOW` after an `lstat`, so a link put where the downloads file goes is refused.
 * - [writeAtomically] stages with `mkstemps`, which creates the file `0600`, forces it with `F_FULLFSYNC` (Apple's
 *   `fsync` does not reach the platter; `fsync` only when the volume refuses it), and moves it with `rename(2)`.
 *
 * Every message is fixed text: no path, which holds the app's container id.
 */
object IosQueueDisk : QueueDisk {
    override fun isFile(path: Path): Boolean = mode(path.toString(), follow = true)?.let(::isRegular) ?: false

    override fun length(path: Path): Long = sizeOf(path.toString(), follow = true) ?: 0L

    override fun exists(path: Path): Boolean = mode(path.toString(), follow = true) != null

    override fun existsNoFollow(path: Path): Boolean = mode(path.toString(), follow = false) != null

    override fun files(directory: Path): List<DiskFile>? {
        val dir = opendir(directory.toString()) ?: return null
        try {
            val found = mutableListOf<DiskFile>()
            while (true) {
                val entry = readdir(dir) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name == "." || name == "..") continue
                val child = Path(directory, name).toString()
                val mode = mode(child, follow = true) ?: continue
                if (isRegular(mode)) found += DiskFile(name, sizeOf(child, follow = true) ?: 0L)
            }
            return found
        } finally {
            closedir(dir)
        }
    }

    /** `remove(3)`: a file, a link or an empty directory, as `Files.deleteIfExists`. A link is not followed. */
    override fun deleteIfExists(path: Path) {
        if (remove(path.toString()) != 0 && errno != ENOENT) throw IOException("a file could not be deleted")
    }

    /** `rmdir(2)` refuses a directory that is not empty, and a link. Its failure is the answer, not an error. */
    override fun deleteIfEmptyDirectory(directory: Path) {
        rmdir(directory.toString())
    }

    override fun realPath(path: Path): Path {
        val resolved = realpath(path.toString(), null) ?: throw IOException("a path could not be resolved")
        try {
            return Path(resolved.toKString())
        } finally {
            free(resolved)
        }
    }

    override fun volumeSpace(directory: Path): VolumeSpace {
        val attributes = NSFileManager.defaultManager.attributesOfFileSystemForPath(directory.toString(), error = null)
        val total = (attributes?.get(NSFileSystemSize) as? Number)?.toLong() ?: 0L
        val free = (attributes?.get(NSFileSystemFreeSize) as? Number)?.toLong() ?: 0L
        return VolumeSpace(total, free)
    }

    override fun readFile(path: Path, maxBytes: Long): ByteArray? {
        val name = path.toString()
        val mode = mode(name, follow = false) ?: return null
        if (!isRegular(mode)) throw IOException("the downloads file is not a file")
        val size = sizeOf(name, follow = false) ?: throw IOException("the downloads file could not be read")
        if (size > maxBytes) throw IOException("the downloads file is too large")
        val fd = open(name, O_RDONLY or O_NOFOLLOW)
        if (fd < 0) throw IOException("the downloads file could not be opened")
        try {
            val out = ByteArray(size.toInt())
            var done = 0
            out.usePinned { pinned ->
                while (done < out.size) {
                    val n = read(fd, pinned.addressOf(done), (out.size - done).convert())
                    when {
                        n > 0 -> done += n.toInt()
                        n == 0L -> break
                        errno == EINTR -> continue
                        else -> throw IOException("the downloads file could not be read")
                    }
                }
            }
            // A file that shrank while it was read is read again next time; what arrived is still whole JSON or not.
            return if (done == out.size) out else out.copyOf(done)
        } finally {
            close(fd)
        }
    }

    override fun writeAtomically(path: Path, bytes: ByteArray) {
        val parent = path.parent ?: throw IOException("no parent directory")
        if (!NSFileManager.defaultManager.createDirectoryAtPath(parent.toString(), true, null, null)) {
            throw IOException("cannot create the data directory")
        }
        val staging = memScoped {
            val template = "${Path(parent, ".${path.name}")}XXXXXX$STAGING_SUFFIX".encodeToByteArray()
            val buffer = allocArray<ByteVar>(template.size + 1)
            template.forEachIndexed { i, b -> buffer[i] = b }
            buffer[template.size] = 0
            val fd = mkstemps(buffer, STAGING_SUFFIX.length)
            if (fd < 0) throw IOException("cannot create a temporary file")
            fd to buffer.toKString()
        }
        val (fd, stagingPath) = staging
        try {
            try {
                writeAll(fd, bytes)
                if (fcntl(fd, F_FULLFSYNC) == -1 && fsync(fd) != 0) throw IOException("the downloads file could not be forced to disk")
            } finally {
                close(fd)
            }
            if (rename(stagingPath, path.toString()) != 0) throw IOException("the downloads file could not be moved into place")
        } finally {
            unlink(stagingPath)
        }
    }

    private fun writeAll(fd: Int, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        var done = 0
        bytes.usePinned { pinned ->
            while (done < bytes.size) {
                val n = write(fd, pinned.addressOf(done), (bytes.size - done).convert())
                when {
                    n > 0 -> done += n.toInt()
                    n < 0 && errno == EINTR -> continue
                    else -> throw IOException("the downloads file could not be written")
                }
            }
        }
    }

    private fun mode(path: String, follow: Boolean): Int? = memScoped {
        val st = alloc<stat>()
        val result = if (follow) stat(path, st.ptr) else lstat(path, st.ptr)
        if (result == 0) st.st_mode.toInt() else null
    }

    private fun sizeOf(path: String, follow: Boolean): Long? = memScoped {
        val st = alloc<stat>()
        val result = if (follow) stat(path, st.ptr) else lstat(path, st.ptr)
        if (result == 0) st.st_size.coerceAtLeast(0L) else null
    }

    private fun isRegular(mode: Int): Boolean = (mode and S_IFMT.toInt()) == S_IFREG.toInt()

    private const val STAGING_SUFFIX = ".part"
}

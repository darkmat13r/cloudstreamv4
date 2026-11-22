package com.lagradost.cloudstream4.downloads

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.io.files.Path

/**
 * The Mac's [QueueDisk]: the `java.io` and `java.nio.file` calls `DesktopDownloads` and `DesktopDownloadStore` made
 * before `KMP-123` moved them into this module, unchanged.
 */
object JvmQueueDisk : QueueDisk {
    override fun isFile(path: Path): Boolean = path.file.isFile

    override fun length(path: Path): Long = path.file.length()

    override fun exists(path: Path): Boolean = path.file.exists()

    override fun existsNoFollow(path: Path): Boolean = Files.exists(path.file.toPath(), LinkOption.NOFOLLOW_LINKS)

    override fun files(directory: Path): List<DiskFile>? =
        directory.file.listFiles()?.filter { it.isFile }?.map { DiskFile(it.name, it.length()) }

    override fun deleteIfExists(path: Path) {
        Files.deleteIfExists(path.file.toPath())
    }

    /** `File.delete` refuses a directory that is not empty, so nobody's other files go with it. */
    override fun deleteIfEmptyDirectory(directory: Path) {
        val folder = directory.file
        if (folder.list()?.isEmpty() == true) folder.delete()
    }

    override fun realPath(path: Path): Path = Path(path.file.toPath().toRealPath().toString())

    override fun volumeSpace(directory: Path): VolumeSpace =
        directory.file.let { VolumeSpace(it.totalSpace, it.usableSpace) }

    override fun readFile(path: Path, maxBytes: Long): ByteArray? {
        val nio = path.file.toPath()
        if (!Files.exists(nio, LinkOption.NOFOLLOW_LINKS)) return null
        if (!Files.isRegularFile(nio, LinkOption.NOFOLLOW_LINKS)) throw IOException("the downloads file is not a file")
        if (Files.size(nio) > maxBytes) throw IOException("the downloads file is too large")
        return Files.readAllBytes(nio)
    }

    /** The temporary file is created `0600` by the JDK, and its permissions are set again explicitly before the move. */
    override fun writeAtomically(path: Path, bytes: ByteArray) {
        val target = path.file
        val parent = checkNotNull(target.absoluteFile.parentFile) { "no parent directory" }
        if (!parent.mkdirs() && !parent.isDirectory) throw IOException("cannot create the data directory")
        val staging = Files.createTempFile(parent.toPath(), ".${target.name}", STAGING_SUFFIX)
        try {
            ownerOnly(staging)
            FileChannel.open(staging, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(staging, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(staging)
        }
    }

    private fun ownerOnly(path: java.nio.file.Path) {
        if ("posix" !in path.fileSystem.supportedFileAttributeViews()) return
        Files.setPosixFilePermissions(path, OWNER_ONLY)
    }

    private val Path.file: File get() = File(toString())

    private const val STAGING_SUFFIX = ".part"

    private val OWNER_ONLY: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")
}

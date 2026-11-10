package com.lagradost.cloudstream4.extensions.repositories

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission

/**
 * A small file only this user can read, written whole or not at all (`KMP-110`): the saved repositories and the
 * installed list, both of which hold repository URLs that can carry a token.
 */
internal object OwnerOnlyFile {
    private val OWNER_ONLY = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    private const val STAGING_SUFFIX = ".part"

    /** What is on disk: nothing, the bytes, or a file that cannot be trusted to say anything. */
    sealed interface Read {
        data object Absent : Read

        class Bytes(val bytes: ByteArray) : Read

        /** Not a regular file (a link would let something else choose its content), or past the limit. */
        data object Unreadable : Read
    }

    fun read(file: File, maxBytes: Long): Read {
        val path = file.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Read.Absent
        return runCatching {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > maxBytes) {
                Read.Unreadable
            } else {
                Read.Bytes(Files.readAllBytes(path))
            }
        }.getOrDefault(Read.Unreadable)
    }

    /**
     * Replaces [file] with [bytes], mode 0600: a new file is written beside it and moved over it, so a crash leaves the
     * old file or the new one, and a file left wider by hand comes back 0600.
     *
     * @param beforeMove runs after the new content is on disk and before it replaces the old. For a test.
     * @throws NotSaved when it could not be written. It carries no path, so it is safe to report.
     */
    fun write(file: File, bytes: ByteArray, beforeMove: () -> Unit = {}) {
        try {
            writeOrThrow(file, bytes, beforeMove)
        } catch (_: IOException) {
            // A `Files` exception names the file, and so the person's home directory. This one names nothing, and
            // keeps no cause that could be printed with it.
            throw NotSaved()
        }
    }

    /** Thrown by [write], with no message and no cause: see there. */
    class NotSaved : IOException()

    private fun writeOrThrow(file: File, bytes: ByteArray, beforeMove: () -> Unit) {
        val parent = checkNotNull(file.absoluteFile.parentFile) { "no parent directory" }
        if (!parent.mkdirs() && !parent.isDirectory) throw IOException("cannot create the settings directory")
        // In the same directory, so the move is atomic. The JDK creates a temporary file 0600; set again, so the mode
        // does not rest on that default.
        val staging = Files.createTempFile(parent.toPath(), ".${file.name}", STAGING_SUFFIX)
        try {
            Files.setPosixFilePermissions(staging, OWNER_ONLY)
            FileChannel.open(staging, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            beforeMove()
            Files.move(staging, file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(staging)
        }
    }
}

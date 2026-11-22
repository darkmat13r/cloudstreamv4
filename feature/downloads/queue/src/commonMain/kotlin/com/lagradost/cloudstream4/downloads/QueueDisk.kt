package com.lagradost.cloudstream4.downloads

import kotlinx.io.IOException
import kotlinx.io.files.Path

/**
 * Every file operation [DesktopDownloads] and its downloads file perform beyond the engine's own (`KMP-123`), so
 * one queue runs on the Mac ([JvmQueueDisk]) and the iPad (`IosQueueDisk`).
 *
 * Every path is absolute. Nothing here puts a path in an exception's message or a log: a path holds the user's
 * account name on the Mac and the app's container id on the iPad.
 */
interface QueueDisk {
    /** A regular file, following a symbolic link: `File.isFile`. */
    fun isFile(path: Path): Boolean

    /** Its size in bytes, following a link; 0 when it is missing or unreadable: `File.length`. */
    fun length(path: Path): Long

    /** Anything is there, following a link: `File.exists`. */
    fun exists(path: Path): Boolean

    /** Anything is there, a dangling symbolic link included: `Files.exists(NOFOLLOW_LINKS)`. */
    fun existsNoFollow(path: Path): Boolean

    /** The regular files directly in [directory], following links, or null when it is not a readable directory. */
    fun files(directory: Path): List<DiskFile>?

    /** Unlinks [path] when it is there; a link is removed, never followed. Throws [IOException] when that fails. */
    fun deleteIfExists(path: Path)

    /** Removes [directory] only when it is an empty directory. Anything else is left alone, silently. */
    fun deleteIfEmptyDirectory(directory: Path)

    /** [path] with every link resolved. Throws [IOException] when it cannot be. */
    fun realPath(path: Path): Path

    /** The size and free space of the volume holding [directory], which exists. */
    fun volumeSpace(directory: Path): VolumeSpace

    /**
     * The whole of [path], or null when nothing is there. Throws [IOException] when it is not a regular file (a link
     * is not followed) or is larger than [maxBytes].
     */
    fun readFile(path: Path, maxBytes: Long): ByteArray?

    /**
     * Replaces [path] with [bytes] in one step: a temporary file beside it, readable by the owner only, forced to
     * disk, then moved over it atomically. A crash leaves the old file or the new one. Creates the parent directory.
     */
    fun writeAtomically(path: Path, bytes: ByteArray)
}

/** A regular file [QueueDisk.files] found: its own name, and its size in bytes. */
class DiskFile(val name: String, val length: Long) {
    override fun toString(): String = "DiskFile(length=$length)"
}

/** A volume's size and the bytes still usable on it, read once. */
class VolumeSpace(val totalBytes: Long, val usableBytes: Long)

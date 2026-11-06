package com.lagradost.cloudstream4.media.download

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.atomicMove
import io.github.vinceglb.filekit.createDirectories
import io.github.vinceglb.filekit.delete
import io.github.vinceglb.filekit.exists
import io.github.vinceglb.filekit.list
import io.github.vinceglb.filekit.path
import io.github.vinceglb.filekit.sink
import io.github.vinceglb.filekit.size
import io.github.vinceglb.filekit.source
import kotlinx.io.RawSink
import kotlinx.io.RawSource

/**
 * Every file operation the engine performs, so a test can wrap [FileKitStorage] with a quota or a
 * failing sink. Nothing here is random access: FileKit has no seek, truncate or fsync, and a part file
 * is only ever appended to.
 *
 * `sink` and `source` do blocking I/O: the caller runs them on an I/O dispatcher.
 */
interface DownloadStorage {
    fun exists(file: PlatformFile): Boolean

    /** The size in bytes, or `null` when the file is missing or cannot be read. Never negative. */
    fun size(file: PlatformFile): Long?

    fun createDirectories(directory: PlatformFile)

    /** Removes a file, or a directory with everything in it. A missing path is not an error. Links are unlinked, never followed. */
    suspend fun delete(file: PlatformFile)

    /** The entries of [directory], or an empty list when it is missing. */
    fun list(directory: PlatformFile): List<PlatformFile>

    /** Opens [file] for writing, creating it. [append] `false` truncates an existing file. */
    fun sink(file: PlatformFile, append: Boolean): RawSink

    fun source(file: PlatformFile): RawSource

    /** Moves [from] to [to] in one step, replacing a file already at [to]. Same volume only. */
    suspend fun atomicMove(from: PlatformFile, to: PlatformFile)

    /** Free bytes on the volume holding [directory], or `null` when that cannot be known. */
    suspend fun availableBytes(directory: PlatformFile): Long?
}

/**
 * [DownloadStorage] over FileKit 0.17.0 (kotlinx-io underneath), the only implementation.
 *
 * `delete` is `PlatformFile.delete(mustExist = false, recursively = true)`: confirmed against the
 * 0.17.0 sources, where `recursively` empties a directory depth-first and unlinks a symbolic link
 * without entering it, and `mustExist = false` makes a missing path a no-op.
 *
 * Android `content://` locations are not supported by this class: `atomicMove` on a Uri is a copy
 * followed by a delete, which breaks the contract of [DownloadStorage.atomicMove].
 */
class FileKitStorage : DownloadStorage {
    override fun exists(file: PlatformFile): Boolean = runCatching { file.exists() }.getOrDefault(false)

    override fun size(file: PlatformFile): Long? = runCatching { file.size() }.getOrNull()?.takeIf { it >= 0L }

    override fun createDirectories(directory: PlatformFile) = directory.createDirectories(mustCreate = false)

    override suspend fun delete(file: PlatformFile) = file.delete(mustExist = false, recursively = true)

    override fun list(directory: PlatformFile): List<PlatformFile> =
        runCatching { directory.list() }.getOrDefault(emptyList())

    override fun sink(file: PlatformFile, append: Boolean): RawSink = file.sink(append)

    override fun source(file: PlatformFile): RawSource = file.source()

    override suspend fun atomicMove(from: PlatformFile, to: PlatformFile) = from.atomicMove(to)

    override suspend fun availableBytes(directory: PlatformFile): Long? = platformAvailableBytes(directory.path)
}

/**
 * Free bytes on the volume holding [path], or `null` when unknown. When [path] does not exist yet the
 * nearest existing ancestor answers: the directory is about to be created on the same volume.
 */
internal expect suspend fun platformAvailableBytes(path: String): Long?

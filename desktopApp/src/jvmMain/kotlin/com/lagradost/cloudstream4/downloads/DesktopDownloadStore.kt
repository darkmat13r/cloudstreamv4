package com.lagradost.cloudstream4.downloads

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

/**
 * One episode as it is written to the downloads file.
 *
 * [url], [headers] and [referer] are what a paused download needs to carry on, and they are credentials, so
 * [toString] leaves them out and nothing logs this class.
 *
 * [state] is a [Phase] name. Only `Done` survives a restart as itself: anything else reads back as paused.
 */
@Serializable
internal class StoredEpisode(
    val episodeId: Int,
    val titleId: Int,
    val titleName: String,
    val kind: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeName: String? = null,
    /** One path segment under the download root. Re-checked on reading. */
    val folder: String,
    /** One path segment inside [folder]. Re-checked on reading. */
    val fileName: String,
    val state: String,
    val bytesDone: Long = 0L,
    val bytesTotal: Long? = null,
    /** When it was first enqueued, as a sequence number. Orders the list. */
    val addedSeq: Long,
    /** When it last went into the queue, as a sequence number. Orders the queue. */
    val queueSeq: Long,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val referer: String? = null,
) {
    override fun toString(): String = "StoredEpisode(episodeId=$episodeId, titleId=$titleId, state=$state)"
}

@Serializable
internal class StoredDownloads(
    val format: Int = FORMAT,
    val nextSeq: Long = 0L,
    val episodes: List<StoredEpisode> = emptyList(),
) {
    override fun toString(): String = "StoredDownloads(episodes=${episodes.size})"

    companion object {
        const val FORMAT = 1
    }
}

/**
 * The downloads file: JSON, written atomically and owner-only.
 *
 * Follows `DesktopLibraryStore`: a temporary file in the same directory, forced to disk, then an atomic move, so a
 * crash leaves the old file or the new one. The temporary file is created `0600` by the JDK, and the permissions
 * are set again explicitly before the move, so the file never exists with wider ones. A link is not followed.
 *
 * Exceptions thrown from here carry fixed text only, never the path: the path holds the user's name.
 */
internal class DesktopDownloadStore(private val file: File) {

    /** The stored downloads, or null when there is no file yet. Throws [IOException] when it is there and unreadable. */
    fun read(): StoredDownloads? {
        val path = file.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw IOException("the downloads file is not a file")
        if (Files.size(path) > MAX_BYTES) throw IOException("the downloads file is too large")
        val text = Files.readAllBytes(path).toString(Charsets.UTF_8)
        val stored = try {
            JSON.decodeFromString(StoredDownloads.serializer(), text)
        } catch (_: IllegalArgumentException) {
            // SerializationException is one. Its message quotes the input, which holds URLs.
            throw IOException("the downloads file is not a CloudStream downloads file")
        }
        if (stored.format != StoredDownloads.FORMAT) throw IOException("the downloads file is from another version")
        return stored
    }

    fun write(downloads: StoredDownloads) {
        val bytes = JSON.encodeToString(StoredDownloads.serializer(), downloads).toByteArray(Charsets.UTF_8)
        val parent = checkNotNull(file.absoluteFile.parentFile) { "no parent directory" }
        if (!parent.mkdirs() && !parent.isDirectory) throw IOException("cannot create the data directory")
        val staging = Files.createTempFile(parent.toPath(), ".${file.name}", STAGING_SUFFIX)
        try {
            ownerOnly(staging)
            FileChannel.open(staging, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(staging, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(staging)
        }
    }

    private fun ownerOnly(path: java.nio.file.Path) {
        if ("posix" !in path.fileSystem.supportedFileAttributeViews()) return
        Files.setPosixFilePermissions(path, OWNER_ONLY)
    }

    companion object {
        private const val STAGING_SUFFIX = ".part"

        /** Thousands of episodes with long signed URLs, and still a file read whole in one go. */
        private const val MAX_BYTES = 16L * 1024 * 1024

        private val OWNER_ONLY: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")

        private val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun defaultFile(): File = File(File(System.getProperty("user.home"), ".cloudstream4"), "downloads.json")
    }
}

package com.lagradost.cloudstream4.downloads

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.io.IOException
import kotlinx.io.files.Path

/**
 * One episode as it is written to the downloads file.
 *
 * No link is written: no URL, header or referer (the owner's ruling on `KMP-52`). A resume after a restart asks the
 * extension again with [provider] and [episodeData], and a finished episode keeps neither: both are null once it is
 * `Done`, since nothing resumes a finished file. [episodeData] is the provider's own string, often an episode
 * page's URL, so [toString] leaves it out and nothing logs this class.
 *
 * [state] is a [Phase] name. `Done` and `Failed` survive a restart as themselves; anything else reads back as paused.
 * A failure is [failure], a `DownloadFailure` variant's name, and [failureNumbers], its numbers (a status code, byte
 * counts) in declaration order: never text from a link, a server or an exception.
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
    val failure: String? = null,
    val failureNumbers: List<Long> = emptyList(),
    /** When it was first enqueued, as a sequence number. Orders the list. */
    val addedSeq: Long,
    /** When it last went into the queue, as a sequence number. Orders the queue. */
    val queueSeq: Long,
    /** The extension's provider name, as `SandboxedProvider.name`. Null once `Done`. */
    val provider: String? = null,
    /** What the player passes to `SandboxedProvider.links` for this episode. */
    val episodeData: String? = null,
    /**
     * The title's poster (`KMP-99`). Kept once `Done`, as the name is: it is what the row shows, not how to fetch
     * anything. Re-checked on reading. Absent from a file written before it, which reads as null.
     */
    val posterUrl: String? = null,
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
        /**
         * 2: no link fields; the source to ask again instead. 3: no source on a finished episode. An older file is
         * refused, not migrated (unreleased).
         */
        const val FORMAT = 3
    }
}

/**
 * The downloads file: JSON, written atomically and owner-only.
 *
 * Follows `DesktopLibraryStore`: a temporary file in the same directory, forced to disk, then an atomic move, so a
 * crash leaves the old file or the new one. The temporary file is readable by its owner only from the moment it
 * exists, so the file never exists with wider permissions. A link is not followed. The file operations are the
 * platform's [QueueDisk] (`KMP-123`).
 *
 * Exceptions thrown from here carry fixed text only, never the path: the path holds the user's name.
 */
internal class DesktopDownloadStore(private val file: Path, private val disk: QueueDisk) {

    /** The stored downloads, or null when there is no file yet. Throws [IOException] when it is there and unreadable. */
    fun read(): StoredDownloads? {
        val bytes = disk.readFile(file, MAX_BYTES) ?: return null
        val text = bytes.decodeToString()
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
        disk.writeAtomically(file, JSON.encodeToString(StoredDownloads.serializer(), downloads).encodeToByteArray())
    }

    companion object {
        /** Thousands of episodes, each with its provider's episode data, and still a file read whole in one go. */
        private const val MAX_BYTES = 16L * 1024 * 1024

        private val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

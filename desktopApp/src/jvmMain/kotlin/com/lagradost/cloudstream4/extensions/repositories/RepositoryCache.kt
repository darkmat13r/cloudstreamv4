package com.lagradost.cloudstream4.extensions.repositories

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A repository as it was last read: what it said, and when (`KMP-110c`, boards X5a and X5b). */
class CachedRepository(val read: RepositoryRead.Read, val readAtMillis: Long)

/**
 * The last list read from each repository, so one that cannot be reached is shown as it was, with when, and never as an
 * empty one (`KMP-110c`).
 *
 * ## What is kept, and how it is read back
 *
 * The bytes the repository sent, not what was made of them: read back, they go through [RepositoryReader.parse], the
 * same parser and the same caps as a live read. A cache is a file on disk that anything running as this user could
 * have written, so it gets no more trust than the network did.
 *
 * - **One file per repository, named by SHA-256 of its URL**, never the URL, which can carry a token.
 * - **0600**, written whole or not at all ([OwnerOnlyFile]), inside a directory beside the extensions directory, so
 *   outside everything a plugin host is given.
 * - **Bounded:** a file past [MAX_BYTES] is not read, and one that would be is not written.
 * - **A corrupt, truncated or foreign file reads as no cache**, never as a failure: the person sees "can't reach it",
 *   as they would with nothing cached.
 */
class RepositoryCache(private val directory: File) {
    private val lock = Any()

    fun read(repositoryUrl: String): CachedRepository? = synchronized(lock) {
        val bytes = when (val read = OwnerOnlyFile.read(fileFor(repositoryUrl), MAX_BYTES)) {
            is OwnerOnlyFile.Read.Bytes -> read.bytes
            else -> return null
        }
        runCatching {
            val saved = json.decodeFromString<SavedFile>(bytes.toString(Charsets.UTF_8))
            if (saved.format != FORMAT || saved.readAtMillis < 0) return null
            val decoder = Base64.getDecoder()
            val raw = RawRepository(decoder.decode(saved.repository), saved.lists.map { it?.let(decoder::decode) })
            RepositoryReader.parse(raw)?.let { CachedRepository(it, saved.readAtMillis) }
        }.getOrNull()
    }

    /**
     * Keeps [read] as [repositoryUrl]'s last list, read at [readAtMillis]. A read with no bytes, or too large to keep,
     * is not kept; the cache that was there stays.
     *
     * @throws IOException when it could not be saved; [OwnerOnlyFile.NotSaved], which names no path.
     */
    fun write(repositoryUrl: String, read: RepositoryRead.Read, readAtMillis: Long): Unit = synchronized(lock) {
        val raw = read.raw ?: return
        val encoder = Base64.getEncoder()
        val saved = SavedFile(
            format = FORMAT,
            readAtMillis = readAtMillis,
            repository = encoder.encodeToString(raw.repository),
            lists = raw.lists.map { it?.let(encoder::encodeToString) },
        )
        val bytes = json.encodeToString(SavedFile.serializer(), saved).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) return
        OwnerOnlyFile.write(fileFor(repositoryUrl), bytes)
    }

    /** Forgets [repositoryUrl]'s list, when the repository is removed. */
    fun remove(repositoryUrl: String): Unit = synchronized(lock) {
        fileFor(repositoryUrl).delete()
    }

    private fun fileFor(repositoryUrl: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(repositoryUrl.toByteArray(Charsets.UTF_8))
        return File(directory, digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') } + SUFFIX)
    }

    @Serializable
    private data class SavedFile(val format: Int, val readAtMillis: Long, val repository: String, val lists: List<String?>)

    companion object {
        private const val FORMAT = 1
        private const val SUFFIX = ".json"

        /**
         * A live read is at most one repository JSON and 16 lists of 1 MiB: 16.06 MiB, a third more once base64. The
         * cap is that, rounded up; the largest list measured was 69,046 bytes, so a real cache is tens of kilobytes.
         */
        private val MAX_BYTES =
            (RepositoryLimits.MAX_REPOSITORY_BYTES + RepositoryLimits.MAX_PLUGIN_LISTS * RepositoryLimits.MAX_PLUGIN_LIST_BYTES) * 4 / 3 + 64 * 1024
        private val json = Json

        /** Beside [extensionsDirectory], and so never inside anything a host is given. */
        fun directoryFor(extensionsDirectory: File): File {
            val directory = extensionsDirectory.canonicalFile
            return File(directory.parentFile ?: directory, "${directory.name}.repository-cache")
        }
    }
}

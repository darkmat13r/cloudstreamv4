package com.lagradost.cloudstream4.extensions.repositories

import java.io.File
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One repository the person added: where it is, and the name it gave itself when it was added. */
@Serializable
data class SavedRepository(val url: String, val name: String) {
    /** Without the URL, which can carry a token: a log or an assertion message must not print it. */
    override fun toString(): String = "SavedRepository(name=$name, url=<redacted>)"
}

/** What was read. [unreadable]: the file is there and is not what [RepositoryStore] writes, so nothing in it counts. */
data class SavedRepositories(val repositories: List<SavedRepository>, val unreadable: Boolean)

/**
 * The repositories the person has added, on disk (`KMP-110`).
 *
 * ## Where, and who can read it
 *
 * Beside the extensions directory, as `ExtensionDecisions` is ([fileFor]), so outside everything a plugin host is
 * given. **Mode 0600**: a repository URL can carry an access token, so the file is the person's alone. Every write
 * makes a new file with that mode and moves it into place, which also takes a file left wider by hand back to 0600.
 * Its content is never printed or logged.
 *
 * ## What it refuses
 *
 * A file that is not a regular file (a link would let something else choose its content), is past [MAX_BYTES], or is
 * not exactly this format reads as [SavedRepositories.unreadable], and is not overwritten: [add] and [remove] throw
 * rather than replace a list someone could still recover by hand.
 *
 * @param beforeMove runs after the new content is on disk and before it replaces the old. For a test.
 */
class RepositoryStore(
    private val file: File,
    private val beforeMove: () -> Unit = {},
) {
    private val lock = Any()

    fun read(): SavedRepositories = synchronized(lock) {
        val saved = when (val read = OwnerOnlyFile.read(file, MAX_BYTES)) {
            OwnerOnlyFile.Read.Absent -> return SavedRepositories(emptyList(), unreadable = false)
            OwnerOnlyFile.Read.Unreadable -> null
            is OwnerOnlyFile.Read.Bytes -> runCatching {
                json.decodeFromString<SavedFile>(read.bytes.toString(Charsets.UTF_8))
                    .takeIf { it.format == FORMAT && it.repositories.size <= MAX_REPOSITORIES }
            }.getOrNull()
        }
        return saved?.let { SavedRepositories(it.repositories, unreadable = false) }
            ?: SavedRepositories(emptyList(), unreadable = true)
    }

    /**
     * Adds [repository] at the end, unless one with the same URL is there already.
     *
     * @return false when it was already there.
     * @throws IOException when it could not be saved, or the file there now could not be read. Nothing changed.
     */
    fun add(repository: SavedRepository): Boolean = synchronized(lock) {
        val current = readable()
        if (current.any { it.url == repository.url }) return false
        if (current.size >= MAX_REPOSITORIES) throw IOException("too many repositories")
        write(current + repository)
        true
    }

    /**
     * Removes the repository at [url].
     *
     * @return false when it was not there.
     * @throws IOException as [add] does.
     */
    fun remove(url: String): Boolean = synchronized(lock) {
        val current = readable()
        if (current.none { it.url == url }) return false
        write(current.filter { it.url != url })
        true
    }

    private fun readable(): List<SavedRepository> {
        val snapshot = read()
        if (snapshot.unreadable) throw IOException("the saved repositories could not be read")
        return snapshot.repositories
    }

    private fun write(repositories: List<SavedRepository>) {
        val bytes = json.encodeToString(SavedFile.serializer(), SavedFile(FORMAT, repositories)).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw IOException("the saved repositories would be too large")
        OwnerOnlyFile.write(file, bytes, beforeMove)
    }

    @Serializable
    private data class SavedFile(val format: Int, val repositories: List<SavedRepository>)

    companion object {
        private const val FORMAT = 1
        private const val MAX_BYTES = 256L * 1024
        private const val MAX_REPOSITORIES = 100
        private val json = Json { prettyPrint = true }

        /** Beside [extensionsDirectory], and so never inside anything a host is given. */
        fun fileFor(extensionsDirectory: File): File {
            val directory = extensionsDirectory.canonicalFile
            return File(directory.parentFile ?: directory, "${directory.name}.repositories")
        }
    }
}

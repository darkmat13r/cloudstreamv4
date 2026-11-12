package com.lagradost.cloudstream4.extensions.repositories

import java.io.File
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One extension a repository installed (`KMP-110`).
 *
 * The version is the repository's, recorded at install. `:app` takes it from the plugin's own manifest once it has
 * loaded, but the Mac does not load a repository's file until it is allowed, so the manifest is not there to read.
 *
 * @property sha256 the installed file's, lowercase hex, so a file changed by hand is not taken for this one.
 * @property verified whether it matched a hash its repository published.
 */
@Serializable
data class InstalledFromRepository(
    val fileName: String,
    val repositoryUrl: String,
    val internalName: String,
    val version: Int,
    val sha256: String,
    val verified: Boolean,
) {
    /** Without the repository URL, which can carry a token. */
    override fun toString(): String =
        "InstalledFromRepository(fileName=$fileName, internalName=$internalName, version=$version, verified=$verified)"
}

/**
 * The list of [InstalledFromRepository], beside the extensions directory ([fileFor]), mode 0600, atomic: the same
 * terms as [RepositoryStore], for the same reason (it holds repository URLs).
 *
 * A file that cannot be read is not overwritten; [put] and [remove] throw instead.
 */
class InstalledFromRepositories(private val file: File) {
    private val lock = Any()

    /** Everything installed, or null when the file is there and cannot be read. */
    fun read(): List<InstalledFromRepository>? = synchronized(lock) {
        when (val read = OwnerOnlyFile.read(file, MAX_BYTES)) {
            OwnerOnlyFile.Read.Absent -> emptyList()
            OwnerOnlyFile.Read.Unreadable -> null
            is OwnerOnlyFile.Read.Bytes -> runCatching {
                json.decodeFromString<SavedFile>(read.bytes.toString(Charsets.UTF_8)).takeIf { it.format == FORMAT }?.installed
            }.getOrNull()
        }
    }

    /** Records [entry], replacing any for its file. @throws IOException when it could not be saved. */
    fun put(entry: InstalledFromRepository): Unit = synchronized(lock) {
        write(readable().filter { it.fileName != entry.fileName } + entry)
    }

    /** Forgets the entry for [fileName]. @throws IOException when it could not be saved. */
    fun remove(fileName: String): Unit = synchronized(lock) {
        write(readable().filter { it.fileName != fileName })
    }

    private fun readable(): List<InstalledFromRepository> =
        read() ?: throw IOException("the installed list could not be read")

    private fun write(installed: List<InstalledFromRepository>) {
        val bytes = json.encodeToString(SavedFile.serializer(), SavedFile(FORMAT, installed)).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw IOException("the installed list would be too large")
        OwnerOnlyFile.write(file, bytes)
    }

    @Serializable
    private data class SavedFile(val format: Int, val installed: List<InstalledFromRepository>)

    companion object {
        private const val FORMAT = 1
        private const val MAX_BYTES = 4L * 1024 * 1024
        private val json = Json { prettyPrint = true }

        /** Beside [extensionsDirectory], and so never inside anything a host is given. */
        fun fileFor(extensionsDirectory: File): File {
            val directory = extensionsDirectory.canonicalFile
            return File(directory.parentFile ?: directory, "${directory.name}.installed")
        }
    }
}

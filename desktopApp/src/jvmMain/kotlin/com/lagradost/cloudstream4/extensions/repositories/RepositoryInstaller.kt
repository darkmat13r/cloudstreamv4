package com.lagradost.cloudstream4.extensions.repositories

import com.lagradost.cloudstream4.extensions.ExtensionChoice
import com.lagradost.cloudstream4.extensions.ExtensionDecisions
import com.lagradost.cloudstream4.extensions.SandboxedExtensions
import com.lagradost.cloudstream4.feature.extensions.ExtensionHash
import com.lagradost.cloudstream4.feature.extensions.ExtensionListing
import com.lagradost.cloudstream4.feature.extensions.ExtensionStatus
import com.lagradost.cloudstream4.feature.extensions.ExtensionVerification
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Why an install did not happen. Kinds, never messages: a message can carry a URL. */
sealed interface InstallFailure {
    /** The repository has it switched off, or offers it only to beta builds, or gave a status this build does not know. */
    data object NotOffered : InstallFailure

    /** The download failed, as [RepositoryHttp] says. */
    data class Fetch(val failure: RepositoryFailure) : InstallFailure

    /** Not the size its repository stated. Nothing was written. */
    data object SizeMismatch : InstallFailure

    /** Not the file its repository's hash describes. Nothing was written. */
    data object HashMismatch : InstallFailure

    /** It could not be saved. The extension that was there before, if any, is still there. */
    data object NotSaved : InstallFailure
}

sealed interface InstallResult {
    /**
     * In the extensions directory as [fileName], and held until it is allowed.
     *
     * @property verification [ExtensionVerification.Verified] when it matched its repository's hash, otherwise
     *   [ExtensionVerification.Skipped]: "Its repository publishes no hash for this file."
     * @property replaced files of the same extension that this took the place of, for the caller to stop: a `.cs3`
     *   when a `.jar` arrives, and the other way round.
     */
    data class Installed(
        val fileName: String,
        val verification: ExtensionVerification,
        val replaced: List<String>,
    ) : InstallResult

    data class Failed(val failure: InstallFailure) : InstallResult
}

/**
 * Puts a repository's extension in the extensions directory, where the existing pipeline takes it (`KMP-110`).
 *
 * Nothing here runs a plugin. It writes bytes, and the next step is the pipeline's: hash, store or conversion with no
 * network, scan, and the hold. A repository's file is always held ([ExtensionChoice.Pending]): installing is not
 * consent to run.
 *
 * ## In order
 *
 * 1. **Offered?** `status` Ok or Slow. Down, beta only and codes this build does not know are not installed.
 * 2. **Which file.** The jar when the repository publishes one (its author built it for the JVM), otherwise the `.cs3`,
 *    which the pipeline converts without network.
 * 3. **Fetched, bounded.** Through [RepositoryHttp], at most the size the repository stated, and never past
 *    [RepositoryLimits.MAX_PLUGIN_BYTES]. A stated size it does not match is refused.
 * 4. **Checked.** Against the hash the repository published, when it did: a mismatch writes nothing. With no hash, or
 *    one in an algorithm this build cannot check, the file is installed and marked unverified, and the hold still
 *    gates it (the owner's decision, 2026-11-10).
 * 5. **Named here.** [fileStem]: the `internalName` reduced to safe characters, and a tag from the repository URL and
 *    the `internalName`, so two repositories' plugins of the same name are two files. Never taken from a URL.
 * 6. **Written so the pipeline never sees half of it.** The hold is recorded first. The file is written in
 *    `.incoming/` and moved in whole. Its sidecar, the published hash that the pipeline checks again on every launch,
 *    is moved in before the file; with no published hash, any sidecar left from an earlier version is removed, so it
 *    neither claims a check that was not made nor refuses the new file.
 *
 * An update that fails after step 6 began leaves the earlier file held, not running: the hold replaces whatever was
 * chosen for that name, and "ask again" is the failure to have. An update whose new sidecar landed and whose file did
 * not is refused at the next launch as a hash mismatch, which is the same.
 */
class RepositoryInstaller(
    directory: File = SandboxedExtensions.defaultDirectory(),
    private val decisions: ExtensionDecisions = ExtensionDecisions(ExtensionDecisions.fileFor(directory)),
    private val installed: InstalledFromRepositories =
        InstalledFromRepositories(InstalledFromRepositories.fileFor(directory)),
    private val fetch: RepositoryReader.Fetcher = RepositoryHttp(callTimeoutMillis = RepositoryHttp.PLUGIN_CALL_TIMEOUT_MILLIS)
        .let { http -> RepositoryReader.Fetcher { url, maxBytes -> http.fetch(url, maxBytes) } },
    private val beforeMove: () -> Unit = {},
) {
    private val root = directory.canonicalFile
    private val incoming = File(root, INCOMING_DIRECTORY)
    private val lock = Mutex()

    suspend fun install(repositoryUrl: String, listing: ExtensionListing): InstallResult = lock.withLock {
        if (!isOffered(listing.status)) return InstallResult.Failed(InstallFailure.NotOffered)
        val jar = listing.jarUrl != null
        val url = listing.jarUrl ?: listing.downloadUrl
        val statedSize = if (jar) listing.jarFileSizeBytes else listing.fileSizeBytes
        val statedHash = if (jar) listing.jarHash else listing.fileHash
        if (statedSize != null && statedSize > RepositoryLimits.MAX_PLUGIN_BYTES) {
            return InstallResult.Failed(InstallFailure.Fetch(RepositoryFailure.TooLarge))
        }
        val bytes = when (val fetched = fetch.fetch(url, statedSize ?: RepositoryLimits.MAX_PLUGIN_BYTES)) {
            is Fetched.Bytes -> fetched.bytes
            is Fetched.Failed -> return InstallResult.Failed(InstallFailure.Fetch(fetched.failure))
        }
        if (statedSize != null && bytes.size.toLong() != statedSize) return InstallResult.Failed(InstallFailure.SizeMismatch)
        val verification = ExtensionHash.verify(bytes, statedHash)
        if (verification is ExtensionVerification.HashMismatch) return InstallResult.Failed(InstallFailure.HashMismatch)

        val stem = fileStem(repositoryUrl, listing.internalName)
        val fileName = stem + if (jar) JAR_SUFFIX else CS3_SUFFIX
        val other = stem + if (jar) CS3_SUFFIX else JAR_SUFFIX
        val sha256 = ExtensionHash.of(bytes).removePrefix(ExtensionHash.PREFIX)
        withContext(Dispatchers.IO) {
            try {
                val target = inRoot(fileName)
                decisions.record(fileName, sha256, ExtensionChoice.Pending)
                val staged = stage(bytes)
                try {
                    val sidecar = inRoot(fileName + SIDECAR_SUFFIX)
                    if (verification is ExtensionVerification.Verified && statedHash != null) {
                        Files.move(stage(statedHash.trim().toByteArray(Charsets.US_ASCII)).toPath(), sidecar.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    } else {
                        Files.deleteIfExists(sidecar.toPath())
                    }
                    beforeMove()
                    Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } finally {
                    Files.deleteIfExists(staged.toPath())
                }
                val replaced = listOf(other).filter { inRoot(it).isFile }
                replaced.forEach { name ->
                    Files.deleteIfExists(inRoot(name).toPath())
                    Files.deleteIfExists(inRoot(name + SIDECAR_SUFFIX).toPath())
                }
                installed.put(
                    InstalledFromRepository(
                        fileName = fileName,
                        repositoryUrl = repositoryUrl,
                        internalName = listing.internalName,
                        version = listing.version,
                        sha256 = sha256,
                        verified = verification is ExtensionVerification.Verified,
                    ),
                )
                installed.read()?.filter { it.fileName in replaced }?.forEach { installed.remove(it.fileName) }
                InstallResult.Installed(fileName, verification, replaced)
            } catch (_: IOException) {
                InstallResult.Failed(InstallFailure.NotSaved)
            } catch (_: IllegalArgumentException) {
                InstallResult.Failed(InstallFailure.NotSaved)
            }
        }
    }

    /**
     * Removes an extension a repository installed: its file, its sidecar and its entry. The caller stops it first
     * (`SandboxedExtensions.forget`). What was chosen for it is kept, so the same name arriving with other bytes is
     * still noticed. A file that a repository did not install is not touched.
     *
     * @return false when no repository installed [fileName].
     * @throws IOException when the installed list could not be read or saved.
     */
    suspend fun remove(fileName: String): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            val entry = (installed.read() ?: throw IOException("the installed list could not be read"))
                .firstOrNull { it.fileName == fileName } ?: return@withContext false
            Files.deleteIfExists(inRoot(entry.fileName).toPath())
            Files.deleteIfExists(inRoot(entry.fileName + SIDECAR_SUFFIX).toPath())
            installed.remove(entry.fileName)
            true
        }
    }

    /** Every file installed from [repositoryUrl], for removing a repository and what it installed. */
    fun installedFrom(repositoryUrl: String): List<InstalledFromRepository> =
        installed.read().orEmpty().filter { it.repositoryUrl == repositoryUrl }

    /** The version [listing] offers over [entry], or null when there is nothing to update to. */
    fun updateFor(entry: InstalledFromRepository, listing: ExtensionListing): Int? {
        if (!isOffered(listing.status) || listing.internalName != entry.internalName) return null
        val newer = listing.version == RepositoryIndexParser.ALWAYS_UPDATE || listing.version > entry.version
        return listing.version.takeIf { newer }
    }

    /** A file in the extensions directory, refused unless it is exactly there: no separator, no `..`, no link out. */
    private fun inRoot(name: String): File {
        require(SAFE_FILE.matches(name) && ".." !in name) { "unsafe file name" }
        return File(root, name).also { require(it.canonicalFile.parentFile == root) { "outside the directory" } }
    }

    private fun stage(bytes: ByteArray): File {
        if (!incoming.mkdirs() && !incoming.isDirectory) throw IOException("cannot create the incoming directory")
        val staged = Files.createTempFile(incoming.toPath(), "install", STAGING_SUFFIX)
        FileChannel.open(staged, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        return staged.toFile()
    }

    companion object {
        private const val INCOMING_DIRECTORY = ".incoming"
        private const val JAR_SUFFIX = ".jar"
        private const val CS3_SUFFIX = ".cs3"
        private const val SIDECAR_SUFFIX = ".sha256"
        private const val STAGING_SUFFIX = ".part"
        private const val READABLE_CHARS = 40
        private const val TAG_HEX = 12
        private val SAFE_FILE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,80}")

        /**
         * Ok and Slow are offered. Down is the repository switching it off; beta only is for beta builds, which the Mac
         * has none of; a code this build does not know is read as Down, as `ExtensionStatus.Unrecognised` asks.
         */
        fun isOffered(status: ExtensionStatus): Boolean = status == ExtensionStatus.Ok || status == ExtensionStatus.Slow

        /**
         * The file name, without its suffix, for [internalName] from [repositoryUrl].
         *
         * Its first 40 characters with anything outside `A-Z a-z 0-9 _ -` as `_`, so a person can tell which file it
         * is; then `-` and 12 hex digits of SHA-256 over the URL and the name, so `18+` and `18_`, and one name from
         * two repositories, are different files. At most 53 characters, which the sandbox's directory names (64) and
         * the decisions file's names both take.
         */
        fun fileStem(repositoryUrl: String, internalName: String): String {
            val readable = internalName.take(READABLE_CHARS)
                .map { if (it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-') it else '_' }
                .joinToString("")
                .trimStart('_', '-')
                .ifEmpty { "extension" }
            val digest = MessageDigest.getInstance("SHA-256").digest("$repositoryUrl\n$internalName".toByteArray(Charsets.UTF_8))
            val tag = digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }.take(TAG_HEX)
            return "$readable-$tag"
        }
    }
}

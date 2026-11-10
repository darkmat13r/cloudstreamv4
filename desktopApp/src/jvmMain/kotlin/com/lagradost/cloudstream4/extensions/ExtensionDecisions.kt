package com.lagradost.cloudstream4.extensions

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** What the person said about an extension whose scan raised warnings. */
enum class ExtensionChoice(internal val word: String) {
    Allow("allow"),
    KeepOff("keep-off"),

    /**
     * Nobody chose: the scan had nothing to say when this file first ran, so it was never asked about. It is recorded so
     * that a different file under this name is noticed, and it is never an approval of warnings.
     */
    Seen("seen"),

    /**
     * Installed from a repository and not allowed yet (`KMP-110`). Held whatever the scan says: installing is not
     * consent to run, so a repository's file waits for Allow even when the scan has nothing to say. Recorded before the
     * file is moved into the directory, so there is no moment when it is there without this.
     */
    Pending("pending"),
}

/**
 * What was read from the decisions file.
 *
 * @property unreadable the file exists and is not exactly what [ExtensionDecisions] writes. Nothing in it
 *   counts, in either direction, because a file that failed its check cannot say what was chosen.
 */
class DecisionsSnapshot internal constructor(
    private val entries: Map<String, Pair<String, ExtensionChoice>>,
    val unreadable: Boolean,
) {
    /** The choice for this file name and this exact content, or null: another hash is another file. */
    fun choiceFor(fileName: String, sha256: String): ExtensionChoice? =
        entries[fileName]?.takeIf { (recorded, _) -> recorded == sha256 }?.second

    /**
     * Whether an earlier file of this stem has been recorded, under either of its suffixes, and this one is not it. A
     * `Foo.jar` beside a `Foo.cs3` takes its place and its directory, so it counts as the same extension.
     */
    fun isNewVersion(fileName: String, sha256: String): Boolean {
        val stem = fileName.substringBeforeLast('.')
        val earlier = entries.filterKeys { it.substringBeforeLast('.') == stem }
        return earlier.isNotEmpty() && earlier[fileName]?.first != sha256
    }

    internal val all: Map<String, Pair<String, ExtensionChoice>> get() = entries
}

/**
 * Remembers Allow and Keep off, per extension and bound to the SHA-256 of its file.
 *
 * ## Where it lives
 *
 * A sibling of the extensions directory, [fileFor], so outside everything a host is given. A plugin host can
 * write only `.sandbox/<its name>` and a conversion only its own directory under `.converting`, both inside the
 * extensions directory, and neither can read anything else there. A plugin that could write this file could
 * approve itself, so `ExtensionSandboxReachTest` asks a real host under the real policy to try.
 *
 * ## What the checksum is for
 *
 * It catches a truncated, edited or half-restored file, and all of those read as undecided: never as approved.
 * It is not a signature. Any process running outside a sandbox as this user can recompute it, and can already
 * do anything else this user can. **The boundary against a plugin is Seatbelt.**
 *
 * @param beforeMove runs after the new content is on disk and before it replaces the old. For a test.
 */
class ExtensionDecisions(
    private val file: File,
    private val beforeMove: () -> Unit = {},
) {
    private val lock = Any()

    fun read(): DecisionsSnapshot = synchronized(lock) {
        val path = file.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return DecisionsSnapshot(emptyMap(), unreadable = false)
        // A link would let the file's content be chosen by whatever the link points at.
        val content = runCatching {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_BYTES) {
                null
            } else {
                parse(Files.readAllBytes(path))
            }
        }.getOrNull()
        return content?.let { DecisionsSnapshot(it, unreadable = false) } ?: DecisionsSnapshot(emptyMap(), unreadable = true)
    }

    /**
     * Records [choice], replacing whatever was recorded for [fileName], whichever hash that was for. So going
     * back to an earlier file asks again.
     *
     * Atomic: a crash leaves the old file or the new one. An unreadable file is replaced, since what it held
     * no longer counts, but it is not destroyed: a regular file is first copied whole to [damagedFileFor] (0600, the
     * one copy kept), so whoever wants to know what it said can still read it (`KMP-110b` review). Replacing it is how
     * the person gets out: "stay off until you choose again" is the notice. Nothing starts because of the replacement,
     * because a repository's file is also held by its installed entry, which this does not touch.
     *
     * @throws IOException when it could not be saved. The old file is untouched.
     */
    fun record(fileName: String, sha256: String, choice: ExtensionChoice): Unit = synchronized(lock) {
        require(fileName.matches(SAFE_NAME)) { "unsafe extension file name" }
        require(sha256.matches(SHA256)) { "not a SHA-256" }
        val current = read()
        if (current.unreadable) keepDamaged()
        val entries = current.all + (fileName to (sha256 to choice))
        val bytes = serialize(entries)
        val parent = checkNotNull(file.absoluteFile.parentFile) { "no parent directory" }
        if (!parent.mkdirs() && !parent.isDirectory) throw IOException("cannot create ${parent.name}")
        // In the same directory, or the move would not be atomic. Created 0600 by the JDK.
        val staging = Files.createTempFile(parent.toPath(), ".${file.name}", STAGING_SUFFIX)
        try {
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

    /** Copies the unreadable file to [damagedFileFor], replacing any earlier copy, before it is written over. */
    private fun keepDamaged() {
        val path = file.toPath()
        // A link or a directory is not copied: following a link would copy whatever it points at.
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return
        val damaged = damagedFileFor(file).toPath()
        val parent = checkNotNull(file.absoluteFile.parentFile) { "no parent directory" }
        val staging = Files.createTempFile(parent.toPath(), ".${file.name}", STAGING_SUFFIX)
        try {
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                Files.newOutputStream(staging, StandardOpenOption.TRUNCATE_EXISTING).use { input.copyTo(it) }
            }
            Files.move(staging, damaged, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(staging)
        }
    }

    private fun serialize(entries: Map<String, Pair<String, ExtensionChoice>>): ByteArray {
        val body = buildString {
            append(HEADER).append('\n')
            entries.toSortedMap().forEach { (name, decision) ->
                append(decision.second.word).append('\t').append(decision.first).append('\t').append(name).append('\n')
            }
        }
        return "$body$CHECKSUM\t${digest(body.toByteArray(Charsets.US_ASCII))}\n".toByteArray(Charsets.US_ASCII)
    }

    /** Strict on purpose: null for anything [serialize] could not have produced. */
    private fun parse(bytes: ByteArray): Map<String, Pair<String, ExtensionChoice>>? {
        if (bytes.any { it < 0 }) return null
        val text = String(bytes, Charsets.US_ASCII)
        if (!text.endsWith('\n')) return null
        val lines = text.removeSuffix("\n").split('\n')
        if (lines.size < 2 || lines.first() != HEADER) return null
        val checksum = lines.last().split('\t')
        if (checksum.size != 2 || checksum[0] != CHECKSUM) return null
        val body = lines.dropLast(1).joinToString("\n", postfix = "\n")
        if (digest(body.toByteArray(Charsets.US_ASCII)) != checksum[1]) return null

        val entries = LinkedHashMap<String, Pair<String, ExtensionChoice>>()
        for (line in lines.subList(1, lines.size - 1)) {
            val fields = line.split('\t')
            if (fields.size != 3) return null
            val (word, sha256, name) = fields
            val choice = ExtensionChoice.entries.firstOrNull { it.word == word } ?: return null
            if (!sha256.matches(SHA256) || !name.matches(SAFE_NAME) || name in entries) return null
            entries[name] = sha256 to choice
        }
        return entries
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val HEADER = "cloudstream4-extension-decisions 1"
        private const val CHECKSUM = "checksum"
        private const val STAGING_SUFFIX = ".part"
        private const val MAX_BYTES = 1024L * 1024
        private val SHA256 = Regex("[0-9a-f]{64}")

        /** The stem is what `SandboxPolicy.dataDirectory` accepts, plus the file's extension. */
        private val SAFE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,72}")

        /** Where an unreadable decisions file is copied before it is replaced: beside it, one copy. */
        fun damagedFileFor(decisionsFile: File): File = File(decisionsFile.absoluteFile.parentFile, "${decisionsFile.name}.damaged")

        /** Beside [extensionsDirectory], and so never inside anything a host is given. */
        fun fileFor(extensionsDirectory: File): File {
            val directory = extensionsDirectory.canonicalFile
            return File(directory.parentFile ?: directory, "${directory.name}.decisions")
        }
    }
}

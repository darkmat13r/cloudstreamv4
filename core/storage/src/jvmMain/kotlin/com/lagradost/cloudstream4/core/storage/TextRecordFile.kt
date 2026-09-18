package com.lagradost.cloudstream4.core.storage

import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A `Map<String, String>` in one file, written atomically.
 *
 * This is the whole of the JVM persistence story: [FileKeyValueJsonStore] stores its JSON records
 * in one of these directly, and [FilePreferenceStore] stores type-tagged primitives in another.
 *
 * ## Why not `java.util.prefs.Preferences`
 *
 * The JDK already ships a key/value store, and it cannot hold what CloudStream stores.
 * `Preferences.MAX_VALUE_LENGTH` is **8192 characters** and `put` throws
 * `IllegalArgumentException` past it. CloudStream's JSON records go far beyond that — the account
 * array at `data_store_helper/account`, the cached sync lists, the download header caches and the
 * per-account token arrays are all whole object graphs encoded into a single string — so a
 * `Preferences`-backed store would throw on the first real account list. (`MAX_KEY_LENGTH` is 80,
 * which flat keys like `auth_tokens/OpenSubtitles/0` are under, but the value limit alone settles
 * it.) Its backing store is also per-user and outside the application directory — a `.plist` in
 * `~/Library/Preferences` or a registry hive — which is the wrong place for a portable install and
 * awkward to point elsewhere. Hence a file, and hence one written by hand.
 *
 * ## The format
 *
 * ```
 * #cloudstream-storage 1
 * <escaped key>=<escaped value>
 * ```
 *
 * UTF-8, `\n`-terminated lines, header first. `\`, newline, carriage return, `=` and `,` are
 * backslash-escaped in both halves, so **any** string round-trips character for character: a value
 * with embedded newlines, quotes, `=` signs or astral-plane characters comes back exactly as it
 * went in. (`,` is escaped for [FilePreferenceStore], which joins string-set members with it.)
 * Anything else — a missing or altered header, a line with no unescaped `=`, an unknown escape, a
 * zero-length file, bytes that are not valid UTF-8 — is corruption, not data.
 *
 * ## Durability
 *
 * Every mutation rewrites the whole file: content into a temp file **in the same directory**,
 * `fsync`, then `ATOMIC_MOVE` over the target, then an `fsync` of the directory where the platform
 * allows it. A crash, a kill or a full disk therefore leaves either the previous file or the new
 * one, never a truncated one, and never a half-written record. A temp file left behind by a crashed
 * process is ignored by [load] (it is not the target path) and costs a few bytes until something
 * cleans it up.
 *
 * The cost is that a single `set` writes the whole file. That is the right trade for CloudStream's
 * stores — a few hundred keys, read constantly and written rarely — and it is why
 * [KeyValueJsonStore.edit] batching matters: one batch is one write.
 *
 * ## Concurrency
 *
 * **Within one process, one instance is safe from any number of threads.** Every read and every
 * write takes this object's lock, and a write is applied to the in-memory map only after the file
 * has been replaced, so a failed write leaves memory and disk agreeing.
 *
 * **Two instances over the same path are not safe, whether or not they are in the same JVM.** Each
 * holds its own snapshot and each write rewrites the whole file, so the later writer silently drops
 * whatever the other one wrote since it loaded. Nothing is corrupted — the move is atomic, so the
 * file is always one writer's complete map — but writes are lost. There is no file lock: an
 * advisory `FileLock` would serialise the writes without fixing the lost update (the loser would
 * still be writing a stale snapshot), and a mandatory one would deadlock the app against a stale
 * lock from a crashed instance. The fix for a real multi-window desktop app is one instance per
 * file, which is what [JvmStorage] hands out, plus a single-instance check at launch.
 *
 * @param writeFault a test seam: invoked after the temp file is written and `fsync`ed and before
 * the atomic move, so a test can simulate a crash at the one moment where a non-atomic writer would
 * have already destroyed the old file. Always `null` in production.
 */
internal class TextRecordFile(
    val path: Path,
    private val onCorruption: StorageCorruptionListener,
    private val writeFault: (() -> Unit)? = null,
) {

    private val lock = ReentrantLock()

    /** The authoritative copy while this instance lives. Insertion-ordered so the file is stable. */
    private val records: MutableMap<String, String> = LinkedHashMap()

    init {
        records.putAll(load())
    }

    fun get(key: String): String? = lock.withLock { records[key] }

    fun contains(key: String): Boolean = lock.withLock { records.containsKey(key) }

    fun keys(): Set<String> = lock.withLock { records.keys.toSet() }

    fun snapshot(): Map<String, String> = lock.withLock { LinkedHashMap(records) }

    /**
     * Applies [mutate] to a copy of the records and, if that changed anything, rewrites the file.
     *
     * Returns the keys whose value or presence changed, so [FilePreferenceStore] can notify exactly
     * those. A block that changes nothing writes nothing.
     *
     * The mutation is visible to this instance only once the file has been replaced: if the write
     * throws, the exception propagates and neither disk nor memory has moved.
     */
    fun edit(mutate: (MutableMap<String, String>) -> Unit): Set<String> = lock.withLock {
        val updated = LinkedHashMap(records)
        mutate(updated)
        val changed = changedKeys(records, updated)
        if (changed.isEmpty()) return@withLock emptySet()

        write(updated)

        records.clear()
        records.putAll(updated)
        changed
    }

    private fun changedKeys(before: Map<String, String>, after: Map<String, String>): Set<String> =
        (before.keys + after.keys).filterTo(LinkedHashSet()) { before[it] != after[it] }

    // --- reading ---

    private fun load(): Map<String, String> {
        if (!Files.isRegularFile(path)) return emptyMap()
        return try {
            // readString decodes strictly: invalid UTF-8 throws rather than yielding replacement
            // characters, so binary garbage is caught here instead of parsing as nonsense.
            parse(Files.readString(path, StandardCharsets.UTF_8))
        } catch (corrupt: StorageFormatException) {
            onCorruption.onCorruption(path, quarantine(), corrupt)
            emptyMap()
        } catch (corrupt: CharacterCodingException) {
            onCorruption.onCorruption(path, quarantine(), corrupt)
            emptyMap()
        } catch (unreadable: IOException) {
            // Not the file's fault — no permission, a vanished mount, a directory in its place.
            // Moving it would fail for the same reason, so it is reported and left alone; the store
            // starts empty and the next write will try to replace it.
            onCorruption.onCorruption(path, null, unreadable)
            emptyMap()
        }
    }

    private fun parse(text: String): Map<String, String> {
        // Zero bytes is not "no records": an empty file is what a truncating writer leaves behind,
        // and this writer never produces one — the header alone is 22 bytes.
        if (text.isEmpty()) throw StorageFormatException("the file is empty")

        val lines = text.split('\n')
        if (lines.first() != HEADER) throw StorageFormatException("unexpected header")

        val parsed = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val separator = line.indexOfUnescaped('=')
                ?: throw StorageFormatException("a record line has no unescaped '='")
            parsed[unescape(line.substring(0, separator))] = unescape(line.substring(separator + 1))
        }
        return parsed
    }

    /**
     * Moves the damaged file aside so it can be looked at, and so the next write starts clean.
     * Returns where it went, or `null` when it could not be moved.
     */
    private fun quarantine(): Path? {
        val damaged = path.resolveSibling("${path.fileName}.corrupt-${System.currentTimeMillis()}")
        return runCatching { Files.move(path, damaged) }.getOrNull()
    }

    // --- writing ---

    private fun write(values: Map<String, String>) {
        val directory = path.toAbsolutePath().parent
        if (!Files.isDirectory(directory)) {
            Files.createDirectories(directory)
            // Only on the directory this code created. An existing one — `~/Library/Application
            // Support`, a directory the user pointed us at with -Dcloudstream.storage.dir — keeps
            // whatever permissions it has; tightening someone else's directory on every write is
            // not this class's business.
            restrictToOwner(directory, isDirectory = true)
        }

        val bytes = render(values).toByteArray(StandardCharsets.UTF_8)
        val temp = Files.createTempFile(directory, path.fileName.toString(), TEMP_SUFFIX)
        try {
            // Before a byte is in it: the temp file must never be more readable than the target.
            restrictToOwner(temp, isDirectory = false)
            FileOutputStream(temp.toFile()).use { out ->
                out.write(bytes)
                out.flush()
                // The bytes are on the device, not just in the page cache. Without this the move
                // can be durable while its content is not, which is the one ordering that loses data.
                out.fd.sync()
            }
            writeFault?.invoke()
            moveOver(temp, path)
            syncDirectory(directory)
        } catch (failure: Throwable) {
            runCatching { Files.deleteIfExists(temp) }
            // The raw failure names the temp file, which tells a reader nothing. Name the store.
            if (failure is IOException) throw IOException("could not write the store at $path", failure)
            throw failure
        }
    }

    private fun render(values: Map<String, String>): String = buildString {
        append(HEADER).append('\n')
        for ((key, value) in values) {
            append(escape(key)).append('=').append(escape(value)).append('\n')
        }
    }

    private fun moveOver(temp: Path, target: Path) {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            // Both paths are in the same directory, so this should not happen on any filesystem the
            // app runs on. If some exotic one says no, a plain replace still beats not writing.
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * `fsync` of the directory entry, so the rename itself survives a power cut on ext4 and friends.
     *
     * Opening a directory as a channel is POSIX-only; Windows throws, and NTFS does not need it.
     * A failure here costs durability of the rename in a power loss, never correctness, so it is
     * swallowed rather than failing a write that has already landed.
     */
    private fun syncDirectory(directory: Path) {
        runCatching {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        }
    }

    /**
     * `rw-------` for a file, `rwx------` for a directory, where the filesystem has POSIX
     * permissions.
     *
     * On Windows, and on any filesystem without a [PosixFileAttributeView] (an exFAT stick, a
     * mounted SMB share), the view is absent and this does nothing: the file inherits the
     * directory's ACL, which for `%APPDATA%` is the user's own profile and for a removable drive is
     * whatever that drive grants — i.e. possibly everyone. That is the honest limit of what this
     * can promise, and it matters most for the secret store; see [JvmStorage.secrets].
     *
     * Applied to the **temp** file, before anything is written into it, so the content is never
     * briefly world-readable. A consequence of replacing the file on every write is that
     * permissions set on the store file by hand do not survive a write: each write installs a
     * freshly created `rw-------` file in its place.
     */
    private fun restrictToOwner(target: Path, isDirectory: Boolean) {
        runCatching {
            val view = Files.getFileAttributeView(target, PosixFileAttributeView::class.java)
                ?: return
            view.setPermissions(
                PosixFilePermissions.fromString(if (isDirectory) "rwx------" else "rw-------"),
            )
        }
    }

    internal companion object {
        /** First line of every file. A file that does not start with it is not one of ours. */
        const val HEADER: String = "#cloudstream-storage 1"

        /** Suffix of the same-directory temp file. Never the target path, so [load] ignores it. */
        const val TEMP_SUFFIX: String = ".tmp"
    }
}

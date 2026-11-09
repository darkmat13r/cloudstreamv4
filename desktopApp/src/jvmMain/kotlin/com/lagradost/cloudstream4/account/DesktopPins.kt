package com.lagradost.cloudstream4.account

import com.lagradost.cloudstream4.feature.account.PinLimit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/** What a PIN prompt's entry came to. */
internal sealed interface PinCheck {
    /** The PIN was right; the profile's count is cleared. */
    data object Right : PinCheck

    /** The PIN was wrong. [waitMs] is the wait it started, 0 while wrong PINs are still free. */
    data class Wrong(val waitMs: Long) : PinCheck

    /** A wait is running, so nothing was compared. */
    data class Waiting(val waitMs: Long) : PinCheck

    /** The PINs file is there but could not be read, so nothing was compared and nothing counted: it stays locked. */
    data object Unreadable : PinCheck
}

/** What reading the PINs file came to. "Couldn't read" is never "no file" (the reviewer of `#286`). */
private sealed interface PinsRead {
    /** No file: no profile has a PIN. */
    data object Absent : PinsRead

    data class Read(val pins: Map<Int, String>) : PinsRead {
        override fun toString(): String = "Read(ids=${pins.keys})"
    }

    /** A file that could not be read: permissions, too large, not ours. Nothing may be decided or written from it. */
    data object Failed : PinsRead
}

@Serializable
private data class StoredPins(val pins: Map<String, String> = emptyMap(), val keychain: Boolean = false)

/** What `pins.json` is on a signed build: D1's PINs, the Keychain's marker, nothing, or something that cannot be read. */
private sealed interface PinsFile {
    data object Absent : PinsFile

    /** `{"keychain":true}`: the PINs are in the Keychain, and an item missing from it is not "no PINs". */
    data object Marker : PinsFile

    class Legacy(val pins: Map<Int, String>) : PinsFile {
        override fun toString(): String = "Legacy(ids=${pins.keys})"
    }

    data object Unreadable : PinsFile
}

@Serializable
private data class StoredAttempts(val attempts: Map<String, StoredAttempt> = emptyMap())

@Serializable
private data class StoredAttempt(val wrong: Int, val startedAt: Long, val waitMs: Long)

/**
 * The Mac's profile PINs and `BUG-55`'s limit on them (`KMP-95c`), in two owner-only files beside the profiles:
 * `pins` and `pin-attempts`. **Never in `profiles.json`** (the reviewer of `#278`): the PINs are kept apart from
 * anything shared, logged or shown.
 * - **In the Keychain on a signed build** (`KMP-95f`): given a [vault], the PINs are one item there, `pins`. D1's file is
 *   moved once: written to the Keychain, read back, and replaced with a marker, `{"keychain":true}`, only when what came
 *   back is what was written (the PM, 2026-11-09). While the Keychain holds a PIN the marker stays, so an item that goes
 *   missing (a re-signed app, a deleted item) is "couldn't read", never "no PINs" (the reviewer of `#308`). A move that
 *   fails keeps the file, the PINs are read from it so nothing opens, and every change is refused. A Keychain that
 *   cannot be read is the same as a file that cannot be read: locked, and never the file instead.
 * - **An unsigned build** has no [vault] and keeps D1's file. If it finds the marker, the PINs are in a Keychain it
 *   cannot use, so every profile but the first stays locked.
 * - **Called from the window's one UI thread**, as everything here is; a Keychain call is short and blocks it briefly.
 *
 * - **Not hashed** (the PM's ruling, D1, as `d081`): a hash of a 4-digit PIN is reversed at once, so it would only look
 *   safer. The file is owner-only and written temp-then-move, as `profiles.json` is.
 * - **What a PIN guards.** It is a household lock: it keeps a profile's history from someone at the window, not from
 *   someone who can read this Mac account's files. A deleted `pins` file is no PINs (D1).
 * - **"Couldn't read" is never "no file"** (the reviewer of `#286`). A `pins` file that cannot be read refuses a locked
 *   profile's check without comparing or counting ([PinCheck.Unreadable]), and [set], [remove], [forget] and
 *   [retainOnly] write nothing and return false. So a read failure never opens a locked profile, and never rewrites
 *   the file with only one profile's PIN, wiping the others'.
 * - **The same at launch** (the PM, 2026-11-09): when the file cannot be read then, which profiles have a PIN is not
 *   known, so every profile but the first is locked until it can be read, and its check says why. Nobody is locked
 *   out: the first profile never has a PIN. Otherwise "break the file, then restart" would open them all.
 * - **Attempts** are counts and waits only, never a PIN or anything taken from one, on [clock]: milliseconds from boot.
 *   The Mac's is `System.nanoTime()`, measured there to count from boot in every process, to ignore the wall clock, and
 *   not to advance in sleep, which can only lengthen a wait. A reboot is [PinLimit]'s case.
 * - The first profile is never locked: it is the way back in when a PIN is forgotten (A6c).
 * - **No PIN is held in memory** (the reviewer of `#286`): only which profiles have one. A check reads `pins` for that
 *   one comparison, and a change reads, changes and writes it.
 * - **A change that cannot be written changes nothing** (the reviewer of `#286`): [set] and [remove] return false and
 *   the profile stays as it was, so it never looks locked now and opens after a restart, or the other way round.
 *
 * @param directory where both files live: `~/.cloudstream4` in the app, a temporary directory in a test.
 * @param onSaveFailed told the class of a failure to write either file or the Keychain, never a path or contents.
 * @param vault the Keychain, on a signed build only (`AccountStorage.forBuild`); null keeps D1's file.
 */
internal class DesktopPins(
    directory: File,
    clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val onSaveFailed: (Throwable) -> Unit = {},
    private val vault: SecretVault? = null,
) {
    private val pinsFile = File(directory, PINS_FILE)
    private val attemptsFile = File(directory, ATTEMPTS_FILE)
    /** The profiles with a PIN. Never the PINs themselves. */
    private val locked: MutableSet<Int> = mutableSetOf()

    /**
     * True until `pins` is read: at launch, and on after a launch that could not read it. While it is, every
     * profile but the first is locked.
     */
    private var unknown = true
    private val attempts: MutableMap<Int, PinLimit.Record> = readAttempts().toMutableMap()

    init {
        // Fills [locked] and ends [unknown], unless the file cannot be read.
        readPins()
    }

    private val limit = PinLimit(
        store = object : PinLimit.Store {
            override fun read(key: Int): PinLimit.Record? = attempts[key]

            override fun write(key: Int, record: PinLimit.Record?) {
                if (record == null) attempts.remove(key) else attempts[key] = record
                writeAttempts()
            }
        },
        clock = clock,
    )

    fun hasPin(id: Int): Boolean = id != StoredProfiles.FIRST && (unknown || id in locked)

    /** How long [id]'s prompt must still wait, in ms; 0 when it may compare now. */
    fun waitMs(id: Int): Long = limit.waitMs(id)

    /** Compares [entry] with [id]'s PIN, under the limit. A profile with no PIN has nothing to compare. */
    fun check(id: Int, entry: String): PinCheck {
        val wait = limit.waitMs(id)
        if (wait > 0L) return PinCheck.Waiting(wait)
        // Read for this comparison only. A PIN gone from the file reads as none, as a deleted file does; a file that
        // cannot be read refuses, and counts nothing.
        val pin = when (val read = readPins()) {
            PinsRead.Failed -> return PinCheck.Unreadable
            PinsRead.Absent -> null
            is PinsRead.Read -> read.pins[id]
        } ?: return PinCheck.Right.also { locked.remove(id) }
        // Constant time: not that four digits need it, but nothing here should hint at how much of a guess was right.
        return if (MessageDigest.isEqual(pin.toByteArray(), entry.toByteArray())) {
            limit.right(id)
            PinCheck.Right
        } else {
            PinCheck.Wrong(limit.wrong(id))
        }
    }

    /** Sets [id]'s PIN to [pin], four ASCII digits. The first profile is never locked. */
    fun set(id: Int, pin: String): Boolean {
        if (id == StoredProfiles.FIRST || !isPin(pin)) return false
        val stored = readPins().pinsOrNull() ?: return false
        if (!writePins(stored + (id to pin))) return false
        locked += id
        limit.right(id)
        return true
    }

    /** Removes [id]'s PIN. False when that could not be read or written: the profile stays locked. */
    fun remove(id: Int): Boolean {
        val stored = readPins().pinsOrNull() ?: return false
        if (id !in stored) return true.also { locked.remove(id) }
        if (!writePins(stored - id)) return false
        locked.remove(id)
        return true
    }

    /**
     * [id]'s profile is gone, or its id is about to be reused (`add`): its PIN and its count go with it. The file is
     * written only when it holds a PIN for [id]. False, with nothing changed, when it could not be read or written.
     */
    fun forget(id: Int): Boolean {
        val stored = readPins().pinsOrNull() ?: return false
        if (id in stored && !writePins(stored - id)) return false
        locked.remove(id)
        limit.forget(id)
        return true
    }

    /**
     * Drops the PIN and count of every profile not in [ids], as the launch sweep does libraries. False, with nothing
     * changed, when the PINs could not be read or written.
     */
    fun retainOnly(ids: Set<Int>): Boolean {
        val stored = readPins().pinsOrNull() ?: return false
        if (!ids.containsAll(stored.keys) && !writePins(stored.filterKeys { it in ids })) return false
        locked.retainAll(ids)
        if (attempts.keys.retainAll(ids)) writeAttempts()
        return true
    }

    private fun PinsRead.pinsOrNull(): Map<Int, String>? = when (this) {
        PinsRead.Absent -> emptyMap()
        is PinsRead.Read -> pins
        PinsRead.Failed -> null
    }

    /** Reads `pins`. Any read that comes back is also what [locked] now holds, ending a launch's [unknown]. */
    private fun readPins(): PinsRead = (if (vault == null) readPinsFile() else readPinsVault(vault)).also { read ->
        val ids = when (read) {
            PinsRead.Failed -> return@also
            PinsRead.Absent -> emptySet()
            is PinsRead.Read -> read.pins.keys
        }
        if (unknown) {
            locked.clear()
            locked += ids
            unknown = false
        }
    }

    /**
     * D1's file, on an unsigned build. The Keychain's marker is "couldn't read": the PINs moved to a Keychain this build
     * cannot use, so every profile but the first stays locked (the reviewer of `#308`).
     */
    private fun readPinsFile(): PinsRead = when (val file = pinsFileState()) {
        PinsFile.Absent -> PinsRead.Absent
        is PinsFile.Legacy -> PinsRead.Read(file.pins)
        PinsFile.Marker, PinsFile.Unreadable -> PinsRead.Failed
    }

    private fun pinsFileState(): PinsFile {
        // Only a file known not to be there is "none": a directory that cannot be searched is not that.
        if (Files.notExists(pinsFile.toPath())) return PinsFile.Absent
        val stored = read(pinsFile) { json.decodeFromString(StoredPins.serializer(), it) } ?: return PinsFile.Unreadable
        return if (stored.keychain) PinsFile.Marker else PinsFile.Legacy(kept(stored))
    }

    /**
     * The Keychain's `pins` item, with `pins.json` as D1's PINs to move once, or as the marker that says they moved.
     *
     * - **The marker** (the reviewer of `#308`): while the Keychain holds a PIN, `pins.json` is `{"keychain":true}`. So a
     *   missing item with the marker there (a re-signed app, a deleted item) is "couldn't read", never "no PINs", and an
     *   unsigned build reading the marker stays locked too. It goes when the last PIN does.
     * - **The move**: D1's PINs are written to the Keychain and read back; only a match replaces the file with the marker.
     *   A write that fails keeps the file. A read-back that differs keeps it and removes the item.
     * - **An item beside D1's file** (a move whose marker or delete failed): the file is replaced with the marker only if
     *   it holds the same PINs. If it differs, it is "couldn't read", and the item is removed so the next launch moves
     *   the file again. A household lock, as D1 says: this guards against a step that failed, not someone who can write
     *   this Mac account's files.
     */
    private fun readPinsVault(vault: SecretVault): PinsRead {
        val file = pinsFileState()
        when (val item = vault.read(PINS_ITEM)) {
            VaultRead.Failed -> return PinsRead.Failed
            is VaultRead.Found -> {
                val pins = parseItem(item.bytes) ?: return PinsRead.Failed
                return when (file) {
                    is PinsFile.Legacy -> if (file.pins == pins) {
                        markInKeychain(pins)
                        PinsRead.Read(pins)
                    } else {
                        vault.delete(PINS_ITEM)
                        PinsRead.Failed
                    }
                    PinsFile.Absent -> PinsRead.Read(pins).also { if (pins.isNotEmpty()) markInKeychain(pins) }
                    // The marker beside an item with no PINs is left from a removal whose marker delete failed: it goes.
                    PinsFile.Marker -> PinsRead.Read(pins).also { if (pins.isEmpty()) markInKeychain(pins) }
                    PinsFile.Unreadable -> PinsRead.Read(pins)
                }
            }
            VaultRead.Absent -> Unit
        }
        val legacy = when (file) {
            PinsFile.Absent -> return PinsRead.Absent
            PinsFile.Marker, PinsFile.Unreadable -> return PinsRead.Failed
            is PinsFile.Legacy -> file.pins
        }
        if (writeItem(vault, legacy)) {
            if ((vault.read(PINS_ITEM) as? VaultRead.Found)?.let { parseItem(it.bytes) } == legacy) {
                markInKeychain(legacy)
            } else {
                vault.delete(PINS_ITEM)
            }
        }
        return PinsRead.Read(legacy)
    }

    /** `pins.json` becomes the marker while the Keychain holds a PIN, and goes when it holds none. False if it could not. */
    private fun markInKeychain(pins: Map<Int, String>): Boolean = if (pins.isEmpty()) {
        runCatching { Files.deleteIfExists(pinsFile.toPath()) }.onFailure(onSaveFailed).isSuccess
    } else {
        write(pinsFile, MARKER)
    }

    /** The PINs in a Keychain item, cleared from memory once parsed. Null when it is not ours. */
    private fun parseItem(bytes: ByteArray): Map<Int, String>? = try {
        if (bytes.size > MAX_FILE_BYTES) null
        else runCatching { kept(json.decodeFromString(StoredPins.serializer(), bytes.decodeToString())) }.getOrNull()
    } finally {
        bytes.fill(0)
    }

    private fun kept(stored: StoredPins): Map<Int, String> = stored.pins.mapNotNull { (key, pin) ->
        val id = key.toIntOrNull()?.takeIf { it in 1 until DesktopProfileFile.MAX_ID } ?: return@mapNotNull null
        if (isPin(pin)) id to pin else null
    }.toMap()

    /**
     * A change on a signed build. The marker is written first, so the Keychain never holds a PIN with no marker beside
     * it; a marker that cannot be written refuses the change. If the Keychain then refuses, a marker this change made is
     * taken back, so a failed first PIN does not lock every profile. The last PIN removed takes the marker with it.
     * While D1's file is still there, or `pins.json` cannot be read, every change is refused.
     */
    private fun writeVault(vault: SecretVault, pins: Map<Int, String>): Boolean {
        // D1's file still there means its move has not finished: a change now could only lose it, so it is refused.
        val marked = when (pinsFileState()) {
            PinsFile.Marker -> true
            PinsFile.Absent -> false
            is PinsFile.Legacy, PinsFile.Unreadable -> return false
        }
        if (pins.isNotEmpty() && !marked && !write(pinsFile, MARKER)) return false
        if (!writeItem(vault, pins)) {
            if (!marked) runCatching { Files.deleteIfExists(pinsFile.toPath()) }.onFailure(onSaveFailed)
            return false
        }
        if (pins.isEmpty()) markInKeychain(pins)
        return true
    }

    private fun writeItem(vault: SecretVault, pins: Map<Int, String>): Boolean {
        val bytes = encodePins(pins).toByteArray()
        return try {
            vault.write(PINS_ITEM, bytes).also { if (!it) onSaveFailed(KeychainWriteFailed()) }
        } finally {
            bytes.fill(0)
        }
    }

    private fun readAttempts(): Map<Int, PinLimit.Record> {
        val stored = read(attemptsFile) { json.decodeFromString(StoredAttempts.serializer(), it) } ?: return emptyMap()
        return stored.attempts.mapNotNull { (key, at) ->
            val id = key.toIntOrNull()?.takeIf { it in 0 until DesktopProfileFile.MAX_ID } ?: return@mapNotNull null
            // A count or a wait out of range was not written here; it reads as the strictest record, never a free one.
            val record = if (at.wrong in 0..MAX_COUNT && at.waitMs in 0..PinLimit.MAX_WAIT_MS) {
                PinLimit.Record(at.wrong, at.startedAt, at.waitMs)
            } else {
                PinLimit.Record(MAX_COUNT, at.startedAt, PinLimit.MAX_WAIT_MS)
            }
            id to record
        }.toMap()
    }

    private fun writePins(pins: Map<Int, String>): Boolean =
        if (vault == null) write(pinsFile, encodePins(pins)) else writeVault(vault, pins)

    private fun encodePins(pins: Map<Int, String>): String =
        json.encodeToString(StoredPins.serializer(), StoredPins(pins.mapKeys { it.key.toString() }))

    private fun writeAttempts(): Boolean = write(
        attemptsFile,
        json.encodeToString(
            StoredAttempts.serializer(),
            StoredAttempts(attempts.map { (id, r) -> id.toString() to StoredAttempt(r.wrong, r.startedAt, r.waitMs) }.toMap()),
        ),
    )

    private fun <T> read(file: File, parse: (String) -> T): T? {
        val bytes = runCatching {
            if (!file.isFile) null else file.inputStream().use { it.readNBytes(MAX_FILE_BYTES + 1) }
        }.getOrNull() ?: return null
        if (bytes.size > MAX_FILE_BYTES) return null
        return runCatching { parse(bytes.decodeToString()) }.getOrNull()
    }

    /** Owner-only, temp-then-move, as `profiles.json`. */
    private fun write(file: File, text: String): Boolean = runCatching {
        val directory = checkNotNull(file.parentFile).toPath()
        if (!Files.isDirectory(directory)) {
            val ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER_DIR))
            try {
                Files.createDirectories(directory, ownerOnly)
            } catch (e: UnsupportedOperationException) {
                Files.createDirectories(directory)
            }
        }
        val next = Files.createTempFile(directory, file.name, ".tmp")
        try {
            runCatching { Files.setPosixFilePermissions(next, PosixFilePermissions.fromString(OWNER_ONLY)) }
            Files.writeString(next, text)
            Files.move(next, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(next)
        }
    }.onFailure(onSaveFailed).isSuccess

    internal companion object {
        const val PINS_FILE = "pins.json"
        const val ATTEMPTS_FILE = "pin-attempts.json"

        /** The Keychain item that holds the PINs on a signed build. */
        const val PINS_ITEM = "pins"

        /** `pins.json` once the PINs are in the Keychain. */
        const val MARKER = "{\"keychain\":true}"
        const val PIN_LENGTH = 4
        private const val MAX_FILE_BYTES = 64 * 1024
        private const val MAX_COUNT = 1_000
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val OWNER_ONLY = "rw-------"
        private const val OWNER_DIR = "rwx------"
        private val json = Json { ignoreUnknownKeys = true }

        fun isPin(pin: String): Boolean = pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }
    }
}

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
}

@Serializable
private data class StoredPins(val pins: Map<String, String> = emptyMap())

@Serializable
private data class StoredAttempts(val attempts: Map<String, StoredAttempt> = emptyMap())

@Serializable
private data class StoredAttempt(val wrong: Int, val startedAt: Long, val waitMs: Long)

/**
 * The Mac's profile PINs and `BUG-55`'s limit on them (`KMP-95c`), in two owner-only files beside the profiles:
 * `pins` and `pin-attempts`. **Never in `profiles.json`** (the reviewer of `#278`): the PINs are kept apart from
 * anything shared, logged or shown. They move to the Keychain in `KMP-95f`.
 *
 * - **Not hashed** (the PM's ruling, D1, as `d081`): a hash of a 4-digit PIN is reversed at once, so it would only look
 *   safer. The file is owner-only and written temp-then-move, as `profiles.json` is.
 * - **What a PIN guards.** It is a household lock: it keeps a profile's history from someone at the window, not from
 *   someone who can read this Mac account's files, who could delete the file as easily. So a `pins` file that cannot
 *   be read reads as no PINs, as a deleted one would; failing closed would lock profiles behind PINs nobody can enter.
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
 * @param onSaveFailed told the class of a failure to write either file, never its path or contents.
 */
internal class DesktopPins(
    directory: File,
    clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val onSaveFailed: (Throwable) -> Unit = {},
) {
    private val pinsFile = File(directory, PINS_FILE)
    private val attemptsFile = File(directory, ATTEMPTS_FILE)
    /** The profiles with a PIN. Never the PINs themselves. */
    private val locked: MutableSet<Int> = readPins().keys.toMutableSet()
    private val attempts: MutableMap<Int, PinLimit.Record> = readAttempts().toMutableMap()

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

    fun hasPin(id: Int): Boolean = id in locked

    /** How long [id]'s prompt must still wait, in ms; 0 when it may compare now. */
    fun waitMs(id: Int): Long = limit.waitMs(id)

    /** Compares [entry] with [id]'s PIN, under the limit. A profile with no PIN has nothing to compare. */
    fun check(id: Int, entry: String): PinCheck {
        val wait = limit.waitMs(id)
        if (wait > 0L) return PinCheck.Waiting(wait)
        // Read for this comparison only. A PIN gone from the file reads as none, as a deleted file does.
        val pin = readPins()[id] ?: return PinCheck.Right.also { locked.remove(id) }
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
        if (!writePins(readPins() + (id to pin))) return false
        locked += id
        limit.right(id)
        return true
    }

    /** Removes [id]'s PIN. False when that could not be written: the profile stays locked. */
    fun remove(id: Int): Boolean {
        val stored = readPins()
        if (id !in stored) return true.also { locked.remove(id) }
        if (!writePins(stored - id)) return false
        locked.remove(id)
        return true
    }

    /**
     * [id]'s profile is gone, or its id is about to be reused (`add`): its PIN and its count go with it. The file is
     * written only when it holds a PIN for [id].
     */
    fun forget(id: Int) {
        val stored = readPins()
        if (id in stored) writePins(stored - id)
        locked.remove(id)
        limit.forget(id)
    }

    /** Drops the PIN and count of every profile not in [ids], as the launch sweep does libraries. */
    fun retainOnly(ids: Set<Int>) {
        val stored = readPins()
        if (!ids.containsAll(stored.keys)) writePins(stored.filterKeys { it in ids })
        locked.retainAll(ids)
        if (attempts.keys.retainAll(ids)) writeAttempts()
    }

    private fun readPins(): Map<Int, String> {
        val stored = read(pinsFile) { json.decodeFromString(StoredPins.serializer(), it) } ?: return emptyMap()
        return stored.pins.mapNotNull { (key, pin) ->
            val id = key.toIntOrNull()?.takeIf { it in 1 until DesktopProfileFile.MAX_ID } ?: return@mapNotNull null
            if (isPin(pin)) id to pin else null
        }.toMap()
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

    private fun writePins(pins: Map<Int, String>): Boolean = write(
        pinsFile,
        json.encodeToString(StoredPins.serializer(), StoredPins(pins.mapKeys { it.key.toString() })),
    )

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

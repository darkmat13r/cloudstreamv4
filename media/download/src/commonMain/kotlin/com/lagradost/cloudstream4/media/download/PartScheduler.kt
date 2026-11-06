package com.lagradost.cloudstream4.media.download

import kotlin.concurrent.Volatile

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** [end] is exclusive and is [UNBOUNDED] for a single stream of unknown length. */
internal class PartEntry(
    val id: Int,
    val start: Long,
    var end: Long,
    /**
     * The absolute offset up to which a writer has claimed bytes, written or about to be. It is reset
     * from the part file's size at every attempt (rule 1); between attempts it never exceeds what a
     * split may assume. Read and written only under the table's lock.
     */
    var reserved: Long,
    var phase: PartState = PartState.PENDING,
) {
    var attempt: Int = 0

    companion object {
        const val UNBOUNDED: Long = Long.MAX_VALUE
    }
}

/** How many bytes of a buffer a writer may write, and whether the part is now full. */
internal class Reservation(val allowed: Int, val atEnd: Boolean)

internal sealed interface AttemptPlan {
    /** The part file already holds the whole range. */
    data object Complete : AttemptPlan

    /** The part file is longer than its range: delete it and ask again (rule 2). */
    data object TooLong : AttemptPlan

    /** Ask the server for `[offset, end)`. */
    class Fetch(val offset: Long, val end: Long) : AttemptPlan
}

/**
 * The parts of one download and the only place their bounds change.
 *
 * ## Split versus writer
 *
 * A writer claims bytes with [reserve] **before** writing them, under the lock, and a claim is clipped
 * to the part's current end. A split reads the claimed cursor under the same lock and cuts the part at
 * or above it. So bytes a writer has claimed are always below any cut a later split makes, and a
 * writer can never write into the range that now belongs to a new part, whatever order the two run in.
 *
 * Persisting the state after a split happens under the lock, before [next] returns, so the new
 * part's worker cannot open a connection before the new structure is on disk.
 */
internal class PartTable(
    total: Long?,
    initial: List<PartEntry>,
    private val minPartBytes: Long,
    private val maxPartFiles: Int,
    private val splittable: Boolean,
    private val persistor: (suspend (List<PersistedPart>) -> Unit)?,
) {
    /** Known length, or `null`. Written under the lock; read unlocked only for progress display. */
    @Volatile
    var total: Long? = total

    /** `true` once the server declared a length (in the probe or on the response). A length learned from the bytes received is not a declaration. */
    @Volatile
    var lengthDeclared: Boolean = total != null

    private val mutex = Mutex()
    private val entries = ArrayList<PartEntry>(initial.sortedBy { it.start })
    private var nextId = (initial.maxOfOrNull { it.id } ?: -1) + 1

    /** The next part for a worker: a pending one, else half of the active part with the most left. `null` ends the worker. */
    suspend fun next(): PartEntry? = mutex.withLock {
        entries.firstOrNull { it.phase == PartState.PENDING }?.let {
            it.phase = PartState.ACTIVE
            return@withLock it
        }
        if (!splittable || entries.size >= maxPartFiles) return@withLock null
        val candidate = entries
            .filter { it.phase == PartState.ACTIVE || it.phase == PartState.RETRYING }
            .maxByOrNull { it.end - it.reserved } ?: return@withLock null
        val left = candidate.end - candidate.reserved
        if (left < 2 * minPartBytes) return@withLock null
        val mid = candidate.reserved + left / 2
        val created = PartEntry(nextId++, mid, candidate.end, reserved = mid, phase = PartState.ACTIVE)
        candidate.end = mid
        entries.add(entries.indexOf(candidate) + 1, created)
        persistLocked()
        created
    }

    /**
     * Sets up an attempt for [part] from [onDisk], the size of its file read back from disk just now.
     * The cursor is reset to it, so nothing a previous attempt counted survives.
     */
    suspend fun beginAttempt(part: PartEntry, onDisk: Long): AttemptPlan = mutex.withLock {
        if (onDisk > part.end - part.start) return@withLock AttemptPlan.TooLong
        part.reserved = part.start + onDisk
        if (part.reserved >= part.end) {
            part.phase = PartState.DONE
            AttemptPlan.Complete
        } else {
            part.phase = PartState.ACTIVE
            AttemptPlan.Fetch(part.reserved, part.end)
        }
    }

    /** Single stream: every attempt starts at 0. [knownEnd] is the declared length, or `null`. */
    suspend fun beginSingle(part: PartEntry, knownEnd: Long?) = mutex.withLock {
        part.reserved = part.start
        part.phase = PartState.ACTIVE
        if (knownEnd != null) {
            part.end = knownEnd
            total = knownEnd
            lengthDeclared = true
        }
    }

    /** Claims up to [wanted] bytes for [part], clipped to its current end. */
    suspend fun reserve(part: PartEntry, wanted: Int): Reservation = mutex.withLock {
        val room = (part.end - part.reserved).coerceAtLeast(0L)
        val allowed = minOf(wanted.toLong(), room).toInt()
        part.reserved += allowed
        Reservation(allowed, atEnd = part.reserved >= part.end)
    }

    suspend fun markRetrying(part: PartEntry, attempt: Int) = mutex.withLock {
        part.phase = PartState.RETRYING
        part.attempt = attempt
    }

    suspend fun markActive(part: PartEntry) = mutex.withLock {
        part.phase = PartState.ACTIVE
        part.attempt = 0
    }

    suspend fun markDone(part: PartEntry) = mutex.withLock {
        part.phase = PartState.DONE
        part.attempt = 0
    }

    /**
     * A throttled worker (429, 503) hands its part back and stops, but only when some other worker is
     * still running, so a host that allows one connection still finishes. Decided under the lock, in the
     * same critical section in which [next] hands out parts, so the other worker cannot have left.
     */
    suspend fun yieldIfOthersActive(part: PartEntry): Boolean = mutex.withLock {
        val others = entries.any { it !== part && (it.phase == PartState.ACTIVE || it.phase == PartState.RETRYING) }
        if (others) {
            part.phase = PartState.PENDING
            part.attempt = 0
        }
        others
    }

    /** Single stream of unknown length finished: its length is what arrived. */
    suspend fun closeUnbounded(part: PartEntry): Long = mutex.withLock {
        val length = part.reserved - part.start
        part.end = part.start + length
        total = length
        part.phase = PartState.DONE
        length
    }

    suspend fun persist() = mutex.withLock { persistLocked() }

    private suspend fun persistLocked() {
        val write = persistor ?: return
        write(entries.map { PersistedPart(it.id, it.start, it.end, (it.reserved - it.start).coerceAtLeast(0L)) })
    }

    suspend fun layout(): List<PersistedPart> = mutex.withLock {
        entries.map { PersistedPart(it.id, it.start, it.end, (it.reserved - it.start).coerceAtLeast(0L)) }
    }

    suspend fun allDone(): Boolean = mutex.withLock { entries.all { it.phase == PartState.DONE } }

    suspend fun snapshot(): List<PartSnapshot> = mutex.withLock {
        entries.map {
            PartSnapshot(
                id = it.id,
                start = it.start,
                endExclusive = if (it.end == PartEntry.UNBOUNDED) null else it.end,
                bytesDone = (it.reserved - it.start).coerceAtLeast(0L),
                state = it.phase,
                attempt = it.attempt,
            )
        }
    }
}

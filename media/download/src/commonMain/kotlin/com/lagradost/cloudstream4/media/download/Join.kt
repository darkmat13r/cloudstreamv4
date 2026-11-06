package com.lagradost.cloudstream4.media.download

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.div
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlin.coroutines.coroutineContext

internal const val JOINED_FILE_NAME = "joined"

internal sealed interface JoinOutcome {
    data object Done : JoinOutcome

    /** Data the join needs is missing or inconsistent: wipe and start over, never report complete. */
    data object Restart : JoinOutcome
}

/**
 * Concatenates the part files into `joined`. The caller has persisted the `joining` phase **before**
 * calling this, because the first step moves part 0 and a kill after that must be recognisable.
 *
 * Part 0 is moved (atomically) to `joined`, and each next part is appended, flushed, closed and its
 * file deleted. Peak space is the total plus the largest part.
 *
 * ## Crash recovery
 *
 * `L = size(joined)` says how much is already in, whatever was happening when the process died. For a
 * part `[s, e)`: `e <= L` is in already (its leftover file is deleted); `s < L < e` skips the first
 * `L - s` bytes of the part file; `s == L` is taken whole; `s > L` is a gap, which cannot be repaired.
 * A part file that is missing or the wrong size where it is needed gives [JoinOutcome.Restart].
 * An empty list creates an empty `joined`.
 */
internal suspend fun joinParts(
    storage: DownloadStorage,
    tempDir: PlatformFile,
    parts: List<PersistedPart>,
    bufferBytes: Int,
): JoinOutcome {
    val joined = tempDir / JOINED_FILE_NAME
    if (parts.isEmpty()) {
        storageCall { storage.sink(joined, append = false).close() }
        return JoinOutcome.Done
    }
    val sorted = parts.sortedBy { it.start }
    val total = sorted.last().endExclusive

    var length: Long
    if (storageCall { storage.exists(joined) }) {
        length = storageCall { storage.size(joined) } ?: return JoinOutcome.Restart
    } else {
        val first = sorted.first()
        val firstFile = tempDir / partFileName(first.id)
        if (first.start != 0L || storageCall { storage.size(firstFile) } != first.endExclusive - first.start) {
            return JoinOutcome.Restart
        }
        storageCall { storage.atomicMove(firstFile, joined) }
        length = first.endExclusive
    }
    if (length > total) return JoinOutcome.Restart

    for (part in sorted) {
        val file = tempDir / partFileName(part.id)
        if (part.endExclusive <= length) {
            storageCall { storage.delete(file) }
            continue
        }
        if (part.start > length) return JoinOutcome.Restart
        if (storageCall { storage.size(file) } != part.endExclusive - part.start) return JoinOutcome.Restart
        appendFrom(storage, file, joined, skip = length - part.start, bufferBytes = bufferBytes)
        length = part.endExclusive
        storageCall { storage.delete(file) }
    }
    return JoinOutcome.Done
}

private suspend fun appendFrom(storage: DownloadStorage, from: PlatformFile, to: PlatformFile, skip: Long, bufferBytes: Int) {
    storageCall {
        val source = storage.source(from).buffered()
        val sink = storage.sink(to, append = true).buffered()
        var failed = false
        try {
            if (skip > 0L) source.skip(skip)
            val buffer = ByteArray(bufferBytes)
            while (true) {
                coroutineContext.ensureActive()
                val read = source.readAtMostTo(buffer, 0, buffer.size)
                if (read < 0) break
                sink.write(buffer, 0, read)
            }
            sink.flush()
        } catch (t: Throwable) {
            failed = true
            throw t
        } finally {
            var closeError: Throwable? = null
            withContext(NonCancellable) {
                runCatching { source.close() }
                try {
                    sink.close()
                } catch (e: Throwable) {
                    closeError = e
                }
            }
            closeError?.let { if (!failed) throw it }
        }
    }
}

/** The digest of [file], computed over bytes read back from disk. */
internal suspend fun hashOf(storage: DownloadStorage, file: PlatformFile, algorithm: HashAlgorithm, bufferBytes: Int): String =
    storageCall {
        val hash = newStreamingHash(algorithm)
        val source = storage.source(file).buffered()
        try {
            val buffer = ByteArray(bufferBytes)
            while (true) {
                coroutineContext.ensureActive()
                val read = source.readAtMostTo(buffer, 0, buffer.size)
                if (read < 0) break
                hash.update(buffer, 0, read)
            }
        } finally {
            runCatching { source.close() }
        }
        hash.finishHex()
    }

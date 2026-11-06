package com.lagradost.cloudstream4.media.download

import com.lagradost.cloudstream4.media.model.DownloadFailure
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.div
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlin.coroutines.cancellation.CancellationException

internal enum class PartOutcome { DONE, YIELDED }

internal fun partFileName(id: Int): String = "part-$id"

/**
 * Fetches one part: request, response checks, write loop, retries.
 *
 * The rule this class exists for (rule 1): **every attempt's offset is the size of the part file read
 * from disk after the previous attempt's sink was closed.** There is no counter, no cached size and no
 * index that carries over. A failure mid-stream therefore cannot cause a byte to be written twice or a
 * gap to be left, however the connection died.
 */
internal class PartFetcher(
    private val table: PartTable,
    private val transport: Transport,
    private val storage: DownloadStorage,
    private val tempDir: PlatformFile,
    private val options: DownloadOptions,
    private val validators: Validators,
) {
    private fun fileOf(part: PartEntry): PlatformFile = tempDir / partFileName(part.id)

    /** Ranged mode: the part is `[start, end)` of a file of [total] bytes. */
    suspend fun runRanged(part: PartEntry, total: Long, buffer: ByteArray): PartOutcome {
        val file = fileOf(part)
        var failures = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val onDisk = storageCall { storage.size(file) ?: 0L }
            var plan = table.beginAttempt(part, onDisk)
            if (plan is AttemptPlan.TooLong) {
                storageCall { storage.delete(file) }
                plan = table.beginAttempt(part, 0L)
            }
            val fetch = when (plan) {
                AttemptPlan.Complete -> return PartOutcome.DONE
                is AttemptPlan.Fetch -> plan
                AttemptPlan.TooLong -> throw FatalFailure(DownloadFailure.Unknown)
            }

            val problem: TransientFailure? = try {
                transport.get(fetch.offset, fetch.end - 1, validators.ifRange) { response ->
                    val limit = acceptRange(response, fetch.offset, fetch.end - 1, total)
                    stream(part, file, fetch.offset, limit, buffer, response)
                }
                null
            } catch (t: TransientFailure) {
                t
            }

            // Rule 20: the attempt's sink is closed; what counts is what the disk says.
            val after = storageCall { storage.size(file) ?: 0L }
            val progressed = after > onDisk
            if (problem == null) {
                if (progressed) {
                    failures = 0
                    table.markActive(part)
                    continue
                }
                // A response that delivered nothing is a failed attempt, not a finished one.
                failures++
                if (failures >= options.maxAttemptsPerPart) throw FatalFailure(DownloadFailure.NetworkLost)
                table.markRetrying(part, failures)
                delay(backoffMillis(failures, null, options))
                continue
            }
            // The budget is consecutive attempts that delivered no new byte: an attempt that made progress
            // and then failed is not one of them, and starts the count again from zero.
            if (progressed) failures = 0 else failures++
            table.markRetrying(part, maxOf(failures, 1))
            if (failures >= options.maxAttemptsPerPart) throw FatalFailure(problem.failure)
            if (problem.throttled && table.yieldIfOthersActive(part)) return PartOutcome.YIELDED
            delay(backoffMillis(maxOf(failures, 1), problem.retryAfterMillis, options))
        }
    }

    /**
     * Checks a response to a range request (rule 5) and returns the offset one past the last byte it
     * promises to carry, which is as far as the writer may go.
     */
    private fun acceptRange(response: HttpResponse, offset: Long, requestedLast: Long, total: Long): Long {
        val status = response.status.value
        response.requireIdentityEncoding()
        when (status) {
            206 -> {
                val range = parseContentRange(response.headers["Content-Range"])
                    ?: throw FatalFailure(DownloadFailure.ServerRefused(206))
                if (range.start == null || range.endInclusive == null) throw FatalFailure(DownloadFailure.ServerRefused(206))
                if (range.start != offset) throw FatalFailure(DownloadFailure.ServerRefused(206))
                val declaredTotal = range.total ?: throw FatalFailure(DownloadFailure.ServerRefused(206))
                if (declaredTotal != total) throw FatalFailure(DownloadFailure.SourceChanged)
                if (range.endInclusive > requestedLast) throw FatalFailure(DownloadFailure.ServerRefused(206))
                if (Validators.of(response).conflictsWith(validators)) throw FatalFailure(DownloadFailure.SourceChanged)
                // Framing that contradicts itself is not trusted, even when the range's bytes would have arrived.
                response.declaredLength()?.let { if (it != range.endInclusive - range.start + 1) throw FatalFailure(DownloadFailure.ServerRefused(206)) }
                return range.endInclusive + 1
            }
            200 -> {
                if (Validators.of(response).conflictsWith(validators)) throw FatalFailure(DownloadFailure.SourceChanged)
                throw RangesIgnored()
            }
            416 -> throw FatalFailure(DownloadFailure.SourceChanged)
            else -> throw statusFailure(response, options.backoffMaxMillis)
        }
    }

    /**
     * Single stream: no ranges, or no length. It cannot resume (rule 3), so every attempt deletes the
     * part file and starts at 0, and an attempt that ends short of a declared length is a failure.
     * Consecutive failures count with no reset: a restart from 0 is not progress.
     */
    suspend fun runSingle(part: PartEntry, knownLength: Long?, buffer: ByteArray): PartOutcome {
        val file = fileOf(part)
        var failures = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            storageCall { storage.delete(file) }
            table.beginSingle(part, knownLength)
            val problem: TransientFailure? = try {
                transport.get(null, null, null) { response ->
                    response.requireIdentityEncoding()
                    val status = response.status.value
                    if (status != 200) {
                        throw if (status in 200..299) FatalFailure(DownloadFailure.ServerRefused(status)) else statusFailure(response, options.backoffMaxMillis)
                    }
                    if (options.rejectHtml && response.looksLikeHtml()) throw FatalFailure(DownloadFailure.NoUsableLink)
                    val declared = response.declaredLength() ?: knownLength
                    if (declared != null) table.beginSingle(part, declared)
                    stream(part, file, 0L, declared ?: PartEntry.UNBOUNDED, buffer, response)
                    val written = storageCall { storage.size(file) ?: 0L }
                    if (declared != null && written != declared) throw TransientFailure(DownloadFailure.NetworkLost)
                    if (declared == null) table.closeUnbounded(part) else table.markDone(part)
                }
                null
            } catch (t: TransientFailure) {
                t
            }
            if (problem == null) return PartOutcome.DONE
            failures++
            table.markRetrying(part, failures)
            if (failures >= options.maxAttemptsPerPart) throw FatalFailure(problem.failure)
            delay(backoffMillis(failures, problem.retryAfterMillis, options))
        }
    }

    /**
     * Reads the body and appends it to [file]. The sink is opened only now, after the response was
     * accepted (rule 1), and is flushed and closed before this returns or throws, even on cancellation.
     * Returns when the body ends, when the limit is reached or when the part is full.
     *
     * A body that ends with a close cause was cut short by the engine: that is a failure to retry, not a
     * short 206 (which ends without one). When this stops early the channel is cancelled at once, so the
     * engine does not wait on a body nobody will read: without that, a part clipped by a split left Ktor's
     * call cleanup waiting forever.
     */
    private suspend fun stream(
        part: PartEntry,
        file: PlatformFile,
        startPos: Long,
        limitPos: Long,
        buffer: ByteArray,
        response: HttpResponse,
    ) = withContext(Dispatchers.IO) {
        val channel = response.bodyAsChannel()
        val sink: Sink = storageCall { storage.sink(file, append = true).buffered() }
        var failed = false
        try {
            var position = startPos
            while (position < limitPos) {
                val want = minOf(buffer.size.toLong(), limitPos - position).toInt()
                val read = readChunk(channel, buffer, want)
                if (read < 0) {
                    if (channel.closedCause != null) throw TransientFailure(DownloadFailure.NetworkLost)
                    break
                }
                if (read == 0) continue
                val claim = table.reserve(part, read)
                if (claim.allowed > 0) writeChunk(sink, buffer, claim.allowed)
                position += claim.allowed
                if (claim.allowed < read || claim.atEnd) break
            }
        } catch (t: Throwable) {
            failed = true
            throw t
        } finally {
            var closeError: Throwable? = null
            withContext(NonCancellable) {
                runCatching { channel.cancel(null) }
                try {
                    sink.close()
                } catch (e: Throwable) {
                    closeError = e
                }
            }
            // A failed flush on the success path is lost data; on a failure path the next attempt re-reads the size anyway.
            closeError?.let { if (!failed) throw StorageFailure(looksLikeOutOfSpace(it)) }
        }
    }

    private suspend fun readChunk(channel: io.ktor.utils.io.ByteReadChannel, buffer: ByteArray, want: Int): Int {
        try {
            return withTimeoutOrNull(options.stallTimeoutMillis) { channel.readAvailable(buffer, 0, want) }
                ?: throw TransientFailure(DownloadFailure.NetworkLost)
        } catch (e: EngineException) {
            throw e
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw TransientFailure(DownloadFailure.NetworkLost)
        } catch (_: Throwable) {
            throw TransientFailure(DownloadFailure.NetworkLost)
        }
    }

    private fun writeChunk(sink: Sink, buffer: ByteArray, length: Int) {
        try {
            sink.write(buffer, 0, length)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw StorageFailure(looksLikeOutOfSpace(e))
        }
    }
}

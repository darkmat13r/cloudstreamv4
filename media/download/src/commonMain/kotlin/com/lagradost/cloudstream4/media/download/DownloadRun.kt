package com.lagradost.cloudstream4.media.download

import com.lagradost.cloudstream4.media.model.DownloadFailure
import com.lagradost.cloudstream4.media.model.DownloadState
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.div
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.parent
import io.github.vinceglb.filekit.path
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

private const val STATE_PERSIST_INTERVAL_MILLIS = 2_000L

private sealed interface Attempt {
    class Finished(val snapshot: DownloadSnapshot) : Attempt
    data object Restart : Attempt
}

private class Plan(
    val total: Long?,
    val ranged: Boolean,
    val entries: List<PartEntry>,
    val validators: Validators,
    /** Resumed in the `joining` phase: every part was complete, and some may already be joined. */
    val joining: Boolean = false,
)

/**
 * One execution of one download: prepare, probe, fetch, join, verify. Publishes snapshots and ends by
 * publishing exactly one terminal snapshot (COMPLETED or FAILED), unless cancelled, which is a pause
 * and publishes nothing further.
 */
internal class DownloadRun(
    client: HttpClient,
    private val storage: DownloadStorage,
    resolver: HostResolver,
    private val request: DownloadRequest,
    private val publish: (DownloadSnapshot) -> Unit,
) {
    private val options = request.options
    private val http = client.config {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = options.stallTimeoutMillis
            socketTimeoutMillis = options.stallTimeoutMillis
        }
    }
    private val transport = Transport(
        http,
        UrlGuard(resolver, options.allowPrivateAddresses),
        request.url,
        request.headers,
        options.maxRedirects,
    )

    private val folder = request.folder
    private val safeName = sanitizeFileName(request.fileName)
    private val finalFile = folder / safeName
    private val tempDir = folder / tempDirectoryName(safeName)
    private val joinedFile = tempDir / JOINED_FILE_NAME

    private val clock = TimeSource.Monotonic.markNow()
    private val rate = RateMeter()
    private var table: PartTable? = null
    private var persistPhase = PersistedPhase.DOWNLOAD

    suspend fun execute() {
        val terminal: DownloadSnapshot = try {
            publish(snapshotOf(DownloadState.DOWNLOADING, emptyList(), null))
            checkDestinationPath()
            var restarts = 0
            var finished: DownloadSnapshot? = null
            while (finished == null) {
                when (val result = attempt()) {
                    is Attempt.Finished -> finished = result.snapshot
                    Attempt.Restart -> {
                        if (restarts++ >= 1) throw FatalFailure(DownloadFailure.Unknown)
                        wipeTemp()
                    }
                }
            }
            finished
        } catch (e: CancellationException) {
            throw e
        } catch (e: FatalFailure) {
            if (e.failure == DownloadFailure.SourceChanged) wipeQuietly()
            failedSnapshot(e.failure)
        } catch (e: TransientFailure) {
            failedSnapshot(e.failure)
        } catch (e: StorageFailure) {
            failedSnapshot(classifyStorage(e))
        } catch (_: Throwable) {
            failedSnapshot(DownloadFailure.Unknown)
        }
        publish(terminal)
    }

    private suspend fun checkDestinationPath() {
        val parent = finalFile.parent()
        if (parent == null || parent.path.trimEnd('/', '\\') != folder.path.trimEnd('/', '\\')) {
            throw FatalFailure(DownloadFailure.StorageUnavailable)
        }
    }

    private suspend fun attempt(): Attempt {
        storageCall { storage.createDirectories(folder) }
        val tempExists = storageCall { storage.exists(tempDir) }
        val state = if (tempExists) storageCall { storage.readStateFile(tempDir) } else null
        if (tempExists && state == null) wipeTemp()

        if (storageCall { storage.exists(finalFile) }) {
            if (state != null && state.phase == PersistedPhase.JOINING && moveAlreadyHappened(state)) {
                return Attempt.Finished(recoverFinished(state))
            }
            if (!request.overwrite) throw FatalFailure(DownloadFailure.DestinationExists)
        }

        var plan: Plan = if (state != null && state.phase == PersistedPhase.JOINING) {
            // Every part was complete when this phase was written: finishing needs no network.
            Plan(
                total = state.total,
                ranged = true,
                entries = state.parts.map { PartEntry(it.id, it.start, it.endExclusive, it.endExclusive, PartState.DONE) },
                validators = Validators.fromState(state.etag, state.modified),
                joining = true,
            )
        } else {
            planFrom(probeWithRetries(transport, options), state)
        }
        checkSpace(plan)

        if (!plan.joining) {
            while (true) {
                try {
                    fetchAll(plan)
                    break
                } catch (_: RangesIgnored) {
                    if (!plan.ranged) throw FatalFailure(DownloadFailure.ServerRefused(200))
                    // The server stopped honouring ranges: what is on disk is worthless. Once, then a plain stream.
                    freshTemp()
                    plan = singlePlan(plan.total, plan.validators)
                }
            }
        } else {
            table = newTable(plan)
        }
        return finish(plan, verifyLayout = !plan.joining)
    }

    // ---- plan -------------------------------------------------------------------------------

    private suspend fun planFrom(probe: ProbeResult, state: PersistedState?): Plan = when (probe) {
        ProbeResult.Empty -> {
            freshTemp()
            Plan(0L, ranged = false, entries = emptyList(), validators = Validators.NONE)
        }
        is ProbeResult.Single -> {
            freshTemp()
            singlePlan(probe.length, probe.validators)
        }
        is ProbeResult.Ranged -> rangedPlan(probe, state)
    }

    private fun singlePlan(length: Long?, validators: Validators) = Plan(
        total = length,
        ranged = false,
        entries = listOf(PartEntry(0, 0L, length ?: PartEntry.UNBOUNDED, 0L)),
        validators = validators,
    )

    /** Resume only with an identity that can be checked (rule 12); a changed one is [DownloadFailure.SourceChanged]. */
    private suspend fun rangedPlan(probe: ProbeResult.Ranged, state: PersistedState?): Plan {
        val fresh = probe.validators
        if (state != null) {
            val stored = Validators.fromState(state.etag, state.modified)
            val hash = request.expectedHash
            val checkable = if (stored.hasIdentity) fresh.hasIdentity || hash != null else hash != null
            if (checkable) {
                if (state.total != probe.total || (stored.hasIdentity && stored.conflictsWith(fresh))) {
                    throw FatalFailure(DownloadFailure.SourceChanged)
                }
                return Plan(probe.total, ranged = true, entries = entriesFromDisk(state), validators = fresh)
            }
        }
        freshTemp()
        val total = probe.total
        val count = minOf(options.parts.toLong(), total / options.minPartBytes).toInt().coerceAtLeast(1)
        val size = total / count
        val entries = (0 until count).map { i ->
            val start = i * size
            PartEntry(i, start, if (i == count - 1) total else start + size, start)
        }
        return Plan(total, ranged = true, entries = entries, validators = fresh)
    }

    /** The saved ranges, each with the size its file really has. A file longer than its range is deleted (rule 2). */
    private suspend fun entriesFromDisk(state: PersistedState): List<PartEntry> = state.parts.map { part ->
        val file = tempDir / partFileName(part.id)
        val length = part.endExclusive - part.start
        var onDisk = storageCall { storage.size(file) } ?: 0L
        if (onDisk > length) {
            storageCall { storage.delete(file) }
            onDisk = 0L
        }
        PartEntry(
            part.id, part.start, part.endExclusive, part.start + onDisk,
            if (onDisk >= length) PartState.DONE else PartState.PENDING,
        )
    }

    /** Rule 13: the bytes still to come plus the join's peak. Unknown free space carries on. */
    private suspend fun checkSpace(plan: Plan) {
        val total = plan.total ?: return
        if (plan.joining || plan.entries.isEmpty()) return
        val onDisk = plan.entries.sumOf { (it.reserved - it.start).coerceAtLeast(0L) }
        val joinPeak = if (plan.entries.size > 1) plan.entries.maxOf { it.end - it.start } else 0L
        val needed = (total - onDisk).coerceAtLeast(0L) + joinPeak
        val available = try {
            storage.availableBytes(folder)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        } ?: return
        if (available < needed) throw FatalFailure(DownloadFailure.InsufficientSpace(needed, available))
    }

    private fun newTable(plan: Plan): PartTable {
        val persistor: (suspend (List<PersistedPart>) -> Unit)? = if (plan.ranged && (plan.total ?: 0L) > 0L) {
            { parts ->
                val state = PersistedState(
                    total = plan.total!!,
                    etag = storableValidator(plan.validators.etag?.raw, StateFile.MAX_ETAG),
                    modified = plan.validators.modified,
                    phase = persistPhase,
                    parts = parts,
                )
                storageCall { storage.writeStateFile(tempDir, state) }
            }
        } else {
            null
        }
        return PartTable(plan.total, plan.entries, options.minPartBytes, options.maxPartFiles, plan.ranged, persistor)
    }

    // ---- fetch ------------------------------------------------------------------------------

    private suspend fun fetchAll(plan: Plan) {
        val parts = newTable(plan)
        table = parts
        persistPhase = PersistedPhase.DOWNLOAD
        parts.persist()
        if (plan.entries.all { it.phase == PartState.DONE }) return

        val fetcher = PartFetcher(parts, transport, storage, tempDir, options, plan.validators)
        coroutineScope {
            val ticker = launch {
                while (true) {
                    delay(options.progressIntervalMillis)
                    publish(progressSnapshot())
                }
            }
            val persister = if (plan.ranged) {
                launch {
                    while (true) {
                        delay(STATE_PERSIST_INTERVAL_MILLIS)
                        parts.persist()
                    }
                }
            } else {
                null
            }
            val workers = List(if (plan.ranged) options.parts else 1) {
                launch {
                    val buffer = ByteArray(options.bufferBytes)
                    while (true) {
                        val part = parts.next() ?: break
                        val outcome = if (plan.ranged) {
                            fetcher.runRanged(part, plan.total!!, buffer)
                        } else {
                            fetcher.runSingle(part, plan.total, buffer)
                        }
                        if (outcome == PartOutcome.YIELDED) break
                    }
                }
            }
            workers.joinAll()
            ticker.cancel()
            persister?.cancel()
        }
        if (!parts.allDone()) throw FatalFailure(DownloadFailure.Unknown)
    }

    // ---- join and verify --------------------------------------------------------------------

    private suspend fun finish(plan: Plan, verifyLayout: Boolean): Attempt {
        val parts = table ?: throw FatalFailure(DownloadFailure.Unknown)
        // Only a length the server declared can verify the result; one learned from the bytes received cannot.
        val total = if (plan.ranged || parts.lengthDeclared) parts.total else null
        val layout = parts.layout()
        if (verifyLayout) verifyParts(layout, total)
        if (plan.ranged && layout.isNotEmpty()) {
            persistPhase = PersistedPhase.JOINING
            parts.persist() // before part 0 moves: a kill after that must be recognisable
        }
        if (joinParts(storage, tempDir, layout, options.bufferBytes) == JoinOutcome.Restart) return Attempt.Restart
        return Attempt.Finished(verifyAndMove(total, parts))
    }

    /** Rule 4, first half: every range present at its exact size, contiguous, covering `[0, total)`. */
    private suspend fun verifyParts(layout: List<PersistedPart>, total: Long?) {
        var expected = 0L
        for (part in layout) {
            val size = storageCall { storage.size(tempDir / partFileName(part.id)) }
            if (part.start != expected || size != part.endExclusive - part.start) throw FatalFailure(DownloadFailure.Unknown)
            expected = part.endExclusive
        }
        if (total != null && expected != total) throw FatalFailure(DownloadFailure.Unknown)
    }

    /** Rule 4, second half: size, hash over bytes re-read from disk, atomic move, size again. */
    private suspend fun verifyAndMove(total: Long?, parts: PartTable): DownloadSnapshot {
        val joinedSize = storageCall { storage.size(joinedFile) } ?: throw FatalFailure(DownloadFailure.Unknown)
        if (total != null && joinedSize != total) {
            wipeTemp()
            throw FatalFailure(DownloadFailure.SizeMismatch(total, joinedSize))
        }
        verifyHash(joinedFile)
        if (!request.overwrite && storageCall { storage.exists(finalFile) }) {
            throw FatalFailure(DownloadFailure.DestinationExists)
        }
        storageCall { storage.atomicMove(joinedFile, finalFile) }
        val expected = total ?: joinedSize
        val finalSize = storageCall { storage.size(finalFile) } ?: 0L
        if (finalSize != expected) {
            removeQuietly(finalFile) // it is ours, and the download is about to report failure
            throw FatalFailure(DownloadFailure.SizeMismatch(expected, finalSize))
        }
        wipeQuietly()
        return DownloadSnapshot(
            state = DownloadState.COMPLETED,
            bytesDone = finalSize,
            bytesTotal = finalSize,
            bytesPerSecond = 0L,
            etaSeconds = null,
            parts = parts.snapshot(),
            failure = null,
            file = finalFile,
            sizeVerified = total != null,
        )
    }

    private suspend fun verifyHash(file: PlatformFile) {
        val expected = request.expectedHash ?: return
        if (hashOf(storage, file, expected.algorithm, options.bufferBytes) != expected.hex) {
            wipeTemp()
            throw FatalFailure(DownloadFailure.HashMismatch)
        }
    }

    /** Rule 14: the final move already happened before a crash. Nothing is left in the temp directory but the state. */
    private suspend fun moveAlreadyHappened(state: PersistedState): Boolean {
        val names = storageCall { storage.list(tempDir) }.map { it.name }
        if (names.any { it.startsWith("part-") || it == JOINED_FILE_NAME }) return false
        return storageCall { storage.size(finalFile) } == state.total
    }

    private suspend fun recoverFinished(state: PersistedState): DownloadSnapshot {
        try {
            verifyHash(finalFile)
        } catch (e: FatalFailure) {
            removeQuietly(finalFile) // the move was ours, so the corrupt file is too
            throw e
        }
        wipeQuietly()
        return DownloadSnapshot(
            state = DownloadState.COMPLETED,
            bytesDone = state.total,
            bytesTotal = state.total,
            bytesPerSecond = 0L,
            etaSeconds = null,
            parts = emptyList(),
            failure = null,
            file = finalFile,
            sizeVerified = true,
        )
    }

    // ---- housekeeping -----------------------------------------------------------------------

    private suspend fun wipeTemp() {
        storageCall { storage.delete(tempDir) }
    }

    private suspend fun freshTemp() {
        wipeTemp()
        storageCall { storage.createDirectories(tempDir) }
    }

    private suspend fun removeQuietly(file: PlatformFile) {
        withContext(NonCancellable) {
            try {
                storageCall { storage.delete(file) }
            } catch (_: Throwable) {
            }
        }
    }

    /** Cleanup that must not turn a result into a failure. */
    private suspend fun wipeQuietly() {
        withContext(NonCancellable) {
            try {
                storageCall { storage.delete(tempDir) }
            } catch (_: Throwable) {
            }
        }
    }

    // ---- snapshots --------------------------------------------------------------------------

    private fun snapshotOf(state: DownloadState, parts: List<PartSnapshot>, failure: DownloadFailure?): DownloadSnapshot =
        DownloadSnapshot(
            state = state,
            bytesDone = parts.sumOf { it.bytesDone },
            bytesTotal = table?.total,
            bytesPerSecond = 0L,
            etaSeconds = null,
            parts = parts,
            failure = failure,
            file = null,
            sizeVerified = false,
        )

    private suspend fun progressSnapshot(): DownloadSnapshot {
        val parts = table?.snapshot() ?: emptyList()
        val done = parts.sumOf { it.bytesDone }
        val total = table?.total
        rate.sample(clock.elapsedNow().inWholeMilliseconds, done)
        val perSecond = rate.bytesPerSecond()
        return DownloadSnapshot(
            state = DownloadState.DOWNLOADING,
            bytesDone = done,
            bytesTotal = total,
            bytesPerSecond = perSecond,
            etaSeconds = etaSeconds(total?.minus(done), perSecond),
            parts = parts,
            failure = null,
            file = null,
            sizeVerified = false,
        )
    }

    private suspend fun failedSnapshot(failure: DownloadFailure): DownloadSnapshot = withContext(NonCancellable) {
        snapshotOf(DownloadState.FAILED, table?.snapshot() ?: emptyList(), failure)
    }

    /** Rule 8: a full disk is [DownloadFailure.StorageFull], anything else the folder gave up on is unavailable. */
    private suspend fun classifyStorage(error: StorageFailure): DownloadFailure = withContext(NonCancellable) {
        val available = try {
            storage.availableBytes(folder)
        } catch (_: Throwable) {
            null
        }
        val parts = table?.snapshot() ?: emptyList()
        val total = table?.total
        val remaining = total?.let { it - parts.sumOf { part -> part.bytesDone } }
        val short = error.outOfSpace ||
            (available != null && (available < options.bufferBytes || (remaining != null && available < remaining)))
        if (!short) {
            DownloadFailure.StorageUnavailable
        } else {
            DownloadFailure.StorageFull(if (remaining != null && available != null) (remaining - available).coerceAtLeast(0L) else null)
        }
    }
}

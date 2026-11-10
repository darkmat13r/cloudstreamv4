package com.lagradost.cloudstream4.media.download.testserver

import com.lagradost.cloudstream4.media.download.DownloadStorage
import com.lagradost.cloudstream4.media.download.FileKitStorage
import com.lagradost.cloudstream4.media.model.DownloadFailure
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** What the engine does when the network, the server or the disk misbehaves. */
class FaultSuiteTest : SuiteBase() {
    private companion object {
        const val MIN_PART = 256 * KIB
        /** Larger than [CHANNEL_LOSS_TOLERANCE], so a drop leaves bytes on disk even after the worst Ktor loss. */
        const val DROP_AT = 3 * MIB + 3

        /**
         * The most received-but-unwritten bytes a failed body can cost **while the engine's reader keeps up**:
         * Ktor's `ByteChannel` buffer of 1048576 bytes (measured with `javap` on ktor-io-jvm 3.6.0), whose unread
         * contents are discarded when the engine fails the body, plus one 64 KiB read buffer in hand. A Ktor
         * limitation, not something the engine can fix.
         *
         * It is not a bound when the reader is behind (`BUG-100`): the channel's two buffers can then hold about
         * twice that. Measured with a slowed disk and one drop at [DROP_AT]: 1,736,707 lost at 0.5 ms a write,
         * 1,974,275 at 2 ms. The test that uses it holds the drop until the engine has written what it was sent,
         * which makes "the reader keeps up" true at the moment of the drop.
         */
        const val CHANNEL_LOSS_TOLERANCE = 1 * MIB + 64 * KIB
        const val SLOW_BYTES_PER_SECOND = 1 * MIB

        /** The one 64 KiB read buffer [CHANNEL_LOSS_TOLERANCE] allows beside Ktor's channel. */
        const val READ_BUFFER_IN_HAND = 64 * KIB

        /** A disk slow enough that Ktor's channel is full when the connection drops (`BUG-100`). */
        const val SLOW_WRITE_MICROS = 500L
        const val MICROS_PER_MILLI = 1_000L
        const val NANOS_PER_MICRO = 1_000L
        const val HIGH_FREE_SPACE = 1L shl 40
    }

    // region dropped connections

    private fun assertDropResumesFromDiskOffset(makeFault: (Long) -> Fault) {
        val res = h.resource(size = 4 * MIB)
        val dropped = AtomicBoolean(false)
        h.server.fault = { info -> if (!info.isProbe && dropped.compareAndSet(false, true)) makeFault(DROP_AT) else null }
        // What the part file holds the moment each body request arrives, read from disk by the server.
        val diskAtArrival = ConcurrentHashMap<Int, Long>()
        h.server.onRequest = { info -> if (!info.isProbe) diskAtArrival[info.index] = h.diskBytes() }

        val run = h.run(h.request(options = suiteOptions(parts = 1, minPartBytes = 4 * MIB)))

        h.assertCompleted(run, res.source)
        val body = h.server.bodyRequests()
        assertTrue(body.size >= 2, "expected the dropped request and a retry, saw ${body.size}")
        val retry = body[1]
        val start = assertIs<Long>(retry.info.rangeStart, "the retry must be a ranged request, was ${retry.info}")
        assertEquals(diskAtArrival.getValue(retry.info.index), start, "retry must ask from the part file's size on disk")
        assertTrue(start > 0, "retry restarted from zero, throwing away ${body[0].bytesSent} delivered bytes")
        assertTrue(start <= DROP_AT, "retry asked from $start, beyond the $DROP_AT bytes the server sent: a gap")
        assertEquals(res.size - start, retry.declaredBytes, "the retry asked for more than the remainder")
        assertEquals(res.size, run.last.bytesDone)
    }

    @Test
    fun `a connection closed mid-part retries from the size of the part file on disk`() =
        assertDropResumesFromDiskOffset { Fault.DropAfter(it) }

    @Test
    fun `a connection reset mid-part retries from the size of the part file on disk`() =
        assertDropResumesFromDiskOffset { Fault.ResetAfter(it) }

    /**
     * `BUG-100`: what reaches the engine is kept, whatever the speed of its disk. The engine writes to a disk slowed by
     * [SLOW_WRITE_MICROS] a write, so Ktor's channel fills, and the server holds the close until the part file has
     * what was sent, less one read buffer. Without the hold, the close lands on a full channel whose bytes Ktor
     * discards: up to about 2 MiB on a slow disk (1,736,707 measured at this speed), which a loaded machine reached
     * by chance, past [CHANNEL_LOSS_TOLERANCE].
     */
    @Test
    fun `bytes received before the connection dropped are kept on disk`() {
        val res = h.resource(size = 4 * MIB)
        val dropped = AtomicBoolean(false)
        val landed = { partBytes() >= DROP_AT - READ_BUFFER_IN_HAND }
        h.server.fault = { info ->
            if (!info.isProbe && dropped.compareAndSet(false, true)) Fault.DropAfter(DROP_AT, holdUntil = landed) else null
        }
        val run = h.run(
            h.request(options = suiteOptions(parts = 1, minPartBytes = 4 * MIB)),
            downloader = h.downloader(storage = SlowStorage(SLOW_WRITE_MICROS)),
        )

        h.assertCompleted(run, res.source)
        val retryStart = assertIs<Long>(h.server.bodyRequests()[1].info.rangeStart)
        assertTrue(
            retryStart >= DROP_AT - CHANNEL_LOSS_TOLERANCE,
            "the server delivered $DROP_AT bytes before closing but the retry resumed from $retryStart: ${DROP_AT - retryStart} received bytes were thrown away",
        )
        // The hold let the part file reach this before the drop, so a retry from below it threw away bytes already
        // written: within CHANNEL_LOSS_TOLERANCE, but not lost to Ktor (BUG-100's review).
        assertTrue(
            retryStart >= DROP_AT - READ_BUFFER_IN_HAND,
            "the part file held at least ${DROP_AT - READ_BUFFER_IN_HAND} bytes before the drop but the retry resumed from $retryStart",
        )
    }

    @Test
    fun `many dropped connections across many parts still give a byte identical file`() {
        val res = h.resource(size = 6 * MIB + 17)
        val seen = AtomicInteger()
        h.server.fault = { info -> if (!info.isProbe && seen.incrementAndGet() % 2 == 0) Fault.DropAfter(300_000L) else null }
        val run = h.run(h.request(options = suiteOptions(parts = 4, minPartBytes = MIN_PART)))

        h.assertCompleted(run, res.source)
        assertTrue(h.server.bodyRequests().size > 4, "faults were never exercised")
    }

    @Test
    fun `every attempt that makes progress resets the attempt budget`() {
        val res = h.resource(size = 12 * MIB)
        // The drop is larger than Ktor's 1 MiB channel buffer, whose unread bytes are lost when a body
        // fails: each attempt then keeps at least ~0.4 MiB on disk however the loss falls, so every
        // attempt makes progress and the budget of 2 must never run out.
        h.server.fault = { info -> if (!info.isProbe) Fault.DropAfter(1_572_864L) else null }
        val run = h.run(h.request(options = suiteOptions(parts = 1, minPartBytes = 12 * MIB, maxAttemptsPerPart = 2)))

        h.assertCompleted(run, res.source)
        assertTrue(h.server.bodyRequests().size >= 8, "expected at least 8 short responses, saw ${h.server.bodyRequests().size}")
    }

    @Test
    fun `a part that never delivers a byte gives up after its attempt budget`() {
        h.resource(size = 2 * MIB)
        h.server.fault = { info -> if (!info.isProbe) Fault.DropAfter(0L) else null }
        val run = h.run(h.request(options = suiteOptions(parts = 1, minPartBytes = 2 * MIB, maxAttemptsPerPart = 3)), timeoutMillis = 30_000)

        assertEquals(DownloadFailure.NetworkLost, h.assertFailed(run).failure)
        val attempts = h.server.bodyRequests().size
        assertTrue(attempts in 3..4, "maxAttemptsPerPart was 3, saw $attempts body requests")
    }

    @Test
    fun `without range support a dropped connection restarts from byte zero and never appends`() {
        val res = h.resource(size = 5 * MIB)
        res.supportsRange = false
        val dropped = AtomicBoolean(false)
        h.server.fault = { info -> if (!info.isProbe && dropped.compareAndSet(false, true)) Fault.DropAfter(DROP_AT) else null }
        val run = h.run(h.request(options = suiteOptions(parts = 4, minPartBytes = MIN_PART)))

        h.assertCompleted(run, res.source)
        val body = h.server.bodyRequests()
        assertTrue(body.size >= 2)
        body.forEach { assertTrue((it.info.rangeStart ?: 0L) == 0L, "single stream retry asked from ${it.info.rangeStart}") }
        assertEquals(res.size, body.last().bytesSent, "the last attempt must deliver the whole file")
    }

    @Test
    fun `a stalled connection is abandoned after the stall timeout and resumed`() {
        val res = h.resource(size = 3 * MIB)
        val stalled = AtomicBoolean(false)
        h.server.fault = { info -> if (!info.isProbe && stalled.compareAndSet(false, true)) Fault.StallAfter(200_000L, 8_000L) else null }
        val run = h.run(
            h.request(options = suiteOptions(parts = 1, minPartBytes = 3 * MIB, stallTimeoutMillis = 400)),
            timeoutMillis = 30_000,
        )

        h.assertCompleted(run, res.source)
        assertTrue(h.server.bodyRequests().size >= 2)
    }

    // endregion

    // region status codes

    @Test
    fun `transient 503 on a part is retried and the file completes`() {
        val res = h.resource(size = 3 * MIB)
        val failures = AtomicInteger()
        h.server.fault = { info -> if (!info.isProbe && failures.incrementAndGet() <= 2) Fault.Status(503) else null }
        val run = h.run(h.request(options = suiteOptions(parts = 2, minPartBytes = MIN_PART)))

        h.assertCompleted(run, res.source)
        assertTrue(h.server.log.count { it.status == 503 } >= 2)
    }

    @Test
    fun `429 with a short Retry-After is honoured and retried`() {
        val res = h.resource(size = 2 * MIB)
        val failures = AtomicInteger()
        h.server.fault = { info -> if (!info.isProbe && failures.incrementAndGet() <= 1) Fault.Status(429, retryAfterSeconds = 1) else null }
        val run = h.run(h.request(options = suiteOptions(parts = 1, minPartBytes = 2 * MIB)))

        h.assertCompleted(run, res.source)
    }

    @Test
    fun `an absurd Retry-After is bounded, not obeyed`() {
        val res = h.resource(size = 2 * MIB)
        val failures = AtomicInteger()
        h.server.fault = { info -> if (!info.isProbe && failures.incrementAndGet() <= 1) Fault.Status(503, retryAfterSeconds = 86_400) else null }
        val run = h.run(h.request(options = suiteOptions(parts = 1, minPartBytes = 2 * MIB)), timeoutMillis = 30_000)

        h.assertCompleted(run, res.source)
    }

    @Test
    fun `a 503 on the probe is retried and does not downgrade a ranged server to a single stream`() {
        val res = h.resource(size = 4 * MIB)
        val failures = AtomicInteger()
        h.server.fault = { if (failures.incrementAndGet() <= 1) Fault.Status(503) else null }
        val run = h.run(h.request(options = suiteOptions(parts = 4, minPartBytes = MIN_PART)))

        h.assertCompleted(run, res.source)
        val starts = h.server.bodyRequests().mapNotNull { it.info.rangeStart }.toSet()
        assertTrue(starts.size >= 2, "after the probe was retried the download should still be segmented, ranges asked: $starts")
    }

    @Test
    fun `403 on a part mid-download is ServerRefused 403 and keeps the temp files`() {
        h.resource(size = 8 * MIB)
        val bodyRequests = AtomicInteger()
        h.server.fault = { info -> if (!info.isProbe && bodyRequests.incrementAndGet() >= 2) Fault.Status(403) else null }
        val run = h.run(h.request(options = suiteOptions(parts = 4, minPartBytes = MIN_PART)))

        assertEquals(DownloadFailure.ServerRefused(403), h.assertFailed(run).failure)
        assertTrue(File(h.tempDir(), "state").isFile, "a refused download keeps its state so a new link can resume it")
    }

    @Test
    fun `404 on the very first request is ServerRefused 404 without retrying`() {
        h.resource(size = 2 * MIB)
        h.server.fault = { Fault.Status(404) }
        val run = h.run(h.request())

        assertEquals(DownloadFailure.ServerRefused(404), h.assertFailed(run).failure)
        assertEquals(1, h.server.log.size, "a 404 is final: ${h.server.log}")
    }

    @Test
    fun `416 on a part is SourceChanged`() {
        h.resource(size = 4 * MIB)
        val bodyRequests = AtomicInteger()
        h.server.fault = { info -> if (!info.isProbe && bodyRequests.incrementAndGet() >= 2) Fault.Status(416) else null }
        val run = h.run(h.request(options = suiteOptions(parts = 4, minPartBytes = MIN_PART)))

        assertEquals(DownloadFailure.SourceChanged, h.assertFailed(run).failure)
    }

    @Test
    fun `a server that stops honouring ranges after the probe falls back to a single stream`() {
        val res = h.resource(size = 3 * MIB)
        h.server.fault = { info -> if (!info.isProbe && info.rangeStart != null) Fault.IgnoreRange else null }
        val run = h.run(h.request(options = suiteOptions(parts = 4, minPartBytes = MIN_PART)), timeoutMillis = 30_000)

        h.assertCompleted(run, res.source)
    }

    // endregion

    // region the file changed

    private fun changeAtSecondBodyRequest(res: Resource, to: Content) {
        val bodyRequests = AtomicInteger()
        h.server.onRequest = { info -> if (!info.isProbe && bodyRequests.incrementAndGet() == 2) res.content = to }
    }

    @Test
    fun `a new ETag answered with a full 200 to a ranged request is SourceChanged`() {
        val res = h.resource(size = 4 * MIB, seed = 1)
        changeAtSecondBodyRequest(res, Content(seed = 2, size = 4 * MIB))
        val run = h.run(h.request(options = suiteOptions(parts = 4, minPartBytes = MIN_PART)))

        assertEquals(DownloadFailure.SourceChanged, h.assertFailed(run).failure)
    }

    @Test
    fun `a new ETag on a 206 from a server that ignores If-Range is SourceChanged`() {
        val res = h.resource(size = 4 * MIB, seed = 1)
        res.honorIfRange = false
        changeAtSecondBodyRequest(res, Content(seed = 2, size = 4 * MIB))
        val run = h.run(h.request(options = suiteOptions(parts = 4, minPartBytes = MIN_PART)))

        assertEquals(DownloadFailure.SourceChanged, h.assertFailed(run).failure)
    }

    @Test
    fun `the same ETag but a different total in Content-Range is SourceChanged`() {
        val res = h.resource(size = 4 * MIB, seed = 1, etag = "\"stable\"")
        changeAtSecondBodyRequest(res, Content(seed = 1, size = 5 * MIB, etag = "\"stable\""))
        val run = h.run(h.request(options = suiteOptions(parts = 4, minPartBytes = MIN_PART)))

        assertEquals(DownloadFailure.SourceChanged, h.assertFailed(run).failure)
    }

    @Test
    fun `a changed Last-Modified with no ETag is SourceChanged`() {
        val res = h.server.add("/f", Content(seed = 1, size = 4 * MIB, etag = null, lastModified = "Wed, 01 Jan 2025 00:00:00 GMT"))
        res.honorIfRange = false
        changeAtSecondBodyRequest(res, Content(seed = 2, size = 4 * MIB, etag = null, lastModified = "Thu, 02 Jan 2025 00:00:00 GMT"))
        val run = h.run(h.request(options = suiteOptions(parts = 4, minPartBytes = MIN_PART)))

        assertEquals(DownloadFailure.SourceChanged, h.assertFailed(run).failure)
    }

    // endregion

    // region storage

    @Test
    fun `a full disk is StorageFull and is not retried (single part)`() {
        h.resource(size = 4 * MIB)
        val storage = QuotaStorage(limitBytes = 1 * MIB, advertisedAvailable = { HIGH_FREE_SPACE })
        val run = h.run(h.request(options = suiteOptions(parts = 1, minPartBytes = 4 * MIB)), downloader = h.downloader(storage))

        assertIs<DownloadFailure.StorageFull>(h.assertFailed(run).failure)
        assertTrue(storage.failedWrites.get() >= 1, "the quota was never hit")
        assertEquals(1, h.server.bodyRequests().size, "a storage failure must not trigger a new request: ${h.server.log}")
    }

    @Test
    fun `a full disk is StorageFull and is not retried (many parts)`() {
        h.resource(size = 8 * MIB)
        val storage = QuotaStorage(limitBytes = 2 * MIB, advertisedAvailable = { HIGH_FREE_SPACE })
        val parts = 4
        val run = h.run(h.request(options = suiteOptions(parts = parts, minPartBytes = MIN_PART)), downloader = h.downloader(storage))

        assertIs<DownloadFailure.StorageFull>(h.assertFailed(run).failure)
        assertTrue(h.server.bodyRequests().size <= parts, "retried after a storage failure: ${h.server.log}")
    }

    @Test
    fun `a full disk whose free space is unknown is still StorageFull`() {
        h.resource(size = 4 * MIB)
        val storage = QuotaStorage(limitBytes = 1 * MIB, advertisedAvailable = { null })
        val run = h.run(h.request(options = suiteOptions(parts = 1, minPartBytes = 4 * MIB)), downloader = h.downloader(storage))

        assertIs<DownloadFailure.StorageFull>(h.assertFailed(run).failure)
    }

    @Test
    fun `a write error that is not about space, with room to spare, is StorageUnavailable and not retried`() {
        h.resource(size = 4 * MIB)
        val storage = QuotaStorage(limitBytes = 1 * MIB, advertisedAvailable = { HIGH_FREE_SPACE }, errorMessage = "Permission denied")
        val run = h.run(h.request(options = suiteOptions(parts = 1, minPartBytes = 4 * MIB)), downloader = h.downloader(storage))

        assertEquals(DownloadFailure.StorageUnavailable, h.assertFailed(run).failure)
        assertEquals(1, h.server.bodyRequests().size)
    }

    /**
     * Rule 4's size check on `joined`, the last guard before the move. Every part file is whole, so
     * only a join that writes short can reach it: here the disk silently loses one byte of the join.
     */
    @Test
    fun `a join that comes out short is SizeMismatch and never a final file`() {
        val res = h.resource(size = 4 * MIB)
        val storage = ShortJoinStorage()
        val run = h.run(h.request(options = suiteOptions(parts = 4, minPartBytes = MIN_PART)), downloader = h.downloader(storage))

        val failure = assertIs<DownloadFailure.SizeMismatch>(h.assertFailed(run).failure)
        assertEquals(res.size, failure.expected)
        assertEquals(res.size - 1, failure.actual)
        assertTrue(storage.dropped.get(), "the join never appended, so the guard was not exercised")
    }

    /** The bytes in the download's part files so far: what the engine has written of what it received. */
    private fun partBytes(): Long = h.tempDir().listFiles().orEmpty().filter { it.name.startsWith("part-") }.sumOf { it.length() }

    /** Every write waits [delayMicros] first: a slow disk, so the engine reads behind the network (`BUG-100`). */
    private class SlowStorage(
        private val delayMicros: Long,
        private val inner: DownloadStorage = FileKitStorage(),
    ) : DownloadStorage by inner {
        override fun sink(file: PlatformFile, append: Boolean): RawSink {
            val sink = inner.sink(file, append)
            return object : RawSink by sink {
                override fun write(source: Buffer, byteCount: Long) {
                    Thread.sleep(delayMicros / MICROS_PER_MILLI, ((delayMicros % MICROS_PER_MILLI) * NANOS_PER_MICRO).toInt())
                    sink.write(source, byteCount)
                }
            }
        }
    }

    /** Appends to `joined` lose the last byte of their first write, once, without an error. */
    private class ShortJoinStorage(
        private val inner: DownloadStorage = FileKitStorage(),
    ) : DownloadStorage by inner {
        val dropped = AtomicBoolean(false)

        override fun sink(file: PlatformFile, append: Boolean): RawSink {
            val sink = inner.sink(file, append)
            if (!append || file.name != "joined") return sink
            return object : RawSink by sink {
                override fun write(source: Buffer, byteCount: Long) {
                    if (byteCount > 0 && dropped.compareAndSet(false, true)) {
                        sink.write(source, byteCount - 1)
                        source.skip(1)
                    } else {
                        sink.write(source, byteCount)
                    }
                }
            }
        }
    }

    @Test
    fun `not enough room before starting is InsufficientSpace and no body is requested`() {
        h.resource(size = 8 * MIB)
        val available = 1 * MIB
        val storage = QuotaStorage(advertisedAvailable = { available })
        val run = h.run(h.request(options = suiteOptions(parts = 4, minPartBytes = MIN_PART)), downloader = h.downloader(storage))

        val failure = assertIs<DownloadFailure.InsufficientSpace>(h.assertFailed(run).failure)
        assertEquals(available, failure.bytesAvailable)
        assertTrue(failure.bytesNeeded >= 8 * MIB, "needed ${failure.bytesNeeded} for an 8 MiB file")
        assertEquals(emptyList(), h.server.bodyRequests().map { it.toString() })
        assertEquals(0L, storage.written.get(), "bytes were written to a disk known to be too small")
    }

    // endregion

    // region scheduling

    @Test
    fun `a slow part is split and the pieces still join byte for byte`() {
        val res = h.resource(size = 16 * MIB)
        val slowEnd = 4 * MIB
        h.server.throttle = { info -> if (!info.isProbe && info.rangeStart == 0L) SLOW_BYTES_PER_SECOND else 0L }
        val run = h.run(h.request(options = suiteOptions(parts = 4, minPartBytes = MIN_PART)))

        h.assertCompleted(run, res.source)
        assertTrue(run.maxParts > 4, "no split seen in snapshots; most parts at once: ${run.maxParts}")
        val splitStarts = h.server.bodyRequests().mapNotNull { it.info.rangeStart }.filter { it in 1 until slowEnd }
        assertTrue(splitStarts.isNotEmpty(), "no request began inside the slow part's range, so it was never split")
    }

    @Test
    fun `splitting stops at maxPartFiles`() {
        val res = h.resource(size = 16 * MIB)
        val cap = 8
        h.server.throttle = { info -> if (!info.isProbe && info.rangeStart == 0L) SLOW_BYTES_PER_SECOND else 0L }
        // Reaching the cap takes four halvings of the slow part's 4 MiB. At MIN_PART (256 KiB) the fourth
        // sits on the `left >= 2 * minPartBytes` edge and failed 1 run in 5; at 32 KiB there is room for several more.
        val run = h.run(h.request(options = suiteOptions(parts = 4, minPartBytes = 32 * KIB, maxPartFiles = cap)))

        h.assertCompleted(run, res.source)
        assertEquals(cap, run.maxParts, "a persistent slow part with idle workers should be split right up to the cap")
        run.snapshots.forEach { assertTrue(it.parts.size <= cap, "${it.parts.size} parts exceeds the cap of $cap") }
    }

    @Test
    fun `thirty two parts at once all join`() {
        val res = h.resource(size = 16 * MIB + 999)
        val run = h.run(h.request(options = suiteOptions(parts = 32, minPartBytes = MIN_PART)))

        h.assertCompleted(run, res.source)
        assertTrue(run.maxParts >= 32, "planned ${run.maxParts} parts for a 32 part request")
        assertTrue(h.server.peakConcurrency <= 32, "peak concurrency ${h.server.peakConcurrency} exceeds 32 parts")
    }

    @Test
    fun `asking for a thousand parts is clamped and still byte exact`() {
        val res = h.resource(size = 16 * MIB + 1)
        val run = h.run(h.request(options = suiteOptions(parts = 1000, minPartBytes = MIN_PART)))

        h.assertCompleted(run, res.source)
        assertTrue(h.server.peakConcurrency <= 32, "peak concurrency ${h.server.peakConcurrency}")
        assertTrue(run.maxParts <= 128, "${run.maxParts} part files")
    }

    @Test
    fun `a host that allows two connections and answers 503 to more still completes`() {
        val res = h.resource(size = 8 * MIB)
        h.server.connectionLimit = 2
        h.server.throttle = { 8 * MIB }
        val run = h.run(
            h.request(options = suiteOptions(parts = 8, minPartBytes = MIN_PART, maxAttemptsPerPart = 3)),
            timeoutMillis = 60_000,
        )

        h.assertCompleted(run, res.source)
        assertTrue(h.server.log.any { it.status == 503 }, "the limit was never hit, so this proved nothing")
        assertTrue(h.server.peakConcurrency <= 2)
    }

    // endregion
}

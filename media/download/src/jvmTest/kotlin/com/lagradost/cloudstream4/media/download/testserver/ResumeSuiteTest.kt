package com.lagradost.cloudstream4.media.download.testserver

import com.lagradost.cloudstream4.media.download.DownloadOptions
import com.lagradost.cloudstream4.media.model.DownloadFailure
import com.lagradost.cloudstream4.media.model.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pause (a cancelled collector), resume, and what a resume must not believe. */
class ResumeSuiteTest : SuiteBase() {
    private companion object {
        const val SIZE = 24 * MIB
        const val MIN_PART = 256 * KIB
        const val PARTS = 4
        const val THROTTLE = 3 * MIB
        const val PAUSE_PERCENT = 40L
    }

    private fun options(): DownloadOptions = suiteOptions(parts = PARTS, minPartBytes = MIN_PART)

    /** One worker: no idle worker, so no split of a part that is already in flight. Isolates a test from split behaviour. */
    private fun serialOptions(): DownloadOptions = suiteOptions(parts = 1, minPartBytes = MIN_PART)

    /**
     * Part 0 is unthrottled so it finishes while the others are still going: the pause then leaves at
     * least one finished part and some partial ones.
     */
    private fun pauseHalfway(res: Resource) {
        h.server.throttle = { info -> if (info.rangeStart == 0L && !info.isProbe) 0L else THROTTLE }
        val run = h.run(h.request(options = options()), stopWhen = { it.bytesDone >= res.size * PAUSE_PERCENT / 100 })
        assertEquals(DownloadState.DOWNLOADING, run.last.state, "the pause point was never reached; last=${run.last.state} ${run.last.failure}")
        assertFalse(h.finalFile().exists(), "a paused download left a final file")
        assertTrue(File(h.tempDir(), "state").isFile, "pausing must keep the state file")
        h.server.throttle = { 0L }
    }

    private fun rewriteState(transform: (String) -> String) {
        val state = File(h.tempDir(), "state")
        state.writeText(state.readLines().joinToString("\n", postfix = "\n") { transform(it) })
    }

    private fun lieAboutDone(done: (PartOnDisk) -> String) {
        val byId = h.partsOnDisk().associateBy { it.id }
        rewriteState { line ->
            if (!line.startsWith("part ")) line else {
                val f = line.split(' ')
                "part ${f[1]} ${f[2]} ${f[3]} ${done(byId.getValue(f[1].toInt()))}"
            }
        }
    }

    private fun assertResumedWithoutRefetch(res: Resource, before: List<PartOnDisk>, mark: Int, run: Run) {
        h.assertCompleted(run, res.source)
        h.assertNoRefetch(before, mark)
    }

    // region collector cancel

    @Test
    fun `a cancelled collector resumes with the right bytes and does not refetch finished parts`() {
        val res = h.resource(size = SIZE)
        pauseHalfway(res)
        val before = h.partsOnDisk()
        val finished = before.filter { it.complete }
        assertTrue(finished.isNotEmpty(), "setup: expected at least one finished part, parts=$before")
        val mark = h.server.log.size

        val run = h.run(h.request(options = serialOptions()), downloader = h.downloader())

        assertResumedWithoutRefetch(res, before, mark, run)
        for (entry in h.server.since(mark).filter { !it.info.isProbe }) {
            val s = entry.servedStart ?: continue
            val e = entry.servedEnd ?: continue
            for (part in finished) {
                assertFalse(
                    s < part.endExclusive && e >= part.start,
                    "request ${entry.info} [$s,$e] overlaps finished part ${part.id} [${part.start},${part.endExclusive})",
                )
            }
        }
    }

    /** Same pause, resumed with four workers: one is idle at once (part 0 is done) and splits a part whose request is in flight. */
    @Test
    fun `a resume with idle workers splits in-flight parts and still finishes`() {
        val res = h.resource(size = SIZE)
        pauseHalfway(res)
        val before = h.partsOnDisk()
        val mark = h.server.log.size

        val run = h.run(h.request(options = options()), timeoutMillis = 30_000)

        assertResumedWithoutRefetch(res, before, mark, run)
    }

    @Test
    fun `resume with a Last-Modified only validator continues instead of restarting`() {
        val res = h.server.add("/f", Content(seed = 5, size = SIZE, etag = null, lastModified = "Wed, 01 Jan 2025 00:00:00 GMT"))
        pauseHalfway(res)
        val before = h.partsOnDisk()
        val mark = h.server.log.size

        val run = h.run(h.request(options = serialOptions()))

        assertResumedWithoutRefetch(res, before, mark, run)
    }

    @Test
    fun `with no validator at all a changed file is never mixed with the old partial bytes`() {
        val res = h.resource(size = SIZE, etag = null)
        pauseHalfway(res)
        res.content = Content(seed = 99, size = SIZE, etag = null)
        val mark = h.server.log.size

        val run = h.run(h.request(options = options()))

        h.assertCompleted(run, res.source)
        assertTrue(h.requestedSince(mark) >= SIZE, "without a validator the partial bytes cannot be trusted; they were reused")
    }

    @Test
    fun `discard removes every temporary file`() {
        val res = h.resource(size = SIZE)
        pauseHalfway(res)

        runBlocking { h.downloader().discard(h.folder, "out.bin") }

        assertFalse(h.tempDir().exists(), "discard left ${h.tempDir().list()?.toList()}")
        assertFalse(h.finalFile().exists())
    }

    @Test
    fun `discard never removes a finished file`() {
        val res = h.resource(size = 2 * MIB)
        h.assertCompleted(h.run(h.request(options = options())), res.source)

        runBlocking { h.downloader().discard(h.folder, "out.bin") }

        assertEquals(null, res.source.describeMismatch(h.finalFile()), "discard damaged the finished file")
    }

    @Test
    fun `a second download of the same target while one runs fails before any byte`() {
        h.resource(size = SIZE)
        h.server.throttle = { THROTTLE }
        val downloader = h.downloader()
        runBlocking {
            val first = CoroutineScope(Dispatchers.Default).launch { downloader.download(h.request(options = options())).collect { } }
            try {
                withTimeout(20_000) { while (h.server.bodyRequests().isEmpty()) kotlinx.coroutines.delay(5) }
                val mark = h.server.log.size
                assertFailsWith<IllegalStateException> { downloader.download(h.request(options = options())).collect { } }
                assertEquals(emptyList(), h.server.since(mark).map { it.toString() }, "the rejected download still sent requests")
            } finally {
                first.cancelAndJoin()
            }
        }
    }

    // endregion

    // region state that lies or is broken

    @Test
    fun `a state file claiming every part is done does not make a partial file complete`() {
        val res = h.resource(size = SIZE)
        pauseHalfway(res)
        val before = h.partsOnDisk()
        lieAboutDone { (it.endExclusive - it.start).toString() }
        val mark = h.server.log.size

        val run = h.run(h.request(options = serialOptions()))

        assertResumedWithoutRefetch(res, before, mark, run)
    }

    @Test
    fun `a state file claiming nothing is done does not make the engine refetch what is on disk`() {
        val res = h.resource(size = SIZE)
        pauseHalfway(res)
        val before = h.partsOnDisk()
        lieAboutDone { "0" }
        val mark = h.server.log.size

        val run = h.run(h.request(options = serialOptions()))

        assertResumedWithoutRefetch(res, before, mark, run)
    }

    @Test
    fun `an absurd recorded byte count is ignored or the state is discarded, never trusted`() {
        val res = h.resource(size = SIZE)
        pauseHalfway(res)
        lieAboutDone { Long.MAX_VALUE.toString() }

        val run = h.run(h.request(options = serialOptions()))

        h.assertCompleted(run, res.source)
    }

    @Test
    fun `garbage in the state file restarts cleanly`() {
        val res = h.resource(size = SIZE)
        pauseHalfway(res)
        File(h.tempDir(), "state").writeText("this is not a state file\n\u0000\u0001")

        val run = h.run(h.request(options = options()))

        h.assertCompleted(run, res.source)
    }

    @Test
    fun `an oversized state file restarts cleanly`() {
        val res = h.resource(size = SIZE)
        pauseHalfway(res)
        File(h.tempDir(), "state").appendText("\n" + "#".repeat(70 * 1024))

        val run = h.run(h.request(options = options()))

        h.assertCompleted(run, res.source)
    }

    @Test
    fun `a state whose ranges overlap restarts cleanly`() {
        val res = h.resource(size = SIZE)
        pauseHalfway(res)
        var first = true
        rewriteState { line ->
            if (line.startsWith("part ") && first) {
                first = false
                val f = line.split(' ')
                "part ${f[1]} ${f[2]} ${f[3].toLong() + 4096} ${f[4]}"
            } else line
        }

        val run = h.run(h.request(options = options()))

        h.assertCompleted(run, res.source)
    }

    @Test
    fun `a part file longer than its range is refetched, not truncated and trusted`() {
        val res = h.resource(size = SIZE)
        pauseHalfway(res)
        val victim = h.partsOnDisk().first { !it.complete }
        val file = File(h.tempDir(), "part-${victim.id}")
        val garbage = ByteArray((victim.endExclusive - victim.start - victim.onDisk + 4096).toInt()) { 0x5A }
        file.appendBytes(garbage)

        val run = h.run(h.request(options = serialOptions()))

        h.assertCompleted(run, res.source)
    }

    @Test
    fun `a state recording a different total than the server has is SourceChanged and the temp files are wiped`() {
        val res = h.resource(size = SIZE)
        pauseHalfway(res)
        // Consistent (the last part still ends at the total) but a different size than the server has.
        val lastPartLine = File(h.tempDir(), "state").readLines().last { it.startsWith("part ") }
        rewriteState {
            when {
                it.startsWith("total ") -> "total ${SIZE + 4096}"
                it == lastPartLine -> lastPartLine.split(' ').let { f -> "part ${f[1]} ${f[2]} ${f[3].toLong() + 4096} ${f[4]}" }
                else -> it
            }
        }

        val first = h.run(h.request(options = options()))

        assertEquals(DownloadFailure.SourceChanged, h.assertFailed(first).failure)
        val leftovers = h.tempDir().listFiles().orEmpty().filter { it.name.startsWith("part-") }
        assertEquals(emptyList(), leftovers.map { it.name }, "parts of a different version of the file were kept")

        val second = h.run(h.request(options = options()))
        h.assertCompleted(second, res.source)
    }

    @Test
    fun `a state recording a different ETag than the server has is SourceChanged`() {
        val res = h.resource(size = SIZE)
        pauseHalfway(res)
        rewriteState { if (it.startsWith("etag ")) "etag \"other-version\"" else it }

        val run = h.run(h.request(options = options()))

        assertEquals(DownloadFailure.SourceChanged, h.assertFailed(run).failure)
    }

    // endregion
}

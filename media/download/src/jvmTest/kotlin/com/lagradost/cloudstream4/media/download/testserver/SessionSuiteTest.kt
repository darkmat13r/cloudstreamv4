package com.lagradost.cloudstream4.media.download.testserver

import com.lagradost.cloudstream4.media.model.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `start(scope, request)`: pause, resume and cancel from the outside. */
class SessionSuiteTest : SuiteBase() {
    private companion object {
        const val SIZE = 16 * MIB
        const val THROTTLE = 4 * MIB
        const val SETTLE_MILLIS = 400L
        const val WAIT_MILLIS = 20_000L
    }

    private fun options() = suiteOptions(parts = 4, minPartBytes = 256 * KIB)

    @Test
    fun `pause stops the bytes, resume finishes the file byte for byte`() {
        val res = h.resource(size = SIZE)
        h.server.throttle = { THROTTLE }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        runBlocking {
            val session = h.downloader().start(scope, h.request(options = options()))
            withTimeout(WAIT_MILLIS) { while (session.snapshots.value.bytesDone < SIZE / 8) delay(5) }
            session.pause()
            withTimeout(WAIT_MILLIS) { while (session.snapshots.value.state != DownloadState.PAUSED) delay(5) }
            val frozen = session.snapshots.value.bytesDone
            delay(SETTLE_MILLIS)
            assertEquals(frozen, session.snapshots.value.bytesDone, "bytes kept arriving while paused")
            assertFalse(h.finalFile().exists())

            h.server.throttle = { 0L }
            session.resume()
            val done = withTimeout(WAIT_MILLIS) { session.join() }

            assertEquals(DownloadState.COMPLETED, done.state, "failure=${done.failure}")
            assertEquals(null, res.source.describeMismatch(h.finalFile()))
        }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun `cancel ends in STOPPED and leaves no final file`() {
        h.resource(size = SIZE)
        h.server.throttle = { THROTTLE }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        runBlocking {
            val session = h.downloader().start(scope, h.request(options = options()))
            withTimeout(WAIT_MILLIS) { while (session.snapshots.value.bytesDone < SIZE / 8) delay(5) }
            session.cancel()
            val done = withTimeout(WAIT_MILLIS) { session.join() }

            assertEquals(DownloadState.STOPPED, done.state, "failure=${done.failure}")
            assertTrue(!h.finalFile().exists())
        }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}

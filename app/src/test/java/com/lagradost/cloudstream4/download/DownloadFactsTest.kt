package com.lagradost.cloudstream4.download

import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadType
import com.lagradost.cloudstream4.media.model.DownloadFailure
import com.lagradost.cloudstream4.media.model.DownloadProgress
import com.lagradost.cloudstream4.media.model.DownloadState
import com.lagradost.cloudstream4.media.model.QueuePosition
import com.lagradost.cloudstream4.media.model.SegmentProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** The sidecar's rules, on the JVM. `DownloadFactsDeviceTest` runs them against real files and sockets. */
class DownloadFactsTest {

    private val id = 7

    @After
    fun forget() = DownloadFacts.clear(id)

    private fun report(
        type: DownloadType,
        downloaded: Long = 10L,
        written: Long = 10L,
        total: Long? = 100L,
        declared: Boolean = true,
        rate: Long = 5L,
        segments: SegmentProgress? = null,
    ) = DownloadFacts.report(id, type, downloaded, written, total, declared, rate, segments)

    private val fact get() = DownloadFacts.facts.value.getValue(id)

    @Test
    fun everyDownloadTypeHasAState() {
        assertEquals(
            mapOf(
                DownloadType.IsPending to DownloadState.QUEUED,
                DownloadType.IsDownloading to DownloadState.DOWNLOADING,
                DownloadType.IsPaused to DownloadState.PAUSED,
                DownloadType.IsDone to DownloadState.COMPLETED,
                DownloadType.IsFailed to DownloadState.FAILED,
                DownloadType.IsStopped to DownloadState.STOPPED,
            ),
            DownloadType.entries.associateWith(DownloadFacts::stateOf),
        )
    }

    @Test
    fun aRunningDownloadReportsRateBytesAndWritten() {
        report(DownloadType.IsDownloading, downloaded = 40L, written = 30L, rate = 8L)
        assertEquals(
            DownloadProgress(
                id,
                DownloadState.DOWNLOADING,
                bytesDownloaded = 40L,
                bytesTotal = 100L,
                bytesPerSecond = 8L,
                bytesWritten = 30L,
            ),
            fact,
        )
        assertEquals(7L, fact.estimatedSecondsRemaining)
    }

    @Test
    fun negativeNumbersAreClampedRatherThanThrown() {
        // onDelete resets the count mid-flight, which makes the computed rate negative.
        report(DownloadType.IsDownloading, downloaded = -1L, written = -1L, rate = -1L)
        assertEquals(0L, fact.bytesDownloaded)
        assertEquals(0L, fact.bytesWritten)
        assertEquals(0L, fact.bytesPerSecond)
    }

    @Test
    fun segmentsPassThrough() {
        report(DownloadType.IsDownloading, segments = SegmentProgress(2, 10))
        assertEquals(SegmentProgress(2, 10), fact.segments)
    }

    @Test
    fun aTotalOfZeroIsUnknown() {
        report(DownloadType.IsDownloading, total = 0L)
        assertNull(fact.bytesTotal)
    }

    @Test
    fun onlyARunningDownloadHasARate() {
        DownloadType.entries.filter { it != DownloadType.IsDownloading }.forEach { type ->
            report(type, written = 100L, downloaded = 100L)
            assertEquals(type.toString(), 0L, fact.bytesPerSecond)
        }
    }

    @Test
    fun aFailureCarriesTheNotedReason() {
        DownloadFacts.note(id, DownloadFailure.NetworkLost)
        report(DownloadType.IsFailed)
        assertEquals(DownloadState.FAILED, fact.state)
        assertEquals(DownloadFailure.NetworkLost, fact.failure)
    }

    @Test
    fun aFailureWithNoReasonSaysUnknownRatherThanNothing() {
        report(DownloadType.IsFailed)
        assertEquals(DownloadFailure.Unknown, fact.failure)
    }

    @Test
    fun aNewAttemptDoesNotInheritTheLastReason() {
        DownloadFacts.note(id, DownloadFailure.NetworkLost)
        report(DownloadType.IsPending)
        report(DownloadType.IsFailed)
        assertEquals(DownloadFailure.Unknown, fact.failure)
    }

    @Test
    fun aFullDiskIsNotOverwrittenByTheErrorsItCauses() {
        DownloadFacts.note(id, DownloadFailure.StorageFull(5L))
        DownloadFacts.note(id, DownloadFailure.NetworkLost)
        report(DownloadType.IsFailed)
        assertEquals(DownloadFailure.StorageFull(5L), fact.failure)
    }

    @Test
    fun unknownDoesNotReplaceANamedReason() {
        DownloadFacts.note(id, DownloadFailure.NetworkLost)
        DownloadFacts.note(id, DownloadFailure.Unknown)
        report(DownloadType.IsFailed)
        assertEquals(DownloadFailure.NetworkLost, fact.failure)
    }

    @Test
    fun aLaterNamedReasonReplacesAnEarlierOne() {
        DownloadFacts.note(id, DownloadFailure.NetworkLost)
        DownloadFacts.note(id, DownloadFailure.SourceFailed)
        report(DownloadType.IsFailed)
        assertEquals(DownloadFailure.SourceFailed, fact.failure)
    }

    @Test
    fun aFullDiskShowsOnARunningTransferAtOnce() {
        // A transfer that hits one may never end, so no later report can be relied on to say so.
        report(DownloadType.IsDownloading)
        DownloadFacts.note(id, DownloadFailure.StorageFull(null), bytesDownloaded = 80L, bytesWritten = 30L)
        assertEquals(DownloadState.DOWNLOADING, fact.state)
        assertEquals(DownloadFailure.StorageFull(null), fact.failure)
        assertEquals(80L, fact.bytesDownloaded)
        assertEquals(30L, fact.bytesWritten)
        assertEquals(0L, fact.bytesPerSecond)

        report(DownloadType.IsDownloading)
        assertEquals(DownloadFailure.StorageFull(null), fact.failure)
    }

    @Test
    fun aNetworkErrorThatARangeMayRetryDoesNotShowOnARunningTransfer() {
        report(DownloadType.IsDownloading)
        DownloadFacts.note(id, DownloadFailure.NetworkLost)
        report(DownloadType.IsDownloading)
        assertNull(fact.failure)
    }

    @Test
    fun doneWithEveryDeclaredByteWrittenIsCompleted() {
        report(DownloadType.IsDone, downloaded = 100L, written = 100L)
        assertEquals(DownloadState.COMPLETED, fact.state)
        assertNull(fact.failure)
    }

    @Test
    fun doneShortOfTheDeclaredTotalIsAFailure() {
        report(DownloadType.IsDone, downloaded = 60L, written = 60L)
        assertEquals(DownloadState.FAILED, fact.state)
        assertEquals(DownloadFailure.SourceFailed, fact.failure)
    }

    @Test
    fun doneWithANotedReasonIsAFailureWithThatReason() {
        // A lost connection in a range of unknown length: there is no total to fall short of.
        DownloadFacts.note(id, DownloadFailure.NetworkLost)
        report(DownloadType.IsDone, total = null)
        assertEquals(DownloadState.FAILED, fact.state)
        assertEquals(DownloadFailure.NetworkLost, fact.failure)
    }

    @Test
    fun anEstimatedTotalProvesNothingAboutTruncation() {
        // HLS: the estimate goes through a Float, so it can land just above the bytes written.
        report(DownloadType.IsDone, downloaded = 99L, written = 99L, total = 100L, declared = false)
        assertEquals(DownloadState.COMPLETED, fact.state)
    }

    @Test
    fun aRealCompletionDropsTheReasonOfAMirrorThatFailedBeforeIt() {
        DownloadFacts.note(id, DownloadFailure.NetworkLost)
        report(DownloadType.IsPending)
        report(DownloadType.IsDone, downloaded = 100L, written = 100L)
        assertEquals(DownloadState.COMPLETED, fact.state)
        DownloadFacts.finish(id, DownloadType.IsFailed)
        assertEquals(DownloadFailure.Unknown, fact.failure)
    }

    @Test
    fun finishingKeepsTheBytesAndNamesTheReason() {
        report(DownloadType.IsDownloading, downloaded = 40L, written = 30L)
        DownloadFacts.note(id, DownloadFailure.NoUsableLink)
        DownloadFacts.finish(id, DownloadType.IsFailed)
        assertEquals(DownloadState.FAILED, fact.state)
        assertEquals(DownloadFailure.NoUsableLink, fact.failure)
        assertEquals(40L, fact.bytesDownloaded)
        assertEquals(30L, fact.bytesWritten)
        assertEquals(0L, fact.bytesPerSecond)
    }

    @Test
    fun finishingWithNoTransferStillLeavesAFact() {
        DownloadFacts.note(id, DownloadFailure.NoUsableLink)
        DownloadFacts.finish(id, DownloadType.IsFailed)
        assertEquals(DownloadFailure.NoUsableLink, fact.failure)
    }

    @Test
    fun aStoppedDownloadHasNoFailure() {
        DownloadFacts.note(id, DownloadFailure.NetworkLost)
        DownloadFacts.finish(id, DownloadType.IsStopped)
        assertEquals(DownloadState.STOPPED, fact.state)
        assertNull(fact.failure)
    }

    @Test
    fun clearingForgetsTheFactAndTheReason() {
        DownloadFacts.note(id, DownloadFailure.NetworkLost)
        report(DownloadType.IsFailed)
        DownloadFacts.clear(id)
        assertFalse(DownloadFacts.facts.value.containsKey(id))

        report(DownloadType.IsFailed)
        assertEquals(DownloadFailure.Unknown, fact.failure)
    }

    @Test
    fun classifyNamesAFullDiskFromTheMessageAndThroughCauses() {
        val full = DownloadFailure.StorageFull(null)
        assertEquals(full, DownloadFacts.classify(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(full, DownloadFacts.classify(IOException("No space left on device")))
        assertEquals(full, DownloadFacts.classify(RuntimeException("wrapped", IOException("x", IOException("enospc")))))
    }

    @Test
    fun classifyPrefersAFullDiskOverANetworkWrapper() {
        assertEquals(
            DownloadFailure.StorageFull(null),
            DownloadFacts.classify(ProtocolException("x").apply { initCause(IOException("ENOSPC")) }),
        )
    }

    @Test
    fun classifyNamesNetworkLoss() {
        listOf(
            UnknownHostException("h"),
            ConnectException("c"),
            SocketTimeoutException("t"),
            EOFException("e"),
            ProtocolException("unexpected end of stream"),
            SSLHandshakeException("s"),
            IOException("wrapped", ConnectException("c")),
        ).forEach { error ->
            assertEquals(error.toString(), DownloadFailure.NetworkLost, DownloadFacts.classify(error))
        }
    }

    @Test
    fun classifyAdmitsWhatItCannotName() {
        assertEquals(DownloadFailure.Unknown, DownloadFacts.classify(IOException("nope")))
        assertEquals(DownloadFailure.Unknown, DownloadFacts.classify(IllegalStateException()))
    }

    @Test
    fun classifySurvivesACycleOfCauses() {
        val first = IOException("a")
        val second = IOException("b", first)
        first.initCause(second)
        assertEquals(DownloadFailure.Unknown, DownloadFacts.classify(first))
    }

    @Test
    fun aShortfallIsWhatIsLeftToWriteLessWhatIsFree() {
        assertEquals(60L, DownloadFacts.bytesShort(100L, 30L, 10L))
    }

    @Test
    fun aShortfallIsUnknownWithoutATotalOrFreeSpace() {
        assertNull(DownloadFacts.bytesShort(null, 30L, 10L))
        assertNull(DownloadFacts.bytesShort(0L, 30L, 10L))
        assertNull(DownloadFacts.bytesShort(100L, 30L, null))
    }

    @Test
    fun enoughRoomIsNoShortfall() {
        assertNull(DownloadFacts.bytesShort(100L, 30L, 70L))
        assertNull(DownloadFacts.bytesShort(100L, 30L, 500L))
    }

    @Test
    fun freeSpaceIsAskedOfTheFolderInUse() {
        assertEquals("/default", DownloadFacts.storagePathOf(null, "/default"))
        assertEquals("/default", DownloadFacts.storagePathOf(" ", "/default"))
        assertEquals("/picked", DownloadFacts.storagePathOf("/picked", "/default"))
        assertNull(DownloadFacts.storagePathOf("content://tree/1", "/default"))
    }

    @Test
    fun theLineIsRunningThenWaitingOneBasedOverBoth() {
        assertEquals(
            mapOf(
                1 to QueuePosition(1, 5),
                2 to QueuePosition(2, 5),
                3 to QueuePosition(3, 5),
                4 to QueuePosition(4, 5),
                5 to QueuePosition(5, 5),
            ),
            DownloadFacts.queuePositions(listOf(1, 2), listOf(3, 4, 5)),
        )
    }

    @Test
    fun anIdInBothListsCountsOnce() {
        assertEquals(
            mapOf(1 to QueuePosition(1, 2), 2 to QueuePosition(2, 2)),
            DownloadFacts.queuePositions(listOf(1), listOf(1, 2)),
        )
    }

    @Test
    fun anEmptyLineHasNoPositions() {
        assertEquals(emptyMap<Int, QueuePosition>(), DownloadFacts.queuePositions(emptyList(), emptyList()))
    }

    private fun progress(id: Int, state: DownloadState, failure: DownloadFailure? = null) =
        DownloadProgress(id, state, bytesDownloaded = 50L, bytesPerSecond = 9L, failure = failure)

    @Test
    fun aWaitingDownloadWithNoReportsStillHasAQueuedFact() {
        assertEquals(
            mapOf(4 to DownloadProgress(4, DownloadState.QUEUED, 0L, queue = QueuePosition(1, 1))),
            DownloadFacts.merge(emptyMap(), emptyList(), listOf(4)),
        )
    }

    @Test
    fun anInstanceStillLoadingLinksHasAPlaceInLine() {
        val merged = DownloadFacts.merge(emptyMap(), listOf(4), listOf(5))
        assertEquals(QueuePosition(1, 2), merged.getValue(4).queue)
        assertEquals(DownloadState.QUEUED, merged.getValue(4).state)
    }

    @Test
    fun aRunningDownloadKeepsItsFactsAndGainsAPosition() {
        val running = progress(1, DownloadState.DOWNLOADING)
        assertEquals(
            running.copy(queue = QueuePosition(1, 1)),
            DownloadFacts.merge(mapOf(1 to running), listOf(1), emptyList()).getValue(1),
        )
    }

    @Test
    fun aFinishedInstanceTakesNoPlaceInLine() {
        // The service removes it a moment later, and until then it must not count as ahead of anyone.
        val done = progress(1, DownloadState.COMPLETED)
        val merged = DownloadFacts.merge(mapOf(1 to done), listOf(1), listOf(2))
        assertEquals(done, merged.getValue(1))
        assertEquals(QueuePosition(1, 1), merged.getValue(2).queue)
    }

    @Test
    fun aFailureBackInTheQueueIsWaitingAgainWithItsBytes() {
        val failed = progress(1, DownloadState.FAILED, DownloadFailure.NetworkLost)
        val merged = DownloadFacts.merge(mapOf(1 to failed), emptyList(), listOf(1)).getValue(1)
        assertEquals(DownloadState.QUEUED, merged.state)
        assertNull(merged.failure)
        assertEquals(0L, merged.bytesPerSecond)
        assertEquals(50L, merged.bytesDownloaded)
        assertEquals(QueuePosition(1, 1), merged.queue)
    }

    @Test
    fun aDownloadOutOfTheLineHasNoPosition() {
        val failed = progress(1, DownloadState.FAILED, DownloadFailure.NetworkLost)
        assertEquals(failed, DownloadFacts.merge(mapOf(1 to failed), emptyList(), emptyList()).getValue(1))
    }

    @Test
    fun theObservedFlowCombinesTheThreeSources() = runBlocking {
        val merged = DownloadFacts.observe(
            MutableStateFlow(mapOf(1 to progress(1, DownloadState.DOWNLOADING))),
            MutableStateFlow(listOf(1)),
            MutableStateFlow(listOf(2)),
        ).first()
        assertEquals(QueuePosition(1, 2), merged.getValue(1).queue)
        assertEquals(QueuePosition(2, 2), merged.getValue(2).queue)
    }
}

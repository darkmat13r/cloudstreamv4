package com.lagradost.cloudstream4.feature.downloads.series

import com.lagradost.cloudstream4.media.model.DownloadFailure
import com.lagradost.cloudstream4.media.model.DownloadProgress
import com.lagradost.cloudstream4.media.model.DownloadState
import com.lagradost.cloudstream4.media.model.QueuePosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SeriesShelfTest {

    private fun episode(
        bytes: Long = 0L,
        total: Long = MB_512,
        fact: DownloadProgress? = null,
        watched: Float? = null,
        season: Int? = 1,
        number: Int? = 1,
        id: Int = 1,
    ) = SeriesShelf.episode(id, season, number, "Pilot", bytes, total, fact, watched)

    private fun fact(state: DownloadState, bytes: Long = 0L, failure: DownloadFailure? = null) =
        DownloadProgress(id = 1, state = state, bytesDownloaded = bytes, bytesTotal = MB_512, bytesPerSecond = 4_000_000L, failure = failure)

    @Test
    fun withNoFactAWholeFileIsDoneAndAPartOneIsPaused() {
        assertEquals(DownloadEpisodeState.Done, episode(bytes = MB_512).state)
        assertEquals(DownloadEpisodeState.Done, episode(bytes = MB_512 - 1000L).state)
        assertEquals(DownloadEpisodeState.Paused, episode(bytes = MB_512 / 2).state)
    }

    @Test
    fun aFactDecidesTheStateAndWaitingIsItsOwn() {
        assertEquals(DownloadEpisodeState.Running, episode(fact = fact(DownloadState.DOWNLOADING)).state)
        assertEquals(DownloadEpisodeState.Queued, episode(fact = fact(DownloadState.QUEUED)).state)
        assertEquals(DownloadEpisodeState.Paused, episode(fact = fact(DownloadState.PAUSED)).state)
        assertEquals(DownloadEpisodeState.Failed, episode(fact = fact(DownloadState.FAILED)).state)
        assertEquals(DownloadEpisodeState.Done, episode(bytes = MB_512, fact = fact(DownloadState.STOPPED)).state)
    }

    @Test
    fun aRunningTransferTakesItsBytesRateAndTimeFromTheFact() {
        val running = episode(bytes = 100L, fact = fact(DownloadState.DOWNLOADING, bytes = MB_128))
        assertEquals(MB_128, running.bytesDone)
        assertEquals(4_000_000L, running.bytesPerSecond)
        assertEquals(0.25f, running.progress)
        assertEquals(true, (running.secondsLeft ?: 0L) > 0L)
    }

    @Test
    fun onlyAFailureCarriesItsReasonAndOnlyARunningOneItsRate() {
        val failed = episode(bytes = MB_128, fact = fact(DownloadState.FAILED, failure = DownloadFailure.NetworkLost))
        assertEquals(DownloadFailure.NetworkLost, failed.failure)
        assertEquals(0L, failed.bytesPerSecond)
        assertNull(episode(fact = fact(DownloadState.PAUSED, failure = DownloadFailure.NetworkLost)).failure)
    }

    @Test
    fun aFinishedEpisodeHasNoProgressRule() {
        assertNull(episode(bytes = MB_512).progress)
        assertEquals(0.5f, episode(bytes = MB_256).progress)
    }

    @Test
    fun playNextIsTheFirstPlayableOneNotWatchedToTheEnd() {
        val watched = episode(id = 1, bytes = MB_512, watched = 0.97f)
        val half = episode(id = 2, bytes = MB_512, watched = 0.4f, number = 2)
        val nothing = episode(id = 3, bytes = 0L, number = 3, fact = fact(DownloadState.QUEUED))
        assertEquals(DownloadEpisodeId(2), SeriesShelf.next(listOf(watched, half, nothing))?.id)
        assertEquals(DownloadEpisodeId(1), SeriesShelf.next(listOf(watched, nothing))?.id)
        assertNull(SeriesShelf.next(listOf(nothing)))
    }

    @Test
    fun theOrderIsSeasonThenEpisodeWithUnknownsFirst() {
        val order = SeriesShelf.sorted(
            listOf(episode(id = 4, season = 2, number = 1), episode(id = 3, season = 1, number = 2), episode(id = 2, season = null, number = null)),
        ).map { it.id.value }
        assertEquals(listOf(2, 3, 4), order)
    }

    @Test
    fun aQueuedOneKeepsItsPlaceInLine() {
        val queued = SeriesShelf.episode(1, 1, 1, null, 0L, 0L, fact(DownloadState.QUEUED).copy(queue = QueuePosition(2, 3)), null)
        assertEquals(QueuePosition(2, 3), queued.queue)
        assertNull(queued.name)
    }

    private companion object {
        const val MB_128 = 128L * 1_048_576L
        const val MB_256 = 256L * 1_048_576L
        const val MB_512 = 512L * 1_048_576L
    }
}

package com.lagradost.cloudstream4.feature.downloads

import com.lagradost.cloudstream4.media.model.DownloadFailure
import com.lagradost.cloudstream4.media.model.DownloadProgress
import com.lagradost.cloudstream4.media.model.DownloadState
import com.lagradost.cloudstream4.media.model.QueuePosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadShelfTest {

    private fun series(vararg episodes: EpisodeOnDisk, facts: Map<Int, DownloadProgress> = emptyMap()) =
        DownloadShelf.title(DownloadTitleId(1), "A series", DownloadTitleKind.Series, episodes.toList(), facts)

    private val whole = EpisodeOnDisk(10, 2_000_000, 2_000_000)
    private val half = EpisodeOnDisk(11, 1_000_000, 2_000_000)

    @Test
    fun everyEpisodeWholeIsDoneWithNoBarAndItsTotal() {
        val title = series(whole, whole.copy(id = 12))
        assertEquals(DownloadTitleState.Done, title.state)
        assertEquals(2, title.episodes)
        assertEquals(4_000_000, title.bytesTotal)
        assertNull(title.progress)
    }

    /** `BaseFetchButton.getStatus`'s margin: a file within 1 KiB of its total is finished. */
    @Test
    fun aFileWithinTheOldMarginOfItsTotalIsDone() {
        assertEquals(DownloadTitleState.Done, series(EpisodeOnDisk(10, 2_000_000, 2_000_500)).state)
        assertEquals(DownloadTitleState.Paused, series(EpisodeOnDisk(10, 2_000_000, 2_002_000)).state)
    }

    /** Facts are in memory, so after a restart a short file must not claim to have failed. */
    @Test
    fun aShortFileWithNoFactIsPausedNotFailed() {
        val title = series(whole, half)
        assertEquals(DownloadTitleState.Paused, title.state)
        assertEquals(0.75f, title.progress)
    }

    @Test
    fun aRunningFactMovesTheBytesThatTheDiskReadCannot() {
        val fact = DownloadProgress(11, DownloadState.DOWNLOADING, bytesDownloaded = 1_500_000, bytesTotal = 2_000_000)
        val title = series(whole, half, facts = mapOf(11 to fact))
        assertEquals(DownloadTitleState.Running, title.state)
        assertEquals(3_500_000, title.bytesDone)
        assertEquals(0.875f, title.progress)
    }

    @Test
    fun aFailureOutranksAnotherEpisodeRunningSoItsRetryIsNotHidden() {
        val facts = mapOf(
            10 to DownloadProgress(10, DownloadState.DOWNLOADING, bytesDownloaded = 1L, bytesTotal = 2_000_000),
            11 to DownloadProgress(11, DownloadState.FAILED, bytesDownloaded = 1L, failure = DownloadFailure.NetworkLost),
        )
        assertEquals(DownloadTitleState.Failed, series(whole, half, facts = facts).state)
    }

    @Test
    fun aSegmentedSingleFileUsesItsOwnFraction() {
        val fact = DownloadProgress(
            11,
            DownloadState.DOWNLOADING,
            bytesDownloaded = 1L,
            bytesTotal = 100L,
            segments = com.lagradost.cloudstream4.media.model.SegmentProgress(1, 4),
        )
        assertEquals(0.25f, series(half, facts = mapOf(11 to fact)).progress)
    }

    @Test
    fun onlyWhatDidNotFinishIsResumable() {
        assertFalse(DownloadShelf.isResumable(whole, null))
        assertTrue(DownloadShelf.isResumable(half, null))
        assertTrue(DownloadShelf.isResumable(half, DownloadProgress(11, DownloadState.FAILED, 1L)))
        assertFalse(DownloadShelf.isResumable(half, DownloadProgress(11, DownloadState.DOWNLOADING, 1L)))
    }

    @Test
    fun theQueueCountsWhatIsMovingAndTheWholeLine() {
        val facts = mapOf(
            1 to DownloadProgress(1, DownloadState.DOWNLOADING, 1L, queue = QueuePosition(1, 3)),
            2 to DownloadProgress(2, DownloadState.QUEUED, 0L, queue = QueuePosition(2, 3)),
            3 to DownloadProgress(3, DownloadState.QUEUED, 0L, queue = QueuePosition(3, 3)),
            4 to DownloadProgress(4, DownloadState.COMPLETED, 1L),
        )
        assertEquals(DownloadQueue(running = 1, total = 3), DownloadShelf.queue(facts))
        assertEquals(2, DownloadShelf.queue(facts).waiting)
        assertEquals(DownloadQueue(), DownloadShelf.queue(emptyMap()))
    }

    @Test
    fun aFullDiskTakesTheLargestKnownShortfallAndAnUnknownOneStillCounts() {
        fun full(id: Int, short: Long?) =
            DownloadProgress(id, DownloadState.FAILED, 1L, failure = DownloadFailure.StorageFull(short))
        assertNull(DownloadShelf.storageFull(mapOf(1 to DownloadProgress(1, DownloadState.FAILED, 1L))))
        assertEquals(StorageShortfall(null), DownloadShelf.storageFull(mapOf(1 to full(1, null))))
        assertEquals(StorageShortfall(9L), DownloadShelf.storageFull(mapOf(1 to full(1, 3L), 2 to full(2, 9L), 3 to full(3, null))))
    }
}

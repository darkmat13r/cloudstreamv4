package com.lagradost.cloudstream4.download

import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadType
import com.lagradost.cloudstream4.download.EngineDownloadRules.StagedMarker
import com.lagradost.cloudstream4.download.EngineDownloadRules.StartAction
import com.lagradost.cloudstream4.media.model.DownloadFailure
import com.lagradost.cloudstream4.media.model.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineDownloadRulesTest {
    @Test
    fun aLostConnectionRetriesTheSameLinkAndNeverHandsItsPartsToAnotherMirror() {
        val status = EngineDownloadRules.statusOf(DownloadFailure.NetworkLost)
        assertEquals(true, status.retrySame)
        assertEquals(false, status.tryNext)
        assertEquals(false, status.success)
    }

    @Test
    fun aSourceThatWillNotWorkMovesToTheNextMirrorWithoutRetrying() {
        listOf(
            DownloadFailure.ServerRefused(403),
            DownloadFailure.NoUsableLink,
            DownloadFailure.SourceChanged,
            DownloadFailure.AddressRefused,
            DownloadFailure.HashMismatch,
            DownloadFailure.SizeMismatch(10, 9),
            DownloadFailure.SourceFailed,
        ).forEach { failure ->
            assertEquals(failure.toString(), EngineDownloadRules.TRY_NEXT, EngineDownloadRules.statusOf(failure))
        }
    }

    @Test
    fun aDiskProblemTriesNoOtherMirrorBecauseEveryMirrorWritesToTheSameDisk() {
        listOf(
            DownloadFailure.StorageFull(null),
            DownloadFailure.StorageFull(5),
            DownloadFailure.StorageUnavailable,
            DownloadFailure.InsufficientSpace(10, 5),
            DownloadFailure.DestinationExists,
        ).forEach { failure ->
            val status = EngineDownloadRules.statusOf(failure)
            assertEquals(failure.toString(), false, status.retrySame)
            assertEquals(failure.toString(), false, status.tryNext)
            assertEquals(failure.toString(), false, status.success)
        }
    }

    @Test
    fun anUnexplainedFailureRetriesThenMovesOnAsDownloadThingDoes() {
        assertEquals(EngineDownloadRules.FAILED, EngineDownloadRules.statusOf(DownloadFailure.Unknown))
    }

    @Test
    fun successAndStopEndTheMirrorLoopAsSuccess() {
        assertEquals(true, EngineDownloadRules.SUCCESS.success)
        assertEquals(true, EngineDownloadRules.STOPPED.success)
        assertEquals(false, EngineDownloadRules.STOPPED.tryNext)
    }

    @Test
    fun everyEngineStateHasTheOldStatusOfTheSameMeaning() {
        assertEquals(DownloadType.IsPending, EngineDownloadRules.typeOf(DownloadState.QUEUED))
        assertEquals(DownloadType.IsDownloading, EngineDownloadRules.typeOf(DownloadState.DOWNLOADING))
        assertEquals(DownloadType.IsPaused, EngineDownloadRules.typeOf(DownloadState.PAUSED))
        assertEquals(DownloadType.IsDone, EngineDownloadRules.typeOf(DownloadState.COMPLETED))
        assertEquals(DownloadType.IsFailed, EngineDownloadRules.typeOf(DownloadState.FAILED))
        assertEquals(DownloadType.IsStopped, EngineDownloadRules.typeOf(DownloadState.STOPPED))
    }

    @Test
    fun theMarkerReadsBackWhatWasWritten() {
        val marker = StagedMarker(123_456_789L, "Episode 1 - Pilot.mp4")
        assertEquals(marker, EngineDownloadRules.parseMarker(EngineDownloadRules.formatMarker(marker)))
    }

    @Test
    fun aTornOrForeignMarkerIsNoMarker() {
        listOf(
            null,
            "",
            "123",
            "123\nname.mp4", // torn: the last newline never reached the disk
            "-1\nname.mp4\n",
            "abc\nname.mp4\n",
            "123\n\n",
            "123\n../escape.mp4\n",
            "123\n.hidden\n",
            "123\ncomplete\n",
            "123\nname.mp4\nextra\n",
        ).forEach { text -> assertNull(text.toString(), EngineDownloadRules.parseMarker(text)) }
    }

    @Test
    fun aCompleteStagedFileIsCopiedAgainNotFetchedAgain() {
        val marker = StagedMarker(1000L, "a.mp4")
        assertEquals(StartAction.COPY_STAGED, EngineDownloadRules.startAction(tryResume = true, marker, 1000L))
    }

    @Test
    fun aMarkerWhoseFileIsMissingOrTheWrongSizeStartsOver() {
        val marker = StagedMarker(1000L, "a.mp4")
        assertEquals(StartAction.DOWNLOAD_FRESH, EngineDownloadRules.startAction(tryResume = true, marker, null))
        assertEquals(StartAction.DOWNLOAD_FRESH, EngineDownloadRules.startAction(tryResume = true, marker, 999L))
    }

    @Test
    fun noMarkerResumesWhatTheEngineCanProve() {
        assertEquals(StartAction.DOWNLOAD_RESUME, EngineDownloadRules.startAction(tryResume = true, null, null))
    }

    @Test
    fun aNewDownloadAlwaysStartsOverAsDownloadThingTruncatesItsFile() {
        val marker = StagedMarker(1000L, "a.mp4")
        assertEquals(StartAction.DOWNLOAD_FRESH, EngineDownloadRules.startAction(tryResume = false, marker, 1000L))
        assertEquals(StartAction.DOWNLOAD_FRESH, EngineDownloadRules.startAction(tryResume = false, null, null))
    }

    @Test
    fun stagingNeedsWhatIsLeftToFetchPlusTheWholeCopy() {
        assertEquals(DownloadFailure.InsufficientSpace(1500L, 1499L), EngineDownloadRules.stagingShortfall(1000L, 500L, 1499L))
        assertNull(EngineDownloadRules.stagingShortfall(1000L, 500L, 1500L))
        assertNull(EngineDownloadRules.stagingShortfall(1000L, 0L, null))
        assertNull(EngineDownloadRules.stagingShortfall(0L, 0L, 0L))
        // More on disk than the total (a part that overshot) never asks for less than the copy.
        assertEquals(DownloadFailure.InsufficientSpace(1000L, 999L), EngineDownloadRules.stagingShortfall(1000L, 1200L, 999L))
    }

    @Test
    fun theCopyNeedsTheStagedSizeFree() {
        assertEquals(DownloadFailure.InsufficientSpace(1000L, 999L), EngineDownloadRules.copyShortfall(1000L, 999L))
        assertNull(EngineDownloadRules.copyShortfall(1000L, 1000L))
        assertNull(EngineDownloadRules.copyShortfall(1000L, null))
    }

    @Test
    fun stagingWhoseEpisodeIsGoneIsSweptAndEverythingStillLiveIsKept() {
        val swept = EngineDownloadRules.stagingToSweep(
            stagedNames = listOf("1", "2", "3", "4", "5", "6", "not-an-id"),
            running = setOf(1),
            queued = setOf(2),
            // 4: resumable and listed. 5: a failed download the list pruned, its resume package left behind.
            resumable = setOf(1, 4, 5),
            listed = setOf(4, 6),
        )
        // 3: nothing knows it. 5: pruned. 6: listed, but nothing can resume it (stopped, or deleted).
        assertEquals(listOf("3", "5", "6"), swept)
    }

    @Test
    fun aRunningOrQueuedIdIsNeverSweptWhateverItsKeysSay() {
        assertEquals(
            emptyList<String>(),
            EngineDownloadRules.stagingToSweep(listOf("7", "8"), setOf(7), setOf(8), emptySet(), emptySet()),
        )
    }

    @Test
    fun theIdIsTheLastSegmentOfAStoredKey() {
        assertEquals(123, EngineDownloadRules.idOfKey("download_resume_2/123"))
        assertEquals(123, EngineDownloadRules.idOfKey("download_episode_cache/9/123"))
        assertEquals(-5, EngineDownloadRules.idOfKey("download_resume_2/-5"))
        assertNull(EngineDownloadRules.idOfKey("download_resume_2/"))
        assertNull(EngineDownloadRules.idOfKey("download_resume_2/abc"))
    }

    @Test
    fun theSmallerKnownReadingWins() {
        assertEquals(5L, EngineDownloadRules.minOfKnown(5L, 9L))
        assertEquals(9L, EngineDownloadRules.minOfKnown(null, 9L))
        assertEquals(5L, EngineDownloadRules.minOfKnown(5L, null))
        assertNull(EngineDownloadRules.minOfKnown(null, null))
    }

    @Test
    fun theDestinationTakesTheStagedNameWhenItKeptItsExtension() {
        assertEquals("Episode 1", EngineDownloadRules.destinationStem("Episode 1.mp4", "mp4", "fallback"))
        assertEquals("fallback", EngineDownloadRules.destinationStem("download", "mp4", "fallback"))
        assertEquals("fallback", EngineDownloadRules.destinationStem(".mp4", "mp4", "fallback"))
    }
}

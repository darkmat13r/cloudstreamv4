package com.lagradost.cloudstream4.feature.detail

import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.episode_minutes_left
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The one decision both episode rows draw from (`KMP-63`). */
class EpisodeWatchReadoutTest {

    private fun row(positionMs: Long? = null, durationMs: Long = DURATION, marked: Boolean = false) = DetailEpisode(
        name = null,
        data = "e1",
        season = 1,
        episode = 1,
        progress = positionMs?.let { DetailResume("e1", it, durationMs, episode = null) },
        markedWatched = marked,
    )

    /**
     * `BUG-91`: the minutes left are a plural, picked by the count, so a locale whose words change with the number
     * can say it once a native speaker writes its forms. Every form holds today's text, so nothing reads differently
     * (`DetailScreenTest` reads the words at 1 and 24).
     */
    @Test
    fun theMinutesLeftAreAPluralPickedByTheirCount() {
        assertEquals(
            UiString.Plural(Res.plurals.episode_minutes_left, 24),
            episodeWatchText(EpisodeWatchReadout.Left(24)),
        )
        assertEquals(UiString.Plural(Res.plurals.episode_minutes_left, 1), episodeWatchText(EpisodeWatchReadout.Left(1)))
    }

    @Test
    fun ninetyFivePercentIsWatchedAndJustUnderItIsTimeLeft() {
        assertEquals(EpisodeWatchReadout.Watched, row(positionMs = DURATION * 95 / 100).watchReadout())
        assertEquals(EpisodeWatchReadout.Left(3), row(positionMs = DURATION * 949 / 1000).watchReadout())
    }

    /**
     * The boundary, to the millisecond, as `fixVisual` draws it: exactly 95% is watched, and one millisecond short
     * is not. Over a long duration that millisecond is below `Float` precision, so `fraction >= 0.95f` would call
     * it watched.
     */
    @Test
    fun ninetyFivePercentToTheMillisecondIsTheIntegerTestFixVisualMakes() {
        val long = 100_000_000L
        assertEquals(EpisodeWatchReadout.Watched, row(positionMs = 95_000_000L, durationMs = long).watchReadout())
        assertFalse(DetailResume("e1", 94_999_999L, long, episode = null).finished)
        assertEquals(EpisodeWatchReadout.Left(84), row(positionMs = 94_999_999L, durationMs = long).watchReadout())
    }

    /** 90% is when `:app`'s Resume moves on, not when a row reads watched. */
    @Test
    fun ninetyPercentStillSaysWhatIsLeft() {
        assertEquals(EpisodeWatchReadout.Left(5), row(positionMs = DURATION * 90 / 100).watchReadout())
    }

    @Test
    fun timeLeftRoundsUpSoThirtySecondsIsOneMinute() {
        assertEquals(EpisodeWatchReadout.Left(1), row(positionMs = 60_000L, durationMs = 90_000L).watchReadout())
    }

    @Test
    fun nothingUsableIsNone() {
        assertEquals(EpisodeWatchReadout.None, row().watchReadout())
        assertEquals(EpisodeWatchReadout.None, row(positionMs = 0L).watchReadout())
        assertEquals(EpisodeWatchReadout.None, row(positionMs = 60_000L, durationMs = 0L).watchReadout())
        assertFalse(DetailResume("e1", 60_000L, 0L, episode = null).finished)
    }

    /**
     * `fixVisual`'s whole-percent rule: at most 1% in integer arithmetic is not started. So 1.1% and 1.9% are
     * still nothing, and 2% is the first that reads what is left.
     */
    @Test
    fun underTwoWholePercentIsNotStartedAsTheOldRowDrawsIt() {
        assertEquals(EpisodeWatchReadout.None, row(positionMs = DURATION * 9 / 1000).watchReadout())
        assertEquals(EpisodeWatchReadout.None, row(positionMs = DURATION * 11 / 1000).watchReadout())
        assertEquals(EpisodeWatchReadout.None, row(positionMs = DURATION * 19 / 1000).watchReadout())
        assertEquals(EpisodeWatchReadout.Left(49), row(positionMs = DURATION * 2 / 100).watchReadout())
    }

    /** `:app` clears the mark when the episode is played again, so a mark still set beats the position. */
    @Test
    fun theMarkWinsOverAPositionPartWayThrough() {
        assertEquals(EpisodeWatchReadout.Watched, row(positionMs = DURATION * 40 / 100, marked = true).watchReadout())
    }

    @Test
    fun theMarkIsWatchedWithOrWithoutAPosition() {
        assertEquals(EpisodeWatchReadout.Watched, row(marked = true).watchReadout())
        assertEquals(EpisodeWatchReadout.Watched, row(positionMs = 60_000L, marked = true).watchReadout())
    }

    private companion object {
        /** 50 minutes. */
        const val DURATION = 3_000_000L
    }
}

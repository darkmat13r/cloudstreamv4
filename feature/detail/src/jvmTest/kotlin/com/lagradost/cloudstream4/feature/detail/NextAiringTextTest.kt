package com.lagradost.cloudstream4.feature.detail

import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.next_episode_format
import com.lagradost.cloudstream4.core.strings.generated.resources.next_episode_time_day_format
import com.lagradost.cloudstream4.core.strings.generated.resources.next_episode_time_days_only
import com.lagradost.cloudstream4.core.strings.generated.resources.next_episode_time_hour_format
import com.lagradost.cloudstream4.core.strings.generated.resources.next_episode_time_min_format
import com.lagradost.cloudstream4.core.strings.generated.resources.next_season_episode_format
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The next episode's countdown: Android's arithmetic and words (`ResultViewModel2`), counted from a fixed now so no
 * clock or time zone enters the test.
 */
class NextAiringTextTest {

    private fun airingIn(ms: Long, season: Int? = 2) = DetailNextAiring(season, episode = 5, airDateMs = NOW + ms)

    private fun countdown(ms: Long) = nextAiringText(airingIn(ms), NOW)?.countdown

    private fun day(days: Int, hours: Int, minutes: Int) =
        UiString.Resource(Res.string.next_episode_time_day_format, listOf(days, hours, minutes))

    private fun hour(hours: Int, minutes: Int) =
        UiString.Resource(Res.string.next_episode_time_hour_format, listOf(hours, minutes))

    private fun min(minutes: Int) = UiString.Resource(Res.string.next_episode_time_min_format, listOf(minutes))

    @Test
    fun aDayOrMoreAwayNamesDaysThenTheHoursAndMinutesLeftOver() {
        assertEquals(day(3, 4, 12), countdown(3 * DAY + 4 * HOUR + 12 * MINUTE))
        assertEquals(day(1, 0, 0), countdown(DAY), "tomorrow, to the minute")
        assertEquals(day(1, 0, 0), countdown(DAY + 59 * SECOND), "seconds are dropped, never rounded up")
        assertEquals(day(400, 0, 1), countdown(400 * DAY + MINUTE), "a far date still counts in days")
    }

    @Test
    fun underADayNamesHoursAndMinutes() {
        assertEquals(hour(23, 59), countdown(DAY - MINUTE))
        assertEquals(hour(1, 0), countdown(HOUR))
    }

    @Test
    fun underAnHourNamesMinutesAlone() {
        assertEquals(min(59), countdown(HOUR - SECOND))
        assertEquals(min(1), countdown(MINUTE))
    }

    /** Decided as Android does it: no "soon", no "aired" — the line goes, and the page reads as if no date were known. */
    @Test
    fun underAMinuteAtOrPastTheAirTimeThereIsNoLine() {
        assertNull(countdown(MINUTE - 1))
        assertNull(countdown(SECOND))
        assertNull(countdown(0))
        assertNull(countdown(-DAY))
    }

    /** A source with only a day (TMDB): [airDateMs] is that day's UTC midnight, and the count is in whole days. */
    private fun daysOnly(ms: Long) =
        nextAiringText(DetailNextAiring(2, episode = 5, airDateMs = NOW + ms, dateOnly = true), NOW)?.countdown

    private fun days(count: Int) = UiString.Plural(Res.plurals.next_episode_time_days_only, count)

    /** No hours or minutes are named, since the source has none to give: any part of a day still to go is a day. */
    @Test
    fun aDateOnlySourceCountsWholeDaysRoundedUp() {
        assertEquals(days(3), daysOnly(2 * DAY + 4 * HOUR + 12 * MINUTE))
        assertEquals(days(3), daysOnly(3 * DAY), "exactly three days")
        assertEquals(days(4), daysOnly(3 * DAY + 1), "a millisecond past three days is a fourth")
        assertEquals(days(1), daysOnly(1), "tomorrow's midnight, a moment from now, is still a day")
        assertEquals(days(1), daysOnly(DAY))
    }

    /** Without a time, the day itself may already have aired: the line goes from its midnight on. */
    @Test
    fun aDateOnlySourceHasNoLineOnTheDayOrAfterIt() {
        assertNull(daysOnly(0))
        assertNull(daysOnly(-HOUR))
        assertNull(daysOnly(-DAY))
    }

    /** The episode's half does not depend on the precision: only the countdown changes. */
    @Test
    fun aDateOnlySourceNamesTheEpisodeTheSameWay() {
        assertEquals(
            UiString.Resource(Res.string.next_season_episode_format, listOf(2, 5)),
            nextAiringText(DetailNextAiring(2, episode = 5, airDateMs = NOW + DAY, dateOnly = true), NOW)?.episode,
        )
    }

    @Test
    fun theEpisodeIsNamedWithItsSeasonWhereThereIsOne() {
        assertEquals(
            UiString.Resource(Res.string.next_season_episode_format, listOf(2, 5)),
            nextAiringText(airingIn(DAY, season = 2), NOW)?.episode,
        )
        assertEquals(
            UiString.Resource(Res.string.next_episode_format, listOf(5)),
            nextAiringText(airingIn(DAY, season = null), NOW)?.episode,
        )
    }

    private companion object {
        /** 2026-11-16T12:54:56.789Z: off the hour and the second, so a truncation slip shows. */
        const val NOW = 1_794_833_696_789L
        const val SECOND = 1_000L
        const val MINUTE = 60 * SECOND
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}

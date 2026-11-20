package com.lagradost.cloudstream4.feature.detail

import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.month_short_dec
import com.lagradost.cloudstream4.core.strings.generated.resources.month_short_feb
import com.lagradost.cloudstream4.core.strings.generated.resources.month_short_jan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * An episode's air date as a calendar day (`KMP-120`). [DetailEpisode.airDateMs] is that day's UTC midnight, so the day
 * is read in UTC and nothing here depends on the time zone the test runs in.
 */
class AirDateTextTest {

    @Test
    fun aUtcMidnightIsItsOwnDay() {
        assertEquals(AirDateText(21, Res.string.month_short_jan, 2008), airDateText(JAN_21_2008))
        assertEquals(AirDateText(29, Res.string.month_short_feb, 2024), airDateText(LEAP_DAY))
        assertEquals(AirDateText(31, Res.string.month_short_dec, 2026), airDateText(DEC_31_2026))
        assertEquals(AirDateText(1, Res.string.month_short_jan, 1970), airDateText(0))
        assertEquals(AirDateText(31, Res.string.month_short_dec, 1969), airDateText(-DAY), "before the epoch")
    }

    /** Any instant in the day is that day: the day is taken, never rounded to the nearest midnight. */
    @Test
    fun theWholeDayIsTheSameDay() {
        assertEquals(airDateText(JAN_21_2008), airDateText(JAN_21_2008 + DAY - 1))
        assertEquals(AirDateText(31, Res.string.month_short_dec, 1969), airDateText(-1))
    }

    /** Years 1 to 9999 only: a date past them is a provider's junk, drawn as no date rather than a strange one. */
    @Test
    fun aDayOutsideYearsOneTo9999IsNone() {
        assertEquals(AirDateText(1, Res.string.month_short_jan, 1), airDateText(FIRST_DAY * DAY))
        assertEquals(AirDateText(31, Res.string.month_short_dec, 9999), airDateText(LAST_DAY * DAY))
        assertNull(airDateText((FIRST_DAY - 1) * DAY))
        assertNull(airDateText((LAST_DAY + 1) * DAY))
        assertNull(airDateText(Long.MIN_VALUE))
        assertNull(airDateText(Long.MAX_VALUE))
    }

    private companion object {
        const val DAY = 86_400_000L
        const val JAN_21_2008 = 1_200_873_600_000L
        const val LEAP_DAY = 1_709_164_800_000L
        const val DEC_31_2026 = 1_798_675_200_000L
        const val FIRST_DAY = -719_162L // 0001-01-01
        const val LAST_DAY = 2_932_896L // 9999-12-31
    }
}

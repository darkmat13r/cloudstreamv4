package com.lagradost.cloudstream3

import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * `addDate(String, String)` resolves an offset-less date-time in `TimeZone.currentSystemDefault()` and
 * takes no zone parameter, so the only way to make an expectation independent of the machine is to
 * pin the JVM default zone. On the JVM kotlinx-datetime reads `ZoneId.systemDefault()`, which follows
 * `java.util.TimeZone.setDefault`, so each case names its zone and states its expected instant as a
 * literal. That is the difference from the cases in [EpisodeDateTest] that build their expectation
 * from `currentSystemDefault()`: those agree with the code in every zone, these do not.
 *
 * JVM-only because the default-zone override has no common equivalent.
 */
class EpisodeDateFixedZoneTest {

    private val api = object : MainAPI() {
        override var name = "Test"
        override var mainUrl = "https://test.com"
    }

    private lateinit var original: TimeZone

    @BeforeTest
    fun saveZone() {
        original = TimeZone.getDefault()
    }

    @AfterTest
    fun restoreZone() {
        TimeZone.setDefault(original)
    }

    private fun parse(zone: String, date: String, format: String): Long? {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        val ep = api.newEpisode("")
        ep.addDate(date, format)
        return ep.date
    }

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilliseconds()

    // No offset: read in the device zone.

    @Test
    fun noOffsetIsoDateTimeInKolkataIsTenThirtyIst() {
        // 10:30 at +05:30 is 05:00Z. The UTC reading would be 10:30Z.
        assertEquals(millis("2026-05-17T05:00:00Z"), parse("Asia/Kolkata", "2026-05-17T10:30:00", "yyyy-MM-dd'T'HH:mm:ss"))
    }

    @Test
    fun noOffsetCustomDateTimeInKolkataIsFourteenThirtyFiveIst() {
        // 14:35 at +05:30 is 09:05Z. The UTC reading would be 14:35Z.
        assertEquals(millis("2026-05-17T09:05:00Z"), parse("Asia/Kolkata", "17/05/2026 14:35", "dd/MM/yyyy HH:mm"))
    }

    @Test
    fun noOffsetDateTimeInNewYorkUsesDaylightTime() {
        // 14:35 at -04:00 (EDT in May) is 18:35Z.
        assertEquals(millis("2026-05-17T18:35:00Z"), parse("America/New_York", "17/05/2026 14:35", "dd/MM/yyyy HH:mm"))
    }

    @Test
    fun noOffsetDateTimeInNewYorkUsesStandardTimeInWinter() {
        // 14:35 at -05:00 (EST in January) is 19:35Z.
        assertEquals(millis("2026-01-17T19:35:00Z"), parse("America/New_York", "17/01/2026 14:35", "dd/MM/yyyy HH:mm"))
    }

    @Test
    fun noOffsetDateTimeInUtcIsTheWallClockAsUtc() {
        assertEquals(millis("2026-05-17T14:35:00Z"), parse("UTC", "17/05/2026 14:35", "dd/MM/yyyy HH:mm"))
    }

    // An offset carried by the string wins, whatever the device zone is.

    @Test
    fun explicitOffsetIsHonouredInEveryDeviceZone() {
        // 14:35 at +02:00 is 12:35Z
        val expected = millis("2026-05-17T12:35:00Z")
        for (zone in listOf("UTC", "Asia/Kolkata", "America/New_York", "Pacific/Kiritimati")) {
            assertEquals(expected, parse(zone, "17/05/2026 14:35+02:00", "dd/MM/yyyy HH:mmXXX"), zone)
        }
    }

    @Test
    fun explicitZeroOffsetIsNotMistakenForMissingOffset() {
        // "+00:00" and "Z" are offsets. 14:35Z must stay 14:35Z in a non-UTC device zone.
        val expected = millis("2026-05-17T14:35:00Z")
        assertEquals(expected, parse("Asia/Kolkata", "17/05/2026 14:35+00:00", "dd/MM/yyyy HH:mmXXX"))
        assertEquals(expected, parse("Asia/Kolkata", "17/05/2026 14:35Z", "dd/MM/yyyy HH:mmXXX"))
    }

    // Date only: start of that day in the device zone (behaviour unchanged).

    @Test
    fun dateOnlyInKolkataIsStartOfDayIst() {
        assertEquals(millis("2026-05-16T18:30:00Z"), parse("Asia/Kolkata", "17/05/2026", "dd/MM/yyyy"))
    }

    @Test
    fun dateOnlyInNewYorkIsStartOfDayEdt() {
        assertEquals(millis("2026-05-17T04:00:00Z"), parse("America/New_York", "17/05/2026", "dd/MM/yyyy"))
    }

    @Test
    fun dateOnlyWithAnOffsetInTheFormatStillUsesTheDeviceZone() {
        // No time to apply the offset to, so it is ignored, as before.
        assertEquals(millis("2026-05-16T18:30:00Z"), parse("Asia/Kolkata", "17/05/2026+02:00", "dd/MM/yyyyXXX"))
    }
}

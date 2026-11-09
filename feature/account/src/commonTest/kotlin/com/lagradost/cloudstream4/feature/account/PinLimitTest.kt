package com.lagradost.cloudstream4.feature.account

import kotlin.test.Test
import kotlin.test.assertEquals

/** `KMP-95c`: `BUG-55`'s numbers, the clock from boot, and a store that outlives the process. */
class PinLimitTest {

    private class MapStore : PinLimit.Store {
        val records = HashMap<Int, PinLimit.Record>()
        override fun read(key: Int) = records[key]
        override fun write(key: Int, record: PinLimit.Record?) {
            if (record == null) records.remove(key) else records[key] = record
        }
    }

    private var now = 1_000_000L
    private val store = MapStore()
    private fun limit() = PinLimit(store) { now }

    @Test
    fun fourWrongPinsAreFreeTheFifthWaitsThirtySecondsAndEachAfterDoublesToAnHour() {
        val limit = limit()
        assertEquals(listOf(0L, 0L, 0L, 0L), (1..4).map { limit.wrong(SAM) })
        val waits = (5..13).map { limit.wrong(SAM) }
        assertEquals(
            listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 960_000L, 1_920_000L, 3_600_000L, 3_600_000L),
            waits,
            "the 12th reaches the hour, and it stays there",
        )
        assertEquals(PinLimit.MAX_WAIT_MS, limit.waitMs(SAM))
    }

    @Test
    fun aWaitRunsDownOnTheClockAndARightPinClearsTheCount() {
        val limit = limit()
        repeat(5) { limit.wrong(SAM) }
        now += 10_000L
        assertEquals(20_000L, limit.waitMs(SAM))
        now += 25_000L
        assertEquals(0L, limit.waitMs(SAM))
        assertEquals(60_000L, limit.wrong(SAM), "the count is kept after a wait ends")
        limit.right(SAM)
        assertEquals(0L, limit.waitMs(SAM))
        assertEquals(0L, limit.wrong(SAM), "a right PIN starts it again from nothing")
    }

    @Test
    fun eachProfileCountsAlone() {
        val limit = limit()
        repeat(5) { limit.wrong(SAM) }
        assertEquals(0L, limit.waitMs(ROBIN))
        assertEquals(0L, limit.wrong(ROBIN))
    }

    @Test
    fun theLimitIsKeptAcrossARestart() {
        repeat(5) { limit().wrong(SAM) }
        now += 5_000L
        assertEquals(25_000L, limit().waitMs(SAM), "a new process reads the same wait")
    }

    @Test
    fun aRebootNeverShortensAWait() {
        val limit = limit()
        repeat(5) { limit.wrong(SAM) }
        now = 2_000L // the clock counts from boot again
        assertEquals(28_000L, limit().waitMs(SAM), "the full wait, counted from boot")
    }

    @Test
    fun aStoreThatCannotWriteNeverLiftsTheLimit() {
        val broken = object : PinLimit.Store {
            override fun read(key: Int): PinLimit.Record? = null
            override fun write(key: Int, record: PinLimit.Record?) = error("disk full")
        }
        val limit = PinLimit(broken) { now }
        repeat(5) { limit.wrong(SAM) }
        assertEquals(30_000L, limit.waitMs(SAM))
    }

    private companion object {
        const val SAM = 1
        const val ROBIN = 2
    }
}

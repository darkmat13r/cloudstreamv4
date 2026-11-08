package com.lagradost.cloudstream3.ui.account

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** `BUG-55`: the wrong-PIN limit's arithmetic, its store, and its clock, with a fake clock and an in-memory store. */
class PinAttemptsTest {

    private var now = 1_000_000L
    private val saved = HashMap<Int, PinAttempts.Record>()
    private val store = object : PinAttempts.Store {
        override fun read(key: Int) = saved[key]
        override fun write(key: Int, record: PinAttempts.Record?) {
            if (record == null) saved.remove(key) else saved[key] = record
        }
    }

    @Before
    fun fakes() {
        PinAttempts.store = store
        PinAttempts.clock = { now }
        PinAttempts.forgetHeld()
    }

    @After
    fun clear() = PinAttempts.forgetHeld()

    private fun wrong(times: Int, key: Int = KIDS) = (1..times).map { PinAttempts.wrong(key) }

    @Test
    fun fourWrongAreFreeAndTheFifthStartsThirtySeconds() {
        assertEquals(listOf(0L, 0L, 0L, 0L), wrong(4))
        assertEquals(0L, PinAttempts.waitMs(KIDS))
        assertEquals(30_000L, PinAttempts.wrong(KIDS))
        assertEquals(30_000L, PinAttempts.waitMs(KIDS))
        now += 29_999L
        assertEquals(1L, PinAttempts.waitMs(KIDS))
        now += 1L
        assertEquals(0L, PinAttempts.waitMs(KIDS))
    }

    @Test
    fun eachFurtherWrongPinDoublesTheWait() {
        wrong(4)
        val waits = (1..4).map { PinAttempts.wrong(KIDS).also { now += it } }
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L), waits)
    }

    /** 30 s doubled: 30, 60, 120, 240, 480, 960, 1920 s, and then the hour, where it stays. */
    @Test
    fun theWaitStopsAtOneHour() {
        val waits = wrong(12).drop(4)
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 960_000L, 1_920_000L, 3_600_000L), waits)
    }

    @Test
    fun theTwentiethWrongPinWaitsExactlyOneHour() {
        wrong(19)
        assertEquals(3_600_000L, PinAttempts.wrong(KIDS))
        assertEquals(3_600_000L, PinAttempts.waitMs(KIDS))
    }

    @Test
    fun theWaitStaysAtOneHourWithoutOverflowing() {
        wrong(200)
        assertEquals(3_600_000L, PinAttempts.waitMs(KIDS))
    }

    @Test
    fun aRightPinClearsTheCountAndTheWait() {
        wrong(5)
        PinAttempts.right(KIDS)
        assertEquals(0L, PinAttempts.waitMs(KIDS))
        assertNull("The record was kept", saved[KIDS])
        assertEquals("The count was kept", listOf(0L, 0L, 0L, 0L), wrong(4))
    }

    @Test
    fun eachProfileCountsItsOwn() {
        wrong(5, KIDS)
        assertEquals(0L, PinAttempts.waitMs(TEENS))
        assertEquals(0L, PinAttempts.wrong(TEENS))
        assertEquals(0L, PinAttempts.waitMs(PinAttempts.UNKEYED))
    }

    /** A restart drops the process's copy; the stored record keeps the count and the wait. */
    @Test
    fun theCountAndTheWaitSurviveARestart() {
        wrong(5)
        now += 10_000L
        PinAttempts.forgetHeld()
        assertEquals(20_000L, PinAttempts.waitMs(KIDS))
        now += 20_000L
        assertEquals("The count restarted", 60_000L, PinAttempts.wrong(KIDS))
    }

    /** Uptime restarts at boot. Below the stored start, the whole wait runs again from boot; it never ends sooner. */
    @Test
    fun aRebootNeverShortensAWait() {
        wrong(5)
        now += 5_000L
        PinAttempts.forgetHeld()
        now = 2_000L // rebooted: uptime is far below the wait's start
        assertEquals(30_000L - 2_000L, PinAttempts.waitMs(KIDS))
        now = 30_000L
        assertEquals(0L, PinAttempts.waitMs(KIDS))
    }

    /** Nothing about a PIN is stored: a record is a count, an uptime and a wait. */
    @Test
    fun aRecordHoldsNoPin() {
        wrong(5)
        assertEquals(PinAttempts.Record(5, now, 30_000L), saved[KIDS])
    }

    /** A store that cannot write still limits within the process. */
    @Test
    fun aFailedWriteDoesNotLiftTheLimit() {
        PinAttempts.store = object : PinAttempts.Store {
            override fun read(key: Int): PinAttempts.Record? = null
            override fun write(key: Int, record: PinAttempts.Record?) = Unit
        }
        wrong(5)
        assertEquals(30_000L, PinAttempts.waitMs(KIDS))
    }

    private companion object {
        const val KIDS = 4970
        const val TEENS = 4971
    }
}

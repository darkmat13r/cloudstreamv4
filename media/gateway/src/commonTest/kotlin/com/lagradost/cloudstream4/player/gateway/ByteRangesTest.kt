package com.lagradost.cloudstream4.player.gateway

import com.lagradost.cloudstream4.player.gateway.ByteRanges.Plan
import com.lagradost.cloudstream4.player.gateway.ByteRanges.Request
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `BUG-114`: the gateway's byte-range arithmetic, to the byte: inclusive ranges, the last byte, and the off-by-ones. */
class ByteRangesTest {

    @Test
    fun theRangeFormsAPlayerSendsAreRead() {
        assertEquals(Request(5, null, null), ByteRanges.parse("bytes=5-"))
        assertEquals(Request(5, 9, null), ByteRanges.parse("bytes=5-9"))
        assertEquals(Request(null, null, 10), ByteRanges.parse("bytes=-10"))
        assertEquals(Request(0, 0, null), ByteRanges.parse("bytes=0-0"))
        assertEquals(Request(5, 9, null), ByteRanges.parse("BYTES= 5-9 "))
    }

    @Test
    fun anythingElseIsIgnoredAndTheWholeBodyServed() {
        listOf(
            null, "", "bytes=", "bytes=-", "bytes=-0", "bytes=9-5", "bytes=0-1,5-6", "items=0-5", "bytes=a-", "bytes=+5-",
            "bytes=5", "bytes=1-2-3", "bytes=99999999999999999999-", "bytes= -5-",
        ).forEach { assertNull(ByteRanges.parse(it), "$it") }
    }

    @Test
    fun aPlanServesExactlyWhatWasAskedAgainstTheTotal() {
        assertEquals(Plan.Serve(0, 99, 100, partial = false), ByteRanges.plan(Request.WHOLE, 100, ranged = false))
        assertEquals(Plan.Serve(5, 99, 100, partial = true), ByteRanges.plan(Request(5, null, null), 100, ranged = true))
        assertEquals(Plan.Serve(5, 9, 100, partial = true), ByteRanges.plan(Request(5, 9, null), 100, ranged = true))
        // The last byte, and an end past the total clamped to it.
        assertEquals(Plan.Serve(99, 99, 100, partial = true), ByteRanges.plan(Request(99, null, null), 100, ranged = true))
        assertEquals(Plan.Serve(5, 99, 100, partial = true), ByteRanges.plan(Request(5, 500, null), 100, ranged = true))
        // A suffix, and one longer than the body.
        assertEquals(Plan.Serve(90, 99, 100, partial = true), ByteRanges.plan(Request(null, null, 10), 100, ranged = true))
        assertEquals(Plan.Serve(0, 99, 100, partial = true), ByteRanges.plan(Request(null, null, 500), 100, ranged = true))
        // A one-byte body.
        assertEquals(Plan.Serve(0, 0, 1, partial = true), ByteRanges.plan(Request(0, null, null), 1, ranged = true))
    }

    @Test
    fun aRangeAtOrPastTheEndIsA416WithTheTotal() {
        assertEquals(Plan.Unsatisfiable(100), ByteRanges.plan(Request(100, null, null), 100, ranged = true))
        assertEquals(Plan.Unsatisfiable(100), ByteRanges.plan(Request(500, 600, null), 100, ranged = true))
        assertEquals("bytes */100", Plan.Unsatisfiable(100).contentRange)
        // An empty body: any range is unsatisfiable, no range is an empty 200.
        assertEquals(Plan.Unsatisfiable(0), ByteRanges.plan(Request(0, null, null), 0, ranged = true))
        assertEquals(0, (ByteRanges.plan(Request.WHOLE, 0, ranged = false) as Plan.Serve).length)
    }

    @Test
    fun aPlansLengthAndContentRangeAreInclusive() {
        val plan = Plan.Serve(5, 9, 100, partial = true)
        assertEquals(5, plan.length)
        assertEquals("bytes 5-9/100", plan.contentRange)
    }

    @Test
    fun chunksAreInclusiveAndTheLastIsClampedToTheEnd() {
        assertEquals(0L..999L, ByteRanges.chunk(0, 4095, 1000))
        assertEquals(4000L..4095L, ByteRanges.chunk(4000, 4095, 1000))
        assertEquals(4095L..4095L, ByteRanges.chunk(4095, 4095, 1000))
        assertEquals("bytes=0-999", ByteRanges.header(0L..999L))
        // Chunks of an exact multiple, and of one more byte, cover the body once with no gap and no overlap.
        for (total in listOf(4096L, 4097L, 1L, 3999L)) {
            var next = 0L
            var covered = 0L
            while (next <= total - 1) {
                val c = ByteRanges.chunk(next, total - 1, 1024)
                assertEquals(next, c.first)
                covered += c.last - c.first + 1
                next = c.last + 1
            }
            assertEquals(total, covered, "total $total")
        }
    }

    @Test
    fun anUpstreamContentRangeIsReadStrictly() {
        assertEquals(ByteRanges.Upstream(0, 99, 1000), ByteRanges.parseContentRange("bytes 0-99/1000"))
        assertEquals(ByteRanges.Upstream(0, 99, null), ByteRanges.parseContentRange("bytes 0-99/*"))
        listOf(
            null, "", "bytes */1000", "bytes 99-0/1000", "bytes 0-1000/1000", "bytes 0-99", "items 0-99/1000",
            "bytes -1-99/1000", "bytes 0-99/abc", "bytes 0-99/10x0",
        ).forEach { assertNull(ByteRanges.parseContentRange(it), "$it") }
    }
}

package com.lagradost.cloudstream4.media.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RangeHeadersTest {
    private fun range(value: String?) = parseContentRange(value)

    @Test
    fun `a satisfied range with a total`() {
        val r = assertNotNull(range("bytes 0-99/1000"))
        assertEquals(0L, r.start)
        assertEquals(99L, r.endInclusive)
        assertEquals(1000L, r.total)
        assertEquals(1000L, assertNotNull(range("bytes 999-999/1000")).total)
    }

    @Test
    fun `a satisfied range with an unknown total`() {
        val r = assertNotNull(range("bytes 5-9/*"))
        assertEquals(5L, r.start)
        assertEquals(9L, r.endInclusive)
        assertNull(r.total)
    }

    @Test
    fun `an unsatisfied range`() {
        val r = assertNotNull(range("bytes */1000"))
        assertNull(r.start)
        assertNull(r.endInclusive)
        assertEquals(1000L, r.total)
        assertEquals(0L, assertNotNull(range("bytes */0")).total)
    }

    @Test
    fun `unit case and surrounding space are tolerated`() {
        assertNotNull(range("Bytes 0-1/2"))
        assertNotNull(range("BYTES 0-1/2"))
        assertNotNull(range("  bytes 0-1/2  "))
        assertNotNull(range("bytes  0-1/2"))
    }

    @Test
    fun `garbage is null`() {
        listOf(
            null, "", " ", "bytes", "bytes ", "bytes 0-1", "bytes 0-1/", "bytes /5", "bytes */*", "bytes -/5", "bytes 1-/5", "bytes -1/5",
            "bytes 5-1/10", "bytes 0-10/10", "bytes 0-11/10", "bytes a-b/c", "bytes 0-1/x", "bytes 0 - 1/5", "bytes 0-1 /5", "bytes 0-1/ 5",
            "bytes +0-1/5", "bytes 0--1/5", "bytes 0-1/-5", "bytes 0-1/5/6", "bytes 0-1-2/5", "items 0-1/5", "0-1/5", "bytes=0-1/5",
            "bytes 0x0-0x1/5", "bytes 1e1-20/30", "bytes 0-1/5, 6-7/10", "bytes\t0-1/5", "bytes \u0660-\u0661/5",
        ).forEach { assertNull(range(it), it.toString()) }
    }

    @Test
    fun `numbers that do not fit a long are null and nothing overflows`() {
        assertNull(range("bytes 0-1/9223372036854775808"))
        assertNull(range("bytes 0-9223372036854775808/*"))
        assertNull(range("bytes 9223372036854775808-9223372036854775809/*"))
        assertNull(range("bytes 0-99999999999999999999/*"))
        assertNull(range("bytes */99999999999999999999"))
        assertNull(range("bytes 0-1/" + "9".repeat(200)))
        val max = Long.MAX_VALUE
        val r = assertNotNull(range("bytes ${max - 1}-${max - 1}/$max"))
        assertEquals(max - 1, r.endInclusive)
        assertNull(range("bytes $max-$max/$max"))
        assertEquals(max, assertNotNull(range("bytes $max-$max/*")).start)
    }

    @Test
    fun `retry after in seconds is bounded`() {
        assertEquals(0L, parseRetryAfterSeconds("0", 60))
        assertEquals(5L, parseRetryAfterSeconds("5", 60))
        assertEquals(5L, parseRetryAfterSeconds(" 5 ", 60))
        assertEquals(60L, parseRetryAfterSeconds("60", 60))
        assertEquals(60L, parseRetryAfterSeconds("61", 60))
        assertEquals(60L, parseRetryAfterSeconds("86400", 60))
        assertEquals(60L, parseRetryAfterSeconds("9223372036854775808", 60))
        assertEquals(60L, parseRetryAfterSeconds("9".repeat(500), 60))
    }

    @Test
    fun `retry after that is not a number of seconds is null`() {
        listOf(null, "", " ", "-1", "+5", "5.5", "1e3", "Wed, 21 Oct 2015 07:28:00 GMT", "soon", "5 s", "0x10").forEach {
            assertNull(parseRetryAfterSeconds(it, 60), it.toString())
        }
    }

    @Test
    fun `strong and weak etags are told apart`() {
        val strong = assertNotNull(parseETag("\"abc\""))
        assertTrue(strong.strong)
        assertEquals("\"abc\"", strong.raw)
        assertEquals("abc", strong.opaque)
        val weak = assertNotNull(parseETag("W/\"abc\""))
        assertFalse(weak.strong)
        assertEquals("W/\"abc\"", weak.raw)
        assertEquals("abc", weak.opaque)
        assertTrue(assertNotNull(parseETag("  \"x\"  ")).strong)
        assertTrue(assertNotNull(parseETag("\"\"")).strong)
    }

    @Test
    fun `etag comparison follows rfc 9110`() {
        val s1 = assertNotNull(parseETag("\"1\""))
        val s1again = assertNotNull(parseETag("\"1\""))
        val w1 = assertNotNull(parseETag("W/\"1\""))
        val s2 = assertNotNull(parseETag("\"2\""))
        assertTrue(s1.stronglyMatches(s1again))
        assertFalse(s1.stronglyMatches(w1))
        assertFalse(w1.stronglyMatches(w1))
        assertTrue(s1.weaklyMatches(w1))
        assertTrue(w1.weaklyMatches(w1))
        assertFalse(s1.weaklyMatches(s2))
        assertFalse(s1.stronglyMatches(s2))
    }

    @Test
    fun `malformed etags are null`() {
        listOf(
            null, "", " ", "abc", "\"abc", "abc\"", "\"", "W/abc", "W/", "w/\"abc\"", "W/ \"abc\"", "\"a b\"", "\"a\"b\"", "\"a\nb\"",
            "\"a\tb\"", "\"a${Char(0)}b\"", "\"a${Char(0x7f)}b\"", "'abc'", "\"" + "a".repeat(255) + "\"",
        ).forEach { assertNull(parseETag(it), it.toString()) }
    }

    @Test
    fun `an etag at the length limit is accepted`() {
        assertNotNull(parseETag("\"" + "a".repeat(254) + "\""))
    }
}

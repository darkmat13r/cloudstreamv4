package com.lagradost.cloudstream3.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * BUG-44: what replaces the platform's `Log.x(tag, msg, throwable)` and `Log.getStackTraceString`
 * keeps the classes and frames, and no URL from any message in the chain.
 */
class StackTraceRedactionTest {

    private val markers = listOf("MARKER_HOST", "MARKER_PATH", "MARKER_TOKEN", "MARKER_URI")

    @Test
    fun `a stack trace keeps classes and frames, and no url from any message`() {
        val cause = IOException("GET https://MARKER_HOST.example/MARKER_PATH?token=MARKER_TOKEN failed")
        val failure = IllegalArgumentException("navigation to content://MARKER_URI/x not found", cause)
        val text = redactedStackTrace(failure)

        markers.forEach { assertFalse("'$it' leaked into:\n$text", text.contains(it)) }
        assertFalse(text, text.contains("://"))
        assertTrue(text, text.startsWith("java.lang.IllegalArgumentException: navigation to <url> not found"))
        assertTrue(text, text.contains("Caused by: java.io.IOException: GET <url> failed"))
        assertTrue(text, text.contains("at com.lagradost.cloudstream3.utils.StackTraceRedactionTest"))
    }

    @Test
    fun `text keeps everything but its urls`() {
        assertEquals("Query failed: <url> (code 7)", redactedText("Query failed: https://MARKER_HOST.example/a?b=c (code 7)"))
        assertEquals("no url here", redactedText("no url here"))
        assertEquals("null", redactedText(null))
    }
}

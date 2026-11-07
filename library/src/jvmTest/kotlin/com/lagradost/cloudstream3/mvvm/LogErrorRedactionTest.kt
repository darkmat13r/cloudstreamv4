package com.lagradost.cloudstream3.mvvm

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BUG-44: `logError` keeps a failure's classes and stack frames, and replaces every URL in the
 * messages of the throwable, its causes and its suppressed throwables with `<url>`. The secrets
 * are marker strings, and both streams `logError` writes to are captured: `Log.d` (stdout on the
 * JVM) and the stack trace (stderr).
 *
 * JVM-only because capturing the process's streams has no common equivalent.
 */
class LogErrorRedactionTest {

    private val markers = listOf("MARKER_HOST", "MARKER_PATH", "MARKER_TOKEN", "MARKER_COOKIE", "MARKER_FILE")

    private fun captured(block: () -> Unit): String {
        val out = System.out
        val err = System.err
        val buffer = ByteArrayOutputStream()
        val stream = PrintStream(buffer, true, Charsets.UTF_8)
        System.setOut(stream)
        System.setErr(stream)
        try {
            block()
        } finally {
            System.setOut(out)
            System.setErr(err)
        }
        return buffer.toString(Charsets.UTF_8)
    }

    @Test
    fun logErrorPrintsNoUrlFromAnyMessageInTheChain() {
        val cause = IOException("Unable to connect to https://MARKER_HOST.example/MARKER_PATH?token=MARKER_TOKEN")
        val failure = IllegalStateException("Failed at http://MARKER_HOST.example/a?c=MARKER_COOKIE then", cause)
        failure.addSuppressed(RuntimeException("closing file:///storage/MARKER_FILE.cs3"))

        val text = captured { logError(failure) }

        markers.forEach { assertFalse(text.contains(it), "'$it' leaked into:\n$text") }
        assertFalse(text.contains("://"), text)
        // What is kept: the classes, the fixed words around each URL, and the frames.
        assertTrue(text.contains("java.lang.IllegalStateException: Failed at <url> then"), text)
        assertTrue(text.contains("Caused by: java.io.IOException: Unable to connect to <url>"), text)
        assertTrue(text.contains("Suppressed: java.lang.RuntimeException: closing <url>"), text)
        assertTrue(text.contains("safeApiCall: Failed at <url> then"), text)
        assertTrue(text.contains("at com.lagradost.cloudstream3.mvvm.LogErrorRedactionTest"), text)
    }

    @Test
    fun logErrorKeepsAMessageWithoutUrlsAsItWas() {
        val text = captured { logError(IllegalArgumentException("index 3 out of bounds")) }
        assertTrue(text.contains("safeApiCall: index 3 out of bounds"), text)
        assertTrue(text.contains("java.lang.IllegalArgumentException: index 3 out of bounds"), text)
    }
}

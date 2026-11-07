package com.lagradost.cloudstream3.mvvm

import com.lagradost.cloudstream3.ErrorLoadingException
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BUG-44: `logError` and `getStackTracePretty` (whose text `safeFail` shows on screen) keep the
 * classes, the frames and a url's host, and nothing from a url's userinfo, path, query or fragment,
 * nor any path under a storage or home root.
 *
 * [cases] is the same table as `:app`'s `StackTraceRedactionTest` and the sandbox's
 * `PluginHostRedactionTest`, because the three copies of the rules must stay in sync. They are run
 * through `logError`'s `safeApiCall:` line, the one place this copy's private rules can be reached.
 *
 * JVM-only because capturing the process's streams has no common equivalent.
 */
class LogErrorRedactionTest {

    private val markers = listOf("MARKER_USER", "MARKER_PASS", "MARKER_PATH", "MARKER_QUERY", "MARKER_FRAG")

    private val cases = listOf(
        "connect to https://MARKER_USER:MARKER_PASS@host.example:8443/MARKER_PATH?t=MARKER_QUERY#MARKER_FRAG now"
            to "connect to https://host.example:8443/<…> now",
        "GET http://host.example?token=MARKER_QUERY failed" to "GET http://host.example/<…> failed",
        "tree content://com.android.externalstorage.documents/tree/MARKER_PATH" to
            "tree content://com.android.externalstorage.documents/<…>",
        "/storage/emulated/0/MARKER_PATH/a b.mkv: open failed: EACCES (Permission denied)" to
            "<path>: open failed: EACCES (Permission denied)",
        "could not read file:/storage/emulated/0/MARKER_PATH/sub.srt" to "could not read <path>",
        "could not read file:///sdcard/MARKER_PATH/sub.srt" to "could not read <path>",
        "cache at /Users/MARKER_USER/.cloudstream4/MARKER_PATH" to "cache at <path>",
        "home /home/MARKER_USER/MARKER_PATH" to "home <path>",
        "files /data/user/0/com.example/files/MARKER_PATH, then" to "files <path>",
        "mounted /mnt/media_rw/MARKER_PATH" to "mounted <path>",
        "/sdcard/MARKER_PATH" to "<path>",
        "saved to C:\\Users\\MARKER_USER\\AppData\\MARKER_PATH\\x.json: access denied" to "saved to <path>: access denied",
        "lower d:\\users\\MARKER_USER\\MARKER_PATH" to "lower <path>",
        "slashes C:/Users/MARKER_USER/MARKER_PATH" to "slashes <path>",
        "disk /Volumes/MARKER_USER Drive/MARKER_PATH/a.mkv: not found" to "disk <path>: not found",
        "not a home C:\\Program Files\\App" to "not a home C:\\Program Files\\App",
        "only the host https://host.example" to "only the host https://host.example",
        "index 3 out of bounds" to "index 3 out of bounds",
        "frame at com.example.Home(Home.kt:12)" to "frame at com.example.Home(Home.kt:12)",
        // BUG-52: an invisible character must not hide a url from the patterns, and line breaks must survive.
        "cdn https:\u200B//cdn.example/x?token=MARKER_QUERY" to "cdn https://cdn.example/<…>",
        "bom ht\uFEFFtps://cdn.example/MARKER_PATH" to "bom https://cdn.example/<…>",
        // A tab or a line break inside "://" must not hide the url, nor rejoin it once a screen strips controls.
        "https:\t//host/p?token=MARKER_QUERY" to "https://host/<…>",
        "nl https:\n//host/p?token=MARKER_QUERY" to "nl https://host/<…>",
        "keeps\nlines\r\nand\ttabs" to "keeps\nlines\r\nand tabs",
    )

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
    fun everyCaseComesOutAsExpectedWithNoMarker() {
        cases.forEach { (input, expected) ->
            val text = captured { logError(IllegalStateException(input)) }
            assertTrue(text.contains("safeApiCall: $expected\n"), "for '$input':\n$text")
            markers.forEach { assertFalse(text.contains(it), "'$it' leaked into:\n$text") }
            // BUG-52: what a screen shows after it strips controls, as Home, Detail and the HUD do.
            val shown = text.filterNot { it.isISOControl() }
            markers.forEach { assertFalse(shown.contains(it), "'$it' rejoined on screen: $shown") }
        }
    }

    @Test
    fun logErrorPrintsNoUrlPathOrUserPathFromAnyMessageInTheChain() {
        val cause = FileNotFoundException(
            "/storage/emulated/0/Download/MARKER_PATH.mkv: open failed: EACCES (Permission denied)"
        )
        val failure = IllegalStateException("Failed at http://host.example/a?c=MARKER_QUERY then", cause)
        failure.addSuppressed(RuntimeException("closing file:/storage/emulated/0/MARKER_PATH.cs3"))

        val text = captured { logError(failure) }

        markers.forEach { assertFalse(text.contains(it), "'$it' leaked into:\n$text") }
        assertTrue(text.contains("java.lang.IllegalStateException: Failed at http://host.example/<…> then"), text)
        assertTrue(text.contains("Caused by: java.io.FileNotFoundException: <path>: open failed: EACCES"), text)
        assertTrue(text.contains("Suppressed: java.lang.RuntimeException: closing <path>"), text)
        assertTrue(text.contains("at com.lagradost.cloudstream3.mvvm.LogErrorRedactionTest"), text)
    }

    @Test
    fun theFailureShownOnScreenCarriesNoUrlPathOrUserPath() {
        val failure = IOException("GET https://MARKER_USER@host.example/MARKER_PATH?t=MARKER_QUERY failed")
        val shown = safeFail<Unit>(failure) as Resource.Failure
        assertEquals(false, shown.isNetworkError)
        markers.forEach { assertFalse(shown.errorString.contains(it), "'$it' reached the screen:\n${shown.errorString}") }
        // getStackTracePretty puts the message first, joined straight onto the first frame's "File.kt line".
        assertTrue(shown.errorString.startsWith("\nGET https://host.example/<…> failed"), shown.errorString)
        assertTrue(shown.errorString.contains("LogErrorRedactionTest.kt"), shown.errorString)
    }

    /**
     * BUG-52: an [ErrorLoadingException]'s message became `errorString` unredacted, and Android's Home and Detail
     * failure lines show its first line.
     */
    @Test
    fun anErrorLoadingExceptionsMessageReachesTheScreenRedacted() {
        val message = "refused https://MARKER_USER@host.example/MARKER_PATH?t=MARKER_QUERY, try again"
        val shown = throwAbleToResource<Unit>(ErrorLoadingException(message)) as Resource.Failure
        assertEquals("refused https://host.example/<…> try again", shown.errorString)
        assertEquals(true, shown.isNetworkError)
        markers.forEach { assertFalse(shown.errorString.contains(it), "'$it' reached the screen: ${shown.errorString}") }

        val noMessage = throwAbleToResource<Unit>(ErrorLoadingException()) as Resource.Failure
        assertEquals("Error loading, try again later.", noMessage.errorString)
    }
}

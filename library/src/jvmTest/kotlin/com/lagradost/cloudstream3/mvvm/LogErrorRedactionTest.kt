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
        "https:\u00A0//host/p?token=MARKER_QUERY" to "https://host/<…>",
        "keeps\nlines\r\nand\ttabs" to "keeps\nlines\r\nand tabs",
        // BUG-64: a code point Unicode calls default-ignorable, Cc and Cf or not, must not hide a url or a path either.
        "cgj https:\u034F//cdn.example/x?token=MARKER_QUERY" to "cgj https://cdn.example/<…>",
        "vs ht\uFE0Ftps://cdn.example/MARKER_PATH" to "vs https://cdn.example/<…>",
        "filler https:\u3164//host/p?token=MARKER_QUERY" to "filler https://host/<…>",
        "supplement https:\uDB40\uDD00//host/p?token=MARKER_QUERY" to "supplement https://host/<…>",
        "tag https:\uDB40\uDC41//host/p?token=MARKER_QUERY" to "tag https://host/<…>",
        "cache at /Us\u034Fers/MARKER_USER/MARKER_PATH" to "cache at <path>",
        "keeps 한국어 and \uD83D\uDE00" to "keeps 한국어 and \uD83D\uDE00",
        // BUG-64: up to two characters a screen draws blank, around or between the slashes, are a url too.
        "braille https:\u2800//host/p?token=MARKER_QUERY" to "braille https://host/<…>",
        "notehead https:\uD834\uDD59//host/p?token=MARKER_QUERY" to "notehead https://host/<…>",
        "between https:/\u2800/cdn.example/x?token=MARKER_QUERY" to "between https://cdn.example/<…>",
        "both https:\u2800/\u2800/host/p?token=MARKER_QUERY" to "both https://host/<…>",
        "spaced https: \u2800 //host/p?token=MARKER_QUERY" to "spaced https://host/<…>",
        "Ratio 16:9 // wide" to "Ratio 16:9 // wide",
        "Note: a/b" to "Note: a/b",
        "Note: a/b/c" to "Note: a/b/c",
        "x:a/b" to "x:a/b",
        "1:2/3/4" to "1:2/3/4",
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
        // getStackTracePretty puts the message first, then the frames' "File.kt line", each on its own line (BUG-63).
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

    /** A frame whose file name a plugin set: an address with a token, and a user path. */
    private fun forged(error: Throwable, fileName: String) = error.apply {
        stackTrace = arrayOf(StackTraceElement("a.B", "c", fileName, 7)) + stackTrace
    }

    @Test
    fun theMessageIsItsOwnLineAndNoFrameIsGluedOntoIt() {
        val shown = safeFail<Unit>(IOException("HTTP 503")) as Resource.Failure
        // BUG-63: the first frame was joined onto the message, so the shown line ended in a file name and a line number.
        assertEquals("HTTP 503", shown.errorString.trim().lines().first())
    }

    @Test
    fun aForgedFrameShowsNoAddressTokenOrUserPath() {
        val names = listOf(
            "https://cdn.example/k?token=MARKER_QUERY provider.kt",
            "/Users/MARKER_USER/secret/provider.kt",
            "file:///storage/emulated/0/MARKER_PATH/provider.kt",
        )
        // BUG-63: with no message, the first line shown is a frame, with ":" made " ", so `https://` read `https //`.
        for (name in names) {
            val pretty = forged(IllegalStateException(), name).getStackTracePretty()
            markers.forEach { assertFalse(pretty.contains(it), "'$it' reached the screen:\n$pretty") }
            // The NullPointerException branch shows a frame named *provider.kt* on its first line.
            val npe = throwAbleToResource<Unit>(forged(NullPointerException(), name)) as Resource.Failure
            markers.forEach { assertFalse(npe.errorString.contains(it), "'$it' reached the screen:\n${npe.errorString}") }
        }
        val npe = throwAbleToResource<Unit>(forged(NullPointerException(), names.first())) as Resource.Failure
        assertTrue(npe.errorString.startsWith("NullPointerException at https://cdn.example/<…> provider.kt:7"), npe.errorString)
    }
}

package com.lagradost.cloudstream3.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/**
 * BUG-44: what replaces the platform's `Log.x(tag, msg, throwable)` and `Log.getStackTraceString`
 * keeps the classes, the frames and a url's host, and nothing from a url's userinfo, path, query
 * or fragment, nor any path under a storage or home root.
 *
 * [cases] is the same table as `:library`'s `LogErrorRedactionTest` and the sandbox's
 * `PluginHostRedactionTest`, because the three copies of the rules must stay in sync.
 */
class StackTraceRedactionTest {

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
        "keeps\nlines\r\nand\ttabs" to "keeps\nlines\r\nand\ttabs",
    )

    @Test
    fun `every case comes out as expected, with no marker`() {
        cases.forEach { (input, expected) ->
            val text = redactedText(input)
            assertEquals(input, expected, text)
            markers.forEach { assertFalse("'$it' leaked into: $text", text.contains(it)) }
        }
        assertEquals("null", redactedText(null))
    }

    @Test
    fun `a stack trace keeps classes, frames and hosts, and no path from any message`() {
        val cause = FileNotFoundException(
            "/storage/emulated/0/Download/MARKER_PATH.mkv: open failed: EACCES (Permission denied)"
        )
        val failure = IllegalArgumentException("GET https://host.example/MARKER_PATH?t=MARKER_QUERY failed", cause)
        failure.addSuppressed(IOException("closing file:/storage/emulated/0/MARKER_PATH.srt"))
        val text = redactedStackTrace(failure)

        markers.forEach { assertFalse("'$it' leaked into:\n$text", text.contains(it)) }
        assertTrue(text, text.startsWith("java.lang.IllegalArgumentException: GET https://host.example/<…> failed"))
        assertTrue(text, text.contains("Caused by: java.io.FileNotFoundException: <path>: open failed: EACCES"))
        assertTrue(text, text.contains("Suppressed: java.io.IOException: closing <path>"))
        assertTrue(text, text.contains("at com.lagradost.cloudstream3.utils.StackTraceRedactionTest"))
    }
}

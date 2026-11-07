package com.lagradost.cloudstream4.detail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * BUG-44/47, `KMP-56`: `:desktopApp`'s copy of the redaction rules, which Home's, Search's and a title's failure lines
 * use. [cases] is copied verbatim from `:feature:extensions:sandbox`'s `PluginHostRedactionTest`, which is the same table
 * as `:app`'s `StackTraceRedactionTest` and `:library`'s `LogErrorRedactionTest`: the four copies must stay in sync.
 */
class DesktopRedactionTest {

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
    )

    @Test
    fun everyCaseComesOutAsExpectedWithNoMarker() {
        cases.forEach { (input, expected) ->
            val text = input.withoutUrls()
            assertEquals(expected, text, input)
            markers.forEach { assertFalse(text.contains(it), "'$it' leaked into: $text") }
            // BUG-52: what a screen shows after it strips controls, as Home, Detail and the HUD do.
            val shown = text.filterNot { it.isISOControl() }
            markers.forEach { assertFalse(shown.contains(it), "'$it' rejoined on screen: $shown") }
        }
    }

    /** `#221` review: an invisible character inside `://` hides a url from the pattern unless it goes first. */
    @Test
    fun aUrlSplitByAnInvisibleCharacterIsStillTakenOut() {
        val split = "refused https:\u200B//host.example/MARKER_PATH?token=MARKER_QUERY"
        val text = split.withoutInvisible().withoutUrls()
        assertEquals("refused https://host.example/<…>", text)
        markers.forEach { assertFalse(text.contains(it), "'$it' leaked into: $text") }
    }
}

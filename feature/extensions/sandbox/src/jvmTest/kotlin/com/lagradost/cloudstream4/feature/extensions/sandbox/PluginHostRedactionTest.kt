package com.lagradost.cloudstream4.feature.extensions.sandbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * BUG-44: the plugin host's copy of the redaction rules, which its failure line uses. [cases] is
 * the same table as `:app`'s `StackTraceRedactionTest` and `:library`'s `LogErrorRedactionTest`,
 * because the three copies must stay in sync.
 */
class PluginHostRedactionTest {

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
        "only the host https://host.example" to "only the host https://host.example",
        "index 3 out of bounds" to "index 3 out of bounds",
        "frame at com.example.Home(Home.kt:12)" to "frame at com.example.Home(Home.kt:12)",
    )

    @Test
    fun everyCaseComesOutAsExpectedWithNoMarker() = with(PluginHost) {
        cases.forEach { (input, expected) ->
            val text = input.withoutUrls()
            assertEquals(expected, text, input)
            markers.forEach { assertFalse(text.contains(it), "'$it' leaked into: $text") }
        }
    }
}

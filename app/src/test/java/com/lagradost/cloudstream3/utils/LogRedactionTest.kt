package com.lagradost.cloudstream3.utils

import androidx.media3.common.PlaybackException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.ui.player.ExtractorUri
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * BUG-43: the log helpers never let a stream's URL, headers, referer or an exception message
 * through. Every secret here is a marker string; the assertions are that no marker survives.
 */
class LogRedactionTest {

    private val markers = listOf(
        "MARKER_HOST", "MARKER_PATH", "MARKER_QUERY", "MARKER_COOKIE", "MARKER_TOKEN", "MARKER_REFERER",
    )

    private fun assertNoMarker(text: String) {
        markers.forEach { assertFalse("'$it' leaked into: $text", text.contains(it)) }
    }

    private fun <T> build(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
        return outcome!!.getOrThrow()
    }

    private val link = build {
        newExtractorLink(
            "SomeProvider",
            "SomeProvider 1080p",
            "https://MARKER_HOST.example/MARKER_PATH/master.m3u8?sig=MARKER_QUERY",
            ExtractorLinkType.M3U8,
        ) {
            referer = "https://MARKER_REFERER.example/"
            quality = 1080
            headers = mapOf("Cookie" to "session=MARKER_COOKIE", "Authorization" to "Bearer MARKER_TOKEN")
            extractorData = "MARKER_TOKEN"
        }
    }

    @Test
    fun `a link keeps its source, quality and type and nothing else`() {
        val text = redactedLinkInfo(link)
        assertEquals("source=SomeProvider quality=1080 type=M3U8", text)
        assertNoMarker(text)
        // The toString the old log lines used does leak, which is what this guards against.
        assertTrue(link.toString().contains("MARKER_TOKEN") || link.toString().contains("MARKER_HOST"))
    }

    @Test
    fun `the player's selection reports the online link, or none`() {
        assertNoMarker(redactedLinkInfo(link to null))
        assertEquals("source=SomeProvider quality=1080 type=M3U8", redactedLinkInfo(link to null))
        assertEquals("link=none", redactedLinkInfo(null as Pair<ExtractorLink?, ExtractorUri?>?))
        assertEquals("link=none", redactedLinkInfo(null as ExtractorLink?))
    }

    @Test
    fun `a subtitle file keeps its language only`() {
        @Suppress("DEPRECATION")
        val file = SubtitleFile("English", "https://MARKER_HOST.example/MARKER_PATH.vtt?t=MARKER_TOKEN")
        file.headers = mapOf("Cookie" to "MARKER_COOKIE")
        val text = redactedSubtitleInfo(file)
        assertEquals("lang=English", text)
        assertNoMarker(text)
    }

    @Test
    fun `a player subtitle drops its url and headers`() {
        val subtitle = SubtitleData(
            originalName = "English",
            nameSuffix = "1",
            url = "https://MARKER_HOST.example/MARKER_PATH.srt?t=MARKER_QUERY",
            origin = SubtitleOrigin.URL,
            mimeType = "application/x-subrip",
            headers = mapOf("Referer" to "MARKER_REFERER", "Cookie" to "MARKER_COOKIE"),
            languageCode = "en",
        )
        val text = redactedSubtitleInfo(subtitle)
        assertEquals("origin=URL mime=application/x-subrip lang=en", text)
        assertNoMarker(text)
    }

    @Test
    fun `a subtitle picked from the device drops its name, which is the file name or the uri`() {
        val picked = SubtitleData(
            originalName = "content://com.android.externalstorage.documents/MARKER_PATH/file.srt",
            nameSuffix = "1",
            url = "content://com.android.externalstorage.documents/MARKER_PATH/file.srt",
            origin = SubtitleOrigin.DOWNLOADED_FILE,
            mimeType = "application/x-subrip",
            headers = emptyMap(),
            languageCode = null,
        )
        val text = redactedSubtitleInfo(picked)
        assertEquals("origin=DOWNLOADED_FILE mime=application/x-subrip lang=null", text)
        assertNoMarker(text)
        assertFalse(text, text.contains("content://"))
    }

    @Test
    fun `a failure keeps its classes and error code, never a message`() {
        val cause = IOException("Unable to connect to https://MARKER_HOST.example/MARKER_PATH?MARKER_TOKEN")
        val failure = PlaybackException(
            "Source error at https://MARKER_HOST.example/?c=MARKER_COOKIE",
            cause,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        )
        val text = redactedThrowable(failure)
        assertEquals(
            "androidx.media3.common.PlaybackException(ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) <- " +
                "java.io.IOException",
            text,
        )
        assertNoMarker(text)
        assertEquals("none", redactedThrowable(null))
    }
}

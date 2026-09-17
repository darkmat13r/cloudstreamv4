package com.lagradost.cloudstream3

import io.ktor.http.Url
import io.ktor.http.URLParserException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins the `ktor-http` URL parsing behaviour extensions see at runtime.
 *
 * Only one `ktor-http` fits on the app classpath and loaded extensions use it, so a Ktor bump
 * changes these results for every extension. Ktor 3.6.0 (KTOR-8755) percent-encodes paths and
 * accepts a bare `%`; since 3.5.2 (KTOR-9759) whitespace in a host is rejected. If a future bump
 * turns one of these red, that is an extension-visible change: record it, do not just update.
 */
class KtorUrlBehaviourTest {

    private val mirror = object : MainAPI() {
        override var mainUrl = "https://mirror.example.org"
    }

    @Test
    fun encodedPathPercentEncodesSpacesAndNonAscii() {
        assertEquals("/a%20b/c.mkv", Url("https://example.com/a b/c.mkv").encodedPath)
        assertEquals(
            "/%D0%B2%D0%B8%D0%B4%D0%B5%D0%BE/%D1%84%D0%B0%D0%B9%D0%BB.mp4",
            Url("https://example.com/видео/файл.mp4").encodedPath,
        )
    }

    @Test
    fun alreadyEncodedPathIsKept() {
        assertEquals("/a%20b/c.mp4", Url("https://example.com/a%20b/c.mp4").encodedPath)
    }

    @Test
    fun barePercentInPathParsesEncoded() {
        assertEquals("/100%25/x.mp4", Url("https://example.com/100%/x.mp4").encodedPath)
    }

    @Test
    fun updateUrlReturnsPercentEncodedPath() {
        assertEquals("https://mirror.example.org/a%20b/c.mkv", mirror.updateUrl("https://example.com/a b/c.mkv"))
    }

    @Test
    fun updateUrlKeepsQueryAndSwapsHost() {
        assertEquals(
            "https://mirror.example.org/path/video.m3u8?token=abc",
            mirror.updateUrl("https://example.com/path/video.m3u8?token=abc"),
        )
    }

    @Test
    fun whitespaceInHostIsRejected() {
        assertFailsWith<URLParserException> { Url("https:// example.com/x") }
        assertFailsWith<URLParserException> { Url("https://example.com /x") }
    }

    @Test
    fun updateUrlReturnsInputUnchangedWhenHostHasWhitespace() {
        assertEquals("https:// example.com/x", mirror.updateUrl("https:// example.com/x"))
    }
}

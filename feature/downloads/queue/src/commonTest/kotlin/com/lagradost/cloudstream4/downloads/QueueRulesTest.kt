package com.lagradost.cloudstream4.downloads

import com.lagradost.cloudstream4.media.model.DownloadFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The queue's pure rules, moved from `DesktopDownloadsTest` by `KMP-123` so they run on iOS as well as the Mac:
 * what poster is kept, how a failure is stored, and which download URL is taken.
 */
class QueueRulesTest {
    @Test
    fun `a poster with userinfo is refused however it is spelled - and an at sign in the path is not userinfo - BUG-78`() {
        listOf(
            "https://user:secret@img.example/a.jpg",
            "https://user@img.example/a.jpg",
            "https://@img.example/a.jpg",
            "HTTPS://User:Secret@img.example/a.jpg",
            "https://img.example@127.0.0.1/a.jpg",
            "https://user:pass@img.example?x=1",
            "https://user:pass@img.example#f",
            "http:///user:pass@img.example/a.jpg",
            "http://\\user:pass@img.example/a.jpg",
            "https://u@[2001:db8::1]/a.jpg",
        ).forEach { refused -> assertNull(DesktopDownloads.posterOf(refused), "kept: $refused") }
        listOf(
            "https://img.example/a@2x.jpg",
            "https://img.example/a.jpg?by=me@example.com",
            "https://img.example/a.jpg#me@x",
            "https://img.example\\a@b.jpg",
            "https://user%40x.img.example/a.jpg",
            "https://[2001:db8::1]/a@b.jpg",
        ).forEach { kept -> assertEquals(kept, DesktopDownloads.posterOf(kept)) }
    }

    @Test
    fun `only a plain http or https poster is kept - and none is fine - KMP-99`() {
        assertEquals("https://img.example/a.jpg", DesktopDownloads.posterOf("https://img.example/a.jpg"))
        assertEquals("HTTP://img.example/a.jpg", DesktopDownloads.posterOf("HTTP://img.example/a.jpg"))
        assertNull(DesktopDownloads.posterOf(null))
        listOf(
            "",
            "file:///etc/hosts",
            "javascript:alert(1)",
            "data:image/png;base64,AAAA",
            "//img.example/a.jpg",
            "https://img.example/a b.jpg",
            "https://img.example/a.jpg\n",
            "https://img.example/" + "a".repeat(2048),
        ).forEach { refused -> assertNull(DesktopDownloads.posterOf(refused), "kept: $refused") }
    }

    @Test
    fun `every failure round-trips through the stored form - with numbers only`() {
        val all = listOf(
            DownloadFailure.NetworkLost, DownloadFailure.StorageFull(123L), DownloadFailure.StorageFull(null),
            DownloadFailure.StorageUnavailable, DownloadFailure.NoUsableLink, DownloadFailure.SourceFailed,
            DownloadFailure.Unknown, DownloadFailure.ServerRefused(403), DownloadFailure.SourceChanged,
            DownloadFailure.SizeMismatch(10L, 9L), DownloadFailure.HashMismatch,
            DownloadFailure.InsufficientSpace(100L, 50L), DownloadFailure.AddressRefused, DownloadFailure.DestinationExists,
        )
        all.forEach { failure ->
            assertEquals(failure, StoredFailure.decode(StoredFailure.name(failure), StoredFailure.numbers(failure)))
        }
        assertEquals(DownloadFailure.Unknown, StoredFailure.decode("SomethingElse", emptyList()))
        assertEquals(DownloadFailure.Unknown, StoredFailure.decode("ServerRefused", listOf(-1L)))
    }

    /**
     * `KMP-123`: the URL rule on both platforms. The JVM reads with `java.net.URI` and iOS with `NSURLComponents`, so
     * these are the cases the rule is for, and each must come out the same on both.
     */
    @Test
    fun `only an absolute http or https URL with a host and no user information is downloaded`() {
        assertEquals("https://cdn.example/a.mp4", DesktopDownloads.cleanUrl("https://cdn.example/a.mp4"))
        assertEquals("HTTP://cdn.example:8080/a.mp4?x=1#f", DesktopDownloads.cleanUrl("HTTP://cdn.example:8080/a.mp4?x=1#f"))
        assertEquals("https://cdn.example/a%20b.mp4", DesktopDownloads.cleanUrl("  https://cdn.example/a b.mp4 "), "spaces are encoded")
        listOf(
            "ftp://cdn.example/a",
            "file:///etc/passwd",
            "https://user:pw@cdn.example/a",
            "https://user@cdn.example/a",
            "https://:pw@cdn.example/a",
            "not a url",
            "//cdn.example/a.mp4",
            "https:///a.mp4",
            "https:a.mp4",
            "javascript:alert(1)",
            "",
        ).forEach { refused -> assertNull(DesktopDownloads.cleanUrl(refused), "kept: $refused") }
    }

    /** `KMP-123`: NFKC, which the address rule reads after, is the same on both platforms. */
    @Test
    fun `a full-width address is read as its ASCII form`() {
        assertEquals("https://a.example/", nfkc("ｈｔｔｐｓ：／／ａ．ｅｘａｍｐｌｅ／"))
        assertEquals("Amélie", nfkc("Ame\u0301lie"), "composed")
    }
}

package com.lagradost.cloudstream4.media.download

import io.github.vinceglb.filekit.PlatformFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadRequestTest {
    private fun request(headers: Map<String, String> = emptyMap(), referer: String? = null, url: String = "https://example.com/a.mp4?sig=SECRET") =
        DownloadRequest(url, PlatformFile("/tmp/x"), "a.mp4", headers, referer)

    @Test
    fun `toString holds no url header or referer`() {
        val text = request(mapOf("Authorization" to "Bearer TOKEN", "Cookie" to "sid=COOKIE"), "https://ref.example/PAGE").toString()
        listOf("SECRET", "example.com", "TOKEN", "COOKIE", "ref.example", "PAGE", "Authorization").forEach {
            assertFalse(text.contains(it), "leaked $it in $text")
        }
    }

    @Test
    fun `the engine's own headers are dropped whatever their case`() {
        val kept = request(
            mapOf(
                "Range" to "bytes=0-", "range" to "bytes=5-", "RANGE" to "x", "If-Range" to "x", "IF-RANGE" to "x", "Accept-Encoding" to "gzip",
                "accept-encoding" to "br", "Host" to "evil", "host" to "evil", "Content-Length" to "5", "Transfer-Encoding" to "chunked",
                "Connection" to "upgrade", "User-Agent" to "UA", "X-Token" to "t",
            ),
        ).headers
        assertEquals(mapOf("User-Agent" to "UA", "X-Token" to "t"), kept)
    }

    @Test
    fun `a value with a control character is dropped not repaired`() {
        val kept = request(
            mapOf(
                "A" to "ok", "B" to "x\r\nInjected: 1", "C" to "x\ny", "D" to "x${Char(0)}y", "E" to "x${Char(0x7f)}y",
                "F" to "caf${Char(0xE9)}", "G" to "tab\tok", "H" to "", "I" to "  padded  ",
            ),
        ).headers
        assertEquals(mapOf("A" to "ok", "G" to "tab\tok", "H" to "", "I" to "padded"), kept)
    }

    @Test
    fun `a name that is not a token is dropped`() {
        val kept = request(
            mapOf(
                "" to "x", "Bad Name" to "x", "Bad:Name" to "x", "Bad\r\nName" to "x", "Na${Char(0xE9)}me" to "x", "X-Ok_1.2" to "x", "A".repeat(129) to "x",
            ),
        ).headers
        assertEquals(mapOf("X-Ok_1.2" to "x"), kept)
    }

    @Test
    fun `two entries that differ only in case collapse to the later one`() {
        assertEquals(mapOf("x-token" to "2"), request(mapOf("X-Token" to "1", "x-token" to "2")).headers)
    }

    @Test
    fun `the referer argument beats a Referer header case insensitively`() {
        assertEquals(mapOf("Referer" to "https://a/"), request(mapOf("referer" to "https://b/"), "https://a/").headers)
        assertEquals(mapOf("Referer" to "https://a/"), request(mapOf("REFERER" to "https://b/", "Referer" to "https://c/"), "https://a/").headers)
    }

    @Test
    fun `a Referer header stands when there is no usable argument`() {
        assertEquals(mapOf("referer" to "https://b/"), request(mapOf("referer" to "https://b/"), null).headers)
        assertEquals(mapOf("referer" to "https://b/"), request(mapOf("referer" to "https://b/"), "   ").headers)
        assertEquals(mapOf("referer" to "https://b/"), request(mapOf("referer" to "https://b/"), "https://bad\r\nx").headers)
    }

    @Test
    fun `a blank referer is not sent`() {
        assertEquals(emptyMap(), request(referer = "").headers)
        assertEquals(emptyMap(), request(referer = "  \t ").headers)
        assertEquals(emptyMap(), request(mapOf("Referer" to "   ")).headers)
        assertEquals(emptyMap(), request(mapOf("referer" to ""), "").headers)
    }

    @Test
    fun `a referer with a control character is not sent`() {
        assertEquals(emptyMap(), request(referer = "https://a/\r\nHost: evil").headers)
    }

    @Test
    fun `at most 64 headers are kept`() {
        val many = (0 until 200).associate { "X-$it" to "v" }
        assertEquals(64, request(many).headers.size)
        assertEquals(64, request(many, "https://a/").headers.size)
    }

    @Test
    fun `no headers and no referer is an empty map`() {
        assertTrue(request().headers.isEmpty())
    }

    @Test
    fun `defaults are the contract's`() {
        val o = DownloadOptions()
        assertEquals(8, o.parts)
        assertEquals(1L shl 20, o.minPartBytes)
        assertEquals(64, o.maxPartFiles)
        assertEquals(5, o.maxAttemptsPerPart)
        assertEquals(500L, o.backoffBaseMillis)
        assertEquals(8000L, o.backoffMaxMillis)
        assertEquals(30_000L, o.stallTimeoutMillis)
        assertEquals(5, o.maxRedirects)
        assertFalse(o.allowPrivateAddresses)
        assertTrue(o.rejectHtml)
        assertEquals(250L, o.progressIntervalMillis)
        assertEquals(64 * 1024, o.bufferBytes)
        assertEquals(o, DownloadOptions())
    }

    @Test
    fun `caps are enforced on construction`() {
        assertEquals(DownloadOptions.MAX_PARTS, DownloadOptions(parts = 1000).parts)
        assertEquals(1, DownloadOptions(parts = 0).parts)
        assertEquals(1, DownloadOptions(parts = -5).parts)
        assertEquals(DownloadOptions.MAX_PART_FILES, DownloadOptions(maxPartFiles = 100_000).maxPartFiles)
        assertEquals(DownloadOptions.MAX_PART_FILES, DownloadOptions(maxPartFiles = Int.MAX_VALUE).maxPartFiles)
        assertEquals(16, DownloadOptions(parts = 16, maxPartFiles = 2).maxPartFiles)
        assertEquals(1L, DownloadOptions(minPartBytes = 0).minPartBytes)
        assertEquals(1L, DownloadOptions(minPartBytes = Long.MIN_VALUE).minPartBytes)
        assertEquals(20, DownloadOptions(maxAttemptsPerPart = 999).maxAttemptsPerPart)
        assertEquals(1, DownloadOptions(maxAttemptsPerPart = 0).maxAttemptsPerPart)
        assertEquals(600_000L, DownloadOptions(backoffBaseMillis = Long.MAX_VALUE).backoffBaseMillis)
        assertEquals(1L, DownloadOptions(backoffBaseMillis = -1).backoffBaseMillis)
        assertEquals(1000L, DownloadOptions(backoffBaseMillis = 1000, backoffMaxMillis = 10).backoffMaxMillis)
        assertEquals(600_000L, DownloadOptions(backoffMaxMillis = Long.MAX_VALUE).backoffMaxMillis)
        assertEquals(100L, DownloadOptions(stallTimeoutMillis = 0).stallTimeoutMillis)
        assertEquals(600_000L, DownloadOptions(stallTimeoutMillis = Long.MAX_VALUE).stallTimeoutMillis)
        assertEquals(10, DownloadOptions(maxRedirects = 1000).maxRedirects)
        assertEquals(0, DownloadOptions(maxRedirects = -1).maxRedirects)
        assertEquals(1L, DownloadOptions(progressIntervalMillis = 0).progressIntervalMillis)
        assertEquals(10_000L, DownloadOptions(progressIntervalMillis = Long.MAX_VALUE).progressIntervalMillis)
        assertEquals(1024, DownloadOptions(bufferBytes = 0).bufferBytes)
        assertEquals(1024 * 1024, DownloadOptions(bufferBytes = Int.MAX_VALUE).bufferBytes)
    }

    @Test
    fun `flags pass through`() {
        val o = DownloadOptions(allowPrivateAddresses = true, rejectHtml = false)
        assertTrue(o.allowPrivateAddresses)
        assertFalse(o.rejectHtml)
    }

    @Test
    fun `expected hash is normalised and validated`() {
        val hash = ExpectedHash(HashAlgorithm.SHA256, "  " + "AB".repeat(32) + " ")
        assertEquals("ab".repeat(32), hash.hex)
        assertEquals(ExpectedHash(HashAlgorithm.SHA256, "ab".repeat(32)), hash)
        assertEquals(32, ExpectedHash(HashAlgorithm.MD5, "0".repeat(32)).hex.length)
        assertFailsWith<IllegalArgumentException> { ExpectedHash(HashAlgorithm.SHA256, "ab".repeat(31)) }
        assertFailsWith<IllegalArgumentException> { ExpectedHash(HashAlgorithm.SHA256, "zz".repeat(32)) }
        assertFailsWith<IllegalArgumentException> { ExpectedHash(HashAlgorithm.SHA256, "") }
        assertFailsWith<IllegalArgumentException> { ExpectedHash(HashAlgorithm.MD5, "ab".repeat(32)) }
    }

    @Test
    fun `snapshot maps to download progress and clamps negatives`() {
        val snapshot = DownloadSnapshot(
            state = com.lagradost.cloudstream4.media.model.DownloadState.DOWNLOADING, bytesDone = 50, bytesTotal = 200, bytesPerSecond = 10,
            etaSeconds = 15, parts = emptyList(), failure = null, file = null, sizeVerified = false,
        )
        val progress = snapshot.toDownloadProgress(7)
        assertEquals(7, progress.id)
        assertEquals(50L, progress.bytesDownloaded)
        assertEquals(200L, progress.bytesTotal)
        assertEquals(10L, progress.bytesPerSecond)
        assertEquals(50L, progress.bytesWritten)
        assertEquals(0.25f, progress.fraction)
        val odd = snapshot.copy(bytesDone = -1, bytesTotal = -5, bytesPerSecond = -3).toDownloadProgress(1)
        assertEquals(0L, odd.bytesDownloaded)
        assertNull(odd.bytesTotal)
        assertEquals(0L, odd.bytesPerSecond)
    }
}

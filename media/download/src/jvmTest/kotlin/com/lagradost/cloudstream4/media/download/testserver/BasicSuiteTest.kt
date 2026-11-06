package com.lagradost.cloudstream4.media.download.testserver

import com.lagradost.cloudstream4.media.download.ExpectedHash
import com.lagradost.cloudstream4.media.download.HashAlgorithm
import com.lagradost.cloudstream4.media.model.DownloadFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Happy paths and the shapes of file a downloader must get byte-exact. */
class BasicSuiteTest : SuiteBase() {
    private companion object {
        const val MIN_PART = 256 * KIB
        const val ODD_SIZE = 6 * MIB + 12_345
    }

    // region ranges

    @Test
    fun `ranged server delivers a byte identical file in several parts`() {
        val res = h.resource(size = ODD_SIZE)
        val run = h.run(h.request(options = suiteOptions(parts = 4, minPartBytes = MIN_PART)))

        h.assertCompleted(run, res.source)
        assertTrue(run.maxParts >= 2, "expected a segmented download, saw at most ${run.maxParts} part(s)")
        val starts = h.server.bodyRequests().mapNotNull { it.info.rangeStart }.toSet()
        assertTrue(starts.size >= 2, "expected requests for several distinct ranges, saw $starts")
        assertEquals(ODD_SIZE, run.last.bytesTotal)
        assertTrue(run.last.sizeVerified)
    }

    @Test
    fun `every request asks for identity encoding and body range requests carry If-Range`() {
        val res = h.resource(size = ODD_SIZE)
        h.run(h.request(options = suiteOptions(parts = 4, minPartBytes = MIN_PART)))

        val all = h.server.log.toList()
        assertTrue(all.isNotEmpty())
        all.forEach { assertEquals("identity", it.info.headers["accept-encoding"], "Accept-Encoding on ${it.info}") }
        val ranged = h.server.bodyRequests().filter { it.info.rangeStart != null }
        assertTrue(ranged.isNotEmpty())
        ranged.forEach { assertEquals(res.content.etag, it.info.headers["if-range"], "If-Range on ${it.info}") }
    }

    @Test
    fun `server without range support yields a byte identical file from one stream`() {
        val res = h.resource(size = ODD_SIZE)
        res.supportsRange = false
        val run = h.run(h.request(options = suiteOptions(parts = 4, minPartBytes = MIN_PART)))

        h.assertCompleted(run, res.source)
        val nonZeroRanges = h.server.bodyRequests().filter { (it.info.rangeStart ?: 0L) > 0L }
        assertEquals(emptyList(), nonZeroRanges.map { it.info.toString() }, "asked for a range of a server that cannot serve one")
        assertTrue(run.last.sizeVerified, "length was declared, so the size can be verified")
    }

    @Test
    fun `no range support and no content length completes but is not size verified`() {
        val res = h.resource(size = 3 * MIB + 7)
        res.supportsRange = false
        res.lengthMode = LengthMode.MISSING
        val run = h.run(h.request())

        h.assertCompleted(run, res.source)
        assertFalse(run.last.sizeVerified, "the server never declared a length, so nothing was verified")
    }

    @Test
    fun `ranged server that omits Content-Length on its partial responses still delivers every byte`() {
        val res = h.resource(size = 3 * MIB + 7)
        res.lengthMode = LengthMode.MISSING
        val run = h.run(h.request(options = suiteOptions(parts = 4, minPartBytes = MIN_PART)))

        h.assertCompleted(run, res.source)
    }

    // endregion

    // region lying lengths

    @Test
    fun `a content length longer than the body is never reported as a complete download`() {
        val res = h.resource(size = 2 * MIB)
        res.supportsRange = false
        res.lengthMode = LengthMode.TOO_LONG
        val run = h.run(h.request(options = suiteOptions(maxAttemptsPerPart = 2)))

        h.assertFailed(run)
    }

    @Test
    fun `a ranged response whose Content-Length is too long is never reported as complete`() {
        val res = h.resource(size = 2 * MIB)
        res.lengthMode = LengthMode.TOO_LONG
        val run = h.run(h.request(options = suiteOptions(parts = 2, minPartBytes = MIN_PART, maxAttemptsPerPart = 2)))

        h.assertFailed(run)
    }

    // endregion

    // region sizes that sit on a boundary

    @Test
    fun `zero byte file when the server answers a range with 416 star slash 0`() {
        val res = h.resource(size = 0)
        res.emptyAs416 = true
        val run = h.run(h.request())

        h.assertCompleted(run, res.source)
        assertEquals(0L, h.finalFile().length())
    }

    @Test
    fun `zero byte file when the server ignores ranges and sends an empty 200`() {
        val res = h.resource(size = 0)
        res.emptyAs416 = false
        val run = h.run(h.request())

        h.assertCompleted(run, res.source)
    }

    @Test
    fun `one byte file`() {
        val res = h.resource(size = 1)
        val run = h.run(h.request(options = suiteOptions(parts = 8, minPartBytes = 1)))

        h.assertCompleted(run, res.source)
    }

    @Test
    fun `sizes on and next to a part boundary are byte exact`() {
        val parts = 4
        val boundary = parts * MIN_PART
        val sizes = listOf(
            2 * MIN_PART - 1, 2 * MIN_PART, 2 * MIN_PART + 1,
            boundary - 1, boundary, boundary + 1,
            MIN_PART - 1, MIN_PART, MIN_PART + 1,
        )
        for (size in sizes) {
            val name = "f-$size.bin"
            val res = h.resource(path = "/f-$size", size = size, seed = size)
            val run = h.run(h.request(path = "/f-$size", name = name, options = suiteOptions(parts = parts, minPartBytes = MIN_PART)))
            h.assertCompleted(run, res.source, name)
        }
    }

    @Test
    fun `a file of exactly parts times minPartBytes gets equal ranges`() {
        val res = h.resource(size = 4 * MIN_PART)
        val run = h.run(h.request(options = suiteOptions(parts = 4, minPartBytes = MIN_PART)))

        h.assertCompleted(run, res.source)
        val first = run.snapshots.first { it.parts.size >= 4 }
        assertEquals(setOf(MIN_PART), first.parts.take(4).map { (it.endExclusive ?: -1) - it.start }.toSet())
    }

    // endregion

    // region integrity

    @Test
    fun `matching SHA-256 completes and matching MD5 completes`() {
        val res = h.resource(size = 2 * MIB + 5)
        val sha = ExpectedHash(HashAlgorithm.SHA256, res.source.sha256Hex())
        h.assertCompleted(h.run(h.request(hash = sha, options = suiteOptions(parts = 3, minPartBytes = MIN_PART))), res.source)

        h.finalFile().delete()
        val md5 = ExpectedHash(HashAlgorithm.MD5, res.source.md5Hex())
        h.assertCompleted(h.run(h.request(hash = md5, options = suiteOptions(parts = 3, minPartBytes = MIN_PART))), res.source)
    }

    @Test
    fun `wrong SHA-256 fails with HashMismatch and leaves no final file`() {
        val res = h.resource(size = 2 * MIB + 5)
        val wrong = ExpectedHash(HashAlgorithm.SHA256, res.source.sha256Hex().reversed())
        val run = h.run(h.request(hash = wrong, options = suiteOptions(parts = 3, minPartBytes = MIN_PART)))

        assertEquals(DownloadFailure.HashMismatch, h.assertFailed(run).failure)
    }

    // endregion

    // region refused responses

    @Test
    fun `html where a file was expected is NoUsableLink`() {
        val res = h.resource(size = 2 * MIB)
        res.contentType = "text/html; charset=utf-8"
        val run = h.run(h.request())

        assertEquals(DownloadFailure.NoUsableLink, h.assertFailed(run).failure)
        assertEquals(emptyList(), h.server.bodyRequests().map { it.toString() }, "fetched body bytes of an html page")
    }

    @Test
    fun `html is accepted when rejectHtml is off`() {
        val res = h.resource(size = 1 * MIB)
        res.contentType = "text/html"
        val run = h.run(h.request(options = suiteOptions(rejectHtml = false)))

        h.assertCompleted(run, res.source)
    }

    @Test
    fun `a gzip content encoding is refused as ServerRefused`() {
        val res = h.resource(size = 2 * MIB)
        res.contentEncoding = "gzip"
        val run = h.run(h.request())

        assertIs<DownloadFailure.ServerRefused>(h.assertFailed(run).failure)
    }

    // endregion

    // region destination

    @Test
    fun `existing destination is DestinationExists, untouched, with no body fetched`() {
        h.resource(size = 1 * MIB)
        val existing = h.finalFile()
        existing.writeText("KEEP ME")
        val run = h.run(h.request())

        assertEquals(DownloadFailure.DestinationExists, run.last.failure)
        assertEquals("KEEP ME", existing.readText())
        assertEquals(emptyList(), h.server.bodyRequests().map { it.toString() })
    }

    @Test
    fun `overwrite replaces an existing destination atomically`() {
        val res = h.resource(size = 2 * MIB)
        val existing = h.finalFile()
        existing.writeText("OLD CONTENT")
        val oldSize = existing.length()
        val run = h.run(h.request(overwrite = true, options = suiteOptions(parts = 2, minPartBytes = MIN_PART)))

        h.assertCompleted(run, res.source, extraAllowedSizes = setOf(oldSize))
        assertNotNull(run.last.file)
    }

    // endregion
}

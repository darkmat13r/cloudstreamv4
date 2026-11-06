package com.lagradost.cloudstream4.media.download.testserver

import com.lagradost.cloudstream4.media.download.DownloadSnapshot
import com.lagradost.cloudstream4.media.model.DownloadFailure
import io.github.vinceglb.filekit.path
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Address guard, redirects, file names, and the rule that no secret reaches disk or a log line. */
class SecuritySuiteTest : SuiteBase() {
    private companion object {
        const val SIZE = 1 * MIB
        const val SECRET_TOKEN = "TOKENSECRET9f3a"
        const val SECRET_PATH = "pathsecret7c1"
        const val SECRET_HEADER = "HDRSECRET41d"
        const val SECRET_BEARER = "AUTHSECRET88e"
        const val SECRET_COOKIE = "COOKIESECRET02b"
        const val SECRET_REFERER = "REFSECRET6aa"
    }

    override fun dnsMap(): Map<String, String> = mapOf(
        "public.example" to "127.0.0.1",
        "public2.example" to "127.0.0.1",
        "internal.example" to "127.0.0.1",
    )

    private val resolver = MapResolver(
        mapOf(
            "public.example" to listOf(PUBLIC_V4),
            "public2.example" to listOf(PUBLIC_V4),
            "internal.example" to listOf(LOOPBACK_V4),
        ),
    )

    private fun guarded(name: String = "out.bin", url: String) =
        h.request(name = name, url = url, options = suiteOptions(allowPrivateAddresses = false, maxRedirects = 5))

    // region address guard

    @Test
    fun `loopback and private literals are refused by default with no request sent`() {
        h.resource(size = SIZE)
        val port = h.server.port
        val urls = listOf(
            "http://127.0.0.1:$port/f",
            "http://localhost:$port/f",
            "http://[::1]:$port/f",
            "http://127.1:$port/f",
            "http://2130706433:$port/f",
            "http://0x7f.0.0.1:$port/f",
            "http://0177.0.0.1:$port/f",
            "http://[::ffff:127.0.0.1]:$port/f",
            "http://10.0.0.5:$port/f",
            "http://192.168.1.1:$port/f",
            "http://169.254.169.254/latest/meta-data",
            "http://foo.localhost:$port/f",
            "http://printer.local:$port/f",
        )
        for (url in urls) {
            val run = h.run(guarded(url = url), downloader = h.downloader(resolver = resolver), timeoutMillis = 20_000)
            assertEquals(DownloadFailure.AddressRefused, run.last.failure, "for $url")
            assertEquals(emptyList(), h.server.log.map { it.toString() }, "a request reached the server for $url")
        }
    }

    @Test
    fun `non http schemes and userinfo are refused even when private addresses are allowed`() {
        h.resource(size = SIZE)
        val port = h.server.port
        val urls = listOf(
            "ftp://127.0.0.1:$port/f",
            "file:///etc/passwd",
            "http://user:pass@127.0.0.1:$port/f",
            "http://user@public.example:$port/f",
        )
        for (url in urls) {
            val run = h.run(h.request(url = url, options = suiteOptions(allowPrivateAddresses = true)), downloader = h.downloader(resolver = resolver))
            assertEquals(DownloadFailure.AddressRefused, run.last.failure, "for $url")
        }
        assertEquals(emptyList(), h.server.log.map { it.toString() })
    }

    @Test
    fun `localhost works when private addresses are allowed`() {
        val res = h.resource(size = SIZE)
        val run = h.run(h.request(url = "http://localhost:${h.server.port}/f", options = suiteOptions(allowPrivateAddresses = true)))

        h.assertCompleted(run, res.source)
    }

    @Test
    fun `a name that resolves to loopback is refused through the resolver`() {
        h.resource(size = SIZE)
        val run = h.run(guarded(url = "http://internal.example:${h.server.port}/f"), downloader = h.downloader(resolver = resolver))

        assertEquals(DownloadFailure.AddressRefused, run.last.failure)
        assertEquals(emptyList(), h.server.log.map { it.toString() })
    }

    @Test
    fun `a name with one public and one private address is refused`() {
        h.resource(size = SIZE)
        val mixed = MapResolver(mapOf("public.example" to listOf(PUBLIC_V4, LOOPBACK_V4)))
        val run = h.run(guarded(url = "http://public.example:${h.server.port}/f"), downloader = h.downloader(resolver = mixed))

        assertEquals(DownloadFailure.AddressRefused, run.last.failure)
        assertEquals(emptyList(), h.server.log.map { it.toString() })
    }

    @Test
    fun `a name that does not resolve is NetworkLost`() {
        val failing = object : com.lagradost.cloudstream4.media.download.HostResolver {
            override suspend fun resolve(host: String): List<ByteArray> = throw java.io.IOException("nxdomain")
        }
        val run = h.run(guarded(url = "http://nowhere.example:1/f"), downloader = h.downloader(resolver = failing), timeoutMillis = 20_000)

        assertEquals(DownloadFailure.NetworkLost, run.last.failure)
    }

    // endregion

    // region redirects

    @Test
    fun `a redirect from a public name to a public name is followed and drops credentials across origins`() {
        val res = h.resource(path = "/file", size = 2 * MIB)
        h.server.redirect("/r-public", "http://public2.example:${h.server.port}/file")
        val run = h.run(
            h.request(
                url = "http://public.example:${h.server.port}/r-public",
                headers = mapOf("Authorization" to "Bearer $SECRET_BEARER", "Cookie" to "sid=$SECRET_COOKIE", "X-Custom" to "kept"),
                options = suiteOptions(allowPrivateAddresses = false, parts = 2, minPartBytes = 256 * KIB),
            ),
            downloader = h.downloader(resolver = resolver),
        )

        h.assertCompleted(run, res.source)
        val firstHop = h.server.log.filter { it.info.path == "/r-public" }
        val secondHop = h.server.log.filter { it.info.path == "/file" }
        assertTrue(firstHop.isNotEmpty() && secondHop.isNotEmpty())
        firstHop.forEach { assertEquals("Bearer $SECRET_BEARER", it.info.headers["authorization"], "same-origin hop keeps Authorization") }
        secondHop.forEach {
            assertNull(it.info.headers["authorization"], "Authorization crossed to another origin on ${it.info}")
            assertNull(it.info.headers["cookie"], "Cookie crossed to another origin on ${it.info}")
            assertEquals("kept", it.info.headers["x-custom"])
        }
        assertTrue(firstHop.size >= secondHop.size, "each part must go through the original URL: ${firstHop.size} hops for ${secondHop.size} fetches")
    }

    @Test
    fun `a redirect from a public name to a loopback literal is refused before connecting`() {
        h.resource(path = "/file", size = SIZE)
        h.server.redirect("/r-literal", "http://127.0.0.1:${h.server.port}/file")
        val run = h.run(guarded(url = "http://public.example:${h.server.port}/r-literal"), downloader = h.downloader(resolver = resolver))

        assertEquals(DownloadFailure.AddressRefused, run.last.failure)
        assertEquals(emptyList(), h.server.log.filter { it.info.path == "/file" }.map { it.toString() }, "the redirect target was fetched")
    }

    @Test
    fun `a redirect from a public name to a name that resolves to loopback is refused`() {
        h.resource(path = "/file", size = SIZE)
        h.server.redirect("/r-internal", "http://internal.example:${h.server.port}/file")
        val run = h.run(guarded(url = "http://public.example:${h.server.port}/r-internal"), downloader = h.downloader(resolver = resolver))

        assertEquals(DownloadFailure.AddressRefused, run.last.failure)
        assertEquals(emptyList(), h.server.log.filter { it.info.path == "/file" }.map { it.toString() })
    }

    @Test
    fun `a redirect to a link-local metadata address is refused`() {
        h.server.redirect("/r-meta", "http://169.254.169.254/latest/meta-data")
        val run = h.run(guarded(url = "http://public.example:${h.server.port}/r-meta"), downloader = h.downloader(resolver = resolver))

        assertEquals(DownloadFailure.AddressRefused, run.last.failure)
    }

    @Test
    fun `a redirect loop ends in ServerRefused and not an endless chase`() {
        h.server.redirect("/loop", "http://public.example:${h.server.port}/loop")
        val run = h.run(
            h.request(url = "http://public.example:${h.server.port}/loop", options = suiteOptions(allowPrivateAddresses = false, maxRedirects = 3)),
            downloader = h.downloader(resolver = resolver),
            timeoutMillis = 20_000,
        )

        assertEquals(DownloadFailure.ServerRefused(302), run.last.failure)
        assertTrue(h.server.log.size <= 6, "followed ${h.server.log.size} redirects with maxRedirects = 3")
    }

    // endregion

    // region file names

    @Test
    fun `hostile file names stay inside the destination folder`() {
        val names = listOf(
            "../../evil.bin", "..\\..\\evil2.bin", "sub/dir/x.bin", "/etc/passwd", "C:\\win\\x.bin", ".hidden",
            "con.txt", "a\u0000b.bin", "\u202Egnp.exe", "...", "", "   ", "x".repeat(300) + ".mp4", "trailing. ",
        )
        h.resource(size = 4 * KIB)
        val outerBefore = h.outer.list()!!.toSet()
        for (name in names) {
            val run = h.run(h.request(name = name, options = suiteOptions(parts = 1)))
            val last = run.last
            val label = "name=${name.take(20).map { if (it.code < 32) "\\u%04x".format(it.code) else it.toString() }.joinToString("")}"
            assertEquals(com.lagradost.cloudstream4.media.model.DownloadState.COMPLETED, last.state, "$label failure=${last.failure}")
            val file = File(assertNotNull(last.file, label).path)
            assertEquals(h.folderFile.canonicalFile, file.canonicalFile.parentFile, "$label escaped the folder: ${file.path}")
            assertTrue(file.name.none { it == '/' || it == '\\' || it.code < 32 }, "$label produced name ${file.name}")
            assertTrue(file.name.toByteArray().size <= 200, "$label produced a ${file.name.toByteArray().size} byte name")
            assertTrue(file.name.isNotBlank() && !file.name.startsWith(".") && !file.name.endsWith(".") && !file.name.endsWith(" "), "$label produced name '${file.name}'")
            assertEquals(4 * KIB, file.length())
            file.delete()
        }
        assertEquals(outerBefore, h.outer.list()!!.toSet(), "something was created beside the destination folder")
    }

    @Test
    fun `a long file name keeps its extension`() {
        h.resource(size = 4 * KIB)
        val run = h.run(h.request(name = "y".repeat(400) + ".mkv", options = suiteOptions(parts = 1)))

        val file = File(assertNotNull(run.last.file).path)
        assertTrue(file.name.endsWith(".mkv"), "extension lost: ${file.name}")
    }

    // endregion

    // region secrets

    private fun secretRequest() = h.request(
        path = "/f/$SECRET_PATH",
        url = h.server.url("/f/$SECRET_PATH?token=$SECRET_TOKEN"),
        headers = mapOf("X-Api-Key" to SECRET_HEADER, "Authorization" to "Bearer $SECRET_BEARER", "Cookie" to "sid=$SECRET_COOKIE"),
        referer = "https://ref.example/$SECRET_REFERER",
        options = suiteOptions(parts = 4, minPartBytes = 256 * KIB),
    )

    private val secrets = listOf(
        SECRET_TOKEN, SECRET_PATH, SECRET_HEADER, SECRET_BEARER, SECRET_COOKIE, SECRET_REFERER, "ref.example", "127.0.0.1", "token=",
    )

    private fun assertClean(label: String, text: String) {
        for (secret in secrets) assertFalse(text.contains(secret), "$label contains '$secret': $text")
    }

    private fun assertSnapshotsClean(snapshots: List<DownloadSnapshot>) {
        snapshots.forEach { s ->
            assertClean("snapshot.toString()", s.toString())
            s.failure?.let { assertClean("failure.toString()", it.toString()) }
            s.parts.forEach { assertClean("part.toString()", it.toString()) }
        }
    }

    private fun assertStateDirClean() {
        val dir = h.tempDir()
        if (!dir.exists()) return
        dir.listFiles().orEmpty().filter { !it.name.startsWith("part-") && it.isFile }.forEach { f ->
            assertClean("temp file ${f.name}", String(f.readBytes(), Charsets.ISO_8859_1))
        }
        dir.listFiles().orEmpty().forEach { assertClean("temp entry name", it.name) }
        h.folderFile.listFiles().orEmpty().forEach { assertClean("folder entry name", it.name) }
    }

    @Test
    fun `no secret reaches the state file or a snapshot while a download is paused`() {
        val res = h.resource(path = "/f/$SECRET_PATH", size = 16 * MIB)
        h.server.throttle = { 2 * MIB }
        val request = secretRequest()
        assertClean("request.toString()", request.toString())

        val run = h.run(request, stopWhen = { it.bytesDone >= res.size / 4 })

        assertSnapshotsClean(run.snapshots)
        assertTrue(File(h.tempDir(), "state").isFile)
        assertStateDirClean()
    }

    @Test
    fun `no secret reaches a snapshot, the state or the folder after a completed download`() {
        val res = h.resource(path = "/f/$SECRET_PATH", size = 2 * MIB)
        val run = h.run(secretRequest())

        h.assertCompleted(run, res.source)
        assertSnapshotsClean(run.snapshots)
        assertStateDirClean()
    }

    @Test
    fun `no secret reaches a snapshot or the state after a refused download`() {
        h.resource(path = "/f/$SECRET_PATH", size = 8 * MIB)
        val bodyRequests = AtomicInteger()
        h.server.fault = { info -> if (!info.isProbe && bodyRequests.incrementAndGet() >= 2) Fault.Status(403) else null }
        val run = h.run(secretRequest())

        assertEquals(DownloadFailure.ServerRefused(403), h.assertFailed(run).failure)
        assertSnapshotsClean(run.snapshots)
        assertStateDirClean()
    }

    // endregion
}

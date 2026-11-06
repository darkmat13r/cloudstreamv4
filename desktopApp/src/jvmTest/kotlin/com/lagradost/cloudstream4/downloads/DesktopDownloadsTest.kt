package com.lagradost.cloudstream4.downloads

import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream4.core.http.HttpClientFactory
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleId
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleKind
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleState
import com.lagradost.cloudstream4.feature.downloads.DownloadsContents
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisode
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisodeId
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisodeState
import com.lagradost.cloudstream4.media.download.DownloadOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real downloads from a loopback server into a temporary folder, compared byte for byte. */
class DesktopDownloadsTest {
    private val base: File = Files.createTempDirectory("desktop-downloads").toFile()
    private val root = File(base, "Downloads")
    private val stateFile = File(File(base, "data"), "downloads.json")
    private val server = RangeServer()
    private val hosts = mutableListOf<DesktopDownloads>()

    @AfterTest
    fun cleanUp() = runBlocking {
        hosts.forEach { it.close() }
        server.close()
        base.deleteRecursively()
        Unit
    }

    /** Loopback is a private address: this is the one place it is allowed, and only for these tests. */
    private fun host(maxRunning: Int = 3, stateFile: File = this.stateFile, log: (String) -> Unit = {}) = DesktopDownloads(
        downloadRoot = root,
        stateFile = stateFile,
        httpClient = HttpClientFactory(OkHttpClient()).create(),
        options = DownloadOptions(parts = 1, allowPrivateAddresses = true, backoffBaseMillis = 10, backoffMaxMillis = 50),
        maxRunning = maxRunning,
        log = log,
        ownsClient = true,
    ).also { hosts += it }

    private fun film(id: Int, name: String, url: String, headers: Map<String, String> = emptyMap(), referer: String? = null) =
        DesktopDownloadRequest(
            episodeId = id,
            titleId = id * 100,
            titleName = name,
            kind = DownloadTitleKind.Film,
            season = null,
            episode = null,
            episodeName = null,
            link = DesktopDownloadLink(url, headers, referer),
        )

    private fun episode(id: Int, titleId: Int, season: Int, number: Int, url: String) = DesktopDownloadRequest(
        episodeId = id,
        titleId = titleId,
        titleName = "Show",
        kind = DownloadTitleKind.Series,
        season = season,
        episode = number,
        episodeName = "Episode $number",
        link = DesktopDownloadLink(url),
    )

    private suspend fun DesktopDownloads.awaitContents(what: (DownloadsContents) -> Boolean): DownloadsContents =
        withTimeout(TIMEOUT) { source().contents.first(what) }

    private suspend fun DesktopDownloads.awaitEpisode(titleId: Int, id: Int, what: (DownloadEpisode) -> Boolean): DownloadEpisode =
        withTimeout(TIMEOUT) {
            series(DownloadTitleId(titleId)).contents.first { contents ->
                contents.episodes.firstOrNull { it.id.value == id }?.let(what) == true
            }.episodes.first { it.id.value == id }
        }

    private suspend fun DesktopDownloads.awaitDone(titleId: Int, id: Int) =
        awaitEpisode(titleId, id) { it.state == DownloadEpisodeState.Done }

    @Test
    fun `an enqueued film reaches Done, and its file is there only then`() = runBlocking {
        val body = server.add("film.bin", 512 * 1024)
        server.chunkDelayMillis.set(10)
        val host = host()
        assertEquals(EnqueueResult.Queued, host.enqueue(film(1, "A Film", server.url("film.bin"))))
        assertNull(host.fileOf(DownloadEpisodeId(1)))
        assertNull(host.fileOf(DownloadTitleId(100)))
        host.awaitEpisode(100, 1) { it.state == DownloadEpisodeState.Running }
        assertNull(host.fileOf(DownloadEpisodeId(1)), "a running download has no file")

        host.awaitDone(100, 1)
        val file = assertNotNull(host.fileOf(DownloadEpisodeId(1)))
        assertEquals(File(File(root, "A Film"), "A Film.mp4"), file)
        assertContentEquals(body, file.readBytes())
        assertEquals(file, host.fileOf(DownloadTitleId(100)))
        val title = host.awaitContents { true }.titles.single()
        assertEquals(DownloadTitleState.Done, title.state)
        assertEquals(body.size.toLong(), title.bytesDone)
        assertEquals(EnqueueResult.AlreadyPresent, host.enqueue(film(1, "A Film", server.url("film.bin"))))
    }

    @Test
    fun `four enqueued run at most three at a time`() = runBlocking {
        val bodies = (1..4).map { server.add("f$it.bin", 1024 * 1024) }
        server.chunkDelayMillis.set(10)
        val host = host()
        (1..4).forEach { host.enqueue(film(it, "Film $it", server.url("f$it.bin"))) }

        var maxRunning = 0
        var sawQueueOfFour = false
        val watcher = launch(Dispatchers.Default) {
            host.source().contents.collect { contents ->
                maxRunning = maxOf(maxRunning, contents.queue.running)
                if (contents.queue.total == 4 && contents.queue.running == 3) sawQueueOfFour = true
            }
        }
        host.awaitContents { contents -> contents.titles.size == 4 && contents.titles.all { it.state == DownloadTitleState.Done } }
        watcher.cancelAndJoin()

        assertEquals(3, maxRunning, "never more than three, and three when four are waiting")
        assertTrue(sawQueueOfFour, "three running of four in line")
        (1..4).forEach { assertContentEquals(bodies[it - 1], host.fileOf(DownloadEpisodeId(it))!!.readBytes()) }
    }

    @Test
    fun `a paused download resumes from what is on disk`() = runBlocking {
        val body = server.add("pause.bin", 2 * 1024 * 1024)
        server.chunkDelayMillis.set(10)
        val host = host()
        host.enqueue(film(1, "Paused", server.url("pause.bin")))
        host.awaitEpisode(100, 1) { it.state == DownloadEpisodeState.Running && it.bytesDone >= 256 * 1024 }

        host.pause(DownloadEpisodeId(1))
        val paused = host.awaitEpisode(100, 1) { it.state == DownloadEpisodeState.Paused }
        assertTrue(paused.bytesDone > 0L, "paused bytes are what is on disk: ${paused.bytesDone}")
        assertNull(host.fileOf(DownloadEpisodeId(1)))
        assertEquals(emptyList(), server.resumedFrom("pause.bin"), "nothing resumed yet")

        host.resume(DownloadEpisodeId(1))
        host.awaitDone(100, 1)
        assertContentEquals(body, host.fileOf(DownloadEpisodeId(1))!!.readBytes())
        val resumedAt = server.resumedFrom("pause.bin")
        assertTrue(resumedAt.isNotEmpty() && resumedAt.first() >= paused.bytesDone - 1024 * 1024, "resumed from $resumedAt")
    }

    @Test
    fun `after a restart the same ids are there, paused, and resume completes byte-identical`() = runBlocking {
        val body = server.add("restart.bin", 2 * 1024 * 1024)
        server.chunkDelayMillis.set(10)
        val first = host()
        first.enqueue(episode(7, 70, 1, 3, server.url("restart.bin")))
        first.awaitEpisode(70, 7) { it.state == DownloadEpisodeState.Running && it.bytesDone >= 256 * 1024 }
        first.close()
        hosts.remove(first)

        val second = host()
        val contents = second.awaitContents { it.titles.isNotEmpty() }
        val title = contents.titles.single()
        assertEquals(DownloadTitleId(70), title.id)
        assertEquals(DownloadTitleState.Paused, title.state)
        assertEquals(DownloadTitleKind.Series, title.kind)
        val episode = second.awaitEpisode(70, 7) { true }
        assertEquals(DownloadEpisodeState.Paused, episode.state, "nothing starts on its own after a restart")
        assertEquals(1, episode.season)
        assertEquals(3, episode.episode)
        assertTrue(episode.bytesDone > 0L, "the parts on disk are counted: ${episode.bytesDone}")

        second.resume(DownloadEpisodeId(7))
        second.awaitDone(70, 7)
        val file = second.fileOf(DownloadEpisodeId(7))!!
        assertEquals("S01E03 Episode 3.mp4", file.name)
        assertContentEquals(body, file.readBytes())
        assertTrue(server.resumedFrom("restart.bin").isNotEmpty(), "the second run continued from the parts")
    }

    @Test
    fun `a waiting download can be cancelled`() = runBlocking {
        server.add("long.bin", 2 * 1024 * 1024)
        server.add("waiting.bin", 64 * 1024)
        server.chunkDelayMillis.set(10)
        val host = host(maxRunning = 1)
        host.enqueue(film(1, "Long", server.url("long.bin")))
        host.enqueue(film(2, "Waiting", server.url("waiting.bin")))
        host.awaitEpisode(200, 2) { it.state == DownloadEpisodeState.Queued }

        host.cancel(DownloadEpisodeId(2))
        val contents = host.awaitContents { it.titles.none { title -> title.id == DownloadTitleId(200) } }
        assertEquals(listOf(DownloadTitleId(100)), contents.titles.map { it.id })
        assertEquals(DownloadTitleState.Running, contents.titles.single().state)
        assertFalse(File(root, "Waiting").exists(), "nothing of it on disk")
        assertNull(server.ranges["waiting.bin"], "never fetched")
    }

    @Test
    fun `deleting a title and an episode removes their files and records`() = runBlocking {
        val filmBody = server.add("film.bin", 128 * 1024)
        server.add("e1.bin", 128 * 1024)
        val e2 = server.add("e2.bin", 128 * 1024)
        val host = host()
        host.enqueue(film(1, "Film", server.url("film.bin")))
        host.enqueue(episode(11, 50, 1, 1, server.url("e1.bin")))
        host.enqueue(episode(12, 50, 1, 2, server.url("e2.bin")))
        host.awaitContents { it.titles.size == 2 && it.titles.all { title -> title.state == DownloadTitleState.Done } }
        val filmFile = host.fileOf(DownloadEpisodeId(1))!!
        val e1File = host.fileOf(DownloadEpisodeId(11))!!
        assertContentEquals(filmBody, filmFile.readBytes())

        host.deleteEpisodes(setOf(DownloadEpisodeId(11)))
        assertFalse(e1File.exists())
        assertNull(host.fileOf(DownloadEpisodeId(11)))
        assertContentEquals(e2, host.fileOf(DownloadEpisodeId(12))!!.readBytes())

        host.delete(setOf(DownloadTitleId(100)))
        assertFalse(filmFile.exists())
        assertFalse(filmFile.parentFile.exists(), "the title's empty folder goes too")
        assertTrue(root.isDirectory, "the download root stays")

        val remaining = host.awaitContents { it.titles.size == 1 }
        assertEquals(DownloadTitleId(50), remaining.titles.single().id)
        assertEquals(1, remaining.titles.single().episodes)

        // And the records: a fresh start over the same file sees the same.
        host.close()
        hosts.remove(host)
        val again = host()
        val reread = again.awaitContents { true }
        assertEquals(listOf(DownloadTitleId(50)), reread.titles.map { it.id })
        assertEquals(listOf(DownloadEpisodeId(12)), again.awaitEpisode(50, 12) { true }.let { listOf(it.id) })
        val stored = stateFile.readText()
        assertFalse("\"episodeId\": 1," in stored || "\"episodeId\":1," in stored, "the film's record is gone")
    }

    @Test
    fun `a file deleted outside the app is noticed on refresh`() = runBlocking {
        server.add("gone.bin", 64 * 1024)
        val host = host()
        host.enqueue(film(1, "Gone", server.url("gone.bin")))
        host.awaitDone(100, 1)
        assertTrue(host.fileOf(DownloadEpisodeId(1))!!.delete())

        host.refresh()
        val contents = host.awaitContents { it.titles.isEmpty() }
        assertTrue(contents.titles.isEmpty())
        assertNull(host.fileOf(DownloadEpisodeId(1)))
    }

    @Test
    fun `contents emits at most four times a second while running and never while idle`() = runBlocking {
        server.add("rate.bin", 3 * 1024 * 1024)
        server.chunkDelayMillis.set(10)
        val host = host()
        val stamps = CopyOnWriteArrayList<Pair<Long, Boolean>>()
        val watcher = CoroutineScope(Dispatchers.Default).launch {
            host.source().contents.collect { contents ->
                stamps += System.nanoTime() to (contents.titles.singleOrNull()?.state == DownloadTitleState.Running)
            }
        }
        host.enqueue(film(1, "Rate", server.url("rate.bin")))
        host.awaitDone(100, 1)
        delay(300)
        watcher.cancelAndJoin()

        val running = stamps.filter { it.second }.map { it.first }
        assertTrue(running.size >= 4, "a run of a few seconds is reported more than once: ${running.size}")
        val gaps = stamps.map { it.first }.zipWithNext { a, b -> (b - a) / 1_000_000 }
        assertTrue(gaps.all { it >= 240 }, "emissions are at least 250 ms apart: $gaps")
        val seconds = (running.last() - running.first()) / 1e9
        val perSecond = (running.size - 1) / seconds
        println("contents while running: ${running.size} emissions over ${"%.2f".format(seconds)} s = ${"%.2f".format(perSecond)}/s")
        assertTrue(perSecond <= 4.2, "at most about four a second: $perSecond")

        // Idle: the current value, then nothing.
        val idle = CopyOnWriteArrayList<Long>()
        val idleWatcher = CoroutineScope(Dispatchers.Default).launch {
            host.source().contents.collect { idle += System.nanoTime() }
        }
        delay(1_250)
        idleWatcher.cancelAndJoin()
        assertEquals(1, idle.size, "one emission for the current state, then none over a second idle")
    }

    @Test
    fun `the downloads file is owner-only`() = runBlocking {
        server.add("perm.bin", 16 * 1024)
        val host = host()
        host.enqueue(film(1, "Perm", server.url("perm.bin")))
        host.awaitDone(100, 1)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(stateFile.toPath())))
        assertTrue(base.resolve("data").listFiles()!!.all { it.name == "downloads.json" }, "no staging file left behind")
    }

    @Test
    fun `only VIDEO links over http are taken`() = runBlocking {
        val host = host()
        val hls = film(1, "Hls", server.url("x.m3u8")).let {
            DesktopDownloadRequest(it.episodeId, it.titleId, it.titleName, it.kind, null, null, null,
                DesktopDownloadLink(server.url("x.m3u8"), type = ExtractorLinkType.M3U8))
        }
        assertEquals(EnqueueResult.Refused(EnqueueRefusal.UnsupportedLinkType), host.enqueue(hls))
        listOf(ExtractorLinkType.DASH, ExtractorLinkType.TORRENT, ExtractorLinkType.MAGNET).forEach { type ->
            val request = DesktopDownloadRequest(2, 200, "T", DownloadTitleKind.Film, null, null, null, DesktopDownloadLink(server.url("x"), type = type))
            assertEquals(EnqueueResult.Refused(EnqueueRefusal.UnsupportedLinkType), host.enqueue(request))
        }
        listOf("ftp://example.com/a", "file:///etc/passwd", "https://user:pw@example.com/a", "not a url").forEach { url ->
            assertEquals(EnqueueResult.Refused(EnqueueRefusal.InvalidUrl), host.enqueue(film(3, "Bad", url)), url)
        }
        assertTrue(host.awaitContents { true }.titles.isEmpty())
    }

    @Test
    fun `no URL, header, referer or path reaches a log line or a toString`() = runBlocking {
        val secrets = listOf("SECRETPATH", "SIGSECRET", "HEADERSECRET", "REFSECRET", "UASECRET")
        server.add("SECRETPATH.bin", 64 * 1024)
        val logs = CopyOnWriteArrayList<String>()
        val err = ByteArrayOutputStream()
        val out = ByteArrayOutputStream()
        val oldErr = System.err
        val oldOut = System.out
        System.setErr(PrintStream(err, true))
        System.setOut(PrintStream(out, true))
        try {
            // A state file under a regular file cannot be written: every save logs.
            val blocker = File(base, "blocker").apply { writeText("x") }
            val host = host(stateFile = File(blocker, "downloads.json"), log = { logs += it })
            val link = DesktopDownloadLink(
                url = server.url("SECRETPATH.bin") + "?sig=SIGSECRET",
                headers = mapOf("X-Api-Key" to "HEADERSECRET", "User-Agent" to "UASECRET"),
                referer = "https://referer.example/REFSECRET",
            )
            val request = DesktopDownloadRequest(1, 100, "Secret", DownloadTitleKind.Film, null, null, null, link)
            val missing = film(2, "Missing", server.url("SECRETPATH-missing.bin") + "?sig=SIGSECRET")
            val results = listOf(host.enqueue(request), host.enqueue(missing))
            host.awaitDone(100, 1)
            host.awaitEpisode(200, 2) { it.state == DownloadEpisodeState.Failed }
            val texts = logs + listOf(
                link.toString(), request.toString(), missing.toString(), results.toString(),
                host.awaitContents { true }.toString(),
                host.awaitEpisode(100, 1) { true }.toString(),
                host.awaitEpisode(200, 2) { true }.toString(),
            ) + listOf(err.toString(), out.toString())
            assertTrue(logs.isNotEmpty(), "the failing saves were logged")
            texts.forEach { text ->
                secrets.forEach { secret -> assertFalse(secret in text, "\"$secret\" leaked into: $text") }
                assertFalse(base.path in text, "a path leaked into: $text")
            }
        } finally {
            System.setErr(oldErr)
            System.setOut(oldOut)
        }
    }

    companion object {
        private const val TIMEOUT = 30_000L
    }
}

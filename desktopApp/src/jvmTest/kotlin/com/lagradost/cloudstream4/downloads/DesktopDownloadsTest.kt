package com.lagradost.cloudstream4.downloads

import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream4.core.http.HttpClientFactory
import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleId
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleKind
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleState
import com.lagradost.cloudstream4.feature.downloads.DownloadsContents
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisode
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisodeId
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisodeState
import com.lagradost.cloudstream4.media.download.DownloadOptions
import com.lagradost.cloudstream4.media.model.DownloadFailure
import kotlinx.coroutines.CompletableDeferred
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

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
    private fun host(
        maxRunning: Int = 3,
        stateFile: File = this.stateFile,
        log: (String) -> Unit = {},
        resolver: LinkResolver = LinkResolver { error("no test here expects to be asked") },
        resolveTimeoutMillis: Long = DesktopDownloads.RESOLVE_TIMEOUT_MILLIS,
        volumeSpace: (File) -> VolumeSpace = VolumeSpace::of,
        runningLimit: (() -> Int)? = null,
    ) = DesktopDownloads(
        downloadRoot = root,
        stateFile = stateFile,
        httpClient = HttpClientFactory(OkHttpClient()).create(),
        resolver = resolver,
        options = DownloadOptions(parts = 1, allowPrivateAddresses = true, backoffBaseMillis = 10, backoffMaxMillis = 50),
        maxRunning = maxRunning,
        resolveTimeoutMillis = resolveTimeoutMillis,
        log = log,
        ownsClient = true,
        volumeSpace = volumeSpace,
        runningLimit = runningLimit,
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
            source = sourceOf(id),
            link = DesktopDownloadLink(url, headers, referer),
        )

    private fun sourceOf(id: Int) = DownloadSourceRef("Test Provider", "episode-data-$id")

    private fun episode(id: Int, titleId: Int, season: Int, number: Int, url: String) = DesktopDownloadRequest(
        episodeId = id,
        titleId = titleId,
        titleName = "Show",
        kind = DownloadTitleKind.Series,
        season = season,
        episode = number,
        episodeName = "Episode $number",
        source = sourceOf(id),
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

    /**
     * `KMP-90`: Settings' Downloads at once is read at every start. Raised, the next in line starts at once, without
     * waiting for a finish. Lowered, nothing running stops, and the next in line waits.
     */
    @Test
    fun `a changed Downloads at once applies to the next start`() = runBlocking {
        val bodies = (1..3).map { server.add("limit$it.bin", 4 * 1024 * 1024) }
        server.chunkDelayMillis.set(10)
        val limit = AtomicInteger(1)
        val host = host(runningLimit = limit::get)
        host.enqueue(film(1, "Film 1", server.url("limit1.bin")))
        host.awaitEpisode(100, 1) { it.state == DownloadEpisodeState.Running }
        host.enqueue(film(2, "Film 2", server.url("limit2.bin")))
        host.awaitEpisode(200, 2) { it.state == DownloadEpisodeState.Queued }

        limit.set(2)
        host.limitChanged()
        host.awaitEpisode(200, 2) { it.state == DownloadEpisodeState.Running }
        assertEquals(DownloadEpisodeState.Running, host.awaitEpisode(100, 1) { true }.state, "it started beside the first")

        limit.set(1)
        host.limitChanged()
        host.enqueue(film(3, "Film 3", server.url("limit3.bin")))
        host.awaitEpisode(300, 3) { it.state == DownloadEpisodeState.Queued }
        val both = host.awaitContents { it.titles.size == 3 }
        assertEquals(2, both.queue.running, "a lower limit stops nothing already running")
        assertEquals(DownloadEpisodeState.Queued, host.awaitEpisode(300, 3) { true }.state)

        (1..3).forEach { host.awaitDone(it * 100, it) }
        (1..3).forEach { assertContentEquals(bodies[it - 1], host.fileOf(DownloadEpisodeId(it))!!.readBytes()) }
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

    /** Starts [request] on a first host, stops it part-way, and returns how many bytes its parts held. */
    private suspend fun interruptedPartWay(request: DesktopDownloadRequest): Long {
        val first = host()
        first.enqueue(request)
        first.awaitEpisode(request.titleId, request.episodeId) {
            it.state == DownloadEpisodeState.Running && it.bytesDone >= 256 * 1024
        }
        first.close()
        hosts.remove(first)
        return partFiles().sumOf { it.length() }
    }

    /** The engine's part files under the download root. */
    private fun partFiles(): List<File> =
        root.walkTopDown().filter { it.isFile && it.parentFile.name.endsWith(".csdl") && it.name.startsWith("part-") }.toList()

    @Test
    fun `after a restart the same ids are there, paused, and resume asks for a fresh link and completes byte-identical`() =
        runBlocking {
            val body = server.add("restart.bin", 2 * 1024 * 1024)
            server.alias("restart-fresh.bin", "restart.bin")
            server.chunkDelayMillis.set(10)
            val held = interruptedPartWay(episode(7, 70, 1, 3, server.url("restart.bin")))
            assertTrue(held > 0L, "parts on disk after the first host stopped")

            val asked = CopyOnWriteArrayList<DownloadSourceRef>()
            val second = host(resolver = { ref ->
                asked += ref
                listOf(
                    DesktopDownloadLink(server.url("restart.m3u8"), type = ExtractorLinkType.M3U8),
                    DesktopDownloadLink(server.url("restart-fresh.bin")),
                )
            })
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
            assertTrue(asked.isEmpty(), "nothing asked before a resume")

            second.resume(DownloadEpisodeId(7))
            second.awaitDone(70, 7)
            assertEquals(listOf(sourceOf(7)), asked, "asked once, with the stored provider and episode data")
            val file = second.fileOf(DownloadEpisodeId(7))!!
            assertEquals("S01E03 Episode 3.mp4", file.name)
            assertContentEquals(body, file.readBytes())
            val resumedAt = server.resumedFrom("restart-fresh.bin")
            assertTrue(resumedAt.isNotEmpty() && resumedAt.first() >= held - 1024 * 1024, "the fresh link resumed from the parts: $resumedAt, held $held")
            assertNull(server.ranges["restart.m3u8"], "the HLS link was skipped")
        }

    @Test
    fun `after a restart a resolver with no VIDEO link fails the episode and keeps its parts`() = runBlocking {
        server.add("nolink.bin", 2 * 1024 * 1024)
        server.chunkDelayMillis.set(10)
        val held = interruptedPartWay(film(8, "No Link", server.url("nolink.bin")))
        assertTrue(held > 0L)

        // A failure is stored, so each new host below loads the previous step's (Failed, NoUsableLink). A reader
        // started too early could see that stored state and take it for the new answer (BUG-56). Before BUG-57 the
        // resume started the run before it published Running, so "the resolver was asked" alone was not enough. It now
        // publishes Running first, but the test does not rely on that order. So each step gates its resolver: it waits
        // until the episode shows Running, which only a publish after the resume shows, and only then lets the
        // resolver answer. A Failed after that can only be this step's.
        suspend fun step(resolve: () -> List<DesktopDownloadLink>): DownloadEpisode {
            val gate = CompletableDeferred<Unit>()
            val stepHost = host(resolver = { gate.await(); resolve() })
            val loaded = stepHost.awaitEpisode(800, 8) { true }
            assertTrue(loaded.state == DownloadEpisodeState.Paused || loaded.state == DownloadEpisodeState.Failed)
            stepHost.resume(DownloadEpisodeId(8))
            stepHost.awaitEpisode(800, 8) { it.state == DownloadEpisodeState.Running }
            gate.complete(Unit)
            val failed = stepHost.awaitEpisode(800, 8) { it.state == DownloadEpisodeState.Failed }
            stepHost.close()
            hosts.remove(stepHost)
            return failed
        }

        val none = step { emptyList() }
        assertEquals(DownloadFailure.NoUsableLink, none.failure)
        assertEquals(held, partFiles().sumOf { it.length() }, "the parts are untouched")
        assertTrue(none.bytesDone > 0L)

        val hls = step { listOf(DesktopDownloadLink(server.url("x.m3u8"), type = ExtractorLinkType.M3U8)) }
        assertEquals(DownloadFailure.NoUsableLink, hls.failure)

        val threw = step { throw java.io.IOException("the extension is not loaded") }
        assertEquals(DownloadFailure.SourceFailed, threw.failure)
        assertEquals(held, partFiles().sumOf { it.length() }, "the parts are untouched")
        assertEquals(1, partFiles().size)
    }

    @Test
    fun `closing while a resolve is in flight leaves the stored record as it was`() = runBlocking {
        server.add("inflight.bin", 2 * 1024 * 1024)
        server.chunkDelayMillis.set(10)
        val held = interruptedPartWay(film(15, "In Flight", server.url("inflight.bin")))
        assertTrue(held > 0L)

        val asked = java.util.concurrent.CountDownLatch(1)
        val host = host(resolver = { asked.countDown(); kotlinx.coroutines.awaitCancellation() })
        assertEquals(DownloadEpisodeState.Paused, host.awaitEpisode(1500, 15) { true }.state)
        val before = stateFile.readBytes()
        host.resume(DownloadEpisodeId(15))
        assertTrue(asked.await(10, java.util.concurrent.TimeUnit.SECONDS), "the resolver was reached")
        // The run can reach the resolver before the queue has written its move into line: wait for that write.
        withTimeout(TIMEOUT) { while ("\"state\":\"Running\"" !in stateFile.readText()) delay(10) }
        val during = stateFile.readBytes().toString(Charsets.UTF_8)
        host.close()
        hosts.remove(host)

        // The queue wrote the move into line (Queued, then Running); close must write nothing after that.
        val after = stateFile.readBytes().toString(Charsets.UTF_8)
        assertEquals(during, after, "close() rewrote the downloads file")
        // "failure" is always written, as null when there is none: a reason is a string value.
        listOf("\"state\":\"Failed\"", "\"failure\":\"").forEach { assertFalse(it in after, "$it after close") }
        val record = { text: String -> Regex("\\{[^{}]*\"episodeId\":15,[^{}]*\\}").find(text)!!.value }
        val bytes = { text: String -> Regex("\"bytesDone\":(\\d+)").find(record(text))!!.groupValues[1] }
        assertEquals(bytes(before.toString(Charsets.UTF_8)), bytes(after), "bytes unchanged")
        assertEquals(held, partFiles().sumOf { it.length() }, "the parts are untouched")

        val again = host()
        val reread = again.awaitEpisode(1500, 15) { true }
        assertEquals(DownloadEpisodeState.Paused, reread.state, "a shutdown is not a failure")
        assertNull(reread.failure)
    }

    @Test
    fun `a resolver that never answers times out as SourceFailed and keeps the parts`() = runBlocking {
        server.add("silent.bin", 2 * 1024 * 1024)
        server.chunkDelayMillis.set(10)
        val held = interruptedPartWay(film(10, "Silent", server.url("silent.bin")))
        assertTrue(held > 0L)

        val asked = java.util.concurrent.atomic.AtomicInteger()
        val host = host(resolveTimeoutMillis = 300, resolver = { asked.incrementAndGet(); kotlinx.coroutines.awaitCancellation() })
        val started = System.nanoTime()
        host.resume(DownloadEpisodeId(10))
        val failed = host.awaitEpisode(1000, 10) { it.state == DownloadEpisodeState.Failed }
        val tookMillis = (System.nanoTime() - started) / 1_000_000
        assertEquals(DownloadFailure.SourceFailed, failed.failure)
        assertEquals(1, asked.get())
        assertTrue(tookMillis in 300..10_000, "failed by the timeout, after $tookMillis ms")
        assertEquals(held, partFiles().sumOf { it.length() }, "the parts are untouched")
        assertTrue(failed.bytesDone > 0L)
    }

    @Test
    fun `a resolver that blocks its thread times out as SourceFailed, keeps the parts and leaves no thread stuck`() =
        runBlocking {
            server.add("blocked.bin", 2 * 1024 * 1024)
            server.chunkDelayMillis.set(10)
            val held = interruptedPartWay(film(11, "Blocked", server.url("blocked.bin")))
            assertTrue(held > 0L)

            val never = java.util.concurrent.CountDownLatch(1)
            val blockedThread = java.util.concurrent.atomic.AtomicReference<Thread>()
            val released = java.util.concurrent.CountDownLatch(1)
            val host = host(resolveTimeoutMillis = 300, resolver = {
                blockedThread.set(Thread.currentThread())
                try {
                    never.await() // blocks the thread; never counted down
                    emptyList()
                } finally {
                    released.countDown()
                }
            })
            val started = System.nanoTime()
            host.resume(DownloadEpisodeId(11))
            val failed = host.awaitEpisode(1100, 11) { it.state == DownloadEpisodeState.Failed }
            val tookMillis = (System.nanoTime() - started) / 1_000_000
            assertEquals(DownloadFailure.SourceFailed, failed.failure)
            assertTrue(tookMillis in 300..10_000, "failed by the timeout, after $tookMillis ms")
            assertTrue(released.await(5, java.util.concurrent.TimeUnit.SECONDS), "the blocked resolver was interrupted")
            val thread = assertNotNull(blockedThread.get())
            val deadline = System.nanoTime() + 5_000_000_000L
            while (thread.state == Thread.State.WAITING && "await" in thread.stackTrace.joinToString { it.methodName } &&
                System.nanoTime() < deadline
            ) {
                Thread.sleep(20)
            }
            assertFalse(thread.stackTrace.any { it.className.startsWith("java.util.concurrent.CountDownLatch") }, "still blocked")
            assertEquals(held, partFiles().sumOf { it.length() }, "the parts are untouched")
        }

    @Test
    fun `a failure keeps its reason across a restart, and a retry then completes`() = runBlocking {
        val body = server.add("reason.bin", 2 * 1024 * 1024)
        server.chunkDelayMillis.set(10)
        val held = interruptedPartWay(film(12, "Reason", server.url("reason.bin")))
        assertTrue(held > 0L)

        val empty = host(resolver = { emptyList() })
        empty.resume(DownloadEpisodeId(12))
        assertEquals(DownloadFailure.NoUsableLink, empty.awaitEpisode(1200, 12) { it.state == DownloadEpisodeState.Failed }.failure)
        empty.close()
        hosts.remove(empty)

        server.chunkDelayMillis.set(0)
        val asked = java.util.concurrent.atomic.AtomicInteger()
        val again = host(resolver = { asked.incrementAndGet(); listOf(DesktopDownloadLink(server.url("reason.bin"))) })
        val reread = again.awaitEpisode(1200, 12) { true }
        assertEquals(DownloadEpisodeState.Failed, reread.state, "a failure is still one after a restart")
        assertEquals(DownloadFailure.NoUsableLink, reread.failure)
        assertEquals(DownloadTitleState.Failed, again.awaitContents { it.titles.isNotEmpty() }.titles.single().state)
        assertEquals(0, asked.get(), "nothing asked before the retry")

        again.retry(DownloadEpisodeId(12))
        again.awaitDone(1200, 12)
        assertEquals(1, asked.get())
        assertContentEquals(body, again.fileOf(DownloadEpisodeId(12))!!.readBytes())
    }

    @Test
    fun `the poster kept with a download is the row's and the series page's, and survives a restart (KMP-99)`() = runBlocking {
        server.add("poster-film.bin", 64 * 1024)
        server.add("poster-episode.bin", 64 * 1024)
        val filmPoster = "https://img.example/posters/film.jpg"
        val seriesPoster = "https://img.example/posters/show.jpg"
        val first = host()
        first.enqueue(
            DesktopDownloadRequest(
                episodeId = 31, titleId = 3100, titleName = "Pictured", kind = DownloadTitleKind.Film,
                season = null, episode = null, episodeName = null, source = sourceOf(31),
                link = DesktopDownloadLink(server.url("poster-film.bin")), posterUrl = filmPoster,
            ),
        )
        first.enqueue(
            DesktopDownloadRequest(
                episodeId = 32, titleId = 3200, titleName = "Show", kind = DownloadTitleKind.Series,
                season = 1, episode = 1, episodeName = "Episode 1", source = sourceOf(32),
                link = DesktopDownloadLink(server.url("poster-episode.bin")), posterUrl = seriesPoster,
            ),
        )
        first.awaitDone(3100, 31)
        first.awaitDone(3200, 32)
        val posters = first.awaitContents { it.titles.size == 2 }.titles.associate { it.id to it.posterUrl }
        assertEquals(mapOf(DownloadTitleId(3100) to filmPoster, DownloadTitleId(3200) to seriesPoster), posters)
        assertEquals(seriesPoster, first.series(DownloadTitleId(3200)).contents.first().posterUrl, "D4's header")
        assertTrue(filmPoster in stateFile.readText(), "kept in the file once Done, as the name is")
        first.close()

        val second = host()
        val again = second.awaitContents { it.titles.size == 2 }.titles.associate { it.id to it.posterUrl }
        assertEquals(posters, again, "read back after a restart")
        assertEquals(seriesPoster, second.series(DownloadTitleId(3200)).contents.first().posterUrl)
    }

    @Test
    fun `a poster with userinfo is never written, and one already in the file is dropped on reading (BUG-78)`() = runBlocking {
        server.add("userinfo.bin", 64 * 1024)
        val clean = "https://img.example/posters/clean.jpg"
        val first = host()
        first.enqueue(
            DesktopDownloadRequest(
                episodeId = 41, titleId = 4100, titleName = "Credentialed", kind = DownloadTitleKind.Film,
                season = null, episode = null, episodeName = null, source = sourceOf(41),
                link = DesktopDownloadLink(server.url("userinfo.bin")), posterUrl = "https://user:secret@img.example/a.jpg",
            ),
        )
        first.awaitDone(4100, 41)
        assertNull(first.awaitContents { it.titles.size == 1 }.titles.single().posterUrl)
        assertFalse("secret" in stateFile.readText(), "the credential never reaches the file")
        first.close()

        // A file from before the rule, or edited by hand: the same field with a credential in it.
        val withClean = stateFile.readText().replace("\"posterUrl\":null", "\"posterUrl\":\"$clean\"")
        assertTrue(clean in withClean, "the poster field is in the file to edit")
        stateFile.writeText(withClean.replace(clean, "https://user:secret@img.example/a.jpg"))
        val second = host()
        assertNull(second.awaitContents { it.titles.size == 1 }.titles.single().posterUrl, "dropped on reading")
        second.close()

        stateFile.writeText(withClean)
        val third = host()
        assertEquals(clean, third.awaitContents { it.titles.size == 1 }.titles.single().posterUrl, "the control: a clean one reads")
    }

    @Test
    fun `a poster with userinfo is refused however it is spelled, and an at sign in the path is not userinfo (BUG-78)`() {
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
    fun `only a plain http(s) poster is kept, and none is fine (KMP-99)`() {
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
    fun `a finished download keeps no source in the file, survives a restart, and deletes cleanly`() = runBlocking {
        val body = server.add("clear.bin", 256 * 1024)
        val providerMarker = "PROVIDERMARKER9Z"
        val dataMarker = "EPISODEDATAMARKER9Z"
        val request = DesktopDownloadRequest(
            episodeId = 13, titleId = 1300, titleName = "Cleared", kind = DownloadTitleKind.Film,
            season = null, episode = null, episodeName = null,
            source = DownloadSourceRef(providerMarker, "https://provider.example/$dataMarker"),
            link = DesktopDownloadLink(server.url("clear.bin")),
        )
        server.add("waiting-clear.bin", 1024 * 1024) // about 3 s at the pace below, so the pause lands mid-run
        val host = host()
        // A second, unfinished episode, so the file is shown to keep a source where one is still needed.
        host.enqueue(request)
        host.awaitDone(1300, 13)
        val paused = DesktopDownloadRequest(14, 1400, "Kept", DownloadTitleKind.Film, null, null, null,
            DownloadSourceRef("KeptProvider", "kept-data-14"), DesktopDownloadLink(server.url("waiting-clear.bin")))
        server.chunkDelayMillis.set(50)
        host.enqueue(paused)
        host.awaitEpisode(1400, 14) { it.state == DownloadEpisodeState.Running }
        host.pause(DownloadEpisodeId(14))
        host.awaitEpisode(1400, 14) { it.state == DownloadEpisodeState.Paused }

        // (a) The raw bytes: no trace of the finished one's source, and its record is there.
        val raw = stateFile.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(providerMarker in raw, "the provider of a finished episode is gone")
        assertFalse(dataMarker in raw, "the episode data of a finished episode is gone")
        assertTrue("\"episodeId\":13" in raw && "\"state\":\"Done\"" in raw && "Cleared.mp4" in raw, "its record stays")
        assertTrue("kept-data-14" in raw && "KeptProvider" in raw, "an unfinished one keeps its source")
        stateFile.parentFile.listFiles()!!.forEach { file ->
            val text = file.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(providerMarker in text || dataMarker in text, "a marker is in ${file.name}")
        }
        // Resume and retry on a finished episode do nothing.
        host.resume(DownloadEpisodeId(13))
        host.retry(DownloadEpisodeId(13))
        assertEquals(EnqueueResult.AlreadyPresent, host.enqueue(request))
        host.refresh()
        assertEquals(DownloadEpisodeState.Done, host.awaitEpisode(1300, 13) { true }.state)
        host.close()
        hosts.remove(host)

        // (b) A restart: still listed, still Done, still its file.
        val again = host()
        val reread = again.awaitContents { it.titles.size == 2 }
        assertEquals(DownloadTitleState.Done, reread.titles.first { it.id == DownloadTitleId(1300) }.state)
        assertEquals(DownloadEpisodeState.Done, again.awaitEpisode(1300, 13) { true }.state)
        assertContentEquals(body, assertNotNull(again.fileOf(DownloadEpisodeId(13))).readBytes())
        assertEquals(DownloadEpisodeState.Paused, again.awaitEpisode(1400, 14) { true }.state)
        val rewritten = stateFile.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(providerMarker in rewritten || dataMarker in rewritten)

        // (c) Delete: the record goes.
        again.delete(setOf(DownloadTitleId(1300)))
        assertNull(again.fileOf(DownloadEpisodeId(13)))
        assertTrue(again.awaitContents { true }.titles.none { it.id == DownloadTitleId(1300) })
        assertFalse("\"episodeId\":13," in stateFile.readText(), "the record is gone from the file")
    }

    @Test
    fun `every failure round-trips through the stored form, with numbers only`() {
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
     * BUG-57: a retry is published as Running before its run starts. Otherwise a reader opened while the run asks the
     * extension sees the old failure, and the screen reads as the retry failing at once. The resolver itself reads
     * the published state, so the check runs exactly while the run is going.
     */
    @Test
    fun `a retry is published as running before its run asks the extension`() = runBlocking {
        server.add("retried.bin", 64 * 1024)
        lateinit var host: DesktopDownloads
        val seen = CopyOnWriteArrayList<DownloadEpisode>()
        host = host(resolver = {
            seen += host.series(DownloadTitleId(1600)).contents.first().episodes.single()
            listOf(DesktopDownloadLink(server.url("retried.bin")))
        })
        host.enqueue(film(16, "Retried", server.url("retried-missing.bin")))
        assertEquals(DownloadFailure.ServerRefused(404), host.awaitEpisode(1600, 16) { it.state == DownloadEpisodeState.Failed }.failure)

        host.retry(DownloadEpisodeId(16))
        host.awaitDone(1600, 16)
        val atAsk = seen.single()
        assertEquals(DownloadEpisodeState.Running, atAsk.state, "what a reader saw while the retry asked: ${atAsk.state}")
        assertNull(atAsk.failure, "the old failure is gone")
    }

    @Test
    fun `a retry asks the extension again, a resume of a pause does not`() = runBlocking {
        val body = server.add("retry.bin", 1024 * 1024)
        server.chunkDelayMillis.set(5)
        val asked = CopyOnWriteArrayList<DownloadSourceRef>()
        val host = host(resolver = { ref -> asked += ref; listOf(DesktopDownloadLink(server.url("retry.bin"))) })
        host.enqueue(film(9, "Retry", server.url("retry-missing.bin")))
        val failed = host.awaitEpisode(900, 9) { it.state == DownloadEpisodeState.Failed }
        assertEquals(DownloadFailure.ServerRefused(404), failed.failure)
        assertTrue(asked.isEmpty(), "the enqueued link was used first")

        // BUG-67: on a fast machine the retried body finished inside one throttled snapshot, so no snapshot showed
        // Running with bytes and the wait timed out. The server now holds the retried body after its first chunk until
        // the pause has landed, so a snapshot with bytes done must appear.
        val held = server.holdAfterFirstChunk("retry.bin")
        host.retry(DownloadEpisodeId(9))
        host.awaitEpisode(900, 9) { it.state == DownloadEpisodeState.Running && it.bytesDone > 0L }
        host.pause(DownloadEpisodeId(9))
        host.awaitEpisode(900, 9) { it.state == DownloadEpisodeState.Paused }
        held.complete(Unit)
        host.resume(DownloadEpisodeId(9))
        host.awaitDone(900, 9)
        assertEquals(listOf(sourceOf(9)), asked, "asked once, by the retry; the resume reused the link in memory")
        assertContentEquals(body, host.fileOf(DownloadEpisodeId(9))!!.readBytes())
    }

    @Test
    fun `no link text ever reaches the downloads file or the engine's state`() = runBlocking {
        val markers = listOf("URLMARKER7Q", "HEADERMARKER7Q", "REFERERMARKER7Q", "AGENTMARKER7Q")
        server.add("blocker.bin", 2 * 1024 * 1024)
        // About 4 s at this pace, so it is still running after the engine's 2 s state-file interval.
        server.add("marked.bin", 6 * 1024 * 1024)
        server.chunkDelayMillis.set(10)
        val dataDir = stateFile.parentFile

        // A pass reads every file listed when it began. The engine renames its state file into place while it runs,
        // so a listed file can be gone by the time it is read (BUG-53). That pass is then thrown away and the whole
        // scan starts again. No file is skipped: the scan only passes once it has read every file it listed.
        fun scan(stage: String, state: String) {
            val written = stateFile.readBytes().toString(Charsets.ISO_8859_1)
            assertTrue("\"state\":\"$state\"" in written, "$stage: the file was written with $state")
            repeat(SCAN_ATTEMPTS) {
                val files = dataDir.listFiles()!!.filter { it.isFile } + root.walkTopDown().filter { it.isFile }
                val texts = files.map { file ->
                    try {
                        file.name to file.readBytes().toString(Charsets.ISO_8859_1)
                    } catch (_: java.io.FileNotFoundException) {
                        null
                    }
                }
                if (null !in texts) {
                    texts.filterNotNull().forEach { (name, text) ->
                        markers.forEach { marker -> assertFalse(marker in text, "$stage: $marker is in $name") }
                    }
                    return
                }
            }
            fail("$stage: files kept vanishing mid-scan in $SCAN_ATTEMPTS passes")
        }

        fun marked(id: Int, path: String) = film(
            id, "Marked $id", server.url(path) + "?token=URLMARKER7Q",
            headers = mapOf("X-Key" to "HEADERMARKER7Q", "User-Agent" to "AGENTMARKER7Q"),
            referer = "https://referer.example/REFERERMARKER7Q",
        )

        val host = host(maxRunning = 1)
        host.enqueue(film(1, "Blocker", server.url("blocker.bin")))
        host.awaitEpisode(100, 1) { it.state == DownloadEpisodeState.Running }
        host.enqueue(marked(2, "marked.bin"))
        host.awaitEpisode(200, 2) { it.state == DownloadEpisodeState.Queued }
        scan("queued", "Queued")

        host.pause(DownloadEpisodeId(1))
        host.awaitEpisode(200, 2) { it.state == DownloadEpisodeState.Running && it.bytesDone > 0L }
        scan("running", "Running")
        delay(2_300) // past the engine's 2 s state-file interval, so its state file has been rewritten mid-run
        scan("running, later", "Running")

        host.pause(DownloadEpisodeId(2))
        host.awaitEpisode(200, 2) { it.state == DownloadEpisodeState.Paused }
        scan("paused", "Paused")

        host.resume(DownloadEpisodeId(2))
        host.awaitDone(200, 2)
        scan("done", "Done")

        host.enqueue(marked(3, "marked-missing.bin"))
        host.awaitEpisode(300, 3) { it.state == DownloadEpisodeState.Failed }
        scan("failed", "Failed")
        val kept = stateFile.readText()
        assertTrue("episode-data-3" in kept, "a failed episode keeps the source to ask again")
        assertFalse("episode-data-2" in kept, "a finished one keeps none")
    }

    /** `KMP-71`: a title or episode name that is a link with a token never reaches the file, a folder or a file name. */
    @Test
    fun `a title or episode name that is an address names nothing and is not stored`() = runBlocking {
        val secrets = listOf(DownloadPathsTest.SECRET_PATH, DownloadPathsTest.SECRET_TOKEN)
        val body = server.add("named.bin", 64 * 1024)
        val address = "cdn.host.to/v/${DownloadPathsTest.SECRET_PATH}?token=${DownloadPathsTest.SECRET_TOKEN}"
        val request = DesktopDownloadRequest(
            episodeId = 15, titleId = 1500, titleName = "https://cdn.example.invalid/${DownloadPathsTest.SECRET_PATH}",
            kind = DownloadTitleKind.Series, season = 1, episode = 5, episodeName = address,
            source = sourceOf(15), link = DesktopDownloadLink(server.url("named.bin")),
        )
        val host = host()
        assertEquals(EnqueueResult.Queued, host.enqueue(request))
        host.awaitDone(1500, 15)

        assertEquals(File(File(root, "Download"), "S01E05.mp4"), host.fileOf(DownloadEpisodeId(15)))
        assertContentEquals(body, host.fileOf(DownloadEpisodeId(15))!!.readBytes())
        assertEquals("Download", host.awaitContents { true }.titles.single().name)
        assertNull(host.awaitEpisode(1500, 15) { true }.name, "listed by its code")
        fun scan(stage: String) {
            val files = stateFile.parentFile.listFiles()!!.filter { it.isFile } + root.walkTopDown().filter { it.isFile }
            files.forEach { file ->
                val text = file.readBytes().toString(Charsets.ISO_8859_1)
                secrets.forEach { assertFalse(it in text, "$stage: $it is in ${file.name}") }
            }
            root.walkTopDown().forEach { path ->
                secrets.forEach { assertFalse(it in path.name, "$stage: $it names ${path.name}") }
            }
        }
        scan("done")
        host.close()
        hosts.remove(host)

        val again = host()
        assertEquals("Download", again.awaitContents { it.titles.size == 1 }.titles.single().name)
        scan("after a restart")
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

    /** D4 sends Cancel for a running download too: it stops, and nothing of it stays on disk or in the file. */
    @Test
    fun `a running download can be cancelled and leaves nothing`() = runBlocking {
        server.add("running.bin", 2 * 1024 * 1024)
        server.chunkDelayMillis.set(10)
        val host = host()
        host.enqueue(film(1, "Running", server.url("running.bin")))
        host.awaitEpisode(100, 1) { it.state == DownloadEpisodeState.Running && it.bytesDone >= 256 * 1024 }
        assertTrue(partFiles().isNotEmpty(), "parts exist before the cancel")

        host.cancel(DownloadEpisodeId(1))
        host.awaitContents { it.titles.isEmpty() }
        assertNull(host.fileOf(DownloadEpisodeId(1)))
        assertEquals(emptyList(), partFiles(), "no part file kept")
        assertFalse(File(root, "Running").walkTopDown().any { it.isFile }, "no file kept")
        assertFalse("Running" in stateFile.readText(), "no record kept")
        val requestsAfter = server.ranges["running.bin"]?.size ?: 0
        delay(500)
        assertEquals(requestsAfter, server.ranges["running.bin"]?.size ?: 0, "no request after the cancel")
    }

    /** A stale Cancel from the UI must never discard a finished file: only delete removes one. */
    @Test
    fun `cancelling a finished download keeps its file and its record`() = runBlocking {
        val body = server.add("done.bin", 64 * 1024)
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val host = host(log = { logs += it })
        host.enqueue(film(1, "Finished", server.url("done.bin")))
        host.awaitDone(100, 1)
        val file = host.fileOf(DownloadEpisodeId(1))!!

        host.cancel(DownloadEpisodeId(1))
        host.refresh()
        val after = host.awaitEpisode(100, 1) { true }
        assertEquals(DownloadEpisodeState.Done, after.state)
        assertContentEquals(body, file.readBytes())
        assertEquals(file, host.fileOf(DownloadEpisodeId(1)))
        assertTrue("Finished" in stateFile.readText(), "the record is kept")
        kotlinx.coroutines.withTimeout(5_000) { while ("cancel refused for a finished download" !in logs) delay(20) }
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
        // BUG-102: the read's own end, not the first view. A new host's first view can be built before its load is
        // published, and is then empty.
        val reread = again.awaitContents { it.titles.isNotEmpty() }
        assertEquals(listOf(DownloadTitleId(50)), reread.titles.map { it.id })
        assertEquals(listOf(DownloadEpisodeId(12)), again.awaitEpisode(50, 12) { true }.let { listOf(it.id) })
        val stored = stateFile.readText()
        assertFalse("\"episodeId\": 1," in stored || "\"episodeId\":1," in stored, "the film's record is gone")
    }

    /** D4's "12.40 GB FREE" is the titles page's own figure for the same volume, never absent once the disk is read. */
    @Test
    fun `a series page reports the same free bytes as the titles page`() = runBlocking {
        server.add("s1e1.bin", 64 * 1024)
        // A fixed volume, so other processes writing to the real disk cannot move the figure between reads (BUG-53).
        val volume = VolumeSpace(totalBytes = 1_000L * GIB, usableBytes = 400L * GIB)
        val host = host(volumeSpace = { volume })
        host.enqueue(episode(21, 70, 1, 1, server.url("s1e1.bin")))
        host.awaitDone(70, 21)

        val storage = assertNotNull(host.source().contents.first().storage)
        val series = host.series(DownloadTitleId(70)).contents.first()
        assertEquals(1_000L * GIB, storage.totalBytes)
        assertEquals(storage.totalBytes - storage.usedBytes, series.freeBytes)
        assertEquals(400L * GIB, series.freeBytes, "both pages read the injected volume")
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
            DesktopDownloadRequest(it.episodeId, it.titleId, it.titleName, it.kind, null, null, null, sourceOf(1),
                DesktopDownloadLink(server.url("x.m3u8"), type = ExtractorLinkType.M3U8))
        }
        assertEquals(EnqueueResult.Refused(EnqueueRefusal.UnsupportedLinkType), host.enqueue(hls))
        listOf(ExtractorLinkType.DASH, ExtractorLinkType.TORRENT, ExtractorLinkType.MAGNET).forEach { type ->
            val request = DesktopDownloadRequest(2, 200, "T", DownloadTitleKind.Film, null, null, null, sourceOf(2), DesktopDownloadLink(server.url("x"), type = type))
            assertEquals(EnqueueResult.Refused(EnqueueRefusal.UnsupportedLinkType), host.enqueue(request))
        }
        listOf("ftp://example.com/a", "file:///etc/passwd", "https://user:pw@example.com/a", "not a url").forEach { url ->
            assertEquals(EnqueueResult.Refused(EnqueueRefusal.InvalidUrl), host.enqueue(film(3, "Bad", url)), url)
        }
        val noSource = DesktopDownloadRequest(4, 400, "T", DownloadTitleKind.Film, null, null, null,
            DownloadSourceRef("Test Provider", " "), DesktopDownloadLink(server.url("x")))
        assertEquals(EnqueueResult.Refused(EnqueueRefusal.NoSource), host.enqueue(noSource))
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
            val request = DesktopDownloadRequest(1, 100, "Secret", DownloadTitleKind.Film, null, null, null, sourceOf(1), link)
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

    /** `KMP-109`: ids are 32 bits of a hash, so a second episode can arrive with the first one's id. */
    @Test
    fun `an id another episode holds is refused, and the first episode is left as it was`() = runBlocking {
        server.add("held.bin", 1024 * 1024)
        server.chunkDelayMillis.set(50)
        val host = host()
        val first = DesktopDownloadRequest(21, 2100, "First", DownloadTitleKind.Film, null, null, null,
            DownloadSourceRef("Test Provider", "first-data"), DesktopDownloadLink(server.url("held.bin")))
        assertEquals(EnqueueResult.Queued, host.enqueue(first))
        host.awaitEpisode(2100, 21) { it.state == DownloadEpisodeState.Running }

        val other = DesktopDownloadRequest(21, 2200, "Other", DownloadTitleKind.Film, null, null, null,
            DownloadSourceRef("Test Provider", "other-data"), DesktopDownloadLink(server.url("held.bin")))
        assertEquals(EnqueueResult.Refused(EnqueueRefusal.IdTaken), host.enqueue(other))
        val otherProvider = DesktopDownloadRequest(21, 2100, "First", DownloadTitleKind.Film, null, null, null,
            DownloadSourceRef("Another Provider", "first-data"), null)
        assertEquals(EnqueueResult.Refused(EnqueueRefusal.IdTaken), host.enqueue(otherProvider))
        assertEquals(EnqueueResult.AlreadyPresent, host.enqueue(first), "the same episode again is still itself")

        val titles = host.awaitContents { true }.titles
        assertEquals(listOf(DownloadTitleId(2100)), titles.map { it.id })
        assertEquals("First", titles.single().name)

        // Paused, it would otherwise be put back in line by an enqueue: the other episode must not resume it.
        host.pause(DownloadEpisodeId(21))
        host.awaitEpisode(2100, 21) { it.state == DownloadEpisodeState.Paused }
        assertEquals(EnqueueResult.Refused(EnqueueRefusal.IdTaken), host.enqueue(other))
        assertEquals(DownloadEpisodeState.Paused, host.awaitEpisode(2100, 21) { true }.state)
    }

    /**
     * `KMP-109`: a plugin chooses its title-id strings, and 32 bits fall in minutes. A title id forced to collide, through
     * the request's own id seam, is refused for another title, whether the first is running or finished, and taken
     * for the same title's next episode.
     */
    @Test
    fun `a title id another title holds is refused, running or finished`() = runBlocking {
        server.add("victim.bin", 64 * 1024)
        val forced = { _: String -> FORCED_TITLE_ID }
        fun page(title: String, provider: String) = TitleDetail(
            title = title, providerName = provider, typeName = "TvSeries", year = null, plot = null,
            posterUrl = null, playData = null, episodes = emptyList(),
        )
        fun episodeOf(n: Int, data: String) = DetailEpisode(name = "Part $n", data = data, season = 1, episode = n)
        val host = host(resolver = LinkResolver { listOf(DesktopDownloadLink(server.url("victim.bin"))) })

        val victim = episodeDownloadRequest("Kept Provider\u0000show", page("Kept Show", "Kept Provider"),
            episodeOf(1, "kept-1"), titleIdOf = forced)
        assertEquals(EnqueueResult.Queued, host.enqueue(victim))
        val renamed = episodeDownloadRequest("Hostile Provider\u0000x", page("Hostile Show", "Hostile Provider"),
            episodeOf(1, "hostile-1"), titleIdOf = forced)
        val sameName = episodeDownloadRequest("Hostile Provider\u0000y", page("Kept Show", "Hostile Provider"),
            episodeOf(1, "hostile-2"), titleIdOf = forced)
        assertEquals(EnqueueResult.Refused(EnqueueRefusal.IdTaken), host.enqueue(renamed), "another name")
        assertEquals(EnqueueResult.Refused(EnqueueRefusal.IdTaken), host.enqueue(sameName), "another provider")

        host.awaitDone(FORCED_TITLE_ID, victim.episodeId)
        assertEquals(EnqueueResult.Refused(EnqueueRefusal.IdTaken), host.enqueue(renamed), "a finished title keeps its name")

        val next = episodeDownloadRequest("Kept Provider\u0000show", page("Kept Show", "Kept Provider"),
            episodeOf(2, "kept-2"), titleIdOf = forced)
        assertEquals(EnqueueResult.Queued, host.enqueue(next), "the same title's next episode")
        host.awaitDone(FORCED_TITLE_ID, next.episodeId)

        val titles = host.awaitContents { true }.titles
        assertEquals(listOf("Kept Show"), titles.map { it.name })
        assertEquals(listOf("Kept Show"), root.listFiles()!!.map { it.name }, "no other title's folder, and none merged in")

        // An episode id that names another title's episode is refused too, even though that one finished.
        val stolen = DesktopDownloadRequest(victim.episodeId, 9900, "Elsewhere", DownloadTitleKind.Series, 1, 1, null,
            DownloadSourceRef("Kept Provider", "kept-1"), null)
        assertEquals(EnqueueResult.Refused(EnqueueRefusal.IdTaken), host.enqueue(stolen))
    }

    /**
     * `KMP-109`, end to end: a title page's episode, with no link, is downloaded by asking the extension through the
     * service's resolver for exactly the episode's source, as a resume does.
     */
    @Test
    fun `a title page's episode downloads through the resolver, from its source alone`() = runBlocking {
        val body = server.add("page-episode.bin", 128 * 1024)
        val asked = CopyOnWriteArrayList<DownloadSourceRef>()
        val host = host(resolver = LinkResolver { ref ->
            asked += ref
            listOf(DesktopDownloadLink(server.url("page-episode.bin")))
        })
        val detail = TitleDetail(
            title = "Fixture Show", providerName = "Fixture Provider", typeName = "TvSeries", year = null, plot = null,
            posterUrl = null, playData = null, episodes = emptyList(),
        )
        val episode = DetailEpisode(
            name = "The Second", data = "https://fixture.example/show/1/2", season = 1, episode = 2,
        )
        val request = episodeDownloadRequest("Fixture Provider\u0000https://fixture.example/show", detail, episode)

        assertEquals(EnqueueResult.Queued, host.enqueue(request))
        val done = host.awaitDone(request.titleId, request.episodeId)
        assertEquals(DownloadEpisodeId(request.episodeId), done.id)
        assertEquals(listOf(DownloadSourceRef("Fixture Provider", "https://fixture.example/show/1/2")), asked.toList())
        val file = assertNotNull(host.fileOf(DownloadEpisodeId(request.episodeId)))
        assertContentEquals(body, file.readBytes())
        assertEquals("Fixture Show", file.parentFile.name)
        assertEquals(EnqueueResult.AlreadyPresent, host.enqueue(request), "a second press adds nothing")
    }

    companion object {
        private const val TIMEOUT = 30_000L
        private const val FORCED_TITLE_ID = 4242
        private const val SCAN_ATTEMPTS = 20
        private const val GIB = 1024L * 1024 * 1024
    }
}

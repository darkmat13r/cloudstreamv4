package com.lagradost.cloudstream4.download

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.services.DownloadQueueService
import com.lagradost.cloudstream3.ui.player.RepoLinkGenerator
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DataStore.containsKey
import com.lagradost.cloudstream3.utils.DataStore.getDefaultSharedPrefs
import com.lagradost.cloudstream3.utils.DataStore.getFolderName
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.getKeys
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import com.lagradost.cloudstream3.utils.DataStore.removeKey
import com.lagradost.cloudstream3.utils.DataStore.removeKeys
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.toFile
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadEpisodeMetadata
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadItem
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadQueueItem
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadQueueWrapper
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadResumePackage
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadedFileInfo
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadActionType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream4.media.model.DownloadFailure
import com.lagradost.cloudstream4.media.model.DownloadState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

private const val MIB = 1_048_576L
private const val TOTAL = 15 * MIB
private const val PROVIDER = "Kmp62FakeProvider"
private const val SIDE = "download_resume_source"

/** Restates `DownloadFactsDeviceTest`'s pattern, so a resume that lands on the wrong offset shows as a wrong byte. */
private fun patternAt(offset: Long): Byte = (offset % 251L).toByte()

/** One file on loopback. It ignores the query string, which is where a link's url marker sits. */
private class Server(var dropPast: Long? = null) : AutoCloseable {
    private val socket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    private val open = Collections.synchronizedList(mutableListOf<Socket>())
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    /** Body bytes sent, so a resume that fetched the whole file again shows. */
    val sent = AtomicLong()
    val base = "http://127.0.0.1:${socket.localPort}/file.mp4"

    /** Slows each connection so a test can look at the stored keys while the download runs. */
    @Volatile
    var bytesPerSecond: Long? = null

    init {
        thread(isDaemon = true, name = "kmp62-server") {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    return@thread
                }
                open += client
                thread(isDaemon = true) { runCatching { client.use(::serve) } }
            }
        }
    }

    private fun serve(client: Socket) {
        val input = client.getInputStream()
        fun line(): String? {
            val out = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return null
                if (c == '\n'.code) return out.toString().trimEnd('\r')
                out.append(c.toChar())
            }
        }
        val lines = generateSequence { line() }.takeWhile { it.isNotEmpty() }.toList()
        val request = lines.firstOrNull() ?: return
        val range = lines.firstOrNull { it.startsWith("range:", ignoreCase = true) }?.substringAfter('=')
        requests += "${request.substringBefore(' ')} ${range ?: "-"}"
        val start = range?.substringBefore('-')?.toLong() ?: 0L
        val end = range?.substringAfter('-')?.toLongOrNull() ?: (TOTAL - 1)
        val output = client.getOutputStream()
        val head = StringBuilder(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            .append("Accept-Ranges: bytes\r\nConnection: close\r\nContent-Type: video/mp4\r\n")
            .append("Content-Length: ${end - start + 1}\r\n")
        if (range != null) head.append("Content-Range: bytes $start-$end/$TOTAL\r\n")
        output.write(head.append("\r\n").toString().toByteArray())
        if (request.startsWith("HEAD")) return
        val stop = minOf(end + 1, dropPast ?: Long.MAX_VALUE)
        val buffer = ByteArray(64 * 1024)
        var offset = start
        while (offset < stop) {
            val count = minOf(buffer.size.toLong(), stop - offset).toInt()
            for (i in 0 until count) buffer[i] = patternAt(offset + i)
            output.write(buffer, 0, count)
            sent.addAndGet(count.toLong())
            offset += count
            bytesPerSecond?.let { Thread.sleep(count * 1000L / it) }
        }
        output.flush()
    }

    override fun close() {
        socket.close()
        synchronized(open) { open.forEach { runCatching { it.close() } } }
    }
}

/** The provider a resume asks again: what `loadLinks` answers is whatever the test last set. */
private class FakeProvider : MainAPI() {
    override var name = PROVIDER
    override var mainUrl = "http://127.0.0.1"
    override val supportedTypes = setOf(TvType.Movie)

    @Volatile
    var answer: List<ExtractorLink> = emptyList()

    @Volatile
    var subtitle: SubtitleFile? = null
    val calls = Collections.synchronizedList(mutableListOf<String>())

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        calls += data
        subtitle?.let(subtitleCallback)
        answer.forEach(callback)
        return answer.isNotEmpty()
    }
}

/**
 * KMP-62: the old downloader stores no link. Every link and subtitle here carries unique markers in its url,
 * referer and headers, and after each step every SharedPreferences file on disk is read raw and must hold none.
 */
@RunWith(AndroidJUnit4::class)
class NoStoredLinksDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val provider = FakeProvider()
    private val ids = mutableListOf<Int>()
    private val markers = mutableListOf<String>()
    private var serviceWasRunning = false

    private fun marker(kind: String) = "KMP62${kind}${UUID.randomUUID().toString().replace("-", "")}".also { markers += it }

    @Before
    fun setUp() {
        // Nothing here may start the real queue service: each test drives the instances itself.
        serviceWasRunning = DownloadQueueService.isRunning
        DownloadQueueService.isRunning = true
        APIHolder.allProviders.add(provider)
        APIHolder.apiMap = null
    }

    @After
    fun tearDown() {
        ids.forEach { id ->
            VideoDownloadManager.downloadEvent.invoke(id to DownloadActionType.Stop)
            DownloadQueueManager.removeFromQueue(id)
            runCatching {
                context.getKey<DownloadedFileInfo>(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString())
                    ?.toFile(context)?.delete()
            }
            context.removeKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString())
            context.removeKey(VideoDownloadManager.KEY_RESUME_PACKAGES, id.toString())
            context.removeKey(VideoDownloadManager.KEY_RESUME_IN_QUEUE, id.toString())
            context.removeKeys("$SIDE/$id")
            context.removeKey(getFolderName(DOWNLOAD_EPISODE_CACHE, id.toString()), id.toString())
            context.removeKey(DOWNLOAD_HEADER_CACHE, id.toString())
            DownloadFacts.clear(id)
            VideoDownloadManager.downloadStatus.remove(id)
        }
        APIHolder.allProviders.remove(provider)
        APIHolder.apiMap = null
        RepoLinkGenerator.cache.clear()
        DownloadQueueService.isRunning = serviceWasRunning
    }

    // --- what goes in ---

    /**
     * A link's source and name are the provider's and can hold a host, so they carry markers too. They stay the
     * same for one mirror within a test, as a provider's would, so a picked mirror can still be recognised.
     */
    private val source by lazy { "src-${marker("SRC")}" }
    private val mirrorNames = mutableMapOf<String, String>()
    private fun mirrorName(tag: String) = mirrorNames.getOrPut(tag) { "Mirror $tag ${marker("NAME")}" }

    private suspend fun markedLink(server: Server, mirror: String = "A"): ExtractorLink =
        newExtractorLink(source, mirrorName(mirror), "${server.base}?u=${marker("URL")}", ExtractorLinkType.VIDEO) {
            referer = "http://127.0.0.1/${marker("REF")}"
            headers = mapOf("X-Kmp62" to marker("HDR"))
        }

    private fun markedSub() = SubtitleData(
        originalName = "English",
        nameSuffix = "1",
        url = "http://127.0.0.1:1/${marker("SUBURL")}.vtt",
        origin = SubtitleOrigin.URL,
        mimeType = "text/vtt",
        headers = mapOf("X-Kmp62-Sub" to marker("SUBHDR")),
        languageCode = "en",
    )

    private suspend fun markedProviderSub() =
        newSubtitleFile("English", "http://127.0.0.1:1/${marker("PSUBURL")}.vtt") {
            headers = mapOf("X-Kmp62-Sub" to marker("PSUBHDR"))
        }

    private fun episode(id: Int, data: String) = ResultEpisode(
        headerName = "kmp62",
        name = "kmp62-$id",
        poster = null,
        episode = 1,
        seasonIndex = null,
        season = null,
        data = data,
        apiName = PROVIDER,
        id = id,
        index = 0,
        position = 0,
        duration = 0,
        score = null,
        description = null,
        isFiller = null,
        tvType = TvType.Movie,
        parentId = id,
        videoWatchState = VideoWatchState.None,
    )

    private fun queueItem(id: Int, data: String, links: List<ExtractorLink>? = null, subs: List<SubtitleData>? = null) =
        DownloadQueueItem(
            episode = episode(id, data),
            isMovie = true,
            resultName = "kmp62-$id",
            resultType = TvType.Movie,
            resultPoster = null,
            apiName = PROVIDER,
            resultId = id,
            resultUrl = "http://127.0.0.1/result/$id",
            links = links,
            subs = subs,
        ).also { ids += id }

    /** What an older version stored for a download it had started: the item with every link in it. */
    private fun legacyItem(id: Int, links: List<ExtractorLink>) = DownloadItem(
        source = "fake",
        folder = "NoStoredLinksDeviceTest",
        ep = DownloadEpisodeMetadata(id, id, "kmp62", PROVIDER, null, "kmp62-$id", null, null, TvType.Movie),
        links = links,
    ).also { if (id !in ids) ids += id }

    // --- running ---

    private fun start(wrapper: DownloadQueueWrapper) =
        VideoDownloadManager.EpisodeDownloadInstance(context, wrapper).also { it.startDownload() }

    private suspend fun VideoDownloadManager.EpisodeDownloadInstance.awaitEnd(timeoutMs: Long = 180_000L): Boolean {
        val id = downloadQueueWrapper.id
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if ((isCompleted || isFailed) && !VideoDownloadManager.currentDownloads.value.contains(id)) return true
            delay(50)
        }
        return false
    }

    private suspend fun awaitWritten(id: Int, timeoutMs: Long = 30_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if ((VideoDownloadManager.getDownloadFileInfo(context, id)?.fileLength ?: 0L) > MIB) return
            delay(50)
        }
    }

    /** A restart, as far as the stored keys and the in-memory link cache go. */
    private fun restart(id: Int) {
        RepoLinkGenerator.cache.clear()
        VideoDownloadManager.downloadStatus.remove(id)
    }

    private fun storedResume(id: Int) = VideoDownloadManager.getDownloadResumePackage(context, id)

    private fun bytesInFile(id: Int): Long {
        val path = VideoDownloadManager.getDownloadFileInfo(context, id)!!.path
        context.contentResolver.openInputStream(path)!!.use { input ->
            val buffer = ByteArray(1024 * 1024)
            var count = 0L
            var offset = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) return count
                for (i in 0 until read) assertEquals("byte at ${offset + i}", patternAt(offset + i), buffer[i])
                offset += read
                count += read
            }
        }
    }

    // --- what reaches disk ---

    /** Flushes every pending `apply`: a `commit` writes after them, and writes the whole file. */
    private fun flush() {
        listOf(context.getSharedPrefs(), context.getDefaultSharedPrefs()).forEach {
            it.edit().putLong("kmp62_flush", System.nanoTime()).commit()
        }
    }

    private fun prefsOnDisk(): String {
        flush()
        val dir = File(context.dataDir, "shared_prefs")
        return dir.listFiles().orEmpty().filter { it.name.endsWith(".xml") }.joinToString("\n") { it.readText() }
    }

    private fun assertNoMarkers(step: String, except: Set<String> = emptySet()) {
        val disk = prefsOnDisk()
        val found = markers.filter { it !in except && disk.contains(it) }
        assertTrue("$step: markers on disk: ${found.map { it.take(12) }}", found.isEmpty())
    }

    private fun sideKeys(id: Int) = context.getKeys("$SIDE/$id")

    @After
    fun dropFlushKey() {
        context.getSharedPrefs().edit().remove("kmp62_flush").commit()
        context.getDefaultSharedPrefs().edit().remove("kmp62_flush").commit()
    }

    // --- the cases ---

    @Test
    fun aPickedMirrorStoresNoLinkFromTheQueueToTheFinishedFile() = runBlocking {
        Server().use { server ->
            server.bytesPerSecond = 4 * MIB
            val id = 920_001
            val data = marker("DATA")
            val link = markedLink(server)
            val wrapper = queueItem(id, data, listOf(link), listOf(markedSub())).toWrapper()

            DownloadQueueManager.init(context)
            DownloadQueueManager.addToQueue(wrapper)
            val queued = DownloadQueueManager.queue.value.single { it.id == id }
            assertNull("the queue holds the links", queued.downloadItem!!.links)
            assertNull("the queue holds the subtitles", queued.downloadItem!!.subs)
            assertTrue("no mirror was named", context.containsKey("$SIDE/$id/mirror"))
            delay(500) // the queue's collector writes it on IO
            assertTrue("the queue was not stored", prefsOnDisk().contains("kmp62-$id"))
            assertNoMarkers("enqueue", except = setOf(data))

            // The queue hands this process the links it was given, so nothing asks the provider.
            val instance = DownloadQueueManager.popQueue(context)!!
            assertEquals(listOf(link), instance.downloadQueueWrapper.downloadItem!!.links)
            instance.startDownload()
            awaitWritten(id)
            val running = storedResume(id)
            assertNotNull("no resume package while running", running)
            assertTrue("the running package holds links", running!!.item.links.isEmpty())
            assertTrue("no side record while running", context.containsKey("$SIDE/$id/item"))
            assertNoMarkers("run", except = setOf(data))

            server.bytesPerSecond = null
            assertTrue("did not end", instance.awaitEnd())
            assertTrue(instance.isCompleted)
            assertTrue("the provider was asked", provider.calls.isEmpty())
            assertEquals(DownloadState.COMPLETED, DownloadFacts.facts.value[id]?.state)
            assertEquals(TOTAL, bytesInFile(id))

            assertNull("a finished file keeps its resume package", storedResume(id))
            assertTrue("a finished file keeps its side record: ${sideKeys(id)}", sideKeys(id).isEmpty())
            assertNull(VideoDownloadManager.getDownloadQueuePackage(context, id))
            assertNoMarkers("finish")
            assertFalse("the episode's data string is still stored", prefsOnDisk().contains(data))
        }
    }

    @Test
    fun aFailedDownloadResumesAfterARestartByAskingTheProviderAgain() = runBlocking {
        Server(dropPast = 12 * MIB).use { server ->
            val id = 920_002
            provider.answer = listOf(markedLink(server))
            provider.subtitle = markedProviderSub()
            val first = start(queueItem(id, marker("DATA")).toWrapper())
            assertTrue("did not end", first.awaitEnd())
            assertTrue(first.isFailed)
            val partial = VideoDownloadManager.getDownloadFileInfo(context, id)!!.fileLength
            assertTrue("partial $partial", partial in 1 until TOTAL)
            assertTrue(storedResume(id)!!.item.links.isEmpty())
            assertNoMarkers("fail", except = provider.calls.toSet())

            // The process is gone: only disk is left, and the source now gives a fresh url for the same mirror.
            restart(id)
            server.dropPast = null
            val sentBefore = server.sent.get()
            provider.answer = listOf(markedLink(server))
            val resumed = start(storedResume(id)!!.toWrapper())
            assertTrue("the resume did not end", resumed.awaitEnd())
            assertTrue("the resume failed: ${DownloadFacts.facts.value[id]}", resumed.isCompleted)
            assertEquals(2, provider.calls.size)
            assertEquals(TOTAL, bytesInFile(id))
            // It carried on from the partial file rather than starting again.
            val sentAfter = server.sent.get() - sentBefore
            assertTrue("the resume fetched $sentAfter bytes of $TOTAL, partial $partial", sentAfter < TOTAL)
            assertTrue(sideKeys(id).isEmpty())
            assertNull(storedResume(id))
            assertNoMarkers("restart-finish")
        }
    }

    @Test
    fun aSourceThatNoLongerAnswersFailsTheResumeWithATypedFailureAndKeepsThePartialFile() = runBlocking {
        Server(dropPast = 12 * MIB).use { server ->
            val id = 920_003
            provider.answer = listOf(markedLink(server))
            val first = start(queueItem(id, marker("DATA")).toWrapper())
            assertTrue("did not end", first.awaitEnd())
            assertTrue(first.isFailed)
            val partial = VideoDownloadManager.getDownloadFileInfo(context, id)!!.fileLength

            restart(id)
            DownloadFacts.clear(id)
            provider.answer = emptyList()
            val resumed = start(storedResume(id)!!.toWrapper())
            assertTrue("the resume did not end", resumed.awaitEnd())
            assertTrue(resumed.isFailed)
            assertEquals(DownloadFailure.NoUsableLink, DownloadFacts.facts.value[id]?.failure)
            assertEquals(DownloadState.FAILED, DownloadFacts.facts.value[id]?.state)
            // The partial file and what a later retry needs are still there.
            assertEquals(partial, VideoDownloadManager.getDownloadFileInfo(context, id)!!.fileLength)
            assertNotNull(storedResume(id))
            assertTrue(context.containsKey("$SIDE/$id/item"))
            assertNoMarkers("no-link", except = provider.calls.toSet())
        }
    }

    @Test
    fun aStoppedDownloadLeavesNoLinkAndNoSideRecord() = runBlocking {
        Server().use { server ->
            server.bytesPerSecond = 2 * MIB
            val id = 920_004
            provider.answer = listOf(markedLink(server))
            val instance = start(queueItem(id, marker("DATA")).toWrapper())
            awaitWritten(id)
            assertNoMarkers("run", except = provider.calls.toSet())
            VideoDownloadManager.downloadEvent.invoke(id to DownloadActionType.Stop)
            assertTrue("did not end", instance.awaitEnd())
            assertTrue(sideKeys(id).isEmpty())
            assertNull(storedResume(id))
            assertNoMarkers("stop")
        }
    }

    /**
     * Two kinds an older version left, migrated: a resume package whose queued item survives (it gets a side
     * record and loses its links), and that queued item, stored with its picked mirror's link and subtitles.
     */
    @Test
    fun anOldPackageWhoseQueuedItemSurvivesIsMigratedAndResumes() = runBlocking {
        Server(dropPast = 12 * MIB).use { server -> Server().use { otherMirror ->
            val id = 920_005
            val data = marker("DATA")
            val oldLink = markedLink(server)
            // The older version's own run, cut off: its package keeps the link, as that version stored it.
            val old = start(DownloadResumePackage(legacyItem(id, listOf(oldLink)), null).toWrapper())
            assertTrue("did not end", old.awaitEnd())
            assertTrue(old.isFailed)
            assertEquals(listOf(oldLink.url), storedResume(id)!!.item.links.map { it.url })
            // And its queued item, which that version kept until the download finished.
            context.setKey(
                VideoDownloadManager.KEY_RESUME_IN_QUEUE, id.toString(),
                queueItem(id, data, listOf(oldLink), listOf(markedSub())).toWrapper(),
            )
            assertTrue(prefsOnDisk().contains(oldLink.url))

            restart(id)
            DownloadQueueManager.init(context)
            assertTrue("the package keeps its links", storedResume(id)!!.item.links.isEmpty())
            assertTrue("no side record", context.containsKey("$SIDE/$id/item"))
            assertTrue("the picked mirror is lost", context.containsKey("$SIDE/$id/mirror"))
            awaitQueued(id)
            assertNoMarkers("migration", except = setOf(data))

            // A second start is the same: nothing left to rewrite.
            DownloadQueueManager.init(context)
            assertNoMarkers("migration-again", except = setOf(data))

            server.dropPast = null
            // The source still offers the picked mirror, beside a better-ranked one it must not switch to.
            provider.answer = listOf(markedLink(otherMirror, mirror = "B"), markedLink(server))
            val instance = DownloadQueueManager.popQueue(context)!!
            instance.startDownload()
            assertTrue("did not end", instance.awaitEnd())
            assertTrue("the resume failed: ${DownloadFacts.facts.value[id]}", instance.isCompleted)
            assertEquals(TOTAL, bytesInFile(id))
            assertTrue(sideKeys(id).isEmpty())
            assertTrue("it switched mirror: ${otherMirror.requests.toList()}", otherMirror.requests.isEmpty())
            assertNoMarkers("migrated-finish")
            assertFalse(prefsOnDisk().contains(data))
        } }
    }

    /** The PM's fallback: nothing says what to ask the provider, so the old links stay, until it finishes. */
    @Test
    fun anOldPackageWithNothingToAskWithKeepsItsLinksUntilItFinishes() = runBlocking {
        Server(dropPast = 12 * MIB).use { server ->
            val id = 920_006
            val oldLink = markedLink(server)
            val old = start(DownloadResumePackage(legacyItem(id, listOf(oldLink)), null).toWrapper())
            assertTrue("did not end", old.awaitEnd())

            restart(id)
            DownloadQueueManager.init(context)
            assertEquals("the kept package lost its link", listOf(oldLink.url), storedResume(id)!!.item.links.map { it.url })
            assertTrue(sideKeys(id).isEmpty())
            assertTrue("the kept link is not on disk", prefsOnDisk().contains(oldLink.url))

            server.dropPast = null
            val resumed = start(storedResume(id)!!.toWrapper())
            assertTrue("did not end", resumed.awaitEnd())
            assertTrue(resumed.isCompleted)
            assertEquals(TOTAL, bytesInFile(id))
            assertNull(storedResume(id))
            assertNoMarkers("kept-finish")
        }
    }

    /** The same fallback, cleaned by removal instead: the queue service's Stop handling. */
    @Test
    fun anOldPackageWithNothingToAskWithGoesWhenItIsRemoved() = runBlocking {
        Server(dropPast = 12 * MIB).use { server ->
            val id = 920_007
            val oldLink = markedLink(server)
            val old = start(DownloadResumePackage(legacyItem(id, listOf(oldLink)), null).toWrapper())
            assertTrue("did not end", old.awaitEnd())
            restart(id)
            DownloadQueueManager.init(context)
            assertTrue(prefsOnDisk().contains(oldLink.url))

            DownloadQueueService().downloadEventListener(id to DownloadActionType.Stop)
            assertNull(storedResume(id))
            assertTrue(sideKeys(id).isEmpty())
            assertNoMarkers("kept-removed")
        }
    }

    /** Deleting a failed episode with no queue service running leaves neither its package nor its side record. */
    @Test
    fun deletingAFailedDownloadWithNoServiceRemovesItsPackageAndSideRecord() = runBlocking {
        Server(dropPast = 12 * MIB).use { server ->
            val id = 920_009
            provider.answer = listOf(markedLink(server))
            val instance = start(queueItem(id, marker("DATA")).toWrapper())
            assertTrue("did not end", instance.awaitEnd())
            assertTrue(instance.isFailed)
            assertNotNull(storedResume(id))
            assertTrue(context.containsKey("$SIDE/$id/item"))

            // The app's delete, as every screen calls it; no DownloadQueueService exists to hear the Stop.
            val deleted = CompletableDeferred<Set<Int>>()
            VideoDownloadManager.deleteFilesAndUpdateSettings(context, setOf(id), this) { deleted.complete(it) }
            assertEquals(setOf(id), deleted.await())

            val disk = prefsOnDisk()
            assertFalse("the side record survived the delete", disk.contains("$SIDE/$id/"))
            assertFalse("the resume package survived the delete", disk.contains("${VideoDownloadManager.KEY_RESUME_PACKAGES}/$id\""))
            assertNull(storedResume(id))
            assertNull(VideoDownloadManager.getDownloadFileInfo(context, id))
            assertNoMarkers("delete")
        }
    }

    /** A finished download depends on no side record: after a restart it is listed, playable and not queued. */
    @Test
    fun aFinishedDownloadIsListedAndPlayableAfterARestartWithNoSideRecord() = runBlocking {
        Server().use { server ->
            val id = 920_008
            val data = marker("DATA")
            provider.answer = listOf(markedLink(server))
            val instance = start(queueItem(id, data).toWrapper())
            assertTrue("did not end", instance.awaitEnd())
            assertTrue(instance.isCompleted)

            restart(id)
            DownloadQueueManager.init(context)
            delay(500)
            assertTrue(sideKeys(id).isEmpty())
            assertNull(storedResume(id))
            assertTrue("re-queued", DownloadQueueManager.queue.value.none { it.id == id })
            // What the Downloads screens list it from, and what the player opens.
            val info = VideoDownloadManager.getDownloadFileInfo(context, id)
            assertNotNull("no file info", info)
            assertEquals(TOTAL, info!!.fileLength)
            assertTrue(context.containsKey(getFolderName(DOWNLOAD_EPISODE_CACHE, id.toString()), id.toString()))
            assertTrue(context.containsKey(DOWNLOAD_HEADER_CACHE, id.toString()))
            assertEquals(TOTAL, bytesInFile(id))
            assertNoMarkers("finished-restart")
            assertFalse(prefsOnDisk().contains(data))
        }
    }

    private suspend fun awaitQueued(id: Int, timeoutMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && DownloadQueueManager.queue.value.none { it.id == id }) delay(50)
        assertTrue("not re-queued", DownloadQueueManager.queue.value.any { it.id == id })
    }
}

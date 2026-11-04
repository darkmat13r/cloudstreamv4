package com.lagradost.cloudstream4.download

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.toFile
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadEpisodeMetadata
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadItem
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadResumePackage
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadStatus
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadedFileInfo
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadActionType
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream4.media.model.DownloadFailure
import com.lagradost.cloudstream4.media.model.DownloadProgress
import com.lagradost.cloudstream4.media.model.DownloadState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

private const val MIB = 1_048_576L
private const val TAG = "DownloadFactsMeasure"

/** What the file holds at each offset, so a gap or a misplaced part shows up as a wrong byte. */
private fun patternAt(offset: Long): Byte = (offset % 251L).toByte()

/**
 * Serves one [total]-byte file on loopback, nothing else: no real network is involved.
 *
 * [dropPast] is the fault: no request gets a byte at or beyond that offset, and the socket closes
 * with the response still owing bytes, which is a connection lost halfway through a file.
 */
private class FakeServer(
    private val total: Long,
    var dropPast: Long? = null,
    private val bytesPerSecondPerConnection: Long? = null,
) : AutoCloseable {
    private val socket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    private val open = Collections.synchronizedList(mutableListOf<Socket>())
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
    val url = "http://127.0.0.1:${socket.localPort}/file.mp4"

    init {
        thread(isDaemon = true, name = "fake-server") {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    return@thread
                }
                open += client
                thread(isDaemon = true) {
                    try {
                        client.use(::serve)
                    } catch (_: Exception) {
                        // The downloader hangs up at the end of every range.
                    }
                }
            }
        }
    }

    private fun serve(client: Socket) {
        val lines = generateSequence { readLine(client) }.takeWhile { it.isNotEmpty() }.toList()
        val request = lines.firstOrNull() ?: return
        val range = lines.firstOrNull { it.startsWith("range:", ignoreCase = true) }
            ?.substringAfter('=')
        requests += "${request.substringBefore(' ')} ${range ?: "-"}"
        val start = range?.substringBefore('-')?.toLong() ?: 0L
        val end = range?.substringAfter('-')?.toLongOrNull() ?: (total - 1)
        val output = client.getOutputStream()
        val head = StringBuilder(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            .append("Accept-Ranges: bytes\r\nConnection: close\r\nContent-Type: video/mp4\r\n")
            .append("Content-Length: ${end - start + 1}\r\n")
        if (range != null) head.append("Content-Range: bytes $start-$end/$total\r\n")
        output.write(head.append("\r\n").toString().toByteArray())
        if (request.startsWith("HEAD")) return

        val stop = minOf(end + 1, dropPast ?: Long.MAX_VALUE)
        val buffer = ByteArray(64 * 1024)
        var offset = start
        while (offset < stop) {
            val count = minOf(buffer.size.toLong(), stop - offset).toInt()
            for (i in 0 until count) buffer[i] = patternAt(offset + i)
            output.write(buffer, 0, count)
            offset += count
            bytesPerSecondPerConnection?.let { Thread.sleep(count * 1000L / it) }
        }
        output.flush()
    }

    private fun readLine(client: Socket): String? {
        val line = StringBuilder()
        val input = client.getInputStream()
        while (true) {
            val char = input.read()
            if (char < 0) return null
            if (char == '\n'.code) return line.toString().trimEnd('\r')
            line.append(char.toChar())
        }
    }

    override fun close() {
        socket.close()
        synchronized(open) { open.forEach { runCatching { it.close() } } }
    }
}

/** One real download through the real instance, the code path the queue service runs. */
private class Episode(private val context: Context, val id: Int, url: String) {
    private val link: ExtractorLink = runBlocking {
        newExtractorLink("fake", "fake", url, ExtractorLinkType.VIDEO)
    }
    private val item = DownloadItem(
        source = "fake",
        folder = "DownloadFactsDeviceTest",
        ep = DownloadEpisodeMetadata(
            id = id,
            parentId = id,
            mainName = "facts",
            sourceApiName = "fake",
            poster = null,
            name = "facts-$id",
            season = null,
            episode = null,
            type = TvType.Movie,
        ),
        links = listOf(link),
    )

    var instance: VideoDownloadManager.EpisodeDownloadInstance? = null

    /** Every status the older event announced, which is what the old screens and notification hear. */
    val announced: MutableList<DownloadType> = Collections.synchronizedList(mutableListOf<DownloadType>())
    private val listener = { event: Pair<Int, DownloadType> ->
        if (event.first == id) announced += event.second
    }

    init {
        VideoDownloadManager.downloadStatusEvent += listener
    }

    fun start(linkIndex: Int? = null) {
        instance = VideoDownloadManager.EpisodeDownloadInstance(
            context,
            DownloadResumePackage(item, linkIndex).toWrapper(),
        ).also { it.startDownload() }
    }

    /** Until the instance has said how it ended and has let go of the id. */
    suspend fun awaitEnd(timeoutMs: Long = 180_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val running = instance!!
        while (System.currentTimeMillis() < deadline) {
            val ended = running.isCompleted || running.isFailed
            if (ended && !VideoDownloadManager.currentDownloads.value.contains(id)) return true
            delay(50)
        }
        return false
    }

    val fileInfo get() = VideoDownloadManager.getDownloadFileInfo(context, id)

    fun deleteFile() {
        VideoDownloadManager.downloadStatusEvent -= listener
        runCatching {
            context.getKey<DownloadedFileInfo>(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString())
                ?.toFile(context)?.delete()
        }
        DownloadFacts.clear(id)
    }

    /** Every byte of the file against the pattern, or the first offset that is wrong. */
    fun firstWrongOffset(): Long? {
        val uri = fileInfo!!.path
        context.contentResolver.openInputStream(uri)!!.use { raw ->
            val input = BufferedInputStream(raw, 256 * 1024)
            var offset = 0L
            while (true) {
                val value = input.read()
                if (value < 0) return null
                if (value.toByte() != patternAt(offset)) return offset
                offset++
            }
        }
    }
}

/** Restates `BaseFetchButton.getStatus`, which is an instance method of a view. */
private fun buttonStatus(id: Int, downloaded: Long, total: Long): DownloadType =
    VideoDownloadManager.downloadStatus[id]
        ?: if (downloaded > 1024L && downloaded + 1024L >= total) DownloadType.IsDone else DownloadType.IsPaused

/**
 * The downloader against real files and a real socket. What it pins about the older code is what the
 * code does today, recorded so that a decision to change it is a decision, not an accident. Those
 * tests are named for the behaviour and every number they rest on goes to logcat under [TAG].
 */
@RunWith(AndroidJUnit4::class)
class DownloadFactsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val episodes = mutableListOf<Episode>()

    @After
    fun deleteFiles() = episodes.forEach { it.deleteFile() }

    private fun episode(id: Int, url: String) = Episode(context, id, url).also { episodes += it }

    private val Episode.fact: DownloadProgress? get() = DownloadFacts.facts.value[id]

    private fun measure(name: String, episode: Episode, server: FakeServer) {
        val info = episode.fileInfo
        val length = info?.fileLength
        val total = info?.totalBytes
        val id = episode.id
        val lines = listOf(
            "isCompleted=${episode.instance?.isCompleted} isFailed=${episode.instance?.isFailed}",
            "fileLength=$length infoTotalBytes=$total",
            "downloadStatus=${VideoDownloadManager.downloadStatus[id]}",
            "KEY_DOWNLOAD_INFO=${context.getKey<DownloadedFileInfo>(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString())}",
            "KEY_RESUME_PACKAGES=${VideoDownloadManager.getDownloadResumePackage(context, id) != null}",
            "KEY_RESUME_IN_QUEUE=${VideoDownloadManager.getDownloadQueuePackage(context, id) != null}",
            "buttonStatus=${if (length != null && total != null) buttonStatus(id, length, total) else null}",
            "queueCalls98=${length != null && total != null && total > 0 && length.toFloat() / total.toFloat() > 0.98f}",
            "announced=${episode.announced.toList().distinct()}",
            "fact=${episode.fact}",
            "requests=${server.requests.size} ${server.requests.toList()}",
        )
        lines.forEach { Log.i(TAG, "$name: $it") }
    }

    private fun DownloadStatus.describe() = "retrySame=$retrySame tryNext=$tryNext success=$success"

    @Test
    fun aDownloadThatFinishesReportsARateAndCompletesWithEveryByteInPlace() = runBlocking {
        val total = 24 * MIB
        FakeServer(total, bytesPerSecondPerConnection = 3 * MIB).use { server ->
            val episode = episode(910_001, server.url)
            val seen = Collections.synchronizedList(mutableListOf<DownloadProgress>())
            val watcher = thread(isDaemon = true) {
                var last: DownloadProgress? = null
                while (!Thread.currentThread().isInterrupted) {
                    val now = DownloadFacts.facts.value[episode.id]
                    if (now != last && now != null) {
                        seen += now
                        last = now
                    }
                    try {
                        Thread.sleep(50)
                    } catch (_: InterruptedException) {
                        return@thread
                    }
                }
            }
            episode.start()
            assertTrue("did not end", episode.awaitEnd())
            watcher.interrupt()
            measure("complete", episode, server)
            Log.i(TAG, "complete: seen=${seen.map { "${it.state}/${it.bytesPerSecond}/${it.estimatedSecondsRemaining}" }}")

            assertTrue(episode.instance!!.isCompleted)
            assertTrue("no rate was ever reported", seen.any { it.bytesPerSecond > 0L })
            assertTrue("no ETA was ever reported", seen.any { (it.estimatedSecondsRemaining ?: 0L) > 0L })
            assertEquals(DownloadState.COMPLETED, episode.fact!!.state)
            assertNull(episode.fact!!.failure)
            assertEquals(total, episode.fact!!.bytesWritten)
            assertEquals(total, episode.fileInfo!!.fileLength)
            assertNull(episode.firstWrongOffset())
            // The real queue and service objects combine with no queue running.
            assertEquals(DownloadState.COMPLETED, DownloadFacts.progress.first().getValue(episode.id).state)
        }
    }

    @Test
    fun aConnectionLostUnderFiftyMegabytesFailsWithNetworkLost() = runBlocking {
        FakeServer(40 * MIB, dropPast = 15 * MIB).use { server ->
            val episode = episode(910_002, server.url)
            episode.start()
            assertTrue("did not end", episode.awaitEnd())
            measure("under50", episode, server)

            assertTrue(episode.instance!!.isFailed)
            assertFalse(episode.instance!!.isCompleted)
            assertEquals(DownloadType.IsFailed, VideoDownloadManager.downloadStatus[episode.id])
            assertEquals(DownloadState.FAILED, episode.fact!!.state)
            assertEquals(DownloadFailure.NetworkLost, episode.fact!!.failure)
            // Up to the drop: a range still in flight is abandoned when another one fails.
            assertTrue(episode.fact!!.bytesWritten!! <= 15 * MIB)
            assertEquals(40 * MIB, episode.fact!!.bytesTotal)
        }
    }

    /** The behaviour the brief reserves for the owner. Nothing here changes it. */
    @Test
    fun aConnectionLostPastFiftyMegabytesWrittenIsFiledAsCompletedAndTheFactSaysFailed() = runBlocking {
        val total = 100 * MIB
        val written = 60 * MIB
        FakeServer(total, dropPast = written).use { server ->
            val episode = episode(910_003, server.url)
            episode.start()
            assertTrue("did not end", episode.awaitEnd())
            measure("past50", episode, server)
            val requestsFirstRun = server.requests.size
            val completed = episode.instance!!.isCompleted
            val failed = episode.instance!!.isFailed
            val status = VideoDownloadManager.downloadStatus[episode.id]
            val length = episode.fileInfo!!.fileLength
            val recordedTotal = episode.fileInfo!!.totalBytes
            val queuePackage = VideoDownloadManager.getDownloadQueuePackage(context, episode.id)
            val fact = episode.fact!!
            val announced = episode.announced.toList()

            // What downloadThing itself returns for that, called directly.
            val direct = VideoDownloadManager.downloadThing(
                context,
                newExtractorLink("fake", "fake", server.url, ExtractorLinkType.VIDEO),
                name = "facts-direct",
                folder = "DownloadFactsDeviceTest",
                extension = "mp4",
                tryResume = false,
                parentId = 910_004,
                createNotificationCallback = {},
                minimumSize = 10 * MIB,
            )
            val directFile = VideoDownloadManager.getDownloadFileInfo(context, 910_004)?.fileLength
            Log.i(TAG, "past50: direct status=${direct.describe()} fileLength=$directFile requestsFirstRun=$requestsFirstRun")
            episodes += Episode(context, 910_004, server.url)

            // A later resume, once the source is healthy again.
            server.dropPast = null
            episode.start(linkIndex = 0)
            assertTrue("resume did not end", episode.awaitEnd())
            measure("past50-resumed", episode, server)

            // The same fault lands on either side of the 50 MB line. A range still in flight is abandoned
            // when another one fails, so what is written by then is a race. Measured: 60 MiB and 46.6 MiB.
            // Which side it landed on decides everything the older record says, so that is what is pinned.
            val filedAsCompleted = length > VideoDownloadManager.DOWNLOAD_PARTIAL_MIN_SIZE
            assertEquals(filedAsCompleted, completed)
            assertEquals(!filedAsCompleted, failed)
            assertEquals(if (filedAsCompleted) null else DownloadType.IsFailed, status)
            assertTrue(DownloadType.IsFailed in announced)
            assertFalse(DownloadType.IsDone in announced)
            assertTrue("wrote $length", length <= written)
            assertEquals(total, recordedTotal)
            assertNull(queuePackage)
            // Sized right, so the button shows a paused download rather than a finished one.
            if (filedAsCompleted) assertEquals(DownloadType.IsPaused, buttonStatus(episode.id, length, total))
            assertEquals(
                if (directFile!! > VideoDownloadManager.DOWNLOAD_PARTIAL_MIN_SIZE) {
                    DownloadStatus(retrySame = true, tryNext = false, success = true)
                } else {
                    DownloadStatus(retrySame = true, tryNext = true, success = false)
                },
                direct,
            )
            // The fact.
            assertEquals(DownloadState.FAILED, fact.state)
            assertEquals(DownloadFailure.NetworkLost, fact.failure)
            assertEquals(length, fact.bytesWritten)
            // The resume finished the same file.
            assertTrue(episode.instance!!.isCompleted)
            assertEquals(total, episode.fileInfo!!.fileLength)
            assertNull(episode.firstWrongOffset())
            assertEquals(DownloadState.COMPLETED, episode.fact!!.state)
        }
    }

    /** `resolveSafe` calls the open-ended range finished however it ended, at any size. */
    @Test
    fun aConnectionLostInASingleRangeFileIsFiledAsDoneAndTheFactSaysFailed() = runBlocking {
        val written = 12 * MIB
        FakeServer(15 * MIB, dropPast = written).use { server ->
            val episode = episode(910_005, server.url)
            episode.start()
            assertTrue("did not end", episode.awaitEnd())
            measure("single-range", episode, server)

            assertTrue(episode.instance!!.isCompleted)
            assertEquals(written, episode.fileInfo!!.fileLength)
            assertTrue(DownloadType.IsDone in episode.announced)
            assertFalse(DownloadType.IsFailed in episode.announced)
            assertEquals(DownloadState.FAILED, episode.fact!!.state)
            assertEquals(DownloadFailure.NetworkLost, episode.fact!!.failure)
        }
    }

    @Test
    fun aConnectionLostInTheLastRangeIsFiledAsDoneAndTheFactSaysFailed() = runBlocking {
        val written = 42 * MIB
        FakeServer(45 * MIB, dropPast = written).use { server ->
            val episode = episode(910_006, server.url)
            episode.start()
            assertTrue("did not end", episode.awaitEnd())
            measure("last-range", episode, server)

            assertTrue(episode.instance!!.isCompleted)
            assertEquals(written, episode.fileInfo!!.fileLength)
            assertTrue(DownloadType.IsDone in episode.announced)
            assertFalse(DownloadType.IsFailed in episode.announced)
            assertEquals(DownloadState.FAILED, episode.fact!!.state)
            assertEquals(DownloadFailure.NetworkLost, episode.fact!!.failure)
        }
    }
}

/**
 * A full disk, separately: it fills the data partition and may leave a download stuck, so it runs on
 * its own class after the others. The filler always goes, and `device-tests.sh` runs the AVD read-only.
 */
@RunWith(AndroidJUnit4::class)
class DownloadFactsDiskFullDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fillLeaving(free: Long, filler: File): Boolean {
        val available = StatFs(Environment.getExternalStorageDirectory().path).availableBytes
        val size = available - free
        if (size <= 0L) return true
        return try {
            FileOutputStream(filler).use { Os.posix_fallocate(it.fd, 0L, size) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "disk-full: cannot fill: $e, code ${(e as? android.system.ErrnoException)?.errno} vs ${OsConstants.ENOSPC}")
            false
        }
    }

    @Test
    fun aFullDiskStallsTheTransferForeverAndTheFactNamesIt() = runBlocking {
        val filler = File(context.cacheDir, "download-facts-filler")
        val free = 30 * MIB
        val total = 200 * MIB
        val available = StatFs(Environment.getExternalStorageDirectory().path)
        val before = available.availableBytes
        val filled = fillLeaving(free, filler)
        assumeTrue("posix_fallocate could not fill the partition", filled)
        FakeServer(total).use { server ->
            val episode = Episode(context, 910_007, server.url)
            try {
                val after = StatFs(Environment.getExternalStorageDirectory().path).availableBytes
                Log.i(TAG, "disk-full: availableBefore=$before availableAfterFill=$after")
                val started = System.currentTimeMillis()
                episode.start()
                var lastBytes = -1L
                var lastChange = System.currentTimeMillis()
                var outcome = "timed out"
                while (System.currentTimeMillis() - started < 180_000L) {
                    val running = episode.instance!!
                    if (running.isCompleted || running.isFailed) {
                        outcome = "ended isCompleted=${running.isCompleted} isFailed=${running.isFailed}"
                        break
                    }
                    val fact = DownloadFacts.facts.value[episode.id]
                    val bytes = fact?.bytesDownloaded ?: -1L
                    if (bytes != lastBytes) {
                        lastBytes = bytes
                        lastChange = System.currentTimeMillis()
                    }
                    if (System.currentTimeMillis() - lastChange > 45_000L) {
                        outcome = "stalled at $bytes for 45 s"
                        break
                    }
                    delay(250)
                }
                val fact = DownloadFacts.facts.value[episode.id]
                Log.i(TAG, "disk-full: outcome=$outcome after ${System.currentTimeMillis() - started} ms")
                Log.i(TAG, "disk-full: fact=$fact")
                val status = VideoDownloadManager.downloadStatus[episode.id]
                Log.i(TAG, "disk-full: downloadStatus=$status")
                Log.i(TAG, "disk-full: fileLength=${episode.fileInfo?.fileLength} requests=${server.requests.size}")

                // The user's way out: does stopping end it?
                val stopped = System.currentTimeMillis()
                VideoDownloadManager.downloadEvent.invoke(episode.id to DownloadActionType.Stop)
                episode.instance?.cancelDownload()
                val ended = episode.awaitEnd(timeoutMs = 30_000L)
                Log.i(TAG, "disk-full: afterStop ended=$ended in ${System.currentTimeMillis() - stopped} ms")

                // Neither finished nor failed, and the legacy status still says it is downloading.
                assertTrue("expected a stall: $outcome", outcome.startsWith("stalled"))
                assertEquals(DownloadType.IsDownloading, status)
                assertNotNull("no fact was ever reported", fact)
                assertEquals(DownloadState.DOWNLOADING, fact!!.state)
                assertTrue("the fact does not name a full disk: $fact", fact.failure is DownloadFailure.StorageFull)
                assertTrue("written ${fact.bytesWritten}", fact.bytesWritten!! > 0L)
                assertTrue(ended)
            } finally {
                VideoDownloadManager.downloadEvent.invoke(episode.id to DownloadActionType.Stop)
                episode.instance?.cancelDownload()
                episode.deleteFile()
                filler.delete()
            }
        }
    }
}

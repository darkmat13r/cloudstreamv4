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
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getBasePath
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getFileName
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
private const val RETRY_ROUNDS = 40
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
private class Episode(private val context: Context, val id: Int, vararg urls: String) {
    private val links: List<ExtractorLink> = runBlocking {
        urls.map { newExtractorLink("fake", "fake", it, ExtractorLinkType.VIDEO) }
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
        links = links,
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

    /**
     * The length the app reads is MediaStore's `_size`, which trails the file: short for up to 33 ms after
     * a download ends in 15 of 150 failures (`BUG-39`). Returns the value it compared, because a second
     * read can be short again.
     */
    suspend fun awaitFileLength(expected: Long, timeoutMs: Long = 10_000L): Long? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var length = fileInfo?.fileLength
        while (System.currentTimeMillis() < deadline && length != expected) {
            delay(50)
            length = fileInfo?.fileLength
        }
        return length
    }

    /** How many bytes the file really holds, read back, which `_size` does not say. */
    fun bytesInFile(): Long {
        context.contentResolver.openInputStream(fileInfo!!.path)!!.use { input ->
            val buffer = ByteArray(1024 * 1024)
            var count = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) return count
                count += read
            }
        }
    }

    private val folder get() = item.folder!!

    /** A subtitle beside the video, named the way `downloadSubtitle` names one. */
    fun writeSubtitle(): String {
        val name = "${getFileName(context, item.ep)} English"
        VideoDownloadManager.setupStream(context, name, folder, "vtt", false).open().use {
            it.write("WEBVTT".toByteArray())
        }
        return "$name.vtt"
    }

    fun folderHolds(name: String): Boolean =
        context.getBasePath().first?.gotoDirectory(folder, createMissingDirectories = false)
            ?.listFiles()?.any { it.name() == name } == true

    fun deleteFileNamed(name: String) {
        context.getBasePath().first?.gotoDirectory(folder, createMissingDirectories = false)
            ?.listFiles()?.firstOrNull { it.name() == name }?.delete()
    }

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
 * The downloader against real files and a real socket. A lost connection or a failed write ends as a
 * failure that keeps the file and can be retried (`BUG-35`). The tests are named for the behaviour and
 * every number they rest on goes to logcat under [TAG].
 */
@RunWith(AndroidJUnit4::class)
class DownloadFactsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val episodes = mutableListOf<Episode>()

    @After
    fun deleteFiles() = episodes.forEach { it.deleteFile() }

    private fun episode(id: Int, vararg urls: String) = Episode(context, id, *urls).also { episodes += it }

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
            assertEquals(total, episode.awaitFileLength(total))
            assertNull(episode.firstWrongOffset())
            // The real queue and service objects combine with no queue running.
            assertEquals(DownloadState.COMPLETED, DownloadFacts.progress.first().getValue(episode.id).state)
        }
    }

    @Test
    fun aConnectionLostUnderFiftyMegabytesFailsKeepsTheFileAndItsSubtitles() = runBlocking {
        FakeServer(40 * MIB, dropPast = 15 * MIB).use { server ->
            val episode = episode(910_002, server.url)
            val subtitle = episode.writeSubtitle()
            try {
                episode.start()
                assertTrue("did not end", episode.awaitEnd())
                measure("under50", episode, server)

                assertFailedAndKept(episode, written = 15 * MIB, total = 40 * MIB)
                assertTrue("the subtitle was deleted with the failure", episode.folderHolds(subtitle))
            } finally {
                episode.deleteFileNamed(subtitle)
            }
        }
    }

    /** What every failure in this file ends as, whichever side of the 50 MB line it landed on. */
    private suspend fun assertFailedAndKept(episode: Episode, written: Long, total: Long) {
        val instance = episode.instance!!
        assertTrue(instance.isFailed)
        assertFalse(instance.isCompleted)
        assertEquals(DownloadType.IsFailed, VideoDownloadManager.downloadStatus[episode.id])
        assertTrue(DownloadType.IsFailed in episode.announced)
        assertFalse(DownloadType.IsDone in episode.announced)
        // Nothing retries it by itself, and what a Retry needs is still there.
        assertNull(VideoDownloadManager.getDownloadQueuePackage(context, episode.id))
        assertNotNull(VideoDownloadManager.getDownloadResumePackage(context, episode.id))
        val fact = episode.fact!!
        assertEquals(DownloadState.FAILED, fact.state)
        val reported = fact.bytesWritten!!
        assertTrue("wrote $reported", reported in 1..written)
        // What the downloader reports is what the file holds, and what the app reads catches up with it.
        assertEquals(reported, episode.bytesInFile())
        assertEquals(reported, episode.awaitFileLength(reported))
        assertEquals(total, episode.fileInfo!!.totalBytes)
        assertNull(episode.firstWrongOffset())
    }

    private suspend fun retryFinishes(episode: Episode, server: FakeServer, total: Long, name: String) {
        server.dropPast = null
        episode.start(linkIndex = 0)
        assertTrue("the retry did not end", episode.awaitEnd())
        val length = episode.awaitFileLength(total)
        measure(name, episode, server)
        assertTrue(episode.instance!!.isCompleted)
        assertEquals(total, length)
        assertNull(episode.firstWrongOffset())
        assertEquals(DownloadState.COMPLETED, episode.fact!!.state)
    }

    @Test
    fun aConnectionLostPastFiftyMegabytesFailsKeepsTheFileAndRetryFinishesIt() = runBlocking {
        val total = 100 * MIB
        val written = 60 * MIB
        FakeServer(total, dropPast = written).use { server ->
            val episode = episode(910_003, server.url)
            episode.start()
            assertTrue("did not end", episode.awaitEnd())
            measure("past50", episode, server)

            assertFailedAndKept(episode, written, total)
            assertEquals(DownloadFailure.NetworkLost, episode.fact!!.failure)

            // What downloadThing itself returns for that, called directly. Which side of the line one
            // attempt lands on is a race, measured at 60 MiB and 46.6 MiB. It only decides the mirror.
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
            val directEpisode = Episode(context, 910_004, server.url).also { episodes += it }
            val directFile = directEpisode.fact!!.bytesWritten!!
            assertEquals(directFile, directEpisode.awaitFileLength(directFile))
            Log.i(TAG, "past50: direct status=${direct.describe()} fileLength=$directFile")
            assertFalse(direct.success)
            assertTrue(direct.retrySame)
            assertEquals(directFile <= VideoDownloadManager.DOWNLOAD_PARTIAL_MIN_SIZE, direct.tryNext)

            // A file the older code filed as completed looks like this after an update: the same keys,
            // and no status in memory. The button computes paused from its size, and a resume finishes it.
            VideoDownloadManager.downloadStatus.remove(episode.id)
            val length = episode.fileInfo!!.fileLength
            assertEquals(DownloadType.IsPaused, buttonStatus(episode.id, length, total))
            retryFinishes(episode, server, total, "past50-retried")
        }
    }

    /**
     * A retry resumes from what the file holds and appends nothing it already has (`BUG-39`). On the old code, which took
     * the length from `_size`, this went wrong in 53 of 900 rounds, so 40 rounds catch it about 91% of the time. A round
     * that ends with no file is another matter, and is skipped here.
     */
    @Test
    fun aRetryAppendsNothingTheFileAlreadyHolds(): Unit = runBlocking {
        repeat(RETRY_ROUNDS) { round ->
            FakeServer(15 * MIB, dropPast = 12 * MIB).use { first ->
                FakeServer(15 * MIB, dropPast = 5 * MIB).use { second ->
                    val episode = episode(910_100 + round, first.url, second.url)
                    episode.start()
                    assertTrue("did not end", episode.awaitEnd())
                    val reported = episode.fact?.bytesWritten
                    if (reported != null) {
                        assertEquals("round $round, ${second.requests.toList()}", reported, episode.bytesInFile())
                        assertNull("round $round", episode.firstWrongOffset())
                    }
                    episode.deleteFile()
                }
            }
        }
    }

    /** `resolveSafe` used to call the open-ended range finished however it ended, at any size. */
    @Test
    fun aConnectionLostInASingleRangeFileFailsAndRetryFinishesIt() = runBlocking {
        val total = 15 * MIB
        val written = 12 * MIB
        FakeServer(total, dropPast = written).use { server ->
            val episode = episode(910_005, server.url)
            episode.start()
            assertTrue("did not end", episode.awaitEnd())
            measure("single-range", episode, server)

            assertFailedAndKept(episode, written, total)
            assertEquals(written, episode.awaitFileLength(written))
            assertEquals(DownloadFailure.NetworkLost, episode.fact!!.failure)
            retryFinishes(episode, server, total, "single-range-retried")
        }
    }

    @Test
    fun aConnectionLostInTheLastRangeFailsAndRetryFinishesIt() = runBlocking {
        val total = 45 * MIB
        val written = 42 * MIB
        FakeServer(total, dropPast = written).use { server ->
            val episode = episode(910_006, server.url)
            episode.start()
            assertTrue("did not end", episode.awaitEnd())
            measure("last-range", episode, server)

            assertFailedAndKept(episode, written, total)
            assertEquals(DownloadFailure.NetworkLost, episode.fact!!.failure)
            retryFinishes(episode, server, total, "last-range-retried")
        }
    }

    /**
     * Under the line a failure still goes on to the next mirror, and a mirror cannot resume another
     * one's bytes, so that attempt replaces the file. Recorded because it is the one place a partial
     * file goes away without the user asking, and `BUG-35` makes a truncated file reach it.
     */
    @Test
    fun aTruncatedFileUnderTheLineIsReplacedByTheNextMirrorsAttempt() = runBlocking {
        val total = 15 * MIB
        FakeServer(total, dropPast = 12 * MIB).use { first ->
            FakeServer(total, dropPast = 5 * MIB).use { second ->
                val episode = episode(910_008, first.url, second.url)
                episode.start()
                assertTrue("did not end", episode.awaitEnd())
                measure("next-mirror", episode, second)

                assertTrue(second.requests.isNotEmpty())
                assertFailedAndKept(episode, written = 5 * MIB, total = total)
                assertEquals(5 * MIB, episode.awaitFileLength(5 * MIB))
            }
        }
    }
}

/**
 * A full disk, separately: it fills the data partition, so it runs on its own class after the others. The filler always goes, and `device-tests.sh` runs the AVD read-only.
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
    fun aFullDiskFailsTheTransferFreesItsSlotKeepsTheFileAndRetryFinishesIt() = runBlocking {
        val filler = File(context.cacheDir, "download-facts-filler")
        val free = 30 * MIB
        val total = 200 * MIB
        val before = StatFs(Environment.getExternalStorageDirectory().path).availableBytes
        assumeTrue("posix_fallocate could not fill the partition", fillLeaving(free, filler))
        FakeServer(total).use { server ->
            FakeServer(total).use { nextMirror ->
                val episode = Episode(context, 910_007, server.url, nextMirror.url)
                try {
                    val after = StatFs(Environment.getExternalStorageDirectory().path).availableBytes
                    Log.i(TAG, "disk-full: availableBefore=$before availableAfterFill=$after")
                    val started = System.currentTimeMillis()
                    episode.start()
                    val ended = episode.awaitEnd(timeoutMs = 90_000L)
                    val tookMs = System.currentTimeMillis() - started
                    val running = episode.instance!!
                    val fact = DownloadFacts.facts.value[episode.id]
                    val status = VideoDownloadManager.downloadStatus[episode.id]
                    val length = episode.fileInfo?.fileLength
                    Log.i(TAG, "disk-full: ended=$ended in $tookMs ms isCompleted=${running.isCompleted} isFailed=${running.isFailed}")
                    Log.i(TAG, "disk-full: fact=$fact")
                    Log.i(TAG, "disk-full: downloadStatus=$status fileLength=$length")
                    Log.i(TAG, "disk-full: requests=${server.requests.size} nextMirror=${nextMirror.requests.size}")

                    assertTrue("still running after $tookMs ms, status $status", ended)
                    assertTrue(running.isFailed)
                    assertFalse(running.isCompleted)
                    assertEquals(DownloadType.IsFailed, status)
                    assertFalse(VideoDownloadManager.currentDownloads.value.contains(episode.id))
                    assertEquals(DownloadState.FAILED, fact!!.state)
                    assertTrue("the fact does not name a full disk: $fact", fact.failure is DownloadFailure.StorageFull)
                    // Another mirror writes to the same disk, and starting one would delete this file.
                    assertTrue(nextMirror.requests.isEmpty())
                    assertTrue("wrote $length", length!! > 0L)
                    assertNull(episode.firstWrongOffset())

                    // Room again: the Retry the failure offers carries on from the same file.
                    filler.delete()
                    episode.start(linkIndex = 0)
                    assertTrue("the retry did not end", episode.awaitEnd())
                    val retried = episode.awaitFileLength(total)
                    Log.i(TAG, "disk-full-retried: fileLength=$retried fact=${DownloadFacts.facts.value[episode.id]}")
                    assertTrue(episode.instance!!.isCompleted)
                    assertEquals(total, retried)
                    assertNull(episode.firstWrongOffset())
                } finally {
                    VideoDownloadManager.downloadEvent.invoke(episode.id to DownloadActionType.Stop)
                    episode.instance?.cancelDownload()
                    episode.deleteFile()
                    filler.delete()
                }
            }
        }
    }
}

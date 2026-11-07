package com.lagradost.cloudstream4.download

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.services.DownloadQueueService
import com.lagradost.cloudstream3.ui.download.DOWNLOAD_ACTION_PLAY_FILE
import com.lagradost.cloudstream3.ui.download.DownloadButtonSetup.handleDownloadClick
import com.lagradost.cloudstream3.ui.download.DownloadClickEvent
import com.lagradost.cloudstream3.ui.download.DownloadViewModel
import com.lagradost.cloudstream3.ui.player.DownloadFileGenerator
import com.lagradost.cloudstream3.ui.player.ExtractorUri
import com.lagradost.cloudstream3.ui.setup.HAS_DONE_SETUP_KEY
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DataStore.getFolderName
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.removeKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getBasePath
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadEpisodeMetadata
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadItem
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadResumePackage
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadedFileInfo
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadActionType
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream4.core.http.HttpClientFactory
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisodeId
import com.lagradost.cloudstream4.media.download.DownloadSnapshot
import com.lagradost.cloudstream4.media.download.FileKitStorage
import com.lagradost.cloudstream4.media.download.PartSnapshot
import com.lagradost.cloudstream4.media.download.SegmentedDownloader
import com.lagradost.safefile.SafeFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val MIB = 1_048_576L

/** What a dropped body can lose before it reaches a part file: Ktor's 1 MiB channel (`KMP-69`, `BUG-45`). */
private const val CHANNEL_DISCARD = MIB
private const val FOLDER = "EngineDownloadListDeviceTest"
private const val TAG = "EngineDownloadListTest"
private const val SERIES_ID = 960_000

/**
 * `KMP-60`: with the switch on, an engine download is listed while it runs, nothing plays its empty path, the
 * Downloads list does not prune it, Stop and Cancel leave nothing behind, and Pause and Resume sent through
 * `downloadEvent` go on from disk. Every download runs against [EngineTestServer] on 127.0.0.1, throttled so it is
 * still running when the test looks.
 */
@RunWith(AndroidJUnit4::class)
class EngineDownloadListDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val stagingRoot = File(context.filesDir, "engine-list-device-test-staging")
    private val ids = mutableListOf<Int>()
    private val instances = mutableListOf<VideoDownloadManager.EpisodeDownloadInstance>()
    private val episodeFolder = getFolderName(DOWNLOAD_EPISODE_CACHE, SERIES_ID.toString())

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val automation = instrumentation.uiAutomation
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE).forEach {
                automation.executeShellCommand("pm grant ${context.packageName} $it")
                    .let { output -> ParcelFileDescriptor.AutoCloseInputStream(output).use { s -> s.readBytes() } }
            }
        }
        stagingRoot.deleteRecursively()
        EngineDownloadSwitch.useSegmentedEngine = true
        EngineDownloadSwitch.allowPrivateAddressesForTests = true
        EngineDownloadSwitch.stagingRootForTests = stagingRoot
        context.setKey(
            DOWNLOAD_HEADER_CACHE,
            SERIES_ID.toString(),
            DownloadObjects.DownloadHeaderCached(
                apiName = "fake",
                url = "https://example.invalid/title",
                type = TvType.TvSeries,
                name = "engine list",
                poster = null,
                cacheTime = 1L,
                id = SERIES_ID,
            ),
        )
    }

    @After
    fun tearDown(): Unit = runBlocking {
        ids.forEach { VideoDownloadManager.downloadEvent.invoke(it to DownloadActionType.Stop) }
        instances.forEach { it.cancelDownload() }
        val deadline = System.currentTimeMillis() + 30_000L
        while (ids.any { VideoDownloadManager.currentDownloads.value.contains(it) } &&
            System.currentTimeMillis() < deadline
        ) delay(50)
        EngineDownloadSwitch.useSegmentedEngine = false
        EngineDownloadSwitch.allowPrivateAddressesForTests = false
        EngineDownloadSwitch.storageForTests = null
        EngineDownloadSwitch.stagingRootForTests = null
        ids.forEach { id ->
            visibleFiles(id).forEach { it.delete() }
            DownloadFacts.clear(id)
            context.removeKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString())
            context.removeKey(VideoDownloadManager.KEY_RESUME_PACKAGES, id.toString())
            context.removeKey(VideoDownloadManager.KEY_RESUME_IN_QUEUE, id.toString())
            context.removeKey(episodeFolder, id.toString())
        }
        context.removeKey(DOWNLOAD_HEADER_CACHE, SERIES_ID.toString())
        stagingRoot.deleteRecursively()
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun nameOf(id: Int) = "engine-list-$id"

    private fun newId(id: Int) = id.also {
        ids += it
        context.setKey(
            episodeFolder,
            it.toString(),
            DownloadObjects.DownloadEpisodeCached(
                name = nameOf(it),
                poster = null,
                episode = it - SERIES_ID,
                season = 1,
                id = it,
                parentId = SERIES_ID,
                score = null,
                description = null,
                cacheTime = 1L,
            ),
        )
    }

    private fun link(url: String): ExtractorLink =
        runBlocking { newExtractorLink("fake", "fake", url, ExtractorLinkType.VIDEO) }

    private fun folder(): SafeFile? = context.getBasePath().first?.gotoDirectory(FOLDER, createMissingDirectories = false)

    private fun visibleFiles(id: Int): List<SafeFile> =
        folder()?.listFiles()?.filter { it.name() == "${nameOf(id)}.mp4" }.orEmpty()

    private fun stagingOf(id: Int) = File(stagingRoot, id.toString())

    /** Bytes in the staging directory, read independently of the code under test. */
    private fun stagedBytes(id: Int): Long = stagingOf(id).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun partFilesOf(id: Int): Map<Int, Long> =
        File(stagingOf(id), ".${nameOf(id)}.mp4.csdl").listFiles().orEmpty()
            .filter { it.name.startsWith("part-") }
            .associate { it.name.removePrefix("part-").toInt() to it.length() }

    /** Through the real `EpisodeDownloadInstance`, which runs `EngineVideoDownload.fromSwitch`. */
    private fun startInstance(id: Int, url: String): VideoDownloadManager.EpisodeDownloadInstance {
        val item = DownloadItem(
            source = "fake",
            folder = FOLDER,
            ep = DownloadEpisodeMetadata(
                id = id,
                parentId = SERIES_ID,
                mainName = "engine list",
                sourceApiName = "fake",
                poster = null,
                name = nameOf(id),
                season = null,
                episode = null,
                type = TvType.TvSeries,
            ),
            links = listOf(link(url)),
        )
        return VideoDownloadManager.EpisodeDownloadInstance(context, DownloadResumePackage(item, null).toWrapper())
            .also { instances += it; it.startDownload() }
    }

    private suspend fun awaitStaged(id: Int, atLeast: Long, timeoutMs: Long = 60_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (stagedBytes(id) >= atLeast && listed(id)) return
            delay(50)
        }
        throw AssertionError("download $id staged ${stagedBytes(id)} bytes, listed=${listed(id)}")
    }

    private fun listed(id: Int) =
        context.getKey<DownloadedFileInfo>(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString()) != null

    private suspend fun awaitEnded(id: Int, instance: VideoDownloadManager.EpisodeDownloadInstance) {
        val deadline = System.currentTimeMillis() + 60_000L
        while (System.currentTimeMillis() < deadline) {
            val ended = instance.isCompleted || instance.isFailed
            if (ended && !VideoDownloadManager.currentDownloads.value.contains(id)) return
            delay(50)
        }
        throw AssertionError("download $id did not end")
    }

    private fun assertRunning(id: Int) =
        assertTrue("download $id is not running", VideoDownloadManager.currentDownloads.value.contains(id))

    private suspend fun <T> awaitValue(timeoutMs: Long = 15_000L, read: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            read()?.let { return it }
            delay(50)
        }
        throw AssertionError("no value within $timeoutMs ms")
    }

    private fun assertNothingLeft(id: Int) {
        assertFalse("the staging directory was left behind", stagingOf(id).exists())
        assertTrue("a destination file was left behind", visibleFiles(id).isEmpty())
        assertNull("still listed", EngineVideoDownload.getDownloadListInfo(context, id))
    }

    // ---- 1. listed while running ---------------------------------------------------------------

    @Test
    fun aRunningEngineDownloadIsListedByEveryReaderWithItsStagedBytesAndAnEmptyPath(): Unit = runBlocking {
        val id = newId(SERIES_ID + 1)
        val source = SeededSource(seed = 11, size = 64 * MIB)
        EngineTestServer(source).use { server ->
            server.bytesPerSecond = MIB / 2
            startInstance(id, server.url)
            awaitStaged(id, atLeast = 2 * MIB)
            assertRunning(id)

            // The record and the fallback, side by side with the file-only lookup the readers used to stop at.
            assertNull("a destination file exists already", VideoDownloadManager.getDownloadFileInfo(context, id))
            val info = EngineVideoDownload.getDownloadListInfo(context, id)
            assertNotNull(info)
            assertEquals(Uri.EMPTY, info!!.path)
            assertTrue("staged ${info.fileLength}", info.fileLength > 1L)
            assertEquals(source.size, info.totalBytes)
            val record = context.getKey<DownloadedFileInfo>(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString())!!
            assertEquals(FOLDER, record.relativePath)
            assertEquals("${nameOf(id)}.mp4", record.displayName)
            assertEquals(server.url.hashCode(), record.linkHash)
            assertNull(record.extraInfo)

            val downloads = DownloadViewModel()
            // DownloadViewModel.calculateDownloadStats, through the header list it feeds.
            downloads.updateHeaderList(context)
            val header = awaitValue {
                (downloads.headerCards.value as? Resource.Success)?.value?.firstOrNull { it.data.id == SERIES_ID }
            }
            Log.i(TAG, "listed: header current=${header.currentBytes} total=${header.totalBytes}")
            assertEquals(1, header.totalDownloads)
            assertTrue("header bytes ${header.currentBytes}", header.currentBytes > 1L)
            assertEquals(source.size, header.totalBytes)

            // DownloadViewModel.updateChildList.
            downloads.updateChildList(context, episodeFolder)
            val child = awaitValue {
                (downloads.childCards.value as? Resource.Success)?.value?.firstOrNull { it.data.id == id }
            }
            assertTrue("child bytes ${child.currentBytes}", child.currentBytes > 1L)
            assertEquals(source.size, child.totalBytes)

            // AndroidDownloadsSource.readEpisodes, with no facts, so the row's bytes are the disk's.
            val titles = withTimeout(15_000L) {
                AndroidDownloadsSource(context, downloads, facts = flowOf(emptyMap())).contents
                    .first { contents -> contents.titles.any { it.id.value == SERIES_ID } }
            }
            val title = titles.titles.single { it.id.value == SERIES_ID }
            assertEquals(1, title.episodes)
            assertTrue("title bytes ${title.bytesDone}", title.bytesDone > 1L)

            // AndroidDownloadSeriesSource.read, also with no facts: only the disk read can list it.
            val series = withTimeout(15_000L) {
                AndroidDownloadSeriesSource(context, "engine list", episodeFolder, facts = flowOf(emptyMap())).contents
                    .first { contents -> contents.episodes.any { it.id == DownloadEpisodeId(id) } }
            }
            val episode = series.episodes.single { it.id == DownloadEpisodeId(id) }
            assertTrue("episode bytes ${episode.bytesDone}", episode.bytesDone > 1L)
            assertEquals(source.size, episode.bytesTotal)
            assertRunning(id)
        }
    }

    // ---- 2. play refused while running ---------------------------------------------------------

    @Test
    fun aRunningEngineDownloadIsRefusedByThePlaySeams(): Unit = runBlocking {
        val id = newId(SERIES_ID + 2)
        val source = SeededSource(seed = 12, size = 64 * MIB)
        EngineTestServer(source).use { server ->
            server.bytesPerSecond = MIB / 2
            startInstance(id, server.url)
            awaitStaged(id, atLeast = MIB)
            assertRunning(id)

            assertTrue(EngineVideoDownload.refusesPlay(context, id))

            // The player's generator, given what the play action builds: no link reaches the player.
            val handed = AtomicReference<ExtractorUri?>(null)
            val generated = DownloadFileGenerator(listOf(ExtractorUri(uri = Uri.EMPTY, name = "x", id = id)))
                .generateLinks(
                    clearCache = false,
                    sourceTypes = emptySet(),
                    callback = { handed.set(it.second) },
                    subtitleCallback = {},
                    offset = 0,
                    isCasting = false,
                )
            assertFalse("the generator reported links", generated)
            assertNull("the generator handed the player ${handed.get()}", handed.get())

            // The play action every list, popup and TV screen sends, in the real app: it does not navigate.
            context.setKey(HAS_DONE_SETUP_KEY, true)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val reached = AtomicBoolean(false)
                var destination: Int? = null
                awaitValue { CommonActivity.activity }
                scenario.onActivity { activity ->
                    reached.set(CommonActivity.activity === activity)
                    handleDownloadClick(
                        DownloadClickEvent(
                            DOWNLOAD_ACTION_PLAY_FILE,
                            context.getKey<DownloadObjects.DownloadEpisodeCached>(episodeFolder, id.toString())!!,
                        )
                    )
                }
                delay(1_500L)
                scenario.onActivity { activity ->
                    val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                    destination = host.navController.currentDestination?.id
                }
                assertTrue("the play action had no activity to act in", reached.get())
                assertTrue("it navigated to the player", destination != R.id.navigation_player)
            }
            assertRunning(id)
        }
    }

    // ---- 3. not pruned while running -----------------------------------------------------------

    @Test
    fun theDownloadsListDoesNotPruneARunningEngineDownload(): Unit = runBlocking {
        val id = newId(SERIES_ID + 3)
        // Has an episode key and nothing else: what pruning is for, so the test shows it did run.
        val orphan = newId(SERIES_ID + 4)
        val source = SeededSource(seed = 13, size = 64 * MIB)
        EngineTestServer(source).use { server ->
            server.bytesPerSecond = MIB / 2
            startInstance(id, server.url)
            awaitStaged(id, atLeast = MIB)
            assertRunning(id)
            // The queue looks idle to calculateDownloadStats, so its pruning branch is the one taken.
            assertFalse(DownloadQueueService.isRunning)
            assertTrue(DownloadQueueService.downloadInstances.value.isEmpty())
            assertTrue(DownloadQueueManager.queue.value.isEmpty())

            DownloadViewModel().updateHeaderList(context)
            awaitValue(timeoutMs = 15_000L) {
                if (context.getKey<DownloadObjects.DownloadEpisodeCached>(episodeFolder, orphan.toString()) == null) true else null
            }
            delay(500L)
            assertNotNull(
                "the running download's entry was pruned",
                context.getKey<DownloadObjects.DownloadEpisodeCached>(episodeFolder, id.toString()),
            )
            assertRunning(id)
        }
    }

    // ---- 4. stop or cancel ---------------------------------------------------------------------

    @Test
    fun aStopDuringTheFetchLeavesNoStagingNoFileAndNoListing(): Unit = runBlocking {
        val id = newId(SERIES_ID + 5)
        EngineTestServer(SeededSource(seed = 14, size = 64 * MIB)).use { server ->
            server.bytesPerSecond = MIB / 2
            val instance = startInstance(id, server.url)
            awaitStaged(id, atLeast = 2 * MIB)
            assertRunning(id)

            VideoDownloadManager.downloadEvent.invoke(id to DownloadActionType.Stop)
            awaitEnded(id, instance)

            // A stop is what the user asked for: `success`, as `DOWNLOAD_STOPPED` is, so the instance is not failed.
            assertTrue(instance.isCompleted)
            assertNothingLeft(id)
            // What stopping downloadThing leaves too: the record stays, nothing it points at does.
            assertTrue(listed(id))
        }
    }

    @Test
    fun aCancelDuringTheFetchLeavesNoStagingNoFileAndNoListing(): Unit = runBlocking {
        val id = newId(SERIES_ID + 6)
        EngineTestServer(SeededSource(seed = 15, size = 64 * MIB)).use { server ->
            server.bytesPerSecond = MIB / 2
            val instance = startInstance(id, server.url)
            awaitStaged(id, atLeast = 2 * MIB)
            assertRunning(id)

            // What DownloadQueueManager.cancelDownload does to a running instance.
            instance.cancelDownload()
            awaitEnded(id, instance)

            assertNothingLeft(id)
        }
    }

    /** Delete from the Downloads list or the queue popup: `deleteFile` finds no file, and used to refuse. */
    @Test
    fun deletingARunningEngineDownloadStopsItAndLeavesNothing(): Unit = runBlocking {
        val id = newId(SERIES_ID + 9)
        EngineTestServer(SeededSource(seed = 18, size = 64 * MIB)).use { server ->
            server.bytesPerSecond = MIB / 2
            val instance = startInstance(id, server.url)
            awaitStaged(id, atLeast = 2 * MIB)
            assertRunning(id)

            val deleted = AtomicReference<Set<Int>?>(null)
            VideoDownloadManager.deleteFilesAndUpdateSettings(context, setOf(id), this) { deleted.set(it) }
            assertEquals(setOf(id), awaitValue { deleted.get() })
            awaitEnded(id, instance)

            assertNothingLeft(id)
            assertFalse("the record survived the delete", listed(id))
        }
    }

    /**
     * Holds the run at the copy: a first run is cut off after its copy (as `EngineDownloadDeviceTest`'s crash
     * tests do), so the restart goes straight to the copy, and the Stop is sent from the notification the restart
     * raises as it starts copying. The copy loop's stop check is what ends it, with the destination already open.
     */
    @Test
    fun aStopDuringTheCopyLeavesNoStagingNoFileAndNoListing(): Unit = runBlocking {
        val id = newId(SERIES_ID + 7)
        val source = SeededSource(seed = 16, size = 24 * MIB)
        EngineTestServer(source).use { server ->
            val downloader = { SegmentedDownloader(HttpClientFactory(app.baseClient).create(), FileKitStorage()) }
            val first = EngineVideoDownload(
                context, downloader(), stagingRoot, allowPrivateAddresses = true,
                afterCopyVerified = { throw IllegalStateException("simulated crash after the copy") },
            )
            assertFalse(first.download(link(server.url), nameOf(id), FOLDER, false, id) {}.success)
            assertTrue("no marker", File(stagingOf(id), EngineDownloadRules.MARKER_FILE_NAME).isFile)

            val mark = server.log.size
            val copyStarted = AtomicBoolean(false)
            val status = EngineVideoDownload(context, downloader(), stagingRoot, allowPrivateAddresses = true)
                .download(link(server.url), nameOf(id), FOLDER, true, id) { meta ->
                    if (meta.type == DownloadType.IsDownloading && copyStarted.compareAndSet(false, true)) {
                        VideoDownloadManager.downloadEvent.invoke(id to DownloadActionType.Stop)
                    }
                }

            assertTrue("the restart never reached the copy", copyStarted.get())
            assertEquals("the restart fetched", mark, server.log.size)
            assertEquals(EngineDownloadRules.STOPPED, status)
            assertNothingLeft(id)
        }
    }

    // ---- 5. pause and resume through downloadEvent ---------------------------------------------

    @Test
    fun pauseStopsTrafficAndResumeGoesOnFromDiskToAByteIdenticalFile(): Unit = runBlocking {
        val id = newId(SERIES_ID + 8)
        val source = SeededSource(seed = 17, size = 32 * MIB)
        EngineTestServer(source).use { server ->
            server.bytesPerSecond = MIB / 2
            val lastParts = AtomicReference<List<PartSnapshot>>(emptyList())
            val wrapper = EngineVideoDownload(
                context,
                SegmentedDownloader(HttpClientFactory(app.baseClient).create(), FileKitStorage()),
                stagingRoot,
                allowPrivateAddresses = true,
                onSnapshot = { s: DownloadSnapshot -> if (s.parts.isNotEmpty()) lastParts.set(s.parts) },
            )
            val run = async(Dispatchers.IO) { wrapper.download(link(server.url), nameOf(id), FOLDER, false, id) {} }
            val deadline = System.currentTimeMillis() + 60_000L
            while (lastParts.get().sumOf { it.bytesDone } < 6 * MIB && System.currentTimeMillis() < deadline) delay(50)

            VideoDownloadManager.downloadEvent.invoke(id to DownloadActionType.Pause)
            // Let the closed connections drain, then watch the wire.
            delay(1_500L)
            val requestsAtPause = server.log.size
            val sentAtPause = server.log.sumOf { it.bytesSent }
            val held = partFilesOf(id)
            delay(2_000L)
            Log.i(TAG, "pause: requests=$requestsAtPause sent=$sentAtPause held=$held")
            assertEquals("a request was made while paused", requestsAtPause, server.log.size)
            assertEquals("bytes were sent while paused", sentAtPause, server.log.sumOf { it.bytesSent })
            assertEquals("the part files grew while paused", held, partFilesOf(id))
            assertFalse("the run ended on a pause", run.isCompleted)

            val starts = lastParts.get().associate { it.id to it.start }
            val heldRanges = held.mapNotNull { (part, length) ->
                starts[part]?.let { start -> start until start + length }
            }.filter { !it.isEmpty() }
            val heldBytes = heldRanges.sumOf { it.last - it.first + 1 }
            assertTrue("nothing was on disk to resume from", heldBytes >= 2 * MIB)

            server.bytesPerSecond = 0L
            VideoDownloadManager.downloadEvent.invoke(id to DownloadActionType.Resume)
            val status = run.await()
            val resumed = server.log.drop(requestsAtPause).filter { !it.isProbe && it.method == "GET" }
            Log.i(TAG, "resume: status=$status resumed=$resumed")

            assertTrue(status.success)
            assertTrue("nothing was requested after the resume", resumed.isNotEmpty())
            // Every resumed request starts at or past what its range held, less at most the channel's discard.
            resumed.forEach { request ->
                val start = request.rangeStart!!
                val end = request.rangeEnd ?: (source.size - 1)
                heldRanges.forEach { range ->
                    val overlap = minOf(end, range.last) - maxOf(start, range.first) + 1
                    assertTrue("$request asked again for $overlap held bytes of $range", overlap <= CHANNEL_DISCARD)
                }
            }
            val files = visibleFiles(id)
            assertEquals(1, files.size)
            assertNull(source.describeMismatch(context.contentResolver.openInputStream(files.single().uri()!!)!!))
            assertFalse(stagingOf(id).exists())
        }
    }
}

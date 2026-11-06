package com.lagradost.cloudstream4.download

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DataStore.removeKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getBasePath
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadEpisodeMetadata
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadItem
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadResumePackage
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadStatus
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream4.core.http.HttpClientFactory
import com.lagradost.cloudstream4.media.download.DownloadSnapshot
import com.lagradost.cloudstream4.media.download.DownloadStorage
import com.lagradost.cloudstream4.media.download.FileKitStorage
import com.lagradost.cloudstream4.media.download.PartSnapshot
import com.lagradost.cloudstream4.media.download.SegmentedDownloader
import com.lagradost.cloudstream4.media.model.DownloadFailure
import com.lagradost.cloudstream4.media.model.DownloadState
import com.lagradost.safefile.SafeFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val MIB = 1_048_576L
private const val FOLDER = "EngineDownloadDeviceTest"
private const val TAG = "EngineDownloadDeviceTest"

/** Stands in for the process dying at a chosen line. */
private class SimulatedCrash : RuntimeException("simulated crash")

/**
 * The `:media:download` engine wired into `VideoDownloadManager`, on a device, against a server on
 * 127.0.0.1 inside the test process. Every finished file is compared byte for byte with the source.
 *
 * Two tests go through the real `EpisodeDownloadInstance` (switch on, switch off); the rest call
 * [EngineVideoDownload] directly, the way `downloadSingleEpisode` does, so a crash or a quota can be
 * injected at one exact point.
 */
@RunWith(AndroidJUnit4::class)
class EngineDownloadDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val stagingRoot = File(context.filesDir, "engine-device-test-staging")
    private val ids = mutableListOf<Int>()

    @Before
    fun setUp() {
        // Below API 29 the default folder is a plain path under Download/, which needs the storage
        // permission a user grants in the app; without it every destination, old path or new, fails.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            // `pm grant` through the shell: UiAutomation.grantRuntimePermission is API 28.
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE).forEach {
                automation.executeShellCommand("pm grant ${context.packageName} $it")
                    .let { output -> ParcelFileDescriptor.AutoCloseInputStream(output).use { s -> s.readBytes() } }
            }
        }
        stagingRoot.deleteRecursively()
        EngineDownloadSwitch.allowPrivateAddressesForTests = true
        EngineDownloadSwitch.stagingRootForTests = stagingRoot
    }

    @After
    fun tearDown() {
        EngineDownloadSwitch.useSegmentedEngine = false
        EngineDownloadSwitch.allowPrivateAddressesForTests = false
        EngineDownloadSwitch.storageForTests = null
        EngineDownloadSwitch.stagingRootForTests = null
        ids.forEach { id ->
            visibleFiles(nameOf(id)).forEach { it.delete() }
            DownloadFacts.clear(id)
        }
        stagingRoot.deleteRecursively()
    }

    private fun nameOf(id: Int) = "engine-$id"

    private fun newId(id: Int) = id.also { ids += it }

    private fun link(url: String): ExtractorLink =
        runBlocking { newExtractorLink("fake", "fake", url, ExtractorLinkType.VIDEO) }

    /** A fresh downloader and a fresh wrapper: nothing is shared with an earlier one but the disk. */
    private fun wrapper(
        storage: DownloadStorage = FileKitStorage(),
        afterCopy: () -> Unit = {},
        onSnapshot: (DownloadSnapshot) -> Unit = {},
    ) = EngineVideoDownload(
        context = context,
        downloader = SegmentedDownloader(HttpClientFactory(app.baseClient).create(), storage),
        stagingRoot = stagingRoot,
        allowPrivateAddresses = true,
        afterCopyVerified = afterCopy,
        onSnapshot = onSnapshot,
    )

    private suspend fun EngineVideoDownload.run(server: EngineTestServer, id: Int, tryResume: Boolean): DownloadStatus =
        download(link(server.url), nameOf(id), FOLDER, tryResume, id) {}

    private fun folder(): SafeFile? = context.getBasePath().first?.gotoDirectory(FOLDER, createMissingDirectories = false)

    /** Every file a user would see under this download's name. */
    private fun visibleFiles(name: String): List<SafeFile> =
        folder()?.listFiles()?.filter { it.name() == "$name.mp4" }.orEmpty()

    private fun assertDestinationIsTheSource(id: Int, source: SeededSource) {
        val files = visibleFiles(nameOf(id))
        assertEquals("visible files: ${files.map { it.name() }}", 1, files.size)
        val uri = files.single().uri()!!
        assertNull(source.describeMismatch(context.contentResolver.openInputStream(uri)!!))
    }

    /** How many distinct bytes the inclusive [ranges] cover together. */
    private fun unionLength(ranges: List<LongRange>): Long {
        var covered = 0L
        var end = -1L
        ranges.sortedBy { it.first }.forEach { range ->
            val start = maxOf(range.first, end + 1)
            if (range.last >= start) covered += range.last - start + 1
            end = maxOf(end, range.last)
        }
        return covered
    }

    private fun stagingOf(id: Int) = File(stagingRoot, id.toString())

    private fun tempOf(id: Int) = File(stagingOf(id), ".${nameOf(id)}.mp4.csdl")

    private fun partFilesOf(id: Int): Map<Int, Long> = tempOf(id).listFiles().orEmpty()
        .filter { it.name.startsWith("part-") }
        .associate { it.name.removePrefix("part-").toInt() to it.length() }

    // ---- through the real instance -----------------------------------------------------------

    private fun item(id: Int, url: String) = DownloadItem(
        source = "fake",
        folder = FOLDER,
        ep = DownloadEpisodeMetadata(
            id = id,
            parentId = id,
            mainName = "engine",
            sourceApiName = "fake",
            poster = null,
            name = nameOf(id),
            season = null,
            episode = null,
            type = TvType.Movie,
        ),
        links = listOf(link(url)),
    )

    private suspend fun runInstance(id: Int, url: String): VideoDownloadManager.EpisodeDownloadInstance {
        val instance = VideoDownloadManager.EpisodeDownloadInstance(
            context,
            DownloadResumePackage(item(id, url), null).toWrapper(),
        )
        instance.startDownload()
        val deadline = System.currentTimeMillis() + 180_000L
        while (System.currentTimeMillis() < deadline) {
            val ended = instance.isCompleted || instance.isFailed
            if (ended && !VideoDownloadManager.currentDownloads.value.contains(id)) return instance
            delay(50)
        }
        throw AssertionError("download $id did not end")
    }

    @Test
    fun switchOnAnEpisodeDownloadsThroughTheEngineAndCompletesByteIdentical() = runBlocking {
        val id = newId(930_001)
        val source = SeededSource(seed = 1, size = 24 * MIB)
        EngineTestServer(source).use { server ->
            EngineDownloadSwitch.useSegmentedEngine = true
            val instance = runInstance(id, server.url)
            Log.i(TAG, "switch-on: requests=${server.log.toList()} fact=${DownloadFacts.facts.value[id]}")

            assertTrue(instance.isCompleted)
            assertDestinationIsTheSource(id, source)
            val info = VideoDownloadManager.getDownloadFileInfo(context, id)
            assertNotNull(info)
            assertEquals(source.size, info!!.totalBytes)
            val fact = DownloadFacts.facts.value[id]!!
            assertEquals(DownloadState.COMPLETED, fact.state)
            assertEquals(source.size, fact.bytesWritten)
            assertFalse("the staging directory was left behind", stagingOf(id).exists())
            // The engine probes with a ranged GET; downloadThing starts with a HEAD.
            assertTrue(server.log.any { it.isProbe })
            assertTrue(server.log.none { it.method == "HEAD" })
        }
    }

    @Test
    fun switchOffAnEpisodeStillDownloadsThroughDownloadThing() = runBlocking {
        val id = newId(930_002)
        // Over downloadThing's 10 MiB minimum for a video.
        val source = SeededSource(seed = 2, size = 12 * MIB)
        EngineTestServer(source).use { server ->
            assertFalse(EngineDownloadSwitch.useSegmentedEngine)
            val instance = runInstance(id, server.url)
            Log.i(TAG, "switch-off: requests=${server.log.toList()}")

            assertTrue(instance.isCompleted)
            assertDestinationIsTheSource(id, source)
            assertTrue("downloadThing always starts with a HEAD", server.log.any { it.method == "HEAD" })
            assertTrue("the engine's probe was sent", server.log.none { it.isProbe })
            assertFalse("the engine staged something", stagingOf(id).exists())
        }
    }

    // ---- the start-up sweep ------------------------------------------------------------------------

    @Test
    fun theStartUpSweepRemovesOnlyStagingWhoseEpisodeIsGone() {
        assertEquals(File(context.noBackupFilesDir, "downloads"), EngineVideoDownload.defaultStagingRoot(context))
        val root = File(stagingRoot, "sweep-root")
        val orphan = 930_101
        val resumable = 930_102
        val queued = 930_103
        val pruned = 930_104
        val all = listOf(orphan, resumable, queued, pruned)
        all.forEach { id -> File(root, "$id/part-0").apply { parentFile!!.mkdirs(); writeText("x") } }
        File(root, "not-an-id").mkdirs()
        try {
            context.setKey("${VideoDownloadManager.KEY_RESUME_PACKAGES}/$resumable", "x")
            context.setKey("$DOWNLOAD_EPISODE_CACHE/1/$resumable", "x")
            // What the Downloads list leaves after pruning a failed download: the resume package alone.
            context.setKey("${VideoDownloadManager.KEY_RESUME_PACKAGES}/$pruned", "x")

            EngineVideoDownload.sweepOrphanedStaging(context, queuedIds = listOf(queued), root = root)

            assertFalse("an orphan survived", File(root, "$orphan").exists())
            assertFalse("a pruned download's staging survived", File(root, "$pruned").exists())
            assertTrue("a resumable download lost its staging", File(root, "$resumable/part-0").isFile)
            assertTrue("a queued download lost its staging", File(root, "$queued/part-0").isFile)
            assertTrue("a directory that is not ours was deleted", File(root, "not-an-id").isDirectory)
        } finally {
            context.removeKey(VideoDownloadManager.KEY_RESUME_PACKAGES, "$resumable")
            context.removeKey(VideoDownloadManager.KEY_RESUME_PACKAGES, "$pruned")
            context.removeKey("$DOWNLOAD_EPISODE_CACHE/1", "$resumable")
            root.deleteRecursively()
        }
    }

    // ---- the wrapper, with faults ---------------------------------------------------------------

    /**
     * **Not a real process kill.** One cannot be made from inside the instrumentation process, which is
     * the process under test. This is the strongest honest stand-in: the coroutine running the download
     * is cancelled mid-transfer, which is the engine's kill-safe path (its parts and state file stay on
     * disk), and then a new [SegmentedDownloader] and a new [EngineVideoDownload] that share nothing with
     * the first but the disk finish the file. `:media:download`'s `KillSuiteTest` kills a real JVM.
     *
     * The proof of a resume is on the wire: no byte the parts already held on disk is asked for again.
     */
    @Test
    fun aDownloadCutOffMidwayResumesFromDiskInAFreshInstanceWithoutRefetchingAnyHeldByte() = runBlocking {
        val id = newId(930_003)
        val source = SeededSource(seed = 3, size = 32 * MIB)
        EngineTestServer(source).use { server ->
            server.bytesPerSecond = MIB
            val lastParts = AtomicReference<List<PartSnapshot>>(emptyList())
            val first = wrapper(onSnapshot = { s -> if (s.parts.isNotEmpty()) lastParts.set(s.parts) })
            val job = launch(Dispatchers.IO) { first.run(server, id, tryResume = false) }
            val deadline = System.currentTimeMillis() + 60_000L
            while (lastParts.get().sumOf { it.bytesDone } < 8 * MIB && System.currentTimeMillis() < deadline) delay(50)
            job.cancelAndJoin()

            val starts = lastParts.get().associate { it.id to it.start }
            val held = partFilesOf(id).mapNotNull { (part, length) ->
                starts[part]?.let { start -> start until start + length }
            }.filter { !it.isEmpty() }
            val heldBytes = held.sumOf { it.last - it.first + 1 }
            Log.i(TAG, "kill: held=$held ($heldBytes bytes) parts=${lastParts.get()}")
            assertTrue("nothing was on disk to resume from", heldBytes >= 4 * MIB)
            assertTrue("the download finished before it was cut off", heldBytes < source.size)

            val mark = server.log.size
            server.bytesPerSecond = 0L
            val status = wrapper().run(server, id, tryResume = true)
            val resumed = server.log.drop(mark).filter { !it.isProbe && it.method == "GET" }
            Log.i(TAG, "kill: status=$status resumed=$resumed")

            assertTrue(status.success)
            assertDestinationIsTheSource(id, source)
            resumed.forEach { request ->
                val start = request.rangeStart!!
                val end = request.rangeEnd ?: (source.size - 1)
                held.forEach { range ->
                    assertFalse("$request asked again for held bytes $range", start <= range.last && end >= range.first)
                }
            }
            // The union, not the sum: the engine's work-stealing splits a range already in flight, so the
            // tail of one request can be asked for again by another (seen as #19 inside #18 on 2026-11-06).
            // That duplicates in-flight bytes, not held ones, and is the engine's to answer.
            val requested = unionLength(resumed.map { it.rangeStart!!..(it.rangeEnd ?: (source.size - 1)) })
            assertTrue("requested $requested distinct bytes of ${source.size}", requested <= source.size - heldBytes)
            assertFalse(stagingOf(id).exists())
        }
    }

    @Test
    fun aConnectionDroppedMidPartIsResumedWithinThePartAndTheFileIsByteIdentical() = runBlocking {
        val id = newId(930_004)
        val source = SeededSource(seed = 4, size = 16 * MIB)
        EngineTestServer(source).use { server ->
            val bodyRequests = AtomicInteger()
            val dropAfter = 256 * 1024L
            server.fault = { served ->
                if (!served.isProbe && served.method == "GET" && bodyRequests.incrementAndGet() == 3) {
                    Fault.DropAfter(dropAfter)
                } else {
                    null
                }
            }
            val status = wrapper().run(server, id, tryResume = false)
            Log.i(TAG, "drop: status=$status requests=${server.log.toList()}")

            assertTrue(status.success)
            assertDestinationIsTheSource(id, source)
            val dropped = server.bodyRequests().single { it.bytesSent == dropAfter && it.rangeEnd != null &&
                it.rangeEnd!! - it.rangeStart!! + 1 > dropAfter }
            val start = dropped.rangeStart!!
            assertTrue(
                "no request carried on inside the dropped part",
                server.bodyRequests().any { it.index > dropped.index && it.rangeStart!! in (start + 1)..(start + dropAfter) },
            )
        }
    }

    @Test
    fun aFullDiskFailsStorageFullLeavesNoVisibleFileAndKeepsTheStagedParts() = runBlocking {
        val id = newId(930_005)
        val source = SeededSource(seed = 5, size = 16 * MIB)
        EngineTestServer(source).use { server ->
            val quota = QuotaStorage(limitBytes = 3 * MIB)
            val status = wrapper(storage = quota).run(server, id, tryResume = false)
            val fact = DownloadFacts.facts.value[id]
            Log.i(TAG, "disk-full: status=$status fact=$fact parts=${partFilesOf(id)}")

            assertFalse(status.success)
            assertFalse("another mirror writes to the same disk", status.tryNext)
            assertFalse(status.retrySame)
            assertEquals(DownloadState.FAILED, fact!!.state)
            assertTrue("not a full disk: ${fact.failure}", fact.failure is DownloadFailure.StorageFull)
            assertTrue("a file is visible", visibleFiles(nameOf(id)).isEmpty())
            assertTrue("the staged parts went", partFilesOf(id).values.sum() > 0L)
        }
    }

    /** Stages a complete download whose run "dies" after the copy was verified and recorded. */
    private suspend fun crashAfterCopy(server: EngineTestServer, id: Int) {
        val status = wrapper(afterCopy = { throw SimulatedCrash() }).run(server, id, tryResume = false)
        assertFalse(status.success)
        assertTrue("no marker", File(stagingOf(id), EngineDownloadRules.MARKER_FILE_NAME).isFile)
        assertEquals(server.source.size, File(stagingOf(id), "${nameOf(id)}.mp4").length())
    }

    @Test
    fun aCrashAfterTheCopyLeavesOneVisibleFileAndTheRestartCopiesWithoutFetching() = runBlocking {
        val id = newId(930_006)
        val source = SeededSource(seed = 6, size = 12 * MIB)
        EngineTestServer(source).use { server ->
            crashAfterCopy(server, id)
            assertDestinationIsTheSource(id, source)

            val mark = server.log.size
            val status = wrapper().run(server, id, tryResume = true)
            assertTrue(status.success)
            assertEquals("the restart fetched again", mark, server.log.size)
            assertDestinationIsTheSource(id, source)
            assertNotNull(VideoDownloadManager.getDownloadFileInfo(context, id))
            assertFalse(stagingOf(id).exists())
        }
    }

    @Test
    fun aCrashDuringTheCopyIsRedoneOverThePartialFileWithoutFetching() = runBlocking {
        val id = newId(930_007)
        val source = SeededSource(seed = 7, size = 12 * MIB)
        EngineTestServer(source).use { server ->
            crashAfterCopy(server, id)
            // What a crash halfway through the copy leaves: the first half, under the real name.
            val half = source.size / 2
            val stream = VideoDownloadManager.setupStream(context, nameOf(id), FOLDER, "mp4", false)
            stream.open().use { out ->
                val buffer = ByteArray(MIB.toInt())
                var written = 0L
                while (written < half) {
                    source.fill(written, buffer, 0, buffer.size)
                    out.write(buffer)
                    written += buffer.size
                }
            }
            assertEquals(half, VideoDownloadManager.fileLengthOnDisk(context, stream.file.uri()))

            val mark = server.log.size
            val status = wrapper().run(server, id, tryResume = true)
            assertTrue(status.success)
            assertEquals("the restart fetched again", mark, server.log.size)
            assertDestinationIsTheSource(id, source)
            assertFalse(stagingOf(id).exists())
        }
    }
}

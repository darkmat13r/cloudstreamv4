package com.lagradost.cloudstream4.download

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.services.DownloadQueueService
import com.lagradost.cloudstream3.ui.player.RepoLinkGenerator
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DataStore.getFolderName
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.removeKey
import com.lagradost.cloudstream3.utils.DataStore.removeKeys
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.toFile
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadQueueItem
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadQueueWrapper
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadedFileInfo
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadActionType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream4.media.model.DownloadFailure
import com.lagradost.cloudstream4.media.model.DownloadState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val MIB = 1_048_576L
private const val TOTAL = 24 * MIB
private const val PARTS = 8
private const val PART_BYTES = TOTAL / PARTS
private const val PROVIDER = "Kmp80FakeProvider"
private const val TAG = "EngineResumeAfterRestart"

/** The provider a resume asks again: `loadLinks` answers whatever the test last set. */
private class AskAgainProvider : MainAPI() {
    override var name = PROVIDER
    override var mainUrl = "http://127.0.0.1"
    override val supportedTypes = setOf(TvType.Movie)

    @Volatile
    var answer: List<ExtractorLink> = emptyList()
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        calls += data
        answer.forEach(callback)
        return answer.isNotEmpty()
    }
}

/**
 * `KMP-80`: with `EngineDownloadSwitch` on, a download that stopped with parts staged is resumed after a restart by
 * asking the provider again (`KMP-62`), and the fresh link reaches the engine's resume rule (rule 12).
 *
 * The first run goes through the real `EpisodeDownloadInstance` until its link expires: the server cuts every
 * connection and answers 403 from then on, which fails the run with its parts kept. A restart here is what one leaves
 * behind: the stored keys and the staging on disk, an empty link cache and no download status. The second run is a
 * new instance started from the stored resume package, and the provider answers with a fresh URL on another server.
 *
 * Every part is [PART_BYTES] and so is `minPartBytes`, so no part is ever split and part `i` starts at
 * `i * PART_BYTES`. That makes the bytes held on disk exact.
 */
@RunWith(AndroidJUnit4::class)
class EngineResumeAfterRestartDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val stagingRoot = File(context.filesDir, "engine-resume-device-test-staging")
    private val provider = AskAgainProvider()
    private val ids = mutableListOf<Int>()
    private var serviceWasRunning = false

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE).forEach {
                instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} $it")
                    .let { output -> ParcelFileDescriptor.AutoCloseInputStream(output).use { s -> s.readBytes() } }
            }
        }
        // Nothing here may start the real queue service: each test drives the instances itself.
        serviceWasRunning = DownloadQueueService.isRunning
        DownloadQueueService.isRunning = true
        APIHolder.allProviders.add(provider)
        APIHolder.apiMap = null
        stagingRoot.deleteRecursively()
        EngineDownloadSwitch.useSegmentedEngine = true
        EngineDownloadSwitch.allowPrivateAddressesForTests = true
        EngineDownloadSwitch.stagingRootForTests = stagingRoot
        EngineDownloadSwitch.minPartBytesForTests = PART_BYTES
    }

    @After
    fun tearDown(): Unit = runBlocking {
        ids.forEach { VideoDownloadManager.downloadEvent.invoke(it to DownloadActionType.Stop) }
        val deadline = System.currentTimeMillis() + 30_000L
        while (ids.any { VideoDownloadManager.currentDownloads.value.contains(it) } &&
            System.currentTimeMillis() < deadline
        ) delay(50)
        EngineDownloadSwitch.useSegmentedEngine = false
        EngineDownloadSwitch.allowPrivateAddressesForTests = false
        EngineDownloadSwitch.stagingRootForTests = null
        EngineDownloadSwitch.minPartBytesForTests = null
        ids.forEach { id ->
            runCatching {
                context.getKey<DownloadedFileInfo>(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString())
                    ?.toFile(context)?.delete()
            }
            context.removeKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString())
            context.removeKey(VideoDownloadManager.KEY_RESUME_PACKAGES, id.toString())
            context.removeKey(VideoDownloadManager.KEY_RESUME_IN_QUEUE, id.toString())
            context.removeKeys("download_resume_source/$id")
            context.removeKey(getFolderName(DOWNLOAD_EPISODE_CACHE, id.toString()), id.toString())
            context.removeKey(DOWNLOAD_HEADER_CACHE, id.toString())
            DownloadFacts.clear(id)
            VideoDownloadManager.downloadStatus.remove(id)
        }
        APIHolder.allProviders.remove(provider)
        APIHolder.apiMap = null
        RepoLinkGenerator.cache.clear()
        DownloadQueueService.isRunning = serviceWasRunning
        stagingRoot.deleteRecursively()
    }

    // --- what goes in ---

    /** The provider's labels for one mirror stay the same across a restart; only its URL changes. */
    private suspend fun link(server: EngineTestServer, mirror: String = "A"): ExtractorLink =
        newExtractorLink("kmp80-source", "Mirror $mirror", server.url, ExtractorLinkType.VIDEO)

    private fun episode(id: Int) = ResultEpisode(
        headerName = "kmp80",
        name = "kmp80-$id",
        poster = null,
        episode = 1,
        seasonIndex = null,
        season = null,
        data = "kmp80-data-$id",
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

    private fun queueItem(id: Int) = DownloadQueueItem(
        episode = episode(id),
        isMovie = true,
        resultName = "kmp80-$id",
        resultType = TvType.Movie,
        resultPoster = null,
        apiName = PROVIDER,
        resultId = id,
        resultUrl = "http://127.0.0.1/result/$id",
        links = null,
        subs = null,
    ).also { ids += it.episode.id }

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

    /** What a restart leaves of this process: nothing in memory, everything on disk. */
    private fun restart(id: Int) {
        RepoLinkGenerator.cache.clear()
        VideoDownloadManager.downloadStatus.remove(id)
        DownloadFacts.clear(id)
    }

    private fun storedResume(id: Int) = VideoDownloadManager.getDownloadResumePackage(context, id)

    private fun stagingOf(id: Int) = File(stagingRoot, id.toString())

    /** Each part file under the staging directory, as the byte range of the source it holds. */
    private fun heldRanges(id: Int): List<LongRange> = stagingOf(id).walkTopDown()
        .filter { it.isFile && it.name.startsWith("part-") }
        .mapNotNull { file ->
            val start = file.name.removePrefix("part-").toLong() * PART_BYTES
            file.length().takeIf { it > 0 }?.let { start until start + it }
        }
        .toList()

    private fun heldBytes(id: Int) = heldRanges(id).sumOf { it.last - it.first + 1 }

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

    private fun EngineTestServer.bodyRanges(): List<LongRange> =
        bodyRequests().map { it.rangeStart!!..(it.rangeEnd ?: (source.size - 1)) }

    private fun assertFileIs(id: Int, source: SeededSource) {
        val info = VideoDownloadManager.getDownloadFileInfo(context, id)
        assertNotNull("no file info", info)
        assertNull(source.describeMismatch(context.contentResolver.openInputStream(info!!.path)!!))
    }

    /**
     * Runs the first download through the real instance until about a third of it is staged, then expires its link.
     * Returns the byte ranges the staged parts hold once the run has failed.
     */
    private suspend fun stageAThirdThenExpire(id: Int, server: EngineTestServer): List<LongRange> {
        provider.answer = listOf(link(server))
        server.bytesPerSecond = 512 * 1024L
        val first = start(queueItem(id).toWrapper())
        val deadline = System.currentTimeMillis() + 60_000L
        while (heldBytes(id) < TOTAL / 3 && System.currentTimeMillis() < deadline) delay(50)
        server.refuseWith = 403
        server.cutOpenConnections()
        assertTrue("the first run did not end", first.awaitEnd())
        assertTrue("the first run finished before its link expired", first.isFailed)
        assertEquals(DownloadFailure.ServerRefused(403), DownloadFacts.facts.value[id]?.failure)

        val held = heldRanges(id)
        val heldBytes = held.sumOf { it.last - it.first + 1 }
        Log.i(TAG, "staged: $heldBytes bytes in ${held.size} parts")
        assertTrue("only $heldBytes bytes were staged to resume from", heldBytes >= TOTAL / 4)
        assertTrue("the whole file was staged", heldBytes < TOTAL)
        assertNotNull("no resume package", storedResume(id))
        assertTrue("the package keeps a link", storedResume(id)!!.item.links.isEmpty())
        return held
    }

    // --- the cases ---

    /** The fresh link serves the same file (same size and ETag): the run resumes from staging. */
    @Test
    fun aRestartedDownloadWhoseFreshLinkServesTheSameFileResumesWithoutRefetchingHeldBytes() = runBlocking {
        val id = 940_001
        val source = SeededSource(seed = 801L, size = TOTAL)
        EngineTestServer(source, etag = "\"v1\"").use { expired ->
            EngineTestServer(source, etag = "\"v1\"").use { fresh ->
                val held = stageAThirdThenExpire(id, expired)
                val heldBytes = held.sumOf { it.last - it.first + 1 }

                restart(id)
                provider.answer = listOf(link(fresh))
                val resumed = start(storedResume(id)!!.toWrapper())
                assertTrue("the resume did not end", resumed.awaitEnd())
                Log.i(TAG, "same: requests=${fresh.log.toList()}")

                assertTrue("the resume failed: ${DownloadFacts.facts.value[id]}", resumed.isCompleted)
                assertEquals(DownloadState.COMPLETED, DownloadFacts.facts.value[id]?.state)
                assertEquals("the provider was not asked again", 2, provider.calls.size)
                assertFileIs(id, source)
                fresh.bodyRanges().forEach { range ->
                    held.forEach { kept ->
                        assertFalse(
                            "$range asked again for held bytes $kept",
                            range.first <= kept.last && range.last >= kept.first,
                        )
                    }
                }
                val requested = unionLength(fresh.bodyRanges())
                assertEquals("the resume fetched other than the missing bytes", TOTAL - heldBytes, requested)
                assertFalse("the staging was left behind", stagingOf(id).exists())
                assertNull(storedResume(id))
            }
        }
    }

    /**
     * The fresh link serves a different file (another ETag): the engine refuses to mix the two (`SourceChanged`),
     * drops what it staged, and the mirror loop moves on to the next link, which downloads from the start.
     */
    @Test
    fun aRestartedDownloadWhoseFreshLinkServesAnotherFileFailsSourceChangedAndMovesToTheNextMirror() = runBlocking {
        val id = 940_002
        val original = SeededSource(seed = 802L, size = TOTAL)
        val other = SeededSource(seed = 803L, size = TOTAL)
        EngineTestServer(original, etag = "\"v1\"").use { expired ->
            EngineTestServer(original, etag = "\"v2\"").use { changed ->
                EngineTestServer(other, etag = "\"m2\"").use { nextMirror ->
                    stageAThirdThenExpire(id, expired)

                    restart(id)
                    // Held until the changed link's failure has been seen, so nothing the next mirror does hides it.
                    val sawSourceChanged = CountDownLatch(1)
                    val stagedWhenItFailed = CompletableDeferred<Long>()
                    nextMirror.beforeAnswer = { sawSourceChanged.await(30, TimeUnit.SECONDS) }
                    val watcher = launch(Dispatchers.Unconfined) {
                        DownloadFacts.facts.collect { facts ->
                            if (facts[id]?.failure == DownloadFailure.SourceChanged && sawSourceChanged.count > 0) {
                                stagedWhenItFailed.complete(heldBytes(id))
                                sawSourceChanged.countDown()
                            }
                        }
                    }
                    // The changed link carries the labels of the one the staged parts came from, so it goes first.
                    provider.answer = listOf(link(nextMirror, mirror = "B"), link(changed))
                    val resumed = start(storedResume(id)!!.toWrapper())
                    assertTrue("the resume did not end", resumed.awaitEnd())
                    watcher.cancel()
                    Log.i(TAG, "changed: changed=${changed.log.toList()} next=${nextMirror.log.size} requests")

                    assertEquals("SourceChanged was never reported", 0L, sawSourceChanged.count)
                    assertEquals(
                        "the changed file's staged parts were kept",
                        0L,
                        withTimeoutOrNull(1_000L) { stagedWhenItFailed.await() },
                    )
                    assertTrue("the changed link was not tried first", changed.log.isNotEmpty())
                    assertTrue("bytes were fetched from the changed file", changed.bodyRequests().isEmpty())
                    assertTrue("the next mirror failed: ${DownloadFacts.facts.value[id]}", resumed.isCompleted)
                    assertEquals(DownloadState.COMPLETED, DownloadFacts.facts.value[id]?.state)
                    assertEquals("the provider was not asked again", 2, provider.calls.size)
                    assertFileIs(id, other)
                    assertEquals("the next mirror did not start from 0", TOTAL, unionLength(nextMirror.bodyRanges()))
                    assertFalse("the staging was left behind", stagingOf(id).exists())
                    assertNull(storedResume(id))
                }
            }
        }
    }
}

package com.lagradost.cloudstream4.download

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.download.DownloadViewModel
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE_BACKUP
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE_BACKUP
import com.lagradost.cloudstream3.utils.DataStore.getFolderName
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getBasePath
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `BUG-86`: two reads of the Downloads list that finish in the reverse of the order they began. The first read saw a
 * series that was then deleted; the second read, begun after the delete, saw none. The list must end on the second.
 *
 * Before the fix the first read posted last, and the deleted series' row stayed with nothing to refresh it. Not what
 * `DownloadSeriesTvNavigationTest`'s delete-all met: that was `BUG-80`, the series page closing twice. Here the order
 * is forced, not hoped for. The first read is given the store as it was before the delete, and it is held at its first
 * read until the second has posted.
 */
@RunWith(AndroidJUnit4::class)
class DownloadHeaderRaceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val folder = getFolderName(DOWNLOAD_EPISODE_CACHE, SERIES_ID.toString())

    @Before
    fun seedOneRealEpisode() {
        instrumentation.runOnMainSync {
            setKey(
                DOWNLOAD_HEADER_CACHE,
                SERIES_ID.toString(),
                DownloadObjects.DownloadHeaderCached(
                    apiName = "NoSuchProvider",
                    url = "https://example.invalid/race",
                    type = TvType.TvSeries,
                    name = SERIES,
                    poster = null,
                    cacheTime = 1L,
                    id = SERIES_ID,
                ),
            )
            setKey(
                folder,
                EPISODE_ID.toString(),
                DownloadObjects.DownloadEpisodeCached(
                    name = "Episode 1",
                    poster = null,
                    episode = 1,
                    season = 1,
                    parentId = SERIES_ID,
                    score = null,
                    description = null,
                    cacheTime = 1L,
                    id = EPISODE_ID,
                ),
            )
        }
        VideoDownloadManager.setupStream(context, FILE_NAME, FOLDER, "mp4", false).open().use { it.write(ByteArray(FILE_BYTES)) }
        instrumentation.runOnMainSync {
            setKey(
                VideoDownloadManager.KEY_DOWNLOAD_INFO,
                EPISODE_ID.toString(),
                DownloadObjects.DownloadedFileInfo(
                    totalBytes = FILE_BYTES.toLong(),
                    relativePath = FOLDER,
                    displayName = "$FILE_NAME.mp4",
                    basePath = context.getBasePath().second,
                ),
            )
        }
    }

    @After
    fun removeIt() {
        runCatching {
            context.getBasePath().first?.gotoDirectory(FOLDER, createMissingDirectories = false)
                ?.findFile("$FILE_NAME.mp4")?.delete()
        }
        instrumentation.runOnMainSync {
            removeKey(folder, EPISODE_ID.toString())
            removeKey(getFolderName(DOWNLOAD_EPISODE_CACHE_BACKUP, SERIES_ID.toString()), EPISODE_ID.toString())
            removeKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, EPISODE_ID.toString())
            removeKey(DOWNLOAD_HEADER_CACHE, SERIES_ID.toString())
            removeKey(DOWNLOAD_HEADER_CACHE_BACKUP, SERIES_ID.toString())
        }
    }

    @Test
    fun aReadThatBeganBeforeTheDeleteCannotPostAfterOneThatBeganAfterIt() {
        val viewModel = DownloadViewModel()
        // The store as the first read found it, the series still in it; then the delete, as delete-all leaves it.
        val before = BeforeTheDelete(context)
        instrumentation.runOnMainSync { removeKey(folder, EPISODE_ID.toString()) }

        lateinit var first: Job
        lateinit var second: Job
        instrumentation.runOnMainSync { first = viewModel.updateHeaderList(before) }
        assertTrue("the first read began", before.reading.await(WAIT_SECONDS, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { second = viewModel.updateHeaderList(context) }
        runBlocking { withTimeout(WAIT_MILLIS) { second.join() } }
        instrumentation.waitForIdleSync()
        assertEquals("the second read, after the delete, has no row for the series", emptyList<String>(), seriesRows(viewModel))

        // Now the first read, begun before the delete, finishes.
        before.release.countDown()
        runBlocking { withTimeout(WAIT_MILLIS) { first.join() } }
        instrumentation.waitForIdleSync()
        assertTrue("the first read found the series", before.served)
        assertEquals("the list ends on the later read", emptyList<String>(), seriesRows(viewModel))
    }

    private fun seriesRows(viewModel: DownloadViewModel): List<String> {
        var rows: List<String>? = null
        instrumentation.runOnMainSync {
            val value = viewModel.headerCards.value
            rows = (value as? Resource.Success)?.value.orEmpty().map { it.data.name }.filter { it == SERIES }
        }
        return checkNotNull(rows)
    }

    /**
     * [base], except that the download store is a copy taken now, and the first read of it waits for [release]. Writes
     * go to the real store, as they would.
     */
    private class BeforeTheDelete(base: Context) : ContextWrapper(base) {
        private val store = base.getSharedPrefs()
        private val copy: Map<String, *> = HashMap(store.all)
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)

        @Volatile
        var served = false

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            val real = super.getSharedPreferences(name, mode)
            return if (real === store) Copy(real) else real
        }

        private inner class Copy(private val real: SharedPreferences) : SharedPreferences by real {
            override fun getAll(): Map<String, *> {
                if (reading.count > 0) {
                    reading.countDown()
                    check(release.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "never released" }
                }
                served = served || copy.keys.any { it.startsWith(DOWNLOAD_EPISODE_CACHE) }
                return copy
            }

            override fun getString(key: String?, defValue: String?): String? = copy[key] as? String ?: defValue

            override fun contains(key: String?): Boolean = copy.containsKey(key)
        }
    }

    private companion object {
        const val SERIES_ID = 987_654_480
        const val EPISODE_ID = 987_654_481
        const val SERIES = "A Raced Show"
        const val FOLDER = "TVSeries/HeaderRaceTest"
        const val FILE_NAME = "header-race-test"
        const val FILE_BYTES = 8_192
        const val WAIT_SECONDS = 20L
        const val WAIT_MILLIS = WAIT_SECONDS * 1_000
    }
}

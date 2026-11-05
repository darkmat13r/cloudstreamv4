package com.lagradost.cloudstream4.download

import android.content.Context
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.download.DownloadChildFragment
import com.lagradost.cloudstream3.ui.download.DownloadFragment
import com.lagradost.cloudstream3.ui.download.queue.DownloadQueueFragment
import com.lagradost.cloudstream3.ui.settings.Globals.updateTv
import com.lagradost.cloudstream3.ui.setup.HAS_DONE_SETUP_KEY
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DataStore.getFolderName
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getBasePath
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadType
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.media.model.DownloadFailure
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real app on a TV: the rail opens the new downloads screen through real navigation, a real file under the real
 * keys is on it, a remote can open it, and Back puts focus back on it.
 */
@RunWith(AndroidJUnit4::class)
class DownloadsTvNavigationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val dpad = DpadDriver()
    private val displayName = "$FILE_NAME.mp4"

    @Before
    fun seedARealDownload() {
        instrumentation.runOnMainSync {
            setKey(HAS_DONE_SETUP_KEY, true)
            setKey(
                DOWNLOAD_HEADER_CACHE,
                SERIES_ID.toString(),
                DownloadObjects.DownloadHeaderCached(
                    apiName = "NoSuchProvider",
                    url = "https://example.invalid/title",
                    type = TvType.TvSeries,
                    name = SERIES,
                    poster = null,
                    cacheTime = 1L,
                    id = SERIES_ID,
                ),
            )
            setKey(
                getFolderName(DOWNLOAD_EPISODE_CACHE, SERIES_ID.toString()),
                EPISODE_ID.toString(),
                DownloadObjects.DownloadEpisodeCached(
                    name = "Episode one",
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
        VideoDownloadManager.setupStream(context, FILE_NAME, FOLDER, "mp4", false).open().use {
            it.write(ByteArray(FILE_BYTES))
        }
        instrumentation.runOnMainSync {
            setKey(
                VideoDownloadManager.KEY_DOWNLOAD_INFO,
                EPISODE_ID.toString(),
                DownloadObjects.DownloadedFileInfo(
                    totalBytes = FILE_BYTES.toLong(),
                    relativePath = FOLDER,
                    displayName = displayName,
                    basePath = context.getBasePath().second,
                ),
            )
        }
    }

    @After
    fun removeIt() {
        DownloadFacts.clear(EPISODE_ID)
        runCatching {
            context.getBasePath().first?.gotoDirectory(FOLDER, createMissingDirectories = false)
                ?.findFile(displayName)?.delete()
        }
        instrumentation.runOnMainSync {
            removeKey(DOWNLOAD_HEADER_CACHE, SERIES_ID.toString())
            removeKey(getFolderName(DOWNLOAD_EPISODE_CACHE, SERIES_ID.toString()), EPISODE_ID.toString())
            removeKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, EPISODE_ID.toString())
        }
        setLayoutPreference(null)
    }

    private fun setLayoutPreference(value: Int?) {
        PreferenceManager.getDefaultSharedPreferences(context).edit(commit = true) {
            val key = context.getString(R.string.app_layout_key)
            if (value == null) remove(key) else putInt(key, value)
        }
        instrumentation.runOnMainSync { context.updateTv() }
    }

    private fun ActivityScenario<MainActivity>.currentFragment(): Fragment? {
        var fragment: Fragment? = null
        onActivity { activity ->
            val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            fragment = host.childFragmentManager.primaryNavigationFragment
        }
        return fragment
    }

    /** With no extensions the app opens on the extension setup page; Back is how a person leaves it. */
    private fun openDownloadsFromTheRail() {
        val rail = By.desc(RAIL_DOWNLOADS)
        repeat(SETUP_BACK_PRESSES) {
            if (device.wait(Until.hasObject(rail), RAIL_WAIT_MS)) return@repeat
            device.pressBack()
        }
        dpad.enter(Dpad.Left)
        generateSequence { dpad.press(Dpad.Left) }.take(RAIL_PRESSES).firstOrNull { it == RAIL_DOWNLOADS }
            ?: generateSequence { dpad.press(Dpad.Down) }.take(RAIL_PRESSES).first { it == RAIL_DOWNLOADS }
        dpad.center()
        device.wait(Until.hasObject(By.text(SERIES)), SCREEN_WAIT_MS)
    }

    private fun pressToTheTitle() {
        dpad.press(Dpad.Right)
        generateSequence { dpad.press(Dpad.Down) }.take(RAIL_PRESSES).first { it == SERIES }
    }

    /** Up lands on the nearest chip, which is not always the first, and every chip is then along the row. */
    private fun upToTheChip(wanted: (String?) -> Boolean) {
        if (wanted(dpad.press(Dpad.Up))) return
        generateSequence { dpad.press(Dpad.Left) }.take(RAIL_PRESSES).firstOrNull(wanted)
            ?: generateSequence { dpad.press(Dpad.Right) }.take(RAIL_PRESSES).first(wanted)
    }

    private fun waitFor(text: String) = assertTrue(text, device.wait(Until.hasObject(By.text(text)), SCREEN_WAIT_MS))

    @Test
    fun theRailOpensTheNewScreenAndASeriesOpensItsPageAndBackRestoresFocusOnIt() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openDownloadsFromTheRail()
            assertEquals(DownloadsTvFragment::class.java, scenario.currentFragment()?.javaClass)

            pressToTheTitle()
            dpad.center()
            device.wait(Until.gone(By.text(SELECT)), SCREEN_WAIT_MS)
            assertEquals(DownloadChildFragment::class.java, scenario.currentFragment()?.javaClass)

            device.pressBack()
            device.wait(Until.hasObject(By.text(SELECT)), SCREEN_WAIT_MS)
            assertEquals(DownloadsTvFragment::class.java, scenario.currentFragment()?.javaClass)
            assertEquals(SERIES, dpad.focused())

            // To the rail and back, to the same row and not to the first stop on the page.
            assertEquals(RAIL_DOWNLOADS, dpad.press(Dpad.Left))
            assertEquals(SERIES, dpad.press(Dpad.Right))
        }
    }

    /** Through the real facts: the sidecar's failure reaches the row and the banner, and the row's way back is a stop. */
    @Test
    fun aRealFailureFactShowsRetryAndTheBanner() {
        ActivityScenario.launch(MainActivity::class.java).use { _ ->
            openDownloadsFromTheRail()
            pressToTheTitle()

            DownloadFacts.note(EPISODE_ID, DownloadFailure.StorageFull(SHORT_BYTES))
            DownloadFacts.finish(EPISODE_ID, DownloadType.IsFailed)

            waitFor(context.getString(R.string.download_failed))
            waitFor(NOT_ENOUGH)
            assertEquals(RETRY, dpad.press(Dpad.Right))
            assertEquals(SERIES, dpad.press(Dpad.Left))
        }
    }

    /** Back ends choosing and stays, and the real dialog really deletes. */
    @Test
    fun selectBackDoesNotLeaveAndDeleteGoesThroughTheRealDialog() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openDownloadsFromTheRail()
            pressToTheTitle()
            upToTheChip { it == SELECT }
            dpad.center()
            waitFor(context.getString(R.string.select_all))
            assertEquals(SERIES, dpad.focused())

            device.pressBack()
            waitFor(SELECT)
            assertEquals(DownloadsTvFragment::class.java, scenario.currentFragment()?.javaClass)

            upToTheChip { it == SELECT }
            dpad.center()
            waitFor(context.getString(R.string.select_all))
            dpad.center()
            val delete = context.getString(R.string.delete)
            assertTrue(device.wait(Until.hasObject(By.textStartsWith(delete)), SCREEN_WAIT_MS))
            upToTheChip { it?.startsWith(delete) == true }
            dpad.center()
            // The old screen's dialog, with its positive button.
            assertTrue(device.wait(Until.hasObject(By.text(delete)), SCREEN_WAIT_MS))
            // By remote. A touch here would leave the display in touch mode, where the next test's screen cannot take focus.
            if (dpad.focused() != delete) {
                generateSequence { dpad.press(Dpad.Right) }.take(RAIL_PRESSES).firstOrNull { it == delete }
                    ?: generateSequence { dpad.press(Dpad.Left) }.take(RAIL_PRESSES).first { it == delete }
            }
            dpad.center()

            waitFor(NO_DOWNLOADS)
            assertFalse(
                "the file went with its row",
                context.getBasePath().first?.gotoDirectory(FOLDER, createMissingDirectories = false)
                    ?.findFile(displayName)?.exists() == true,
            )
        }
    }

    /** The two with no board keep the old routes. Back from the queue puts focus back on its chip. */
    @Test
    fun theQueueAndPlayFromUrlOpenTheirOldRoutes() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openDownloadsFromTheRail()
            pressToTheTitle()
            upToTheChip { it == QUEUE }
            dpad.center()
            device.wait(Until.gone(By.text(SELECT)), SCREEN_WAIT_MS)
            assertEquals(DownloadQueueFragment::class.java, scenario.currentFragment()?.javaClass)

            device.pressBack()
            device.wait(Until.hasObject(By.text(SELECT)), SCREEN_WAIT_MS)
            assertEquals(QUEUE, dpad.focused())

            assertEquals(PLAY_FROM_URL, dpad.press(Dpad.Right))
            dpad.center()
            assertTrue(device.wait(Until.hasObject(By.res(context.packageName, "stream_url")), SCREEN_WAIT_MS))
            device.pressBack()
            assertTrue(device.wait(Until.gone(By.res(context.packageName, "stream_url")), SCREEN_WAIT_MS))
        }
    }

    /** `DEC-17`'s switch is for Android TV: every other layout keeps the old fragment. */
    @Test
    fun thePhoneLayoutKeepsTheOldFragment() {
        setLayoutPreference(LAYOUT_PHONE)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var navigated = false
            repeat(SETUP_BACK_PRESSES) {
                scenario.onActivity { activity ->
                    val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                    navigated = runCatching { host.navController.navigate(R.id.navigation_downloads) }.isSuccess
                }
                if (navigated) return@repeat
                device.pressBack()
            }
            device.waitForIdle()
            assertEquals(DownloadFragment::class.java, scenario.currentFragment()?.javaClass)
        }
    }

    private companion object {
        const val SERIES_ID = 987_654_323
        const val EPISODE_ID = 987_654_324
        const val SERIES = "A Downloaded Series"
        const val FILE_NAME = "downloads-nav-test"
        const val FOLDER = "TVSeries/DownloadsNavTest"
        const val FILE_BYTES = 8_192
        const val SHORT_BYTES = 1_200_000_000L
        const val RAIL_DOWNLOADS = "Downloads"
        const val SELECT = "Select"
        const val QUEUE = "Download queue"
        const val PLAY_FROM_URL = "Play from URL"
        const val RETRY = "Retry"
        const val NOT_ENOUGH = "Not enough storage"
        const val NO_DOWNLOADS = "No downloads yet"
        const val LAYOUT_PHONE = 0
        const val RAIL_WAIT_MS = 5_000L
        const val SCREEN_WAIT_MS = 10_000L
        const val SETUP_BACK_PRESSES = 3
        const val RAIL_PRESSES = 6
    }
}

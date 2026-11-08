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
import com.lagradost.cloudstream4.core.devicetest.TouchModeReset
import com.lagradost.cloudstream4.media.model.DownloadFailure
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real app on a TV: a series' row on the Downloads page opens the new page for that series through real navigation,
 * with real files under the real keys. Every press is a remote's, never a touch: a touch leaves the display in touch mode,
 * where the next test's screens cannot take focus (`KMP-40`).
 */
@RunWith(AndroidJUnit4::class)
class DownloadSeriesTvNavigationTest {

    /** `BUG-59`: starts and ends out of touch mode, whatever ran before it. */
    @get:Rule
    val touchMode = TouchModeReset()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val dpad = DpadDriver()
    private val folder = getFolderName(DOWNLOAD_EPISODE_CACHE, SERIES_ID.toString())

    @Before
    fun seedTwoRealEpisodes() {
        instrumentation.runOnMainSync {
            setKey(HAS_DONE_SETUP_KEY, true)
            setKey(
                DOWNLOAD_HEADER_CACHE,
                SERIES_ID.toString(),
                DownloadObjects.DownloadHeaderCached(
                    apiName = "NoSuchProvider",
                    url = "https://example.invalid/series",
                    type = TvType.TvSeries,
                    name = SERIES,
                    poster = null,
                    cacheTime = 1L,
                    id = SERIES_ID,
                ),
            )
        }
        EPISODES.forEach { (id, number) -> seed(id, number) }
    }

    private fun seed(id: Int, number: Int) {
        instrumentation.runOnMainSync {
            setKey(
                folder,
                id.toString(),
                DownloadObjects.DownloadEpisodeCached(
                    name = "Episode $number",
                    poster = null,
                    episode = number,
                    season = 1,
                    parentId = SERIES_ID,
                    score = null,
                    description = null,
                    cacheTime = 1L,
                    id = id,
                ),
            )
        }
        VideoDownloadManager.setupStream(context, fileName(id), FOLDER, "mp4", false).open().use { it.write(ByteArray(FILE_BYTES)) }
        instrumentation.runOnMainSync {
            setKey(
                VideoDownloadManager.KEY_DOWNLOAD_INFO,
                id.toString(),
                DownloadObjects.DownloadedFileInfo(
                    totalBytes = FILE_BYTES.toLong(),
                    relativePath = FOLDER,
                    displayName = "${fileName(id)}.mp4",
                    basePath = context.getBasePath().second,
                ),
            )
        }
    }

    @After
    fun removeThem() {
        EPISODES.forEach { (id, _) ->
            DownloadFacts.clear(id)
            runCatching {
                context.getBasePath().first?.gotoDirectory(FOLDER, createMissingDirectories = false)
                    ?.findFile("${fileName(id)}.mp4")?.delete()
            }
            instrumentation.runOnMainSync {
                removeKey(folder, id.toString())
                removeKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString())
            }
        }
        instrumentation.runOnMainSync { removeKey(DOWNLOAD_HEADER_CACHE, SERIES_ID.toString()) }
        setLayoutPreference(null)
    }

    private fun fileName(id: Int) = "series-nav-test-$id"

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

    private fun waitFor(text: String) = assertTrue(text, device.wait(Until.hasObject(By.text(text)), SCREEN_WAIT_MS))

    /** From a cold start to the series page, as a person would: the rail, Downloads, Right, Down to the series, OK. */
    private fun openTheSeries(scenario: ActivityScenario<MainActivity>) {
        val rail = By.desc(RAIL_DOWNLOADS)
        repeat(SETUP_BACK_PRESSES) {
            if (device.wait(Until.hasObject(rail), RAIL_WAIT_MS)) return@repeat
            device.pressBack()
        }
        dpad.enter(Dpad.Left)
        generateSequence { dpad.press(Dpad.Left) }.take(PRESSES).firstOrNull { it == RAIL_DOWNLOADS }
            ?: generateSequence { dpad.press(Dpad.Down) }.take(PRESSES).first { it == RAIL_DOWNLOADS }
        dpad.center()
        waitFor(SERIES)
        dpad.press(Dpad.Right)
        generateSequence { dpad.press(Dpad.Down) }.take(PRESSES).first { it == SERIES }
        dpad.center()
        waitFor(PLAY_NEXT)
        assertEquals(DownloadSeriesTvFragment::class.java, scenario.currentFragment()?.javaClass)
    }

    @Test
    fun aSeriesOpensTheNewPageWithFocusOnPlayNextAndBackReturnsToItsRow() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openTheSeries(scenario)
            assertEquals(PLAY_NEXT, dpad.focused())
            waitFor(EPISODE_ONE)
            waitFor(EPISODE_TWO)

            device.pressBack()
            waitFor(SELECT)
            assertEquals(DownloadsTvFragment::class.java, scenario.currentFragment()?.javaClass)
            assertEquals(SERIES, dpad.focused())
        }
    }

    /** Through the real facts: a failure reaches its row's column B and the page's `Retry failed`. */
    @Test
    fun aRealFailureShowsItsRetryAndRetryFailed() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openTheSeries(scenario)
            DownloadFacts.note(EPISODES[1].first, DownloadFailure.NetworkLost)
            DownloadFacts.finish(EPISODES[1].first, DownloadType.IsFailed)
            waitFor("Retry failed (1)")
            assertTrue(device.wait(Until.hasObject(By.textStartsWith("Download Failed · Network lost")), SCREEN_WAIT_MS))
            // Down from the buttons to the failed row, then along it to its Retry.
            generateSequence { dpad.press(Dpad.Down) }.take(PRESSES).first { it == EPISODE_TWO }
            assertEquals(PLAY, dpad.press(Dpad.Right))
            assertEquals(RETRY, dpad.press(Dpad.Right))
        }
    }

    /** The old screen's dialog, and the old screen's answer to deleting the last episode: the page closes. */
    @Test
    fun deleteAllGoesThroughTheRealDialogAndThePageClosesWhenNothingIsLeft() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openTheSeries(scenario)
            assertEquals(DELETE_ALL, dpad.press(Dpad.Right))
            dpad.center()
            val delete = context.getString(R.string.delete)
            assertTrue(device.wait(Until.hasObject(By.text(delete)), SCREEN_WAIT_MS))
            if (dpad.focused() != delete) {
                generateSequence { dpad.press(Dpad.Right) }.take(PRESSES).firstOrNull { it == delete }
                    ?: generateSequence { dpad.press(Dpad.Left) }.take(PRESSES).first { it == delete }
            }
            dpad.center()

            waitFor(NO_DOWNLOADS)
            assertEquals(DownloadsTvFragment::class.java, scenario.currentFragment()?.javaClass)
            EPISODES.forEach { (id, _) ->
                assertFalse(
                    "episode $id's file went",
                    context.getBasePath().first?.gotoDirectory(FOLDER, createMissingDirectories = false)
                        ?.findFile("${fileName(id)}.mp4")?.exists() == true,
                )
            }
        }
    }

    /** `DEC-17`'s switch is for Android TV: every other layout keeps the old page. */
    @Test
    fun thePhoneLayoutKeepsTheOldPage() {
        setLayoutPreference(LAYOUT_PHONE)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var navigated = false
            repeat(SETUP_BACK_PRESSES) {
                scenario.onActivity { activity ->
                    val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                    navigated = runCatching {
                        host.navController.navigate(R.id.navigation_downloads)
                        host.navController.navigate(
                            R.id.action_navigation_downloads_to_navigation_download_child,
                            DownloadChildFragment.newInstance(SERIES, folder),
                        )
                    }.isSuccess
                }
                if (navigated) return@repeat
                device.pressBack()
            }
            device.waitForIdle()
            assertEquals(DownloadChildFragment::class.java, scenario.currentFragment()?.javaClass)
        }
    }

    private companion object {
        const val SERIES_ID = 987_654_400
        val EPISODES = listOf(987_654_401 to 1, 987_654_402 to 2)
        const val SERIES = "A Downloaded Show"
        const val EPISODE_ONE = "S1 · E1 · Episode 1"
        const val EPISODE_TWO = "S1 · E2 · Episode 2"
        const val FOLDER = "TVSeries/SeriesNavTest"
        const val FILE_BYTES = 8_192
        const val RAIL_DOWNLOADS = "Downloads"
        const val SELECT = "Select"
        const val PLAY_NEXT = "Play next"
        const val DELETE_ALL = "Delete all"
        const val PLAY = "Play"
        const val RETRY = "Retry"
        const val NO_DOWNLOADS = "No downloads yet"
        const val LAYOUT_PHONE = 0
        const val RAIL_WAIT_MS = 5_000L
        const val SCREEN_WAIT_MS = 10_000L
        const val SETUP_BACK_PRESSES = 3
        const val PRESSES = 8
    }
}

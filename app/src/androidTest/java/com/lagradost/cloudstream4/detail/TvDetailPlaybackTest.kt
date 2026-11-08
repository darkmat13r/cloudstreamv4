@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.lagradost.cloudstream4.detail

import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.GeneratorState
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.getId
import com.lagradost.cloudstream3.ui.setup.HAS_DONE_SETUP_KEY
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.devicetest.TouchModeReset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Playing from the new TV detail page writes the position and the Continue Watching entry that the old
 * page writes, under the ids the old page uses.
 *
 * ## Why this test needed a provider, and how it has one
 *
 * `TvDetailNavigationTest` is honest that **no extension is installed on the emulator**, so the page's
 * only reachable state there is its failure. That is not enough here: the thing under test is what the
 * *player* is handed when a real title is played, so a real title has to load. `APIHolder` takes a
 * provider at runtime through the public `addPluginMapping`, which is how a plugin registers, so
 * [FakeProvider] registers the same way and is removed again in [unregister]. No network: its `load`
 * returns a response it holds and its `loadLinks` yields one URL that nothing fetches.
 *
 * ## What this proves and what it does not
 *
 * It proves the fix: `PlayerGeneratorViewModel` holds a `ResultEpisode` meta whose `id`, `parentId`,
 * `episode` and `season` are what `postEpisodes`' arithmetic gives that part — read off the real
 * ViewModel of the real `GeneratorPlayer`, reached by a remote from the real page. Before the fix the
 * generator was `NoVideoGenerator`, whose `videos` list is empty, so that meta was null and
 * `setViewPosAndResume` wrote nothing (`DataStoreHelper.kt:719-742`).
 *
 * It does **not** play video. The player's own `setViewPosAndResume` call is driven by playback
 * progress, and there is no decodable stream here, so [assertContinueWatchingReads] calls that same
 * function with the meta the player actually holds and then reads the keys back. The step the test
 * stands in for is the player calling it; every value it passes is the player's own.
 */
@RunWith(AndroidJUnit4::class)
class TvDetailPlaybackTest {

    /** `BUG-59`: starts and ends out of touch mode, whatever ran before it. */
    @get:Rule
    val touchMode = TouchModeReset()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val dpad = DpadDriver()
    private val homeLabel = instrumentation.targetContext.getString(R.string.title_home)

    @Before
    fun skipFirstRunSetup() {
        instrumentation.runOnMainSync { setKey(HAS_DONE_SETUP_KEY, true) }
    }

    @After
    fun unregister() {
        APIHolder.removePluginMapping(FakeProvider)
    }

    /**
     * An episode pressed in the page's own list — the case that was worst before the fix, where
     * neither a position nor a Continue Watching entry was written.
     */
    @Test
    fun anEpisodePlayedFromTheListIsKeptUnderThatEpisodesOldPageId() {
        onThePage(
            url = SERIES_URL,
            response = series(),
            prepare = { titleId -> DataStoreHelper.removeLastWatched(titleId) },
        ) { scenario, titleId ->
            // `postEpisodes`' series formula, spelled out rather than read from `episodeSlots`, so that
            // this is a check on the shared function and not a restatement of it.
            val expectedId = titleId + 1 * 100_000 + 2 + 1
            assertTrue(
                "the episode row is reached by remote",
                reach(SECOND_EPISODE)?.contains(SECOND_EPISODE) == true,
            )
            dpad.center()
            val meta = scenario.awaitPlayerMeta()

            assertEquals("the id the old page stores this episode's position under", expectedId, meta.id)
            assertEquals("the title it belongs to", titleId, meta.parentId)
            assertEquals(2, meta.episode)
            assertEquals(1, meta.season)
            assertEquals(SECOND_EPISODE, meta.name)
            assertEquals(TvType.TvSeries, meta.tvType)

            assertContinueWatchingReads(meta, scenario.playerNextMeta())
        }
    }

    /**
     * And the whole list went to the generator, which is what the player's next and previous episode
     * read — `VideoGenerator.hasNext` is `videoIndex < videos.lastIndex`, and `NoVideoGenerator`'s
     * list was empty, so both were always false.
     */
    @Test
    fun theWholeSeasonReachesThePlayerSoNextAndPreviousWork() {
        onThePage(
            url = SERIES_URL,
            response = series(),
            prepare = { titleId -> DataStoreHelper.removeLastWatched(titleId) },
        ) { scenario, _ ->
            assertTrue(reach(SECOND_EPISODE)?.contains(SECOND_EPISODE) == true)
            dpad.center()
            scenario.awaitPlayerMeta()

            val all = scenario.playerAllMeta()
            assertEquals("every episode the page showed", EPISODE_NAMES, all.map { it.name })
            // The index the player opened on, which is what `hasNext`/`hasPrev` are measured from.
            val index = scenario.playerIndex()
            assertEquals("the player opened on the episode that was pressed", 1, index)
            assertEquals(SECOND_EPISODE, all[index].name)
            assertTrue("there is a next episode", index < all.lastIndex)
            assertTrue("and a previous one", index > 0)
        }
    }

    /** A film: one part, under the title's own id, which is why `getViewPos(titleId)` is its resume. */
    @Test
    fun aFilmIsKeptUnderTheTitlesOwnId() {
        onThePage(
            url = MOVIE_URL,
            response = movie(),
            prepare = { titleId ->
                // A position left by an earlier run would relabel the one control Resume. Zero, not a
                // removal: `resumeOf` drops a non-positive position, so the page offers a cold start
                // again, and `DataStoreHelper` has no public remove for this key.
                DataStoreHelper.setViewPos(titleId, 0L, DURATION_MS)
                DataStoreHelper.removeLastWatched(titleId)
            },
        ) { scenario, titleId ->
            assertFocusHolds(PLAY_MOVIE)
            dpad.center()
            val meta = scenario.awaitPlayerMeta()

            assertEquals("a film's part has the title's own id", titleId, meta.id)
            assertEquals(titleId, meta.parentId)
            assertEquals(0, meta.episode)
            assertNull(meta.season)

            assertContinueWatchingReads(meta, scenario.playerNextMeta())
        }
    }

    /**
     * Resume: the episode the page offers is the one the stored entry names, and it is played under the
     * id that entry was stored with. This is the case that half-worked before — the position was kept,
     * because the host could read that one id off the stored entry, and the Continue Watching entry
     * still was not rewritten, so the title never advanced past it.
     */
    @Test
    fun resumePlaysTheStoredEpisodeUnderTheStoredId() {
        onThePage(
            url = SERIES_URL,
            response = series(),
            prepare = { titleId ->
                DataStoreHelper.setLastWatched(
                    titleId, titleId + 1 * 100_000 + 3 + 1, episode = 3, season = 1,
                )
                DataStoreHelper.setViewPos(titleId + 1 * 100_000 + 3 + 1, 120_000L, DURATION_MS)
            },
        ) { scenario, titleId ->
            val thirdId = titleId + 1 * 100_000 + 3 + 1
            assertFocusHolds(RESUME)
            dpad.center()
            val meta = scenario.awaitPlayerMeta()

            assertEquals("the stored episode's id", thirdId, meta.id)
            assertEquals(3, meta.episode)
            assertEquals(THIRD_EPISODE, meta.name)
            assertEquals("the position the page resumed from", 120_000L, meta.position)
        }
    }

    /**
     * The keys read back, with the player's own meta.
     *
     * `setViewPosAndResume` is `:app`'s, unchanged, and it is the one function that writes the Continue
     * Watching row — [getAllResumeStateIds] is the list Home enumerates to draw it.
     */
    private fun assertContinueWatchingReads(meta: ResultEpisode, next: ResultEpisode?) {
        instrumentation.runOnMainSync {
            DataStoreHelper.setViewPosAndResume(meta.id, POSITION_MS, DURATION_MS, meta, next)
        }

        val stored = DataStoreHelper.getViewPos(meta.id)
        assertEquals("the position, under the part's own id", POSITION_MS, stored?.position)
        assertEquals(DURATION_MS, stored?.duration)

        val resume = DataStoreHelper.getLastWatched(meta.parentId)
        assertNotNull("setLastWatched wrote nothing — this is the KMP-38 regression", resume)
        assertEquals("the entry points back at the part", meta.id, resume?.episodeId)
        assertEquals(meta.episode, resume?.episode)
        assertEquals(meta.season, resume?.season)

        assertTrue(
            "Home's Continue Watching enumerates ${meta.parentId}",
            DataStoreHelper.getAllResumeStateIds()?.contains(meta.parentId) == true,
        )
    }

    /**
     * Registers the fake and keeps registering until it survives two reads in a row.
     *
     * The first attempt registered once in `@Before` and every page loaded its failure state.
     * `MainActivity` assigns `APIHolder.apis = allProviders.distinctBy { … }` from **two `ioSafe`
     * coroutines** (`MainActivity.kt:841`, `:1676`), each of which drops anything `addPluginMapping`
     * added before it ran. Registering after `ActivityScenario.launch` is not enough on its own either,
     * because both writes are asynchronous and neither is awaited — so this re-adds until it holds,
     * which is the only deterministic way in without changing `:app`.
     */
    private fun registerProviderUntilItSticks() {
        val deadline = System.currentTimeMillis() + SCREEN_WAIT_MS
        var stable = 0
        while (System.currentTimeMillis() < deadline) {
            if (APIHolder.getApiFromNameNull(PROVIDER_NAME) == null) {
                stable = 0
                APIHolder.addPluginMapping(FakeProvider)
            } else {
                stable++
                if (stable >= PROVIDER_STABLE_READS) return
            }
            Thread.sleep(PROVIDER_POLL_MS)
        }
        fail("the fake provider never stayed registered; `apis` is being reassigned under the test")
    }

    /**
     * With no extensions the app opens on the extension setup page; Back is how a person leaves it.
     *
     * A real early return and not `return@repeat`, which is a continue — that would go on pressing Back
     * after Home had arrived, and Back on Home leaves the app.
     */
    private fun leaveSetup() {
        repeat(SETUP_BACK_PRESSES) {
            if (device.wait(Until.hasObject(By.desc(homeLabel)), RAIL_WAIT_MS)) return
            device.pressBack()
        }
    }

    /**
     * The page, opened the way every caller opens one, with the title's id handed to the body.
     *
     * The id is computed **here**, after the fake is registered, and not in the test body.
     * `getLoadResponseIdFromUrl` strips the provider's own `mainUrl` from the URL before hashing it
     * (`ResultViewModel2.kt:415-418`), so with no provider registered it hashes a different string and
     * returns a different number. Measured: the resume case wrote its stored entry under that wrong id,
     * and the page drew Play where it should have drawn Resume.
     *
     * @param prepare storage to write before the page loads, given the title's real id.
     */
    private fun onThePage(
        url: String,
        response: LoadResponse,
        prepare: (Int) -> Unit = {},
        body: (ActivityScenario<MainActivity>, Int) -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            leaveSetup()
            registerProviderUntilItSticks()
            val titleId = response.getId()
            instrumentation.runOnMainSync { prepare(titleId) }

            scenario.onActivity { activity ->
                activity.loadResult(url = url, apiName = PROVIDER_NAME, name = TITLE)
            }
            scenario.awaitFragment(DetailTvFragment::class.java)
            if (!device.wait(Until.hasObject(By.text(TITLE)), SCREEN_WAIT_MS)) {
                // Said in full, because the two ways this fails want different fixes: the page drew
                // its failure state (the provider did not answer), or it drew the title under some
                // other label (this assertion is wrong, not the page).
                fail(
                    "the page did not draw \"$TITLE\". Failure state on screen: " +
                        "${device.findObject(By.text(FAILED_HEADLINE)) != null}. " +
                        "Focused stop: ${dpad.focused()}"
                )
            }
            body(scenario, titleId)
        }
    }

    /**
     * DOWN until the wanted stop has focus, which is how a remote gets to the episode list.
     *
     * Matched by `contains` rather than equality: a `ListRow` carries a subtitle as well as a title, so
     * its accessibility label is not only the episode's name. The label is not what is under test here —
     * `DetailScreenTvDeviceTest` is where focus labels are asserted exactly — the ids are.
     */
    private fun reach(label: String): String? {
        var focused: String? = dpad.enter()
        repeat(MAX_PRESSES) {
            if (focused?.contains(label) == true) return focused
            focused = dpad.press(Dpad.Down) ?: focused
        }
        return focused
    }

    private fun assertFocusHolds(label: String) {
        val focused = dpad.enter()
        assertTrue("focus is on $label, not $focused", focused.contains(label))
    }

    private fun ActivityScenario<MainActivity>.fragment(): Fragment? {
        var fragment: Fragment? = null
        onActivity { activity ->
            val host = activity.supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            fragment = host.childFragmentManager.primaryNavigationFragment
        }
        return fragment
    }

    private fun ActivityScenario<MainActivity>.awaitFragment(expected: Class<*>) {
        val deadline = System.currentTimeMillis() + SCREEN_WAIT_MS
        while (fragment()?.javaClass != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
        }
        assertEquals("the destination's fragment", expected, fragment()?.javaClass)
    }

    /** The real player's real ViewModel: `GeneratorPlayer` reads its own meta from exactly this. */
    private fun ActivityScenario<MainActivity>.generatorState(): Any? {
        var state: Any? = null
        onActivity {
            val player = playerOn(it) ?: return@onActivity
            state = ViewModelProvider(player)[PlayerGeneratorViewModel::class.java]
                .state.generatorState
        }
        return state
    }

    /**
     * The player among the host's fragments, not only its primary navigation one.
     *
     * `primaryNavigationFragment` is what `TvDetailNavigationTest` reads, and it is right for a
     * destination swap. It is not relied on here, because a player that errors can stop being primary
     * while still being attached, and this test only ever needs the instance.
     */
    private fun playerOn(activity: MainActivity): GeneratorPlayer? {
        val host = activity.supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        return host.childFragmentManager.fragments.filterIsInstance<GeneratorPlayer>().firstOrNull()
    }

    /**
     * Waits for the player's own meta, polling fast.
     *
     * `PlayerGeneratorViewModel.loadLinks` sets the meta **before** it asks the generator for links
     * (`:388-402`), so it is there as soon as the player starts loading — but a player can leave again
     * on its own, so this looks often rather than every 200 ms.
     */
    private fun ActivityScenario<MainActivity>.awaitPlayerMeta(): ResultEpisode {
        val deadline = System.currentTimeMillis() + SCREEN_WAIT_MS
        var meta: Any? = null
        var sawPlayer = false
        while (meta == null && System.currentTimeMillis() < deadline) {
            onActivity { if (playerOn(it) != null) sawPlayer = true }
            meta = (generatorState() as? GeneratorState)?.meta
            if (meta == null) Thread.sleep(FAST_POLL_MS)
        }
        assertTrue(
            "the player never opened (seen at all: $sawPlayer). With no links it pops itself, so " +
                "check the fake emitted one.",
            sawPlayer,
        )
        assertTrue(
            "the player's meta is a ResultEpisode, not $meta — without one nothing is stored",
            meta is ResultEpisode,
        )
        return meta as ResultEpisode
    }

    private fun ActivityScenario<MainActivity>.playerNextMeta(): ResultEpisode? =
        (generatorState() as? GeneratorState)
            ?.nextMeta as? ResultEpisode

    private fun ActivityScenario<MainActivity>.playerIndex(): Int =
        (generatorState() as? GeneratorState)?.index ?: -1

    private fun ActivityScenario<MainActivity>.playerAllMeta(): List<ResultEpisode> =
        (generatorState() as? GeneratorState)
            ?.allMeta?.filterIsInstance<ResultEpisode>().orEmpty()

    /**
     * A provider with no network, registered the way a plugin is.
     *
     * An `object` and not a per-test instance because `addPluginMapping` keys `apiMap` by name and
     * `removePluginMapping` filters by identity; one instance keeps both honest across tests.
     */
    private object FakeProvider : MainAPI() {
        override var name = PROVIDER_NAME
        override var mainUrl = "https://fake.invalid"
        override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
        override val hasMainPage = false

        override suspend fun load(url: String): LoadResponse? =
            if (url.startsWith(MOVIE_URL)) movie() else series()

        /**
         * One link, which is **not optional**.
         *
         * The first attempt returned `true` and emitted nothing, and every test failed with the player
         * fragment never appearing. `GeneratorPlayer.noLinksFound()` ends with
         * `activity?.popCurrentPage()` (`:1588-1606`), so a player with no links opens and immediately
         * pops back to the page. Measured on `Television_720p_2`.
         *
         * The URL is never fetched — nothing decodes here — but it has to exist and be a type the
         * quality filter admits, which is why it is `VIDEO` and not inferred.
         */
        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = "$data.m3u8",
                    type = ExtractorLinkType.VIDEO,
                )
            )
            return true
        }
    }

    private companion object {
        const val SECOND_EPISODE = "That Would Be Me"
        const val THIRD_EPISODE = "Reckoning"

        /** Ten per cent, which is under `NEXT_WATCH_EPISODE_PERCENTAGE`, so the entry is this part. */
        const val POSITION_MS = 60_000L
        const val DURATION_MS = 600_000L

        const val RAIL_WAIT_MS = 5_000L
        const val SCREEN_WAIT_MS = 20_000L
        const val POLL_MS = 200L

        /** The player can leave on its own, so its meta is looked for often. */
        const val FAST_POLL_MS = 25L
        const val SETUP_BACK_PRESSES = 3

        /** Long enough to outlast both of `MainActivity`'s `ioSafe` writes to `APIHolder.apis`. */
        const val PROVIDER_POLL_MS = 500L
        const val PROVIDER_STABLE_READS = 4
        const val MAX_PRESSES = 24

        // `values/strings.xml`, as the screen resolves them.
        const val RESUME = "Resume"
        const val PLAY_MOVIE = "Play Movie"
        const val FAILED_HEADLINE = "Couldn't open this title"
    }
}

/**
 * The two responses, top-level so that [TvDetailPlaybackTest]'s expectations and the fake provider's
 * answers cannot drift: `getId()` is derived from `uniqueUrl` and `apiName`, so a test computing the
 * expected id from a different object than the one the page loaded would prove nothing.
 */
private fun series() = TvSeriesLoadResponse(
    name = TITLE,
    url = SERIES_URL,
    apiName = PROVIDER_NAME,
    type = TvType.TvSeries,
    episodes = EPISODE_NAMES.mapIndexed { index, name ->
        Episode(data = "$SERIES_URL/s1e${index + 1}", name = name, season = 1, episode = index + 1)
    },
    plot = "A series the fake provider answers with.",
)

private fun movie() = MovieLoadResponse(
    name = TITLE,
    url = MOVIE_URL,
    apiName = PROVIDER_NAME,
    type = TvType.Movie,
    dataUrl = "$MOVIE_URL/stream",
)

private const val PROVIDER_NAME = "Detail Playback Fake"
private const val TITLE = "The Last Signal"
private const val SERIES_URL = "https://fake.invalid/the-last-signal"
private const val MOVIE_URL = "https://fake.invalid/arrival"
private val EPISODE_NAMES = listOf("Kassa", "That Would Be Me", "Reckoning", "Aldhani")

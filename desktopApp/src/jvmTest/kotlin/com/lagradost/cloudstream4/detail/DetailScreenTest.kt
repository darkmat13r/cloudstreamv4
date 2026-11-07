package com.lagradost.cloudstream4.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.lagradost.cloudstream4.core.designsystem.desktop.forDesktop
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.LocalCloudStreamTypography
import com.lagradost.cloudstream4.feature.detail.DetailAction
import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.DetailLoad
import com.lagradost.cloudstream4.feature.detail.DetailResume
import com.lagradost.cloudstream4.feature.detail.DetailSeason
import com.lagradost.cloudstream4.feature.detail.DetailState
import com.lagradost.cloudstream4.feature.detail.DetailWatchStatus
import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import com.lagradost.cloudstream4.feature.detail.desktop.DetailScreenDesktop
import com.lagradost.cloudstream4.startPosition
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * `DS-21`'s title page, E1 to E6 (`KMP-58`), through real pointer and key events in the scene, plus a PNG of each state
 * when `cloudstream.detail.renders` names a directory.
 *
 * A control is found in the scene's semantics tree, by the words or description a screen reader would be given, and
 * then pressed at the middle of its bounds with real pointer events. A control that says nothing to a screen reader
 * therefore cannot be pressed here, which is the point.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class DetailScreenTest {

    private val episodes = listOf(
        DetailEpisode("Pilot", "s1e1", 1, 1, runTimeMinutes = 48),
        DetailEpisode("Cat's in the Bag", "s1e2", 1, 2, runTimeMinutes = 48),
        DetailEpisode("And the Bag's in the River", "s1e3", 1, 3, runTimeMinutes = 48),
        DetailEpisode("Seven Thirty-Seven", "s2e1", 2, 1, runTimeMinutes = 47),
        DetailEpisode("Grilled", "s2e2", 2, 2, runTimeMinutes = 47),
    )

    private val series = TitleDetail(
        title = "The Last Signal",
        providerName = "CineTmdb",
        typeName = "TvSeries",
        year = 2019,
        plot = "Six months after the broadcast went dark, a salvage crew picks up a repeating signal from a station " +
            "decommissioned forty years ago. What they find aboard is older than the station, and it is still listening.",
        posterUrl = null,
        playData = null,
        episodes = episodes,
        seasons = listOf(DetailSeason(1, 3), DetailSeason(2, 2)),
        resume = DetailResume("s1e3", positionMs = 19 * MINUTE, durationMs = 48 * MINUTE, episode = episodes[2]),
        watchStatus = DetailWatchStatus.Watching,
    )

    private val film = TitleDetail(
        title = "The Last Expedition",
        providerName = "CineTmdb",
        typeName = "Movie",
        year = 2024,
        plot = "A crew goes north and does not come back the way they went.",
        posterUrl = null,
        playData = "film",
        episodes = emptyList(),
        resume = DetailResume("film", positionMs = 84 * MINUTE, durationMs = 136 * MINUTE, episode = null),
        watchStatus = DetailWatchStatus.None,
    )

    private fun links(count: Int) = List(count) { index ->
        StreamLink("Source ${index + 1}", "${QUALITIES[index % QUALITIES.size]}p", "https://a.invalid/$index", null, null, null)
    }

    private fun scene(width: Int = WIDTH, height: Int = HEIGHT, content: @Composable () -> Unit) =
        ImageComposeScene(width, height, Density(1f)) {
            CloudStreamTheme {
                CompositionLocalProvider(LocalCloudStreamTypography provides CloudStreamTheme.typography.forDesktop()) {
                    Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) { content() }
                }
            }
        }

    private fun page(
        state: DetailState,
        onAction: (DetailAction) -> Unit = {},
        onBack: () -> Unit = {},
        width: Int = WIDTH,
        height: Int = HEIGHT,
    ) = scene(width, height) {
        DetailScreenDesktop(
            state = state,
            onAction = onAction,
            onBack = onBack,
            artwork = { Box(Modifier.fillMaxSize().background(ART)) },
            origin = "Home",
        )
    }

    private fun loaded(detail: TitleDetail) = DetailState(DetailLoad.Loaded(detail))

    private fun ImageComposeScene.settle() = repeat(SETTLE_FRAMES) { render(it * FRAME_NANOS) }

    private fun ImageComposeScene.click(at: Offset) {
        sendPointerEvent(PointerEventType.Move, at)
        sendPointerEvent(PointerEventType.Press, at)
        sendPointerEvent(PointerEventType.Release, at)
        settle()
    }

    private fun ImageComposeScene.hover(at: Offset) {
        sendPointerEvent(PointerEventType.Move, at)
        settle()
    }

    private fun ImageComposeScene.press(key: Key) {
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
        settle()
    }

    private fun ImageComposeScene.save(name: String) {
        val directory = System.getProperty("cloudstream.detail.renders") ?: return
        settle()
        val png = checkNotNull(render(SETTLE_FRAMES * FRAME_NANOS).encodeToData(EncodedImageFormat.PNG)).bytes
        File(directory, "$name.png").writeBytes(png)
    }

    private fun SemanticsNode.all(): List<SemanticsNode> = listOf(this) + children.flatMap { it.all() }

    /** Every node a screen reader would read as [words], by its text or its description. */
    private fun ImageComposeScene.nodes(words: String): List<SemanticsNode> {
        settle()
        return semanticsOwners.flatMap { it.rootSemanticsNode.all() }.filter { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == words } ||
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == words }
        }
    }

    /** The middle of the one control that reads as [words]. Fails if none does, or more than one. */
    private fun ImageComposeScene.centreOf(words: String, index: Int = 0): Offset {
        val found = nodes(words)
        check(found.size > index) { "no control reads \"$words\" (found ${found.size})" }
        return found[index].boundsInRoot.center
    }

    private fun ImageComposeScene.has(words: String) = nodes(words).isNotEmpty()

    private fun ImageComposeScene.isSelected(words: String): Boolean {
        val found = nodes(words).filter { SemanticsProperties.Selected in it.config }
        val node = found.singleOrNull() ?: error("expected one choice reading \"$words\", found ${found.size}")
        return node.config[SemanticsProperties.Selected]
    }

    // ---- E1: a series ----

    @Test
    fun `Resume plays where it was left, and Start over plays the same episode from its beginning (E1)`() {
        val actions = mutableListOf<DetailAction>()
        val scene = page(loaded(series), { actions += it })
        scene.save("detail-e1-series")
        scene.click(scene.centreOf("Resume S1:E3"))
        scene.click(scene.centreOf("Start over"))
        assertEquals(
            listOf<DetailAction>(
                DetailAction.PlaybackRequested("s1e3", fromStart = false),
                DetailAction.PlaybackRequested("s1e3", fromStart = true),
            ),
            actions,
        )
        // What is left, on one line under the rule.
        assertTrue(scene.has("29m remaining"))
        scene.close()
    }

    @Test
    fun `with nothing to come back to, the primary is Play and there is no Start over`() {
        val actions = mutableListOf<DetailAction>()
        val scene = page(loaded(series.copy(resume = null)), { actions += it })
        scene.click(scene.centreOf("Play Episode"))
        assertEquals(listOf<DetailAction>(DetailAction.PlaybackRequested("s1e1", fromStart = false)), actions)
        assertFalse(scene.has("Start over"))
        scene.close()
    }

    @Test
    fun `the watch list opens its panel, and the synopsis opens in place`() {
        val actions = mutableListOf<DetailAction>()
        val scene = page(loaded(series), { actions += it })
        scene.click(scene.centreOf("Set watch status"))
        scene.click(scene.centreOf(series.plot!!))
        assertEquals(listOf(DetailAction.WatchStatusPickerOpened, DetailAction.SynopsisToggled), actions)
        scene.close()

        val open = page(loaded(series).copy(synopsisOpen = true))
        open.save("detail-e1-synopsis-open")
        open.close()
    }

    @Test
    fun `the bar's way back is Back and the name of where the page came from`() {
        var backs = 0
        val scene = page(loaded(series), onBack = { backs++ })
        scene.click(scene.centreOf("Go Back"))
        scene.click(scene.centreOf("Home"))
        assertEquals(2, backs)
        scene.close()
    }

    // ---- Seasons and episodes (E1, E2) ----

    @Test
    fun `the episodes open on Resume's season, and a season button shows its own`() {
        val actions = mutableListOf<DetailAction>()
        val scene = page(loaded(series), { actions += it })
        assertTrue(scene.isSelected("Season 1"))
        assertFalse(scene.isSelected("Season 2"))
        assertTrue(scene.has("Pilot") && !scene.has("Grilled"))
        scene.click(scene.centreOf("Season 2"))
        assertTrue(scene.isSelected("Season 2"))
        assertTrue(scene.has("Grilled") && !scene.has("Pilot"))
        scene.save("detail-e1-season-2")
        scene.click(scene.centreOf("Grilled"))
        assertEquals(listOf<DetailAction>(DetailAction.PlaybackRequested("s2e2", fromStart = false)), actions)
        scene.close()
    }

    @Test
    fun `season 0 is Specials, and episodes with no season are Other, last`() {
        val odd = series.copy(
            episodes = listOf(
                DetailEpisode("Making of", "sp", 0, 1),
                DetailEpisode("Pilot", "s1e1", 1, 1),
                DetailEpisode("Recap", "x", null, null),
            ),
            resume = null,
        )
        val scene = page(loaded(odd))
        val specials = scene.centreOf("Specials")
        val first = scene.centreOf("Season 1")
        val other = scene.centreOf("Other")
        assertTrue(specials.x < first.x && first.x < other.x, "in the order the numbers go, Other last")
        scene.close()
    }

    @Test
    fun `a row shows Play only while the pointer is on it, and Play plays that episode (E2)`() {
        val actions = mutableListOf<DetailAction>()
        val scene = page(loaded(series), { actions += it })
        assertFalse(scene.has("Play Episode"), "no row's verb shows before the pointer arrives")
        scene.hover(scene.centreOf("Cat's in the Bag"))
        val verbs = scene.nodes("Play Episode")
        assertEquals(1, verbs.size, "exactly the hovered row's verb")
        scene.save("detail-e2-row-hovered")
        scene.click(verbs.single().boundsInRoot.center)
        assertEquals(listOf<DetailAction>(DetailAction.PlaybackRequested("s1e2", fromStart = false)), actions)
        scene.close()
    }

    // ---- E3: a film and its sources ----

    @Test
    fun `a film's sources panel lists what has arrived, and Cancel closes it (E3)`() {
        val actions = mutableListOf<DetailAction>()
        val state = loaded(film).copy(links = DetailLoad.Loaded(links(3)), linksArriving = true)
        val scene = page(state, { actions += it })
        scene.save("detail-e3-film-sources")
        assertTrue(scene.has("Loading…"))
        scene.click(scene.centreOf("Source 2"))
        scene.click(scene.centreOf("Cancel"))
        assertEquals(listOf(DetailAction.LinkActivated(links(3)[1]), DetailAction.LinksDismissed), actions)
        scene.close()
    }

    // ---- The watch list ----

    @Test
    fun `the watch list panel marks the title's own list, and a choice is sent`() {
        val actions = mutableListOf<DetailAction>()
        val scene = page(loaded(series).copy(watchStatusPicking = true), { actions += it })
        scene.save("detail-watch-status")
        assertTrue(scene.isSelected("Watching"))
        assertFalse(scene.isSelected("Completed"))
        scene.click(scene.centreOf("Completed"))
        scene.click(scene.centreOf("Done"))
        assertEquals(
            listOf(DetailAction.WatchStatusPicked(DetailWatchStatus.Completed), DetailAction.PanelDismissed),
            actions,
        )
        scene.close()
    }

    // ---- E4, E5 ----

    @Test
    fun `loading draws where the page will land (E4)`() {
        val scene = page(DetailState(DetailLoad.Loading))
        scene.save("detail-e4-loading")
        assertFalse(scene.has("Play Episode") || scene.has("Resume"))
        scene.close()
    }

    @Test
    fun `a title that could not be opened offers Try again and Go back, and shows nothing of why (E5)`() {
        val actions = mutableListOf<DetailAction>()
        var backs = 0
        val failed = DetailState(DetailLoad.Failed("java.io.IOException: https://example.invalid/?token=abc"))
        val scene = page(failed, { actions += it }, { backs++ })
        scene.save("detail-e5-failed")
        assertTrue(scene.has("Couldn't open this title"))
        assertFalse(scene.nodes("java.io.IOException: https://example.invalid/?token=abc").isNotEmpty())
        scene.click(scene.centreOf("Try again"))
        // The second Go Back: the bar's is the first.
        scene.click(scene.centreOf("Go Back", index = 1))
        assertEquals(listOf<DetailAction>(DetailAction.Retry), actions)
        assertEquals(1, backs)
        scene.close()
    }

    // ---- E6: the minimum window ----

    @Test
    fun `in the minimum window everything on E1 is still there and still answers (E6)`() {
        val actions = mutableListOf<DetailAction>()
        val scene = page(loaded(series), { actions += it }, width = MIN_SCREEN_WIDTH, height = MIN_HEIGHT)
        scene.save("detail-e6-minimum-window")
        scene.click(scene.centreOf("Resume S1:E3"))
        scene.click(scene.centreOf("Set watch status"))
        assertEquals(
            listOf(DetailAction.PlaybackRequested("s1e3", fromStart = false), DetailAction.WatchStatusPickerOpened),
            actions,
        )
        scene.close()
    }

    @Test
    fun `Tab reaches Resume, and Return plays it`() {
        val actions = mutableListOf<DetailAction>()
        val scene = page(loaded(series), { actions += it })
        var tabs = 0
        while (actions.isEmpty()) {
            assertTrue(++tabs < MAX_TABS, "Tab never reached a control that plays")
            scene.press(Key.Tab)
            scene.press(Key.Enter)
            // Back and the breadcrumb go back, the synopsis opens: none of those is what is looked for here.
            actions.removeAll { it == DetailAction.SynopsisToggled }
        }
        assertEquals(listOf<DetailAction>(DetailAction.PlaybackRequested("s1e3", fromStart = false)), actions)
        scene.close()
    }

    // ---- What the host does with a press ----

    @Test
    fun `Resume starts where it was left, Start over and another episode start at the beginning`() {
        assertEquals(19 * MINUTE, startPosition(series, "s1e3", fromStart = false))
        assertEquals(0L, startPosition(series, "s1e3", fromStart = true))
        assertEquals(0L, startPosition(series, "s1e2", fromStart = false))
        assertEquals(0L, startPosition(series.copy(resume = null), "s1e3", fromStart = false))
        assertEquals(0L, startPosition(null, "s1e3", fromStart = false))
        // A stored position with no usable length is nothing to start from.
        val broken = series.copy(resume = series.resume!!.copy(durationMs = 0L))
        assertEquals(0L, startPosition(broken, "s1e3", fromStart = false))
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 800
        /** The minimum window, 800, as the page fills it: it has no sidebar (that is `KMP-77`). */
        const val MIN_SCREEN_WIDTH = 800
        const val MIN_HEIGHT = 520
        const val SETTLE_FRAMES = 30
        const val FRAME_NANOS = 50_000_000L
        const val MINUTE = 60_000L
        const val MAX_TABS = 12
        val ART = Color(0xFF00FF80)
        val QUALITIES = listOf(2160, 1080, 720, 480)
    }
}

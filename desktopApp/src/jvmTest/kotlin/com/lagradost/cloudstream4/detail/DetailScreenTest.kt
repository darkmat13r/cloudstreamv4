package com.lagradost.cloudstream4.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.assertPageBar
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopTypography
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.LocalCloudStreamTypography
import com.lagradost.cloudstream4.core.theme.cloudStreamDarkColors
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
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisode
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisodeId
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisodeState
import com.lagradost.cloudstream4.feature.downloads.series.desktop.EpisodeDownloadDesktop
import com.lagradost.cloudstream4.startPosition
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
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
                CompositionLocalProvider(LocalCloudStreamTypography provides desktopTypography()) {
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
        backdrop: @Composable (String) -> Unit = {},
        thumbnail: @Composable (String) -> Unit = {},
        episodeTrailing: (@Composable (DetailEpisode, shown: Boolean) -> Unit)? = null,
    ) = scene(width, height) {
        DetailScreenDesktop(
            state = state,
            onAction = onAction,
            onBack = onBack,
            artwork = { Box(Modifier.fillMaxSize().background(ART)) },
            origin = "Home",
            backdrop = backdrop,
            episodeTrailing = episodeTrailing,
            thumbnail = thumbnail,
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

    /**
     * Where the one piece reading as [words] is drawn, in the unmerged tree: a row is one clickable, so the merged tree
     * gives its pieces the whole row's bounds.
     */
    private fun ImageComposeScene.boundsOf(words: String): Rect {
        settle()
        val found = semanticsOwners.flatMap { it.unmergedRootSemanticsNode.all() }.filter { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == words } ||
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == words }
        }
        return found.singleOrNull()?.boundsInRoot ?: error("expected one piece reading \"$words\", found ${found.size}")
    }

    /** The colour drawn at [x], [y] once the scene has settled. */
    private fun ImageComposeScene.pixel(x: Int, y: Int): Color {
        settle()
        val bitmap = Bitmap.makeFromImage(render(SETTLE_FRAMES * FRAME_NANOS))
        return Color(bitmap.getColor(x, y))
    }

    /** Where the page starts under the bar: the Back button, the bar's padding under it, and its hairline. */
    private fun ImageComposeScene.barBottom(): Int =
        (nodes("Go Back").single().boundsInRoot.bottom + RELATED_GAP + 1).toInt()

    /** What a screen reader is told about the one control that reads as [words], beside its name. */
    private fun ImageComposeScene.stateOf(words: String): String? =
        nodes(words).single().config.getOrNull(SemanticsProperties.StateDescription)

    private fun ImageComposeScene.isSelected(words: String): Boolean {
        val found = nodes(words).filter { SemanticsProperties.Selected in it.config }
        val node = found.singleOrNull() ?: error("expected one choice reading \"$words\", found ${found.size}")
        return node.config[SemanticsProperties.Selected]
    }

    // ---- E1: a series ----

    @Test
    fun `a title's page starts with the page bar, 52 tall with a hairline under it (KMP-98, E1)`() {
        // Back and the path, in the one band every Mac page has.
        val scene = page(loaded(series))
        scene.settle()
        scene.assertPageBar(WIDTH, atNanos = SETTLE_FRAMES * FRAME_NANOS)
        scene.close()
    }

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

    @Test
    fun `the watch list button says which list the title is on, or None`() {
        val on = page(loaded(series))
        assertEquals("Watching", on.stateOf("Set watch status"))
        on.close()
        val off = page(loaded(film))
        assertEquals("None", off.stateOf("Set watch status"))
        off.close()
    }

    // ---- E1's facts and genres (KMP-82) ----

    private val genres = listOf("Drama", "Mystery", "Science Fiction", "Thriller", "Slow Burn", "Space", "Horror")

    private val withFacts = series.copy(
        score = 8.4f,
        tags = genres,
        contentRating = "TV-MA",
        statusName = "Ongoing",
        durationMinutes = 48,
    )

    /** The name, the synopsis and every word under them, with no fact the source might have given. */
    private val withoutFacts = series.copy(year = null, typeName = null, providerName = "")

    /** The name as E1's heading: the breadcrumb says it too, smaller. */
    private fun ImageComposeScene.heading(name: String) = nodes(name).maxBy { it.boundsInRoot.height }.boundsInRoot

    private fun ImageComposeScene.gapUnderName(words: String, name: String): Float =
        nodes(words).single().boundsInRoot.top - heading(name).bottom

    @Test
    fun `a title with every fact draws each of them under its name, and five of its genres`() {
        val scene = page(loaded(withFacts))
        scene.save("detail-e1-facts")
        // The score is read as one thing, its star and its number.
        assertTrue(scene.has("Rating 8.4"), "the score, with its star")
        listOf("TV-MA", "2019", "48 min", "Ongoing", "Series", "CineTmdb").forEach { fact ->
            assertTrue(scene.has(fact), fact)
        }
        assertTrue(scene.has(genres.take(5).joinToString(" · ")), "the first five genres, on their own line")
        assertFalse(scene.has(genres.take(6).joinToString(" · ")), "no sixth")
        // The facts come before the genres, and both before the synopsis.
        val facts = scene.nodes("TV-MA").single().boundsInRoot
        val shown = scene.nodes(genres.take(5).joinToString(" · ")).single().boundsInRoot
        val synopsis = scene.nodes(series.plot!!).single().boundsInRoot
        assertTrue(facts.bottom <= shown.top && shown.bottom <= synopsis.top, "$facts, $shown, $synopsis")
        scene.close()
    }

    @Test
    fun `a title with no facts draws neither line, and its synopsis sits where the facts would have`() {
        val scene = page(loaded(withoutFacts))
        assertFalse(scene.has("Rating 8.4"))
        assertFalse(scene.nodes(series.title).isEmpty())
        // Only the column's own gap between the name and the synopsis: nothing empty is drawn between them.
        assertEquals(RELATED_GAP, scene.gapUnderName(series.plot!!, series.title))
        scene.close()

        val full = page(loaded(withFacts))
        assertTrue(full.gapUnderName(series.plot!!, series.title) > RELATED_GAP, "the facts take room when there are some")
        full.close()
    }

    @Test
    fun `facts that need two lines keep every fact, and no line starts with a separator`() {
        val source = "Source One, the long-named mirror"
        val scene = page(loaded(withFacts.copy(providerName = source)), width = MIN_SCREEN_WIDTH)
        scene.save("detail-e1-facts-wrapped")
        val facts = listOf("Rating 8.4", "TV-MA", "2019", "48 min", "Ongoing", "Series", source)
        val bounds = facts.map { fact -> scene.nodes(fact).single().boundsInRoot }
        val name = scene.heading(series.title)
        // Facts are centred on their line, so a line is the facts sharing a centre, give or take the half pixel that
        // centring a badge or the star's row rounds to: neither is as tall as a word. Lines are a line's height apart.
        val lines = bounds.sortedBy { it.center.y }.fold(mutableListOf<MutableList<Rect>>()) { found, fact ->
            if (found.isNotEmpty() && fact.center.y - found.last().first().center.y < SAME_LINE) found.last() += fact
            else found += mutableListOf(fact)
            found
        }.associateBy { it.first().center.y }
        assertTrue(lines.size >= 2, "the facts wrap at this width: ${bounds.map { it.center.y }}")
        // Each line's first fact sits at the column's own edge: nothing, a separator least of all, comes before it.
        lines.forEach { (centre, line) -> assertEquals(name.left, line.minOf { it.left }, "the line centred at $centre") }
        bounds.forEach { assertTrue(it.right <= name.left + READING_WIDTH, "inside the reading width: $it") }
        scene.close()
    }

    @Test
    fun `a fact wider than a line is cut to it, on one line`() {
        val source = "Source " + "w".repeat(200)
        val scene = page(loaded(withFacts.copy(providerName = source)), width = MIN_SCREEN_WIDTH)
        val year = scene.nodes("2019").single().boundsInRoot
        val wide = scene.nodes(source).single().boundsInRoot
        val name = scene.heading(series.title)
        assertEquals(year.height, wide.height, "one line, cut with an ellipsis rather than wrapped")
        assertEquals(name.left, wide.left, "on a line of its own")
        assertTrue(wide.right <= name.left + READING_WIDTH, "inside the reading width: $wide")
        scene.close()
    }

    @Test
    fun `long genres stay on one line inside the reading width`() {
        val long = List(5) { index -> "Genre ${index + 1} " + "w".repeat(56) }
        val short = page(loaded(withFacts))
        val oneLine = short.nodes(genres.take(5).joinToString(" · ")).single().boundsInRoot.height
        short.close()

        val scene = page(loaded(withFacts.copy(tags = long)))
        scene.save("detail-e1-long-genres")
        val line = scene.nodes(long.joinToString(" · ")).single().boundsInRoot
        val name = scene.heading(series.title)
        assertEquals(oneLine, line.height, "one line, cut with an ellipsis rather than wrapped")
        assertTrue(line.right <= name.left + READING_WIDTH, "inside the reading width: $line")
        // The facts above them are all still there.
        listOf("TV-MA", "2019", "48 min", "Ongoing").forEach { fact -> assertTrue(scene.has(fact), fact) }
        scene.close()
    }

    // ---- E1 and E3's backdrop (KMP-100) ----

    @Test
    fun `a title with a backdrop washes the header with it, and one without draws plain ground`() {
        val asked = mutableListOf<String>()
        val drawn = page(loaded(series.copy(backdropUrl = "https://img.example/b.jpg")), backdrop = { url ->
            asked += url
            Box(Modifier.fillMaxSize().background(BACKDROP))
        })
        drawn.save("detail-e1-backdrop")
        // Right of the words, near the top of the header: the art shows through the scrim's lightest part. Sampling
        // settles the scene, so the slot has been composed by the time it is asked about below.
        val top = drawn.pixel(WIDTH - 40, drawn.barBottom() + 24)
        assertEquals(listOf("https://img.example/b.jpg"), asked.distinct())
        assertTrue(top != Color.Black, "the wash is drawn: $top")
        // At the scrim's foot the page is the ground again.
        val foot = drawn.pixel(WIDTH - 40, drawn.barBottom() + BACKDROP_HEIGHT - 1)
        assertTrue(foot.red < 0.01f && foot.green < 0.01f && foot.blue < 0.01f, "the scrim's foot is the ground: $foot")
        drawn.close()

        val plain = page(loaded(series), backdrop = { url -> asked += "none: $url" })
        assertEquals(Color.Black, plain.pixel(WIDTH - 40, plain.barBottom() + 24))
        plain.close()
        assertTrue(asked.none { it.startsWith("none") }, "no slot is asked for a title without a backdrop: $asked")
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
    fun `an unstarted show with a special listed first opens its list on Season 1, the season Play starts (KMP-92)`() {
        val actions = mutableListOf<DetailAction>()
        val unstarted = series.copy(
            episodes = listOf(DetailEpisode("Making of", "sp", 0, 1)) + episodes,
            resume = null,
        )
        val scene = page(loaded(unstarted), { actions += it })
        assertTrue(scene.isSelected("Season 1"))
        assertFalse(scene.isSelected("Specials"))
        assertTrue(scene.has("Pilot") && !scene.has("Making of"))
        scene.click(scene.centreOf("Play Episode"))
        assertEquals(listOf<DetailAction>(DetailAction.PlaybackRequested("s1e1", fromStart = false)), actions)
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

    // ---- KMP-109: the host's cell on an episode's row ----

    /** A cell that says which episode it was given and whether its row shows its verbs. */
    private val sayingCell: @Composable (DetailEpisode, Boolean) -> Unit = { episode, shown ->
        BasicText("cell ${episode.data} ${if (shown) "shown" else "hidden"}")
    }

    @Test
    fun `each row draws the host's cell, told its own episode and whether the pointer is on the row (KMP-109)`() {
        val scene = page(loaded(series), episodeTrailing = sayingCell)
        listOf("s1e1", "s1e2", "s1e3").forEach { data ->
            assertTrue(scene.has("cell $data hidden"), "$data's cell, hidden before the pointer arrives")
        }
        assertFalse(scene.has("cell s2e1 hidden"), "a season that is not shown draws no cells")
        scene.hover(scene.centreOf("Cat's in the Bag"))
        assertTrue(scene.has("cell s1e2 shown"), "the hovered row's cell is told to show its verbs")
        assertTrue(scene.has("cell s1e1 hidden") && scene.has("cell s1e3 hidden"), "and no other row's")
        scene.hover(Offset(4f, 4f))
        assertTrue(scene.has("cell s1e2 hidden"), "the pointer gone, the cell hides its verbs again")
        scene.close()
    }

    @Test
    fun `a row with focus tells its cell to show, as it shows Play (KMP-109, E2)`() {
        val scene = page(loaded(series), episodeTrailing = sayingCell)
        var presses = 0
        while (!scene.has("Play Episode") && presses < MAX_TABS * 2) {
            scene.press(Key.Tab)
            presses++
        }
        assertEquals(1, scene.nodes("Play Episode").size, "Tab reached a row")
        val shown = listOf("s1e1", "s1e2", "s1e3").filter { scene.has("cell $it shown") }
        assertEquals(listOf("s1e1"), shown, "the first row has focus, and only its cell shows")
        scene.close()
    }

    @Test
    fun `the cell sits after the row's words and clear of Play, and the words do not move (KMP-109)`() {
        val cell: @Composable (DetailEpisode, Boolean) -> Unit = { episode, _ ->
            Box(Modifier.size(CELL_WIDTH.dp, CELL_HEIGHT.dp).semantics { contentDescription = "cell ${episode.data}" })
        }
        val plain = page(loaded(series))
        plain.hover(plain.centreOf("Cat's in the Bag"))
        val wordsBefore = plain.boundsOf("Cat's in the Bag")
        plain.close()
        val scene = page(loaded(series), episodeTrailing = cell)
        scene.hover(scene.centreOf("Cat's in the Bag"))
        val words = scene.boundsOf("Cat's in the Bag")
        val box = scene.boundsOf("cell s1e2")
        val play = scene.boundsOf("Play Episode")
        assertEquals(wordsBefore.left, words.left, "the name starts where it does with no cell")
        assertEquals(wordsBefore.top, words.top, "and at the same height")
        assertTrue(box.left >= words.right, "the cell is after the name: cell ${box.left}, name ends ${words.right}")
        assertTrue(box.right <= play.left, "the cell ends before Play: cell ${box.right}, Play ${play.left}")
        assertEquals(play.center.y, box.center.y, 1f, "the cell is centred on the row, as Play is")
        scene.close()
    }

    @Test
    fun `part 1's Download cell in the slot says each episode's state, and its verbs only on the hovered row`() {
        val downloads = mapOf(
            "s1e1" to download(1, DownloadEpisodeState.Done, done = 612 * MB, total = 612 * MB),
            "s1e2" to download(2, DownloadEpisodeState.Running, done = 240 * MB, total = 598 * MB),
        )
        val scene = page(loaded(series)) { episode, shown ->
            EpisodeDownloadDesktop(
                download = downloads[episode.data],
                shown = shown,
                formatBytes = { "${it / MB} MB" },
                onDownload = {},
                onPause = {},
                onResume = {},
                onCancel = {},
            )
        }
        assertFalse(scene.has("Pause") || scene.has("Download"), "no verb before the pointer arrives")
        scene.hover(scene.centreOf("Cat's in the Bag"))
        assertEquals(1, scene.nodes("Pause").size, "the downloading row offers Pause")
        assertEquals(1, scene.nodes("Cancel").size, "and Cancel")
        assertFalse(scene.has("Download"), "the row with no download is not hovered, so offers nothing")
        scene.save("detail-e2-download-cells")
        scene.hover(scene.centreOf("And the Bag's in the River"))
        assertEquals(1, scene.nodes("Download").size, "the row with no download offers Download")
        assertFalse(scene.has("Pause"), "and the downloading row's verbs are gone")
        scene.save("detail-e2-download-cell-none")
        scene.close()
    }

    @Test
    fun `a refused episode's row says it is not available here and offers no Download (KMP-109 part 2b)`() {
        val downloads = mapOf(
            "s1e1" to download(1, DownloadEpisodeState.Done, done = 612 * MB, total = 612 * MB),
            "s1e2" to download(2, DownloadEpisodeState.Running, done = 240 * MB, total = 598 * MB),
        )
        val scene = page(loaded(series)) { episode, shown ->
            EpisodeDownloadDesktop(
                download = downloads[episode.data],
                shown = shown,
                formatBytes = { "${it / MB} MB" },
                onDownload = {},
                onPause = {},
                onResume = {},
                onCancel = {},
                unavailable = episode.data == "s1e3",
            )
        }
        assertEquals(1, scene.nodes("Not available here").size, "the refused row says so before the pointer arrives")
        scene.hover(scene.centreOf("And the Bag's in the River"))
        assertFalse(scene.has("Download"), "and offers no Download while hovered")
        assertEquals(1, scene.nodes("Play Episode").size, "the row still plays")
        scene.save("detail-e2-download-unavailable")
        scene.close()
    }

    private fun download(id: Int, state: DownloadEpisodeState, done: Long, total: Long) =
        DownloadEpisode(DownloadEpisodeId(id), 1, id, null, state, bytesDone = done, bytesTotal = total)

    // ---- KMP-63: each episode's own watch state, on its second line ----

    /**
     * The page's first season with one episode marked watched, one not started, and the one Resume plays 19 minutes
     * in, so its row and Resume's "29m remaining" agree.
     */
    private val watchedSeries = series.copy(
        episodes = listOf(
            episodes[0].copy(markedWatched = true),
            episodes[1],
            episodes[2].copy(progress = DetailResume("s1e3", 19 * MINUTE, 48 * MINUTE, episode = null)),
        ) + episodes.drop(3),
    )

    @Test
    fun `an episode's second line says Watched or the minutes left where its runtime was (KMP-63)`() {
        val scene = page(loaded(watchedSeries))
        assertTrue(scene.has("S1:E1 \u00b7 Watched"), "a watched episode says so")
        assertTrue(scene.has("S1:E2 \u00b7 48 min"), "one not started keeps its runtime")
        assertTrue(scene.has("S1:E3 \u00b7 29 min left"), "a started one says how long is left, as Resume does")
        assertFalse(scene.has("S1:E1 \u00b7 48 min"), "the watched one's runtime is not drawn")
        scene.save("detail-e2-watch-state")
        scene.close()
    }

    /** `BUG-91`: the minutes left are now a plural; one form and the other read the words the string did. */
    @Test
    fun `one minute left and twenty-four read as they did, through the plural (BUG-91)`() {
        val started = series.copy(
            episodes = listOf(
                // 9 of 10 minutes: 90%, under the 95% that reads watched, with 1 minute left.
                episodes[0].copy(progress = DetailResume("s1e1", 9 * MINUTE, 10 * MINUTE, episode = null)),
                episodes[1].copy(progress = DetailResume("s1e2", 24 * MINUTE, 48 * MINUTE, episode = null)),
            ) + episodes.drop(2),
        )
        val scene = page(loaded(started))
        assertTrue(scene.has("S1:E1 \u00b7 1 min left"), "one")
        assertTrue(scene.has("S1:E2 \u00b7 24 min left"), "other")
        scene.close()
    }

    @Test
    fun `the watch state changes only the episodes' second lines and their stills' rules, pixel for pixel (KMP-63)`() {
        val plain = page(loaded(series))
        val before = plain.bitmap()
        // The old lines and the new, in case an old one is the wider.
        val oldLines = listOf("S1:E1 \u00b7 48 min", "S1:E3 \u00b7 48 min").map { plain.boundsOf(it) }
        plain.close()
        val scene = page(loaded(watchedSeries))
        // KMP-115: the watch state also draws E1's rule along the still of a watched or started row.
        val stills = listOf("1", "3").map { stillBoxOf(scene.boundsOf(it)) }
        val lines = oldLines + stills +
            listOf("S1:E1 \u00b7 Watched", "S1:E3 \u00b7 29 min left").map { scene.boundsOf(it) }
        val after = scene.bitmap()
        scene.close()
        var outside = 0
        var inside = 0
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            if (before.getColor(x, y) == after.getColor(x, y)) continue
            if (lines.any { it.contains(Offset(x + 0.5f, y + 0.5f)) }) inside++ else outside++
        }
        assertEquals(0, outside, "pixels changed outside the second lines and the stills")
        assertTrue(inside > 0, "the second lines changed")
    }

    private fun ImageComposeScene.bitmap(): Bitmap {
        settle()
        return Bitmap.makeFromImage(render(SETTLE_FRAMES * FRAME_NANOS))
    }

    // ---- KMP-115: board E1's still and its progress rule on each episode row ----

    /**
     * Season 1: a watched episode, one not started with no still, and the one Resume plays, 19 of 48 minutes in, so its
     * row and Resume's "29m remaining" agree. The first and third have a still.
     */
    private val stillSeries = series.copy(
        episodes = listOf(
            episodes[0].copy(markedWatched = true, stillUrl = "https://img.example/s1e1.jpg"),
            episodes[1],
            episodes[2].copy(
                stillUrl = "https://img.example/s1e3.jpg",
                progress = DetailResume("s1e3", 19 * MINUTE, 48 * MINUTE, episode = null),
            ),
        ) + episodes.drop(3),
    )

    private fun stillPage(asked: MutableList<String> = mutableListOf()) =
        page(loaded(stillSeries), thumbnail = { url ->
            asked += url
            Box(Modifier.fillMaxSize().background(STILL))
        })

    /** A row's still, from its number's cell: E1's 96 by 56, ending 16 before the cell, centred on the row. */
    private fun stillBoxOf(number: Rect): Rect {
        val right = number.left - GROUP_GAP
        val middle = number.center.y
        return Rect(right - STILL_WIDTH, middle - STILL_HEIGHT / 2f, right, middle + STILL_HEIGHT / 2f)
    }

    /** The first and last x on line [y] drawn exactly [colour], or null where none is. */
    private fun Bitmap.runOf(y: Int, colour: Color, from: Int = 0, to: Int = WIDTH): IntRange? {
        val xs = (from until to).filter { Color(getColor(it, y)) == colour }
        return if (xs.isEmpty()) null else xs.first()..xs.last()
    }

    /** The first and last y in column [x] drawn exactly [colour], between [from] and [to]. */
    private fun Bitmap.columnOf(x: Int, colour: Color, from: Int, to: Int): IntRange? {
        val ys = (from until to).filter { Color(getColor(x, it)) == colour }
        return if (ys.isEmpty()) null else ys.first()..ys.last()
    }

    @Test
    fun `each row starts with its still, 96 by 56, 16 before its number, and the rows are 72 apart (KMP-115, E1)`() {
        val asked = mutableListOf<String>()
        val scene = stillPage(asked)
        val one = scene.boundsOf("1")
        val two = scene.boundsOf("2")
        val image = scene.bitmap()
        scene.save("detail-e1-episode-stills")
        scene.close()
        assertEquals(EPISODE_ROW, (two.top - one.top).toInt(), "row pitch")
        val across = assertNotNull(image.runOf(one.center.y.toInt(), STILL, to = one.left.toInt()), "the first still")
        assertEquals(STILL_WIDTH, across.last - across.first + 1, "the still's width")
        assertEquals(GROUP_GAP, (one.left - (across.last + 1)).toInt(), "from the still to the number cell")
        val middle = one.center.y.toInt()
        val down = assertNotNull(image.columnOf(across.first + STILL_WIDTH / 2, STILL, middle - EPISODE_ROW / 2, middle + EPISODE_ROW / 2))
        // The first row is watched, so its rule takes the still's last 4 lines.
        assertEquals(STILL_HEIGHT - RULE, down.last - down.first + 1, "the still above its rule")
        assertEquals(listOf("https://img.example/s1e1.jpg", "https://img.example/s1e3.jpg"), asked.distinct())
    }

    @Test
    fun `the rule is the share watched, snapped to the 4 step, the whole still, 40 for 19 of 48, none unstarted (KMP-115)`() {
        val scene = stillPage()
        val rows = listOf("1", "2", "3").map { scene.boundsOf(it) }
        val image = scene.bitmap()
        scene.close()
        val live = cloudStreamDarkColors().live
        val stillLeft = assertNotNull(image.runOf(rows[0].center.y.toInt(), STILL, to = rows[0].left.toInt())).first
        val stillRight = stillLeft + STILL_WIDTH - 1
        fun ruleOf(row: Rect): Pair<IntRange, IntRange>? {
            // Clear of the still's rounded corner, where the rule's edge is blended.
            val middle = row.center.y.toInt()
            val down = image.columnOf(stillLeft + CLEAR_OF_CORNER, live, middle - EPISODE_ROW / 2, middle + EPISODE_ROW / 2) ?: return null
            val across = assertNotNull(image.runOf(down.first, live, from = stillLeft, to = stillRight + 1))
            return down to across
        }
        val (watchedDown, watched) = assertNotNull(ruleOf(rows[0]), "a watched row has a rule")
        assertEquals(RULE, watchedDown.last - watchedDown.first + 1, "4 tall")
        // The still's rounded corner takes the last pixels of a full rule's top line.
        assertTrue(watched.last >= stillRight - RULE, "a watched row's rule runs the whole still: ends ${watched.last}, still ends $stillRight")
        assertNull(ruleOf(rows[1]), "a row not started has none")
        val (_, started) = assertNotNull(ruleOf(rows[2]), "a started row has a rule")
        assertEquals(STARTED_RULE, started.last - stillLeft + 1, "19 of 48 minutes, snapped to the 4 step")
    }

    /**
     * The PM's ruling on `#374`: a season with no still keeps the compact row, so a host that sends none (the Mac's,
     * until `KMP-116`) draws the page as before. No still column, no ground box, nothing asked for.
     */
    @Test
    fun `a season with no still keeps the compact 56 row and draws no still column (KMP-115)`() {
        val asked = mutableListOf<String>()
        val scene = page(loaded(series), thumbnail = { url -> asked += url })
        val one = scene.boundsOf("1")
        val two = scene.boundsOf("2")
        val image = scene.bitmap()
        scene.close()
        assertEquals(COMPACT_ROW, (two.top - one.top).toInt(), "row pitch")
        assertNull(image.runOf(one.center.y.toInt(), cloudStreamDarkColors().surface, to = one.left.toInt()), "no still ground")
        assertTrue(asked.isEmpty(), "no image asked for")
    }

    /** One still in the season is enough: every row of it takes the still column, and the others draw the ground. */
    @Test
    fun `one still in the season gives every row of it the still column (KMP-115)`() {
        val one = series.copy(episodes = listOf(episodes[0].copy(stillUrl = "https://img.example/s1e1.jpg")) + episodes.drop(1))
        val scene = page(loaded(one), thumbnail = { Box(Modifier.fillMaxSize().background(STILL)) })
        val first = scene.boundsOf("1")
        val second = scene.boundsOf("2")
        val image = scene.bitmap()
        scene.close()
        assertEquals(EPISODE_ROW, (second.top - first.top).toInt(), "row pitch")
        val ground = assertNotNull(image.runOf(second.center.y.toInt(), cloudStreamDarkColors().surface, to = second.left.toInt()))
        assertEquals(STILL_WIDTH, ground.last - ground.first + 1, "the second row's still ground")
    }

    @Test
    fun `a row with no still draws the still's ground, and its image is never asked for (KMP-115)`() {
        val asked = mutableListOf<String>()
        val scene = stillPage(asked)
        val two = scene.boundsOf("2")
        val image = scene.bitmap()
        scene.close()
        val ground = assertNotNull(image.runOf(two.center.y.toInt(), cloudStreamDarkColors().surface, to = two.left.toInt()))
        assertEquals(STILL_WIDTH, ground.last - ground.first + 1, "the still's ground, as wide as a still")
        assertTrue(asked.none { "s1e2" in it }, "nothing asked for a row with no still")
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
        const val MB = 1_000_000L

        /** A cell a little wider than one verb, so its edges are easy to tell from Play's. */
        const val CELL_WIDTH = 48
        const val CELL_HEIGHT = 20

        /** `DesktopDimens.RelatedGap` and `ReadingWidth`, in pixels at the scene's density of 1. */
        const val RELATED_GAP = 8f

        /** Two facts whose centres are nearer than this are on one line: a line is about 24 apart. */
        const val SAME_LINE = 4f
        const val READING_WIDTH = 640f
        const val MAX_TABS = 12
        val ART = Color(0xFF00FF80)
        val STILL = Color(0xFF3060FF)

        /** Board E1's row, in pixels at the scene's density of 1: 72 tall, a 96 by 56 still, a 4 rule, the 16 gap. */
        const val EPISODE_ROW = 72

        /** The row in a season with no still: the 56 it was before `KMP-115`. */
        const val COMPACT_ROW = 56
        const val STILL_WIDTH = 96
        const val STILL_HEIGHT = 56
        const val RULE = 4
        const val GROUP_GAP = 16

        /** Twice the still's corner radius in from its left edge: the rule there is drawn whole. */
        const val CLEAR_OF_CORNER = 16

        /** 19 of 48 minutes of a 96 still is 38, drawn at 40 on the 4 step, as E1 draws it. */
        const val STARTED_RULE = 40
        val BACKDROP = Color(0xFFFF8040)

        /** `DetailScreenDesktopDefaults.BackdropHeight`, in pixels at the scene's density of 1. */
        const val BACKDROP_HEIGHT = 296
        val QUALITIES = listOf(2160, 1080, 720, 480)
    }
}

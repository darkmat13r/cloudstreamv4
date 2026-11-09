package com.lagradost.cloudstream4.search

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
import com.lagradost.cloudstream4.core.designsystem.desktop.PosterSize
import com.lagradost.cloudstream4.core.designsystem.desktop.forDesktop
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.LocalCloudStreamTypography
import com.lagradost.cloudstream4.feature.search.SearchAction
import com.lagradost.cloudstream4.feature.search.SearchContent
import com.lagradost.cloudstream4.feature.search.SearchHistoryEntry
import com.lagradost.cloudstream4.feature.search.SearchResultGroup
import com.lagradost.cloudstream4.feature.search.SearchResultId
import com.lagradost.cloudstream4.feature.search.SearchResultItem
import com.lagradost.cloudstream4.feature.search.SearchSourceChoice
import com.lagradost.cloudstream4.feature.search.SearchSourceHealth
import com.lagradost.cloudstream4.feature.search.SearchSources
import com.lagradost.cloudstream4.feature.search.SearchState
import com.lagradost.cloudstream4.feature.search.SearchStatus
import com.lagradost.cloudstream4.feature.search.SearchTypeFilter
import com.lagradost.cloudstream4.feature.search.SearchTypeFilters
import com.lagradost.cloudstream4.feature.search.SourcePanelState
import com.lagradost.cloudstream4.feature.search.desktop.SearchScreenDesktop
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * `KMP-56`, boards S1 to S7 of `DS-22`: real pointer and key events through the scene, and a PNG of each state when
 * `cloudstream.search.renders` names a directory.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class SearchScreenTest {

    private val chips = SearchTypeFilters(
        available = listOf(
            SearchTypeFilter.Movies,
            SearchTypeFilter.TvSeries,
            SearchTypeFilter.Anime,
            SearchTypeFilter.AsianDrama,
            SearchTypeFilter.Documentaries,
        ),
        selected = setOf(SearchTypeFilter.TvSeries),
    )
    private val sources = SearchSources(selected = 12, total = 48)

    private val recent = SearchContent.Recent(
        listOf("blade runner" to "2 minutes ago", "severance" to "Today 19:04", "frieren" to "Yesterday")
            .map { (query, at) -> SearchHistoryEntry(query, at) },
    )

    private fun group(name: String, count: Int) =
        SearchResultGroup(name, List(count) { SearchResultItem(SearchResultId("$name$it"), "$name title $it", providerName = name) })

    private val results = SearchContent.Results(listOf(group("Vidsrc", 6), group("Gogoanime", 4)))

    private val choices = listOf(
        SearchSourceChoice("Gogoanime", "Gogoanime", selected = true, health = SearchSourceHealth.Ok),
        SearchSourceChoice("Vidsrc", "Vidsrc", selected = true, health = SearchSourceHealth.Ok),
        SearchSourceChoice("Zoro", "Zoro", selected = false, health = SearchSourceHealth.Failed),
    )

    private fun state(status: SearchStatus, query: String = "", panel: SourcePanelState? = null) =
        SearchState(query = query, sources = sources, status = status, typeFilters = chips, sourcePanel = panel)

    private val openPanel = SourcePanelState(choices, draft = setOf("Gogoanime", "Vidsrc"))

    private fun scene(width: Int = WIDTH, height: Int = HEIGHT, content: @Composable () -> Unit) =
        ImageComposeScene(width, height, Density(1f)) {
            CloudStreamTheme {
                CompositionLocalProvider(LocalCloudStreamTypography provides CloudStreamTheme.typography.forDesktop()) {
                    Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) { content() }
                }
            }
        }

    private fun screen(state: SearchState, width: Int = WIDTH, height: Int = HEIGHT, onAction: (SearchAction) -> Unit = {}) =
        scene(width, height) {
            SearchScreenDesktop(state, onAction, artwork = { Box(Modifier.fillMaxSize().background(Color.DarkGray)) })
        }

    private fun ImageComposeScene.settle() = repeat(SETTLE_FRAMES) { render(it * FRAME_NANOS) }

    private fun ImageComposeScene.click(at: Offset) {
        sendPointerEvent(PointerEventType.Move, at)
        sendPointerEvent(PointerEventType.Press, at)
        sendPointerEvent(PointerEventType.Release, at)
        settle()
    }

    private fun ImageComposeScene.save(name: String) {
        val directory = System.getProperty("cloudstream.search.renders") ?: return
        settle()
        val png = checkNotNull(render(SETTLE_FRAMES * FRAME_NANOS).encodeToData(EncodedImageFormat.PNG)).bytes
        File(directory, "$name.png").writeBytes(png)
    }

    /**
     * `KMP-90e`: Poster size sets the smallest card, 128, 160 or 200, so a bigger size is fewer columns. Medium is the
     * grid as it was. At 760 and 1280 wide, less the 24 margins, with 16 between cards.
     */
    @Test
    fun `Poster size changes how many results fit across, and Medium is the grid as it was`() {
        val many = state(SearchStatus.Showing(SearchContent.Results(listOf(group("Wide", 12)))), query = "q")
        val expected = mapOf(
            760 to listOf(PosterSize.Small to 5, PosterSize.Medium to 4, PosterSize.Large to 3),
            1280 to listOf(PosterSize.Small to 8, PosterSize.Medium to 7, PosterSize.Large to 5),
        )
        expected.forEach { (width, sizes) ->
            sizes.forEach { (size, columns) ->
                val scene = scene(width) {
                    SearchScreenDesktop(many, {}, artwork = { Box(Modifier.fillMaxSize().background(Color.DarkGray)) }, posterSize = size)
                }
                assertEquals(columns, scene.firstLine("Wide title"), "$size at $width")
                if (width == 1280) scene.save("poster-size-${size.name.lowercase()}")
                scene.close()
            }
        }
        val before = scene(1280) { SearchScreenDesktop(many, {}, artwork = { Box(Modifier.fillMaxSize().background(Color.DarkGray)) }) }
        assertEquals(7, before.firstLine("Wide title"), "no posterSize is Medium")
        before.close()
    }

    /** How many cards the first line of [prefix]'s titles holds, read from what a screen reader is told. */
    private fun ImageComposeScene.firstLine(prefix: String): Int {
        settle()
        fun SemanticsNode.every(): List<SemanticsNode> = listOf(this) + children.flatMap { it.every() }
        val tops = semanticsOwners.flatMap { it.rootSemanticsNode.every() }
            .filter { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.startsWith(prefix) } }
            .map { it.boundsInRoot }
        val first = tops.minOf { it.top }
        return tops.filter { it.top == first }.map { it.left }.distinct().size
    }

    @Test
    fun `renders`() {
        if (System.getProperty("cloudstream.search.renders") == null) return
        val partial = SearchStatus.Partial(results, answered = 9, total = 12, detail = "TIMEOUT · HTTP 503")
        listOf(
            "S1-recent" to state(SearchStatus.Showing(recent)),
            "S2-results" to state(SearchStatus.Showing(results), query = "blade runner"),
            "S3-partial" to state(partial, query = "blade runner"),
            "S4-sources" to state(SearchStatus.Showing(results), query = "blade runner", panel = openPanel),
            "S6-no-results" to state(SearchStatus.NoResults(12), query = "kingfisher"),
        ).forEach { (name, state) ->
            val scene = screen(state)
            scene.save(name)
            scene.close()
        }
        val small = screen(state(partial, query = "blade runner"), width = MIN_WIDTH, height = MIN_HEIGHT)
        small.save("S7-minimum-partial")
        small.close()
    }

    @Test
    fun `sources is a button that opens the panel, and a chip toggles its type`() {
        val actions = mutableListOf<SearchAction>()
        val scene = screen(state(SearchStatus.Showing(recent)), onAction = { actions += it })
        scene.settle()
        scene.click(Offset(SOURCES_X, TOOLBAR_Y))
        scene.click(Offset(ANIME_CHIP_X, CHIP_Y))
        assertEquals(listOf(SearchAction.SourcePanelOpened, SearchAction.TypeFilterToggled(SearchTypeFilter.Anime)), actions)
        scene.close()
    }

    @Test
    fun `the panel toggles a row, commits on done, and the scrim discards`() {
        val actions = mutableListOf<SearchAction>()
        val scene = screen(state(SearchStatus.Showing(results), query = "q", panel = openPanel), onAction = { actions += it })
        scene.settle()
        scene.click(Offset(PANEL_ROW_X, PANEL_FIRST_ROW_Y + 2 * PANEL_ROW_STEP))
        scene.click(Offset(DONE_X, DONE_Y))
        scene.click(Offset(SCRIM_X, SCRIM_Y))
        assertEquals(
            listOf(SearchAction.SourceToggled("Zoro"), SearchAction.SourcePanelDone, SearchAction.SourcePanelDismissed),
            actions,
        )
        scene.close()
    }

    /** The field keeps focus under the panel, and its own Escape would empty it. The panel's has to win. */
    @Test
    fun `escape closes the open panel and leaves the query alone`() {
        val actions = mutableListOf<SearchAction>()
        val scene = screen(state(SearchStatus.Showing(results), query = "blade runner", panel = openPanel), onAction = { actions += it })
        scene.settle()
        assertTrue(scene.sendKeyEvent(escape()))
        assertEquals(listOf<SearchAction>(SearchAction.SourcePanelDismissed), actions)
        scene.close()
    }

    private fun escape() = KeyEvent(Key.Escape, KeyEventType.KeyDown)

    private companion object {
        const val WIDTH = 1060
        const val HEIGHT = 800
        /** The window's minimum, less the 220 sidebar: what the page gets at 800×520. */
        const val MIN_WIDTH = 580
        const val MIN_HEIGHT = 520
        const val SETTLE_FRAMES = 30
        const val FRAME_NANOS = 50_000_000L
        /** Measured from `S1-recent.png` and `S4-sources.png` at 1060×800. */
        const val TOOLBAR_Y = 44f
        const val SOURCES_X = 950f
        const val CHIP_Y = 104f
        const val ANIME_CHIP_X = 212f
        const val PANEL_ROW_X = 850f
        const val PANEL_FIRST_ROW_Y = 88f
        const val PANEL_ROW_STEP = 48f
        const val DONE_X = 1011f
        const val DONE_Y = 30f
        const val SCRIM_X = 100f
        const val SCRIM_Y = 700f
    }
}

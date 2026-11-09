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
import com.lagradost.cloudstream4.allNodes
import com.lagradost.cloudstream4.assertOutlined
import com.lagradost.cloudstream4.assertPageBar
import com.lagradost.cloudstream4.buttonLabelled
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopFailureBannerDefaults
import com.lagradost.cloudstream4.core.designsystem.desktop.PosterSize
import com.lagradost.cloudstream4.tagged
import com.lagradost.cloudstream4.textNode
import com.lagradost.cloudstream4.texts
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopTypography
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

    /** S2's second line: a type and a quality, a type alone, and neither. */
    private val labelled = SearchContent.Results(
        listOf(
            SearchResultGroup(
                "Source One",
                listOf(
                    SearchResultItem(SearchResultId("a"), "Quiet Harbor", providerName = "Source One", typeLabel = "Movie", qualityLabel = "4K"),
                    SearchResultItem(SearchResultId("b"), "Low Tide", providerName = "Source One", typeLabel = "Series"),
                    SearchResultItem(SearchResultId("c"), "Bare title", providerName = "Source One"),
                ),
            ),
        ),
    )

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
                CompositionLocalProvider(LocalCloudStreamTypography provides desktopTypography()) {
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
    fun `Search starts with the page bar, 52 tall with a hairline under it (KMP-98, S1)`() {
        // The field and Sources, in the one band every Mac page has.
        val scene = screen(state(SearchStatus.Showing(results), query = "blade runner"))
        scene.settle()
        scene.assertPageBar(WIDTH, atNanos = SETTLE_FRAMES * FRAME_NANOS)
        scene.close()
    }

    @Test
    fun `a partial failure is one band across the page, with an outlined retry that counts the sources (KMP-101, S3)`() {
        val actions = mutableListOf<SearchAction>()
        val partial = SearchStatus.Partial(labelled, answered = 9, total = 12, detail = "TIMEOUT · HTTP 503")
        val scene = screen(state(partial, query = "harbor"), onAction = { actions += it })
        scene.settle()
        val band = scene.tagged(DesktopFailureBannerDefaults.TestTag).single().boundsInRoot
        val margin = DesktopDimens.WindowMargin.value
        assertEquals(margin, band.left, "from the page's margin")
        assertEquals(WIDTH - 2 * margin, band.width, "to the other margin: the width of the page")
        assertEquals(DesktopFailureBannerDefaults.MinHeight.value, band.height)
        assertTrue(band.top > CHIP_Y, "under the type chips")
        scene.textNode("3 sources failed")
        scene.textNode("TIMEOUT · HTTP 503")

        val retry = scene.buttonLabelled("Retry 3 sources").boundsInRoot
        assertEquals(band.right - DesktopDimens.RelatedGap.value, retry.right, "at the band's far end")
        // It sits on the band, so the band's fill is what shows through: read in the band's padding, before the glyph.
        val onBand = (band.left + BAND_PROBE).toInt() to band.center.y.toInt()
        scene.assertOutlined(retry, SETTLE_FRAMES * FRAME_NANOS, page = onBand)
        scene.click(retry.center)
        assertEquals(listOf<SearchAction>(SearchAction.Retry), actions)
        scene.close()
    }

    @Test
    fun `one failed source says so in the singular`() {
        val scene = screen(state(SearchStatus.Partial(labelled, answered = 11, total = 12, detail = "HTTP 503"), query = "harbor"))
        scene.settle()
        scene.buttonLabelled("Retry 1 source")
        scene.close()
    }

    @Test
    fun `a card's second line is its type and quality, and nothing when it has neither (KMP-101, S2)`() {
        val scene = screen(state(SearchStatus.Showing(labelled), query = "harbor"))
        scene.settle()
        scene.textNode("Movie · 4K")
        scene.textNode("Series")
        scene.textNode("Bare title")
        assertTrue(scene.allNodes().none { "" in it.texts() }, "a card with neither draws no empty second line")
        scene.close()
    }

    @Test
    fun `an empty or failed search offers its way on with an outlined button, not a filled one (KMP-101, S6)`() {
        listOf(
            state(SearchStatus.NoResults(12), query = "kingfisher") to "Search everything",
            state(SearchStatus.Offline, query = "kingfisher") to "Try again",
            state(SearchStatus.Failed("HTTP 503"), query = "kingfisher") to "Try again",
        ).forEach { (state, label) ->
            val scene = screen(state)
            scene.settle()
            val button = scene.buttonLabelled(label).boundsInRoot
            scene.assertOutlined(button, SETTLE_FRAMES * FRAME_NANOS, page = SCRIM_X.toInt() to SCRIM_Y.toInt())
            scene.close()
        }
    }

    @Test
    fun `renders`() {
        if (System.getProperty("cloudstream.search.renders") == null) return
        val partial = SearchStatus.Partial(results, answered = 9, total = 12, detail = "TIMEOUT · HTTP 503")
        listOf(
            "S1-recent" to state(SearchStatus.Showing(recent)),
            "S2-results" to state(SearchStatus.Showing(results), query = "blade runner"),
            "S2-card-lines" to state(SearchStatus.Showing(labelled), query = "harbor"),
            "S5-offline" to state(SearchStatus.Offline, query = "kingfisher"),
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
        /** Inside the band's leading padding, which is 12. */
        const val BAND_PROBE = 4f
        const val FRAME_NANOS = 50_000_000L
        /** The middle of the 52 page bar (`KMP-98`). */
        const val TOOLBAR_Y = 26f
        const val SOURCES_X = 950f
        /** The chips' middle: the bar's 52, a 16 gap, and half a 32 chip. */
        const val CHIP_Y = 84f
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

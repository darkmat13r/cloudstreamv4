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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopTypography
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.LocalCloudStreamTypography
import com.lagradost.cloudstream4.feature.detail.DetailAction
import com.lagradost.cloudstream4.feature.detail.DetailCastMember
import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.DetailLoad
import com.lagradost.cloudstream4.feature.detail.DetailRecommendation
import com.lagradost.cloudstream4.feature.detail.DetailState
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import com.lagradost.cloudstream4.feature.detail.desktop.DetailCastDesktopDefaults
import com.lagradost.cloudstream4.feature.detail.desktop.DetailScreenDesktop
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat

/**
 * `DS-26`'s Cast and More like this under the Mac title page (`KMP-104`), through real pointer events in the scene, and
 * a PNG of each board's state when `cloudstream.detail.renders` names a directory.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class DetailCastScreenTest {

    private val people = listOf(
        DetailCastMember("Ines Marlowe", PHOTO, "Cdr. Rhea Voss"),
        DetailCastMember("Tobias Kerr", PHOTO, "Idris Pell"),
        DetailCastMember("Amara Osei", null, "Dr. Lune Hart"),
        DetailCastMember("Jonah Wilde", PHOTO, "Casimir"),
        DetailCastMember("Saoirse Doyle", PHOTO, "Mara Quill"),
        DetailCastMember("Ren Takeda", null, null, "Hana Sato", roleKind = "Main"),
        DetailCastMember("Lucía Ferrer", PHOTO, null, roleKind = "Supporting"),
        DetailCastMember("Malik Haddad", PHOTO, null),
        DetailCastMember("Freya Lindqvist", PHOTO, "Councillor Brandt"),
    )

    private fun recommendations(count: Int) = List(count) { index ->
        DetailRecommendation("rec-$index", "Title $index", if (index % 3 == 1) null else "$POSTER$index", 2000 + index, "TvSeries")
    }

    private val film = TitleDetail(
        title = "The Quiet Orbit",
        providerName = "Archive",
        typeName = "Movie",
        year = 2018,
        plot = null,
        posterUrl = null,
        playData = "film",
        episodes = emptyList(),
        cast = people,
        recommendations = recommendations(12),
    )

    private val series = film.copy(
        title = "The Long Meridian",
        typeName = "TvSeries",
        playData = null,
        episodes = List(24) { DetailEpisode("Episode ${it + 1}", "e$it", 1, it + 1) },
        cast = people + List(15) { DetailCastMember("Person $it", "$PHOTO_OF$it", "Role $it") },
        recommendations = recommendations(20),
    )

    private fun scene(width: Int, height: Int, content: @Composable () -> Unit) =
        ImageComposeScene(width, height, Density(1f)) {
            CloudStreamTheme {
                CompositionLocalProvider(LocalCloudStreamTypography provides desktopTypography()) {
                    Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) { content() }
                }
            }
        }

    /** The page, with every picture a flat fill and each one asked for counted in [asked]. */
    private fun page(
        detail: TitleDetail,
        onAction: (DetailAction) -> Unit = {},
        width: Int = WIDTH,
        height: Int = TALL,
        asked: MutableSet<String> = HashSet(),
    ) = scene(width, height) {
        DetailScreenDesktop(
            state = DetailState(DetailLoad.Loaded(detail)),
            onAction = onAction,
            onBack = {},
            artwork = {},
            origin = "Home",
            thumbnail = { url ->
                asked += url
                Box(Modifier.fillMaxSize().background(PICTURE))
            },
        )
    }

    private fun ImageComposeScene.settle() = repeat(SETTLE_FRAMES) { render(it * FRAME_NANOS) }

    private fun ImageComposeScene.click(at: Offset) {
        sendPointerEvent(PointerEventType.Move, at)
        sendPointerEvent(PointerEventType.Press, at)
        sendPointerEvent(PointerEventType.Release, at)
        settle()
    }

    /** Scrolls a step at a time until [words] are in the window, so nothing further down is composed on the way. */
    private fun ImageComposeScene.scrollUntil(words: String) {
        repeat(MAX_SCROLLS) {
            nodes(words).firstOrNull()?.let { if (it.boundsInRoot.bottom <= HEIGHT) return }
            sendPointerEvent(PointerEventType.Scroll, Offset(WIDTH / 2f, HEIGHT / 2f), scrollDelta = Offset(0f, 1f))
        }
        error("\"$words\" never came on screen")
    }

    private fun ImageComposeScene.save(name: String) {
        val directory = System.getProperty("cloudstream.detail.renders") ?: return
        settle()
        File(directory, "$name.png").writeBytes(checkNotNull(render(SETTLE_FRAMES * FRAME_NANOS).encodeToData(EncodedImageFormat.PNG)).bytes)
    }

    private fun SemanticsNode.all(): List<SemanticsNode> = listOf(this) + children.flatMap { it.all() }

    private fun ImageComposeScene.nodes(words: String): List<SemanticsNode> {
        settle()
        return semanticsOwners.flatMap { it.rootSemanticsNode.all() }.filter { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == words }
        }
    }

    private fun ImageComposeScene.has(words: String) = nodes(words).isNotEmpty()

    private fun ImageComposeScene.boundsOf(words: String) = nodes(words).single().boundsInRoot

    /** How many of [names] share the first one's line. */
    private fun ImageComposeScene.onFirstLine(names: List<String>): Int {
        val top = boundsOf(names.first()).top
        return names.count { abs(boundsOf(it).top - top) < 1f }
    }

    /** The box of the person whose words include [words]: the nearest tagged node above them. */
    private fun ImageComposeScene.personOf(words: String): SemanticsNode {
        var node: SemanticsNode? = nodes(words).single()
        while (node != null && node.config.getOrNull(SemanticsProperties.TestTag) != DetailCastDesktopDefaults.PersonTestTag) {
            node = node.parent
        }
        return checkNotNull(node) { "\"$words\" is not inside a person" }
    }

    /** The laid-out text of [words], as drawn. */
    private fun ImageComposeScene.layoutOf(words: String): TextLayoutResult {
        val results = ArrayList<TextLayoutResult>()
        nodes(words).single().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    /** How many pixels in [rows] across [columns] differ from the page's own ground there: ink. */
    private fun ImageComposeScene.inkIn(columns: IntRange, rows: IntRange): Int {
        settle()
        val bitmap = Bitmap.makeFromImage(render(SETTLE_FRAMES * FRAME_NANOS))
        val ground = bitmap.getColor(columns.last + GROUND_OFFSET, rows.first)
        fun channelsOf(color: Int) = listOf(color shr 16 and 0xFF, color shr 8 and 0xFF, color and 0xFF)
        val groundChannels = channelsOf(ground)
        var ink = 0
        for (y in rows) for (x in columns) {
            val channels = channelsOf(bitmap.getColor(x, y))
            if (channels.zip(groundChannels).any { (a, b) -> abs(a - b) > INK_THRESHOLD }) ink++
        }
        return ink
    }

    @Test
    fun `everyone in the cast shows, four a line in a 1280 window, with what they play (C1)`() {
        val scene = page(film)
        scene.save("detail-c1-cast")
        assertTrue(scene.has("Cast"))
        people.forEach { assertTrue(scene.has(it.name), "${it.name} is shown") }
        assertEquals(4, scene.onFirstLine(people.map { it.name }))
        assertTrue(scene.has("Cdr. Rhea Voss"))
        assertEquals(
            scene.boundsOf("Ines Marlowe").bottom,
            scene.boundsOf("Cdr. Rhea Voss").top,
            "the role sits right under the name",
        )
        scene.close()
    }

    @Test
    fun `a kind with no words reads in words, a voice actor goes under the role, and no role is no line (C3, C3b)`() {
        val scene = page(film)
        assertTrue(scene.has("Main"), "ActorRole.Main in words")
        assertTrue(scene.has("Supporting"))
        assertTrue(scene.has("Voiced by Hana Sato"))
        assertEquals(scene.boundsOf("Main").bottom, scene.boundsOf("Voiced by Hana Sato").top)
        assertTrue(scene.has("Malik Haddad"), "a person with neither words nor a kind is a name alone")
        scene.close()
        val unknown = page(film.copy(cast = listOf(DetailCastMember("Noor Aziz", null, null, roleKind = "Lead"))))
        assertTrue(unknown.has("Noor Aziz"))
        assertFalse(unknown.has("Lead"), "a kind the page has no words for is never shown as an identifier")
        unknown.close()
    }

    @Test
    fun `a person with no photo shows initials, and only those with one are asked for theirs (C3)`() {
        val asked = HashSet<String>()
        val scene = page(film, asked = asked)
        scene.settle()
        assertTrue(scene.has("AO"), "Amara Osei's initials")
        assertTrue(scene.has("RT"))
        assertEquals(setOf(PHOTO) + recommendations(12).take(5).mapNotNull { it.posterUrl }, asked)
        scene.close()
    }

    @Test
    fun `More like this opens on one line, Load more adds a line, and goes once all show (C1, C2)`() {
        val scene = page(film)
        val titles = recommendations(12).map { it.title }
        assertEquals(listOf(true, true, true, true, true, false), titles.take(6).map { scene.has(it) }, "five a line at 1280")
        assertEquals(5, scene.onFirstLine(titles.take(5)))
        scene.click(scene.boundsOf("Load more").center)
        scene.save("detail-c2-more")
        assertTrue(scene.has("Title 9") && !scene.has("Title 10"))
        scene.click(scene.boundsOf("Load more").center)
        assertTrue(scene.has("Title 11"))
        assertFalse(scene.has("Load more"), "everything shows, so there is nothing to load")
        assertTrue(scene.has("Series · 2001"), "the kind and the year under a card")
        scene.close()
    }

    @Test
    fun `a card opens its title`() {
        val actions = mutableListOf<DetailAction>()
        val scene = page(film, { actions += it })
        // A card reads as one control, its poster and words together, as Home's does.
        val card = scene.boundsOf("Title 2")
        scene.click(Offset(card.center.x, card.top + card.width * 3 / 4))
        assertEquals(listOf<DetailAction>(DetailAction.RecommendationActivated("rec-2")), actions, "the poster opens it")
        val next = scene.boundsOf("Title 3")
        scene.click(Offset(next.center.x, next.bottom - 4f))
        assertEquals(DetailAction.RecommendationActivated("rec-3"), actions.last(), "and so do its words")
        scene.close()
    }

    @Test
    fun `with no cast there is no Cast header, and with nothing at all the page ends after the episodes (C5a, C5b)`() {
        val noCast = page(series.copy(cast = emptyList()))
        noCast.save("detail-c5a-no-cast")
        assertFalse(noCast.has("Cast"))
        assertTrue(noCast.has("More like this"))
        noCast.close()
        val neither = page(series.copy(cast = emptyList(), recommendations = emptyList()))
        assertFalse(neither.has("Cast") || neither.has("More like this") || neither.has("Load more"))
        neither.close()
        val noMore = page(film.copy(recommendations = emptyList()))
        noMore.save("detail-c5b-film")
        assertTrue(noMore.has("Cast"))
        assertFalse(noMore.has("More like this"))
        noMore.close()
    }

    @Test
    fun `the minimum window has two people and three cards a line (C7)`() {
        val scene = page(film, width = MIN_PAGE_WIDTH, height = TALL)
        scene.save("detail-c7-minimum")
        assertEquals(2, scene.onFirstLine(people.map { it.name }))
        assertEquals(3, scene.onFirstLine(recommendations(12).take(3).map { it.title }))
        scene.close()
    }

    @Test
    fun `a line below the window is not asked for its pictures until it is scrolled to`() {
        val asked = HashSet<String>()
        val scene = page(series, height = HEIGHT, asked = asked)
        scene.settle()
        assertTrue(asked.isEmpty(), "24 episodes stand between the header and the cast: nothing is asked for yet")
        scene.scrollUntil("Ines Marlowe")
        assertTrue(PHOTO in asked, "the cast's first line, now on screen")
        assertFalse("${PHOTO_OF}14" in asked, "its last line, still below the window, is not asked for")
        assertFalse(asked.any { it.startsWith(POSTER) }, "More like this is still below the window: no poster asked for")
        scene.scrollUntil("Title 0")
        val posters = recommendations(20).take(5).mapNotNull { it.posterUrl }.toSet()
        assertTrue(asked.containsAll(posters), "the first line of More like this, now on screen")
        scene.close()
    }

    @Test
    fun `a person cut by an ellipsis shows the whole of it once the pointer rests (C4)`() {
        val long = DetailCastMember("Maximilian Alexander von Hohenberg-Castellane", null, "Archduke Leopold Friedrich of the Northern Reach")
        val scene = page(film.copy(cast = listOf(long) + people.take(3)))
        val whole = "${long.name} · ${long.role}"
        assertFalse(scene.has(whole))
        val name = scene.boundsOf(long.name)
        scene.sendPointerEvent(PointerEventType.Move, name.center)
        scene.settle()
        // The tooltip waits for the pointer to rest, on the real clock.
        Thread.sleep(TOOLTIP_WAIT)
        scene.settle()
        scene.save("detail-c4-long")
        assertTrue(scene.has(whole), "the tooltip holds every word")
        scene.sendPointerEvent(PointerEventType.Move, Offset(WIDTH - 10f, 10f))
        scene.settle()
        assertFalse(scene.has(whole), "and goes with the pointer")
        val short = page(film.copy(cast = people.take(1)))
        short.sendPointerEvent(PointerEventType.Move, short.boundsOf("Ines Marlowe").center)
        short.settle()
        Thread.sleep(TOOLTIP_WAIT)
        short.settle()
        assertFalse(short.has("Ines Marlowe · Cdr. Rhea Voss"), "nothing was cut, so there is no tooltip")
        short.close()
        scene.close()
    }

    private val voiced = DetailCastMember("Noor Aziz", null, "Navigator", VOICE_ACTOR)
    private val unvoiced = DetailCastMember("Tobias Kerr", null, "Idris Pell")

    /**
     * The descender check (the PM, 2026-11-10): with Manrope loaded, "Voiced by Pippa Quigley-Jory", whose g, j, p, q and y
     * all descend, lies inside its person, its line box whole, and ink is drawn below its baseline.
     */
    @Test
    fun `a voice line lies inside its person, and Pippa Quigley-Jory's descenders are drawn whole (C3b)`() {
        val scene = page(film.copy(cast = listOf(voiced, unvoiced)))
        val voice = scene.boundsOf(VOICE_LINE)
        val person = scene.personOf(VOICE_LINE).boundsInRoot
        val layout = scene.layoutOf(VOICE_LINE)
        val baseline = voice.top + layout.firstBaseline
        val lineBottom = voice.top + layout.getLineBottom(0)
        println(
            "KMP-104 voice line: person ${person.top}..${person.bottom} (${person.height}); voice box ${voice.top}..${voice.bottom} " +
                "(${voice.height}); baseline $baseline; line bottom $lineBottom; overflow ${layout.hasVisualOverflow}",
        )
        assertTrue(voice.top >= person.top && voice.bottom <= person.bottom, "the voice line $voice inside its person $person")
        assertFalse(layout.hasVisualOverflow, "nothing of the line is cut")
        assertTrue(lineBottom <= voice.bottom, "the line box, descent and all, fits the box it is given")
        val descenders = scene.inkIn(
            columns = voice.left.toInt() until voice.right.toInt(),
            rows = (baseline.toInt() + 1) until lineBottom.toInt(),
        )
        println("KMP-104 voice line: $descenders pixels of ink below the baseline")
        assertTrue(descenders >= MIN_DESCENDER_INK, "the descenders are drawn: $descenders pixels below the baseline")
        scene.close()
    }

    @Test
    fun `a line with one voiced person makes everyone on it as tall as the voiced one`() {
        val scene = page(film.copy(cast = listOf(voiced, unvoiced)))
        assertEquals(PERSON_WITH_VOICE, scene.personOf("Noor Aziz").boundsInRoot.height)
        assertEquals(PERSON_WITH_VOICE, scene.personOf("Tobias Kerr").boundsInRoot.height, "the unvoiced one takes the line's height")
        scene.close()
    }

    @Test
    fun `a line with no voice stays at the two-line height`() {
        val scene = page(film.copy(cast = listOf(unvoiced, DetailCastMember("Amara Osei", null, "Dr. Lune Hart"))))
        assertEquals(PERSON, scene.personOf("Tobias Kerr").boundsInRoot.height)
        assertEquals(PERSON, scene.personOf("Amara Osei").boundsInRoot.height)
        scene.close()
    }

    private companion object {
        /** The 1280 window's page beside the 220 sidebar, where DS-26 draws four people and five cards a line. */
        const val WIDTH = 1060
        const val HEIGHT = 772
        const val TALL = 2400

        /** The 800 window's page beside the 220 sidebar. */
        const val MIN_PAGE_WIDTH = 580
        const val SETTLE_FRAMES = 30
        const val FRAME_NANOS = 50_000_000L
        const val MAX_SCROLLS = 400
        const val TOOLTIP_WAIT = 1_000L
        const val PHOTO = "https://img.example/person.jpg"
        const val PHOTO_OF = "https://img.example/person-"
        const val POSTER = "https://img.example/poster-"
        val PICTURE = Color(0xFF3B4A5C)

        const val VOICE_ACTOR = "Pippa Quigley-Jory"
        const val VOICE_LINE = "Voiced by $VOICE_ACTOR"

        /** At density 1, dp are pixels: `DetailCastDesktopDefaults.PersonHeight` and `PersonHeightWithVoice`. */
        const val PERSON = 40f
        const val PERSON_WITH_VOICE = 56f

        /** Past this in any channel, a pixel is ink and not the page's ground. */
        const val INK_THRESHOLD = 24

        /** Where the ground is sampled: just past the line's right end, inside the person, where no word is drawn. */
        const val GROUND_OFFSET = 4

        /** Five descending letters at 12 sp leave far more than this below the baseline; a clipped line leaves none. */
        const val MIN_DESCENDER_INK = 20
    }
}

package com.lagradost.cloudstream4

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.detail.DetailAction
import com.lagradost.cloudstream4.feature.detail.DetailLoad
import com.lagradost.cloudstream4.feature.detail.DetailState
import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import com.lagradost.cloudstream4.feature.detail.desktop.DetailScreenDesktop
import com.lagradost.cloudstream4.feature.search.SearchAction
import com.lagradost.cloudstream4.feature.search.SearchContent
import com.lagradost.cloudstream4.feature.search.SearchResultGroup
import com.lagradost.cloudstream4.feature.search.SearchResultId
import com.lagradost.cloudstream4.feature.search.SearchResultItem
import com.lagradost.cloudstream4.feature.search.SearchState
import com.lagradost.cloudstream4.feature.search.SearchStatus
import com.lagradost.cloudstream4.feature.search.desktop.SearchScreenDesktop
import com.lagradost.cloudstream4.player.Hud
import com.lagradost.cloudstream4.player.PlayerStatus
import com.lagradost.cloudstream4.player.SeekTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the desktop screens do under a real pointer and keyboard, sent through the scene's own input.
 *
 * The screenshot suite cannot guard these screens (`DEC-10`), and none of this shows in a still anyway.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class DesktopInteractionTest {

    private fun scene(content: @Composable () -> Unit) =
        ImageComposeScene(WIDTH, HEIGHT, Density(1f)) { CloudStreamTheme { content() } }

    private fun ImageComposeScene.settle() = repeat(SETTLE_FRAMES) { render(it * FRAME_NANOS) }

    private fun ImageComposeScene.click(at: Offset) {
        sendPointerEvent(PointerEventType.Move, at)
        sendPointerEvent(PointerEventType.Press, at)
        sendPointerEvent(PointerEventType.Release, at)
        settle()
    }

    @Test
    fun `an empty field has nothing to press`() {
        val actions = mutableListOf<SearchAction>()
        val scene = scene {
            SearchScreenDesktop(SearchState(status = SearchStatus.Idle), { actions += it })
        }
        scene.settle()
        scene.click(Offset(WIDTH / 2f, HEIGHT / 2f))
        assertTrue(actions.isEmpty(), "the idle words are not a control: $actions")
        scene.close()
    }

    /**
     * Escape reaches the field's handler only while focus is inside the field, so this is also the test
     * that a search finding nothing does not move focus to its button mid-typing.
     */
    @Test
    fun `focus stays in the field when nothing is found, and escape empties it`() {
        val actions = mutableListOf<SearchAction>()
        var query by mutableStateOf("nasa")
        val scene = scene {
            SearchScreenDesktop(SearchState(query = query, status = SearchStatus.NoResults(1)), { actions += it })
        }
        scene.settle()
        assertTrue(scene.sendKeyEvent(escape()), "handled while there is text")
        assertEquals(listOf<SearchAction>(SearchAction.Cleared), actions)

        query = ""
        scene.settle()
        actions.clear()
        assertFalse(scene.sendKeyEvent(escape()), "passed on, so the window can go back")
        assertTrue(actions.isEmpty())
        scene.close()
    }

    @Test
    fun `a click on a card opens it`() {
        val actions = mutableListOf<SearchAction>()
        val item = SearchResultItem(SearchResultId("one"), "Title", providerName = "YouTube")
        val state = SearchState(
            query = "q",
            status = SearchStatus.Showing(SearchContent.Results(listOf(SearchResultGroup("YouTube", listOf(item))))),
        )
        val scene = scene {
            SearchScreenDesktop(state, { actions += it })
        }
        scene.settle()
        scene.click(Offset(CARD_X, CARD_Y))
        assertEquals(listOf<SearchAction>(SearchAction.ResultActivated(item.id)), actions)
        scene.close()
    }

    @Test
    fun `clicking outside the source panel dismisses it, and a click in the panel does not`() {
        val actions = mutableListOf<DetailAction>()
        val link = StreamLink("Source", "1080p", "u", null, null, null)
        val detail = TitleDetail("T", "P", null, null, null, null, "d", emptyList())
        val scene = scene {
            DetailScreenDesktop(
                DetailState(DetailLoad.Loaded(detail), DetailLoad.Loaded(listOf(link))),
                { actions += it },
                {},
                {},
            )
        }
        scene.settle()
        scene.click(Offset(WIDTH - 100f, FIRST_PANEL_ROW_Y))
        assertEquals(listOf<DetailAction>(DetailAction.LinkActivated(link)), actions)

        actions.clear()
        scene.click(Offset(100f, HEIGHT - 20f))
        assertEquals(listOf<DetailAction>(DetailAction.LinksDismissed), actions)
        scene.close()
    }

    /** A seek is a network request, so dragging must not send one per pixel. */
    @Test
    fun `scrubbing seeks once, on release, to where the pointer ended`() {
        val seeks = mutableListOf<Float>()
        val status = PlayerStatus(playing = true, buffering = false, positionMillis = 0, lengthMillis = 100_000)
        val scene = scene {
            SeekTrack(status, { seeks += it }, remember { Modifier.fillMaxWidth() })
        }
        scene.settle()
        val y = 16f
        scene.sendPointerEvent(PointerEventType.Move, Offset(200f, y))
        scene.sendPointerEvent(PointerEventType.Press, Offset(200f, y))
        scene.settle()
        scene.sendPointerEvent(PointerEventType.Move, Offset(500f, y))
        scene.settle()
        assertTrue(seeks.isEmpty(), "no seek while held")
        // Well above the 32dp track: a scrub keeps tracking wherever the pointer wanders while held.
        scene.sendPointerEvent(PointerEventType.Move, Offset(600f, HEIGHT - 10f))
        scene.sendPointerEvent(PointerEventType.Release, Offset(600f, HEIGHT - 10f))
        scene.settle()
        assertEquals(1, seeks.size)
        assertTrue(seeks.single() in 0.4f..0.8f, "${seeks.single()}")
        scene.close()
    }

    /**
     * The button leaves the composition as it is pressed, so focus inside it would go with it. Escape is
     * only heard from inside the field, so it proves focus never left.
     */
    @Test
    fun `the clear button empties the field and leaves focus in it`() {
        val actions = mutableListOf<SearchAction>()
        var query by mutableStateOf("nasa")
        val scene = scene {
            SearchScreenDesktop(SearchState(query = query, status = SearchStatus.NoResults(1)), { actions += it })
        }
        scene.settle()
        // From the right edge in, because the Sources button beside the field is as wide as its string. Its own
        // press (`KMP-56`) is passed over: the first control that clears is the field's.
        val x = generateSequence(WIDTH - 1f) { it - 4f }.takeWhile { it > 0f }.first { candidate ->
            actions.remove(SearchAction.SourcePanelOpened)
            scene.sendPointerEvent(PointerEventType.Move, Offset(candidate, FIELD_CENTRE_Y))
            scene.sendPointerEvent(PointerEventType.Press, Offset(candidate, FIELD_CENTRE_Y))
            scene.sendPointerEvent(PointerEventType.Release, Offset(candidate, FIELD_CENTRE_Y))
            scene.render(0)
            SearchAction.Cleared in actions
        }
        assertEquals(listOf<SearchAction>(SearchAction.Cleared), actions, "at x=$x")
        query = ""
        scene.settle()

        query = "again"
        scene.settle()
        actions.clear()
        assertTrue(scene.sendKeyEvent(escape()), "focus is still in the field")
        assertEquals(listOf<SearchAction>(SearchAction.Cleared), actions)
        scene.close()
    }

    /**
     * The clear button sits between the field and the Sources button (`KMP-56`), so Tab from the field lands on one
     * of the two: pressing what it landed on says which.
     */
    @Test
    fun `tab does not land on the clear button`() {
        val actions = mutableListOf<SearchAction>()
        val scene = scene {
            SearchScreenDesktop(SearchState(query = "nasa", status = SearchStatus.Loading), { actions += it })
        }
        scene.settle()
        scene.sendKeyEvent(KeyEvent(Key.Tab, KeyEventType.KeyDown))
        scene.sendKeyEvent(KeyEvent(Key.Tab, KeyEventType.KeyUp))
        scene.settle()
        scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown))
        scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyUp))
        scene.settle()
        assertEquals(listOf<SearchAction>(SearchAction.SourcePanelOpened), actions)
        scene.close()
    }

    @Test
    fun `the transport buttons skip, toggle and go back`() {
        val calls = mutableListOf<String>()
        val status = PlayerStatus(playing = true, buffering = false, positionMillis = 0, lengthMillis = 100_000)
        val scene = scene {
            Hud(
                caption = "T",
                status = status,
                onTogglePause = { calls += "toggle" },
                onSeekTo = { calls += "track" },
                onSeekBy = { calls += "by $it" },
                onBack = { calls += "back" },
            )
        }
        scene.settle()
        // A row 720 wide at most, centred, inset 16, of 36dp buttons 16 apart.
        val y = HEIGHT - 50f
        scene.click(Offset(174f, y))
        scene.click(Offset(226f, y))
        scene.click(Offset(278f, y))
        scene.click(Offset(42f, 42f))
        assertEquals(listOf("by -10000", "toggle", "by 10000", "back"), calls)
        scene.close()
    }

    private fun escape() = KeyEvent(Key.Escape, KeyEventType.KeyDown)

    private companion object {
        const val WIDTH = 1000
        const val HEIGHT = 600
        const val SETTLE_FRAMES = 30
        const val FRAME_NANOS = 50_000_000L
        /** Inside the first card: below the toolbar and section header. */
        const val CARD_X = 60f
        const val CARD_Y = 200f
        /** Inside the first row: below the panel's title row. */
        const val FIRST_PANEL_ROW_Y = 88f
        /** The middle of the 52 page bar the field sits in (`KMP-98`). */
        const val FIELD_CENTRE_Y = 26f
    }
}

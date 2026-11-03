package com.lagradost.cloudstream4.search

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import com.lagradost.cloudstream4.feature.search.SearchContent
import com.lagradost.cloudstream4.feature.search.SearchStatus
import com.lagradost.cloudstream4.feature.search.toUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The words have to be there the first time a state is drawn. When a ViewModel held them they could
 * arrive after it read them, and the idle screen had no headline, no body and a button with no label.
 *
 * Runs here rather than in `:feature:search` because this module is where the resources are packaged
 * for a real render on the JVM.
 */
class SearchStatusWordsTest {

    private fun render(status: SearchStatus): UiState<SearchContent> {
        var state: UiState<SearchContent>? = null
        val scene = ImageComposeScene(width = 100, height = 100, density = Density(1f)) {
            state = status.toUiState {}
        }
        scene.close()
        return checkNotNull(state)
    }

    private fun assertHasWords(prompt: StatePrompt) {
        assertTrue(prompt.headline.isNotBlank(), "headline")
        assertTrue(prompt.body.isNotBlank(), "body")
        assertTrue(prompt.action.label.isNotBlank(), "action label")
    }

    @Test
    fun everyStateThatTakesWordsHasThemOnItsFirstComposition() {
        assertHasWords((render(SearchStatus.Idle) as UiState.Empty).prompt)
        assertHasWords((render(SearchStatus.NoResults(3)) as UiState.Empty).prompt)
        assertHasWords((render(SearchStatus.Failed("x")) as UiState.Error).prompt)
        assertHasWords((render(SearchStatus.Offline) as UiState.Offline).prompt)
    }

    /**
     * Compose Resources substitutes only positional `%1$d`, and these are authored with a bare `%d`, so
     * this once rendered literally as "%d RESULTS" and needed a hand replace at the call site.
     */
    @Test
    fun numbersAreFilledIn() {
        val empty = render(SearchStatus.NoResults(3)) as UiState.Empty
        assertEquals("3 QUERIED · 3 ANSWERED · 0 FAILED · 0 RESULTS", empty.detail)
        assertTrue("3" in empty.prompt.body, empty.prompt.body)
        assertFalse("%" in empty.prompt.body, empty.prompt.body)

        val partial = render(SearchStatus.Partial(SearchContent.Results(emptyList()), 9, 12, "x"))
        val headline = (partial as UiState.Partial).notice.headline
        assertTrue("3" in headline, headline)
        assertFalse("%" in headline, headline)
    }
}

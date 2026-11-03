package com.lagradost.cloudstream4.home

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import com.lagradost.cloudstream4.feature.home.HomeRow
import com.lagradost.cloudstream4.feature.home.HomeStatus
import com.lagradost.cloudstream4.feature.home.toUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Runs here because this module is where the resources are packaged for a real render on the JVM. */
class HomeStatusWordsTest {

    private fun render(status: HomeStatus, name: String? = "YouTube"): UiState<List<HomeRow>> {
        var state: UiState<List<HomeRow>>? = null
        val scene = ImageComposeScene(width = 100, height = 100, density = Density(1f)) {
            state = status.toUiState(name) {}
        }
        scene.close()
        return checkNotNull(state)
    }

    private fun assertHasWords(prompt: StatePrompt) {
        assertTrue(prompt.headline.isNotBlank(), "headline")
        assertTrue(prompt.body.isNotBlank(), "body")
        assertTrue(prompt.action.label.isNotBlank(), "action label")
        assertFalse("%" in prompt.body, prompt.body)
    }

    @Test
    fun everyStateThatTakesWordsHasThemOnItsFirstComposition() {
        assertHasWords((render(HomeStatus.Empty) as UiState.Empty).prompt)
        assertHasWords((render(HomeStatus.NoSources(0)) as UiState.Empty).prompt)
        assertHasWords((render(HomeStatus.NoSources(2)) as UiState.Empty).prompt)
        assertHasWords((render(HomeStatus.Failed("x")) as UiState.Error).prompt)
        assertHasWords((render(HomeStatus.Offline) as UiState.Offline).prompt)
    }

    @Test
    fun aFailureNamesTheSourceAndKeepsTheReason() {
        val failed = render(HomeStatus.Failed("HTTP 503")) as UiState.Error
        assertTrue("YouTube" in failed.prompt.body, failed.prompt.body)
        assertEquals("HTTP 503", failed.detail)
    }

    /** "Nothing installed" and "installed, but only for search" have different ways out. */
    @Test
    fun theTwoNoSourceStatesSayDifferentThings() {
        val none = (render(HomeStatus.NoSources(0)) as UiState.Empty).prompt
        val searchOnly = (render(HomeStatus.NoSources(2)) as UiState.Empty).prompt
        assertNotEquals(none.headline, searchOnly.headline)
        assertNotEquals(none.action.label, searchOnly.action.label)
    }

    @Test
    fun aPartialPageCountsWhatFailedAndAPressInFlightSaysSo() {
        val partial = HomeStatus.Partial(emptyList(), listOf(1, 2), 6, "HTTP 503")
        val notice = (render(partial) as UiState.Partial).notice
        assertEquals("2 / 6 · HTTP 503", notice.detail)
        val retrying = (render(partial.copy(retrying = true)) as UiState.Partial).notice
        assertNotEquals(notice.retry.label, retrying.retry.label)
    }
}

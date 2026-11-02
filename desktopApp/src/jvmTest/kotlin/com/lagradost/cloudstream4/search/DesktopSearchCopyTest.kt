package com.lagradost.cloudstream4.search

import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The ViewModel's init reads `idle()` during the host's composition, so the words have to be there by
 * then. They once arrived in a SideEffect, which runs after it: the idle screen had no headline, no
 * body, and a focused button with no label.
 */
class DesktopSearchCopyTest {

    @Test
    fun idlePromptHasItsWordsDuringTheFirstComposition() {
        var idle: UiState.Empty? = null
        val scene = ImageComposeScene(width = 100, height = 100, density = Density(1f)) {
            val (_, prompts) = rememberDesktopSearchCopy()
            remember { idle = prompts.idle() }
        }
        try {
            val prompt = checkNotNull(idle).prompt
            assertTrue(prompt.headline.isNotBlank(), "headline")
            assertTrue(prompt.body.isNotBlank(), "body")
            assertTrue(prompt.action.label.isNotBlank(), "action label")
        } finally {
            scene.close()
        }
    }
}

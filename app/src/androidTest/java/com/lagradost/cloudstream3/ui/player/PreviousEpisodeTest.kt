package com.lagradost.cloudstream3.ui.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `BUG-42`: the player's previous episode played the next one, because `loadLinksPrev` stepped the index forward.
 *
 * On the device rather than the JVM because `loadLinks` launches on `viewModelScope`, which needs the main dispatcher.
 * The generator asks nobody for links: it answers at once with none.
 */
@RunWith(AndroidJUnit4::class)
class PreviousEpisodeTest {

    private class ThreeEpisodes : VideoGenerator<String>(listOf("one", "two", "three")) {
        override val hasCache = false
        override val canSkipLoading = false
        override fun getId(index: Int): Int? = index
        override suspend fun generateLinks(
            clearCache: Boolean,
            sourceTypes: Set<ExtractorLinkType>,
            callback: (Pair<ExtractorLink?, ExtractorUri?>) -> Unit,
            subtitleCallback: (SubtitleData) -> Unit,
            offset: Int,
            isCasting: Boolean,
        ): Boolean = false
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test
    fun previousLandsOnTheEpisodeBefore() {
        var index = -1
        onMain {
            val viewModel = PlayerGeneratorViewModel()
            viewModel.generator = ThreeEpisodes()
            viewModel.episodeIndex = 1
            viewModel.loadLinksPrev()
            index = viewModel.episodeIndex
        }
        assertEquals(0, index)
    }

    @Test
    fun previousFromTheFirstEpisodeStaysOnIt() {
        var index = -1
        onMain {
            val viewModel = PlayerGeneratorViewModel()
            viewModel.generator = ThreeEpisodes()
            viewModel.episodeIndex = 0
            viewModel.loadLinksPrev()
            index = viewModel.episodeIndex
        }
        assertEquals(0, index)
    }

    @Test
    fun nextStillLandsOnTheEpisodeAfter() {
        var index = -1
        onMain {
            val viewModel = PlayerGeneratorViewModel()
            viewModel.generator = ThreeEpisodes()
            viewModel.episodeIndex = 1
            viewModel.loadLinksNext()
            index = viewModel.episodeIndex
        }
        assertEquals(2, index)
    }
}

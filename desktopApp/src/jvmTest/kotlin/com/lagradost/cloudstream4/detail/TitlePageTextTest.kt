package com.lagradost.cloudstream4.detail

import com.lagradost.cloudstream4.feature.extensions.sandbox.RemoteDetails
import com.lagradost.cloudstream4.feature.extensions.sandbox.RemoteEpisode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BUG-93 (audit A-3): a plugin's title, plot and episode names reach the Mac's title page without the characters that
 * hide or move text, as on every other page. Made-up titles only.
 */
class TitlePageTextTest {
    private fun page(name: String, plot: String? = null, episode: String? = null) = RemoteDetails(
        name, "u", "TvSeries", plot, null, 2024, null,
        listOfNotNull(episode?.let { RemoteEpisode(it, "e1", 1, 1) }),
    ).toTitleDetail("Provider")

    private val hidden = listOf("\u202E", "\u202D", "\u2066", "\u2067", "\u2068", "\u2069", "\u200B", "\u200F", "\uFEFF")

    @Test
    fun `a title that would read reversed reads as written`() {
        assertEquals("Safe Titleexe.4pm", page("Safe Title\u202Eexe.4pm").title)
    }

    @Test
    fun `no character that hides or moves text survives in the title, plot or an episode's name`() {
        hidden.forEach { c ->
            val shown = page("Tide${c}Pool", plot = "One${c}two", episode = "Pilot${c}one")
            assertEquals("TidePool", shown.title, "U+%04X in the title".format(c.codePointAt(0)))
            assertEquals("Onetwo", shown.plot, "U+%04X in the plot".format(c.codePointAt(0)))
            assertEquals("Pilotone", shown.episodes.single().name, "U+%04X in an episode".format(c.codePointAt(0)))
        }
        // A tag character, outside the basic plane: format too, and a cut cannot leave half of it.
        assertEquals("TidePool", page("Tide\uDB40\uDC41Pool").title)
    }

    @Test
    fun `a plot stays one line of plain text, as before`() {
        assertEquals("First line. Second line.", page("Tide", plot = "First line.\u202E<br>Second line.").plot)
    }

    @Test
    fun `a title past its length ends in an ellipsis`() {
        val title = page("A".repeat(400)).title
        assertTrue(title.length == 301 && title.endsWith("…"), "${title.length}")
    }
}

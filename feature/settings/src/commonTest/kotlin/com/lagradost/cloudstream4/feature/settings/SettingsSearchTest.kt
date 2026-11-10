package com.lagradost.cloudstream4.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsSearchTest {
    /** The English words, as the window resolves them, plus a made-up folder name. */
    private val words: (SettingsRow) -> List<String> = { row ->
        when (row) {
            SettingsRow.PosterSize -> listOf("Poster size")
            SettingsRow.RandomButton -> listOf("Random Button")
            SettingsRow.PlayNextEpisode -> listOf("Play the next episode", "Counts down 10 s at the end of an episode.")
            SettingsRow.SeekAmount -> listOf("Seek amount")
            SettingsRow.DownloadFolder -> listOf("Download folder", "Harbor Films")
            SettingsRow.DownloadsAtOnce -> listOf("Downloads at once", "How many titles download together.")
        }
    }

    /** Made-up pane names that no row's words contain, so a match on one can only be the name. */
    private val title: (SettingsPane) -> String = { pane ->
        when (pane) {
            SettingsPane.Appearance -> "Looks"
            SettingsPane.Playback -> "Watching"
            SettingsPane.Downloads -> "Saved Titles"
            SettingsPane.Extensions -> "Add-ons"
        }
    }

    private fun panes(query: String) = SettingsSearch.panes(query, words, title)

    @Test
    fun anEmptyQueryKeepsEveryPane() {
        assertEquals(SettingsPane.entries, panes(""))
        assertEquals(SettingsPane.entries, panes("   "))
        assertTrue(SettingsSearch.rows(SettingsPane.Downloads, "", words).isEmpty())
    }

    @Test
    fun aQueryKeepsOnlyThePanesHoldingAMatchingRowIgnoringCase() {
        assertEquals(listOf(SettingsPane.Playback), panes("EPISODE"))
        assertEquals(listOf(SettingsPane.Downloads), panes("AT ONCE"))
        assertEquals(listOf(SettingsPane.Downloads), panes("download"))
        assertEquals(listOf(SettingsPane.Appearance), panes("random"))
        assertEquals(listOf(SettingsPane.Appearance), panes("POSTER"))
        assertEquals(setOf(SettingsRow.PosterSize), SettingsSearch.rows(SettingsPane.Appearance, "poster", words))
        assertEquals(SettingsPane.entries, panes("o"))
    }

    @Test
    fun aPaneNameKeepsItsPaneAndMarksNoRow() {
        assertEquals(listOf(SettingsPane.Downloads), panes("saved TITLES"))
        assertTrue(SettingsSearch.rows(SettingsPane.Downloads, "saved titles", words).isEmpty())
    }

    @Test
    fun theMatchingRowsAreMarkedAndTheLineUnderATitleCounts() {
        assertEquals(setOf(SettingsRow.DownloadsAtOnce), SettingsSearch.rows(SettingsPane.Downloads, "together", words))
        assertEquals(setOf(SettingsRow.DownloadFolder), SettingsSearch.rows(SettingsPane.Downloads, "harbor", words))
    }

    @Test
    fun seekFindsPlaybackAndMarksSeekAmount() {
        assertEquals(listOf(SettingsPane.Playback), panes("seek"))
        assertEquals(setOf(SettingsRow.SeekAmount), SettingsSearch.rows(SettingsPane.Playback, "SEEK", words))
    }

    @Test
    fun aQueryForARowThatIsNotBuiltFindsNothing() {
        // Casting is not a Mac setting, and the title pages have no row yet: search only finds what exists (KMP-90).
        assertTrue(panes("cast").isEmpty())
        assertTrue(panes("trailer").isEmpty())
    }
}

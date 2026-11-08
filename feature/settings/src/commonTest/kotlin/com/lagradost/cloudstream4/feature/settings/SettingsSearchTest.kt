package com.lagradost.cloudstream4.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsSearchTest {
    /** The English words, as the window resolves them, plus a made-up folder name. */
    private val words: (SettingsRow) -> List<String> = { row ->
        when (row) {
            SettingsRow.DownloadFolder -> listOf("Download folder", "Harbor Films")
            SettingsRow.DownloadsAtOnce -> listOf("Downloads at once", "How many titles download together.")
        }
    }

    /** A made-up pane name that no row's words contain, so a match on it can only be the name. */
    private val title: (SettingsPane) -> String = { "Saved Titles" }

    private fun panes(query: String) = SettingsSearch.panes(query, words, title)

    @Test
    fun anEmptyQueryKeepsEveryPane() {
        assertEquals(SettingsPane.entries, panes(""))
        assertEquals(SettingsPane.entries, panes("   "))
        assertTrue(SettingsSearch.rows(SettingsPane.Downloads, "", words).isEmpty())
    }

    @Test
    fun aQueryKeepsOnlyThePanesHoldingAMatchingRowIgnoringCase() {
        assertEquals(listOf(SettingsPane.Downloads), panes("AT ONCE"))
        assertEquals(listOf(SettingsPane.Downloads), panes("download"))
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
    fun aQueryForARowThatIsNotBuiltFindsNothing() {
        // Casting is not a Mac setting, and Appearance and Playback have no row yet: search only finds what exists
        // (KMP-90).
        assertTrue(panes("cast").isEmpty())
        assertTrue(panes("poster").isEmpty())
        assertTrue(panes("episode").isEmpty())
    }
}

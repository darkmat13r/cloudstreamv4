package com.lagradost.cloudstream4.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsSearchTest {
    /** The English words, as the window resolves them, plus a made-up folder name. */
    private val words: (SettingsRow) -> List<String> = { row ->
        when (row) {
            SettingsRow.PlayNextEpisode -> listOf("Play the next episode", "Counts down 10 s at the end of an episode.")
            SettingsRow.DownloadFolder -> listOf("Download folder", "Harbor Films")
            SettingsRow.DownloadsAtOnce -> listOf("Downloads at once", "How many titles download together.")
        }
    }

    @Test
    fun anEmptyQueryKeepsEveryPane() {
        assertEquals(SettingsPane.entries, SettingsSearch.panes("", words))
        assertEquals(SettingsPane.entries, SettingsSearch.panes("   ", words))
        assertTrue(SettingsSearch.rows(SettingsPane.Downloads, "", words).isEmpty())
    }

    @Test
    fun aQueryKeepsOnlyThePanesHoldingAMatchingRowIgnoringCase() {
        assertEquals(listOf(SettingsPane.Playback), SettingsSearch.panes("EPISODE", words))
        assertEquals(listOf(SettingsPane.Downloads), SettingsSearch.panes("at once", words))
        assertEquals(listOf(SettingsPane.Downloads), SettingsSearch.panes("download", words))
        assertEquals(SettingsPane.entries, SettingsSearch.panes("o", words))
    }

    @Test
    fun theMatchingRowsAreMarkedAndTheLineUnderATitleCounts() {
        assertEquals(setOf(SettingsRow.DownloadsAtOnce), SettingsSearch.rows(SettingsPane.Downloads, "together", words))
        assertEquals(setOf(SettingsRow.DownloadFolder), SettingsSearch.rows(SettingsPane.Downloads, "harbor", words))
    }

    @Test
    fun aQueryForARowThatIsNotBuiltFindsNothing() {
        // Casting is not a Mac setting, and Appearance has no row yet: search only finds what exists (KMP-90).
        assertTrue(SettingsSearch.panes("cast", words).isEmpty())
        assertTrue(SettingsSearch.panes("poster", words).isEmpty())
    }
}

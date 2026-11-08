package com.lagradost.cloudstream4.feature.detail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Board 7i's season chips, as [seasonGroups] derives them. Pure, so none of this needs a device. */
class DetailSeasonGroupsTest {

    private fun episode(season: Int?, number: Int) =
        DetailEpisode(name = "E$number", data = "s$season-e$number", season = season, episode = number)

    @Test
    fun `numbered seasons come first and ascend`() {
        val groups = listOf(episode(2, 1), episode(1, 1), episode(3, 1)).seasonGroups()
        assertEquals(listOf(1, 2, 3), groups.map { it.season })
    }

    @Test
    fun `specials goes last among the numbers, not first`() {
        // The owner's ruling. Season 0 would sort first as a number; a specials season is not
        // season zero of the story, so it is drawn after the seasons it belongs beside.
        val groups = listOf(episode(SPECIALS_SEASON, 1), episode(1, 1), episode(2, 1)).seasonGroups()
        assertEquals(listOf(1, 2, SPECIALS_SEASON), groups.map { it.season })
    }

    @Test
    fun `other goes after specials, and holds the episodes with no season`() {
        val groups = listOf(
            episode(null, 1),
            episode(SPECIALS_SEASON, 1),
            episode(1, 1),
        ).seasonGroups()
        assertEquals(listOf(1, SPECIALS_SEASON, null), groups.map { it.season })
        assertEquals(listOf("snull-e1"), groups.last().episodes.map { it.data })
    }

    @Test
    fun `no episode is hidden - every episode lands in exactly one group`() {
        // The whole point of the Other chip, so it is asserted rather than assumed.
        val episodes = listOf(
            episode(1, 1), episode(1, 2), episode(2, 1),
            episode(SPECIALS_SEASON, 1), episode(null, 1), episode(null, 2),
        )
        val grouped = episodes.seasonGroups().flatMap { it.episodes }
        assertEquals(episodes.size, grouped.size)
        assertEquals(episodes.toSet(), grouped.toSet())
    }

    @Test
    fun `a title whose provider numbered nothing is one Other group`() {
        val groups = listOf(episode(null, 1), episode(null, 2)).seasonGroups()
        assertEquals(listOf(null), groups.map { it.season })
        assertFalse(groups.chipsVisible(), "one group is a label, not a selector")
    }

    @Test
    fun `chips need two groups`() {
        assertFalse(listOf(episode(1, 1), episode(1, 2)).seasonGroups().chipsVisible())
        assertTrue(listOf(episode(1, 1), episode(2, 1)).seasonGroups().chipsVisible())
        // Other counts as a group: one season plus unnumbered episodes is a real choice.
        assertTrue(listOf(episode(1, 1), episode(null, 1)).seasonGroups().chipsVisible())
    }

    @Test
    fun `no episodes is no groups`() {
        assertEquals(emptyList(), emptyList<DetailEpisode>().seasonGroups())
        assertFalse(emptyList<DetailEpisode>().seasonGroups().chipsVisible())
    }

    @Test
    fun `a film has no groups, and no last index to clamp against`() {
        // A film's `episodes` is empty, so this returns no groups and `lastIndex` is -1. The screen
        // used to clamp the chosen chip with `coerceIn(0, seasons.lastIndex)`, which throws on an
        // empty range and crashed every film's page. Pinned here because the screen's own guard is
        // one `if` that a later edit could drop without any jvm test noticing.
        val groups = emptyList<DetailEpisode>().seasonGroups()
        assertEquals(emptyList(), groups)
        assertEquals(-1, groups.lastIndex)
        assertFalse(groups.chipsVisible())
    }

    @Test
    fun `episode order inside a group is the order it arrived in`() {
        // `AndroidDetailSource` has already sorted by season then episode; regrouping must not reorder.
        val groups = listOf(episode(1, 1), episode(2, 1), episode(1, 2)).seasonGroups()
        assertEquals(listOf(1, 2), groups.first().episodes.map { it.episode })
    }

    @Test
    fun `groups disagree with TitleDetail seasons exactly where a season is null`() {
        // `toSeasons()` drops null-season episodes, which is why the chips are not built from it.
        // This pins the disagreement rather than leaving it as a claim in a KDoc.
        val episodes = listOf(episode(1, 1), episode(null, 1))
        val groups = episodes.seasonGroups()
        assertEquals(2, groups.size)
        assertEquals(1, groups.count { it.season != null })
    }
}

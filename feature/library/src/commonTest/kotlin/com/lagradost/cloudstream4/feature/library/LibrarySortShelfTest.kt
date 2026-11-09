package com.lagradost.cloudstream4.feature.library

import kotlin.test.Test
import kotlin.test.assertEquals

/** `KMP-50`: the four criteria, each both ways, over a bucket's titles and a service's. Pure, so it reaches iOS. */
class LibrarySortShelfTest {

    private fun title(name: String, keptAt: Long, year: Int? = null, rating: Int? = null) = LibraryTitle(
        id = LibraryTitleId("Prov\u0000/$name"),
        title = name,
        bucket = LibraryBucket.Watching,
        keptAt = keptAt,
        releaseYear = year,
        ratingValue = rating,
    )

    private val arrival = title("Arrival", keptAt = 3, year = 2016, rating = 80)
    private val tide = title("tide", keptAt = 5, year = 2021)
    private val brazil = title("Brazil", keptAt = 1, rating = 95)
    private val alien = title("Alien", keptAt = 4, year = 1979, rating = 80)
    private val titles = listOf(arrival, tide, brazil, alien)

    private fun order(sort: LibrarySort) =
        LibraryShelf.cards(LibraryTab.Bucket(LibraryBucket.Watching), titles, emptyList(), sort = sort).map { it.title }

    private fun sort(criterion: LibrarySortCriterion, descending: Boolean) = LibrarySort(criterion, descending)

    @Test
    fun `the default is last updated, newest first, the order a list had before there was a sort`() {
        assertEquals(LibrarySort(LibrarySortCriterion.Updated, descending = true), LibrarySort.Default)
        assertEquals(listOf("tide", "Alien", "Arrival", "Brazil"), order(LibrarySort.Default))
        assertEquals(
            order(LibrarySort.Default),
            LibraryShelf.cards(LibraryTab.Bucket(LibraryBucket.Watching), titles, emptyList()).map { it.title },
        )
    }

    @Test
    fun `last updated, oldest first`() {
        assertEquals(listOf("Brazil", "Arrival", "Alien", "tide"), order(sort(LibrarySortCriterion.Updated, false)))
    }

    @Test
    fun `a title, A to Z and Z to A, ignoring case`() {
        assertEquals(listOf("Alien", "Arrival", "Brazil", "tide"), order(sort(LibrarySortCriterion.Title, false)))
        assertEquals(listOf("tide", "Brazil", "Arrival", "Alien"), order(sort(LibrarySortCriterion.Title, true)))
    }

    @Test
    fun `release, either way round, with a title of no known year last both times`() {
        assertEquals(listOf("tide", "Arrival", "Alien", "Brazil"), order(sort(LibrarySortCriterion.Released, true)))
        assertEquals(listOf("Alien", "Arrival", "tide", "Brazil"), order(sort(LibrarySortCriterion.Released, false)))
    }

    @Test
    fun `rating, either way round, unrated last both times, and a tie keeps newest kept first`() {
        // Arrival and Alien are both 80; Alien was kept later, so it comes first in both directions.
        assertEquals(listOf("Brazil", "Alien", "Arrival", "tide"), order(sort(LibrarySortCriterion.Rating, true)))
        assertEquals(listOf("Alien", "Arrival", "Brazil", "tide"), order(sort(LibrarySortCriterion.Rating, false)))
    }

    @Test
    fun `a service's list sorts the same way`() {
        val list = SyncedList("watching", "Watching", titles)
        assertEquals(
            listOf("Alien", "Arrival", "Brazil", "tide"),
            LibraryShelf.syncedCards(list, sort = sort(LibrarySortCriterion.Title, false)).map { it.title },
        )
    }

    @Test
    fun `continue watching is always newest watched first, whatever the sort`() {
        val positions = listOf(
            WatchPosition(arrival.id, null, 60_000L, 600_000L, watchedAt = 9),
            WatchPosition(alien.id, null, 60_000L, 600_000L, watchedAt = 2),
        )
        val cards = LibraryShelf.cards(
            LibraryTab.ContinueWatching, titles, positions, sort = sort(LibrarySortCriterion.Title, true),
        )
        assertEquals(listOf("Arrival", "Alien"), cards.map { it.title })
    }

    @Test
    fun `a choice the source cannot sort by falls back to the default, and one it can is kept`() {
        val local = LibrarySource(LibrarySourceId("Local"), "Local", local = true, sorts = setOf(
            LibrarySortCriterion.Updated, LibrarySortCriterion.Title, LibrarySortCriterion.Released,
        ))
        val rating = sort(LibrarySortCriterion.Rating, true)
        assertEquals(LibrarySort.Default, rating.appliedTo(local))
        assertEquals(sort(LibrarySortCriterion.Title, true), sort(LibrarySortCriterion.Title, true).appliedTo(local))
        assertEquals(rating, rating.appliedTo(null), "no source, nothing to fall back from")
    }

    @Test
    fun `each criterion is first offered A to Z for a name and newest or highest first for the rest`() {
        assertEquals(false, LibrarySort.of(LibrarySortCriterion.Title).descending)
        listOf(LibrarySortCriterion.Updated, LibrarySortCriterion.Released, LibrarySortCriterion.Rating).forEach {
            assertEquals(true, LibrarySort.of(it).descending, it.name)
        }
        assertEquals(sort(LibrarySortCriterion.Title, true), LibrarySort.of(LibrarySortCriterion.Title).flipped())
    }
}

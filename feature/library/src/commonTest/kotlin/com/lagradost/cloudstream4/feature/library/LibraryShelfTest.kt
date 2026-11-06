package com.lagradost.cloudstream4.feature.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure Kotlin, so this is the suite that also runs on iOS. */
class LibraryShelfTest {

    private val dune = LibraryTitleId("Prov\u0000/dune")
    private val arrival = LibraryTitleId("Prov\u0000/arrival")

    private fun title(id: LibraryTitleId, bucket: LibraryBucket?, keptAt: Long = 0L) =
        LibraryTitle(id = id, title = id.value, bucket = bucket, keptAt = keptAt, providerName = "Prov")

    private fun position(
        id: LibraryTitleId,
        positionMillis: Long = 60_000L,
        durationMillis: Long = 600_000L,
        watchedAt: Long = 0L,
        episodeData: String? = null,
    ) = WatchPosition(id, episodeData, positionMillis, durationMillis, watchedAt)

    @Test
    fun `a fraction needs a duration — an unknown one is zero rather than infinite`() {
        assertEquals(0f, position(dune, positionMillis = 5_000L, durationMillis = 0L).fraction)
        assertEquals(0f, position(dune, positionMillis = 5_000L, durationMillis = -1L).fraction)
        assertEquals(0.1f, position(dune, positionMillis = 60_000L, durationMillis = 600_000L).fraction)
    }

    @Test
    fun `a position past the end cannot report more than all of it`() {
        assertEquals(1f, position(dune, positionMillis = 900_000L, durationMillis = 600_000L).fraction)
    }

    @Test
    fun `the first half minute is not something to resume`() {
        assertTrue(position(dune, positionMillis = 30_000L).resumable)
        assertTrue(!position(dune, positionMillis = 29_999L).resumable)
    }

    @Test
    fun `a finished title leaves continue watching but a nearly finished one stays`() {
        val nearly = position(dune, positionMillis = 560_000L, durationMillis = 600_000L)
        val done = position(dune, positionMillis = 580_000L, durationMillis = 600_000L)
        assertTrue(nearly.resumable)
        assertTrue(!done.resumable)
        assertTrue(done.finished)
    }

    @Test
    fun `a duration the player never reported can never be resumed however far in it got`() {
        assertTrue(!position(dune, positionMillis = 5_000_000L, durationMillis = 0L).resumable)
    }

    @Test
    fun `one card per title — from its newest episode`() {
        val positions = listOf(
            position(dune, watchedAt = 10L, episodeData = "e1"),
            position(dune, watchedAt = 30L, episodeData = "e3"),
            position(dune, watchedAt = 20L, episodeData = "e2"),
        )
        val cards = LibraryShelf.cards(LibraryTab.ContinueWatching, listOf(title(dune, null)), positions)
        assertEquals(1, cards.size)
        assertEquals("e3", cards.single().resume?.episodeData)
    }

    @Test
    fun `continue watching is newest first`() {
        val titles = listOf(title(dune, null), title(arrival, null))
        val positions = listOf(position(dune, watchedAt = 10L), position(arrival, watchedAt = 20L))
        val cards = LibraryShelf.cards(LibraryTab.ContinueWatching, titles, positions)
        assertEquals(listOf(arrival, dune), cards.map { it.id })
    }

    @Test
    fun `a position whose title the store does not know is dropped`() {
        val cards = LibraryShelf.cards(LibraryTab.ContinueWatching, emptyList(), listOf(position(dune)))
        assertEquals(emptyList(), cards)
    }

    @Test
    fun `continue watching is capped`() {
        val many = (1..LibraryShelf.MAX_CONTINUE + 10).map { LibraryTitleId("Prov\u0000/$it") }
        val titles = many.map { title(it, null) }
        val positions = many.mapIndexed { index, id -> position(id, watchedAt = index.toLong()) }
        val cards = LibraryShelf.cards(LibraryTab.ContinueWatching, titles, positions)
        assertEquals(LibraryShelf.MAX_CONTINUE, cards.size)
        // The cap keeps the newest, not whatever the file happened to list first.
        assertEquals(many.last(), cards.first().id)
    }

    @Test
    fun `a bucket holds only its own titles — newest kept first`() {
        val titles = listOf(
            title(dune, LibraryBucket.Watching, keptAt = 10L),
            title(arrival, LibraryBucket.Watching, keptAt = 20L),
            title(LibraryTitleId("Prov\u0000/other"), LibraryBucket.Completed, keptAt = 30L),
        )
        val cards = LibraryShelf.cards(LibraryTab.Bucket(LibraryBucket.Watching), titles, emptyList())
        assertEquals(listOf(arrival, dune), cards.map { it.id })
    }

    @Test
    fun `a bucket card shows how far through it is even when it is finished`() {
        val titles = listOf(title(dune, LibraryBucket.Watching))
        val done = position(dune, positionMillis = 600_000L, durationMillis = 600_000L)
        val card = LibraryShelf.cards(LibraryTab.Bucket(LibraryBucket.Watching), titles, listOf(done)).single()
        assertEquals(1f, card.progress)
        // Finished, so there is nowhere to come back to — the bar says watched, the card offers nothing.
        assertNull(card.resume)
    }

    @Test
    fun `no progress is no bar rather than a bar of nothing`() {
        val titles = listOf(title(dune, LibraryBucket.PlanToWatch))
        val cards = LibraryShelf.cards(LibraryTab.Bucket(LibraryBucket.PlanToWatch), titles, emptyList())
        assertNull(cards.single().progress)
        val zero = position(dune, positionMillis = 0L)
        val withZero = LibraryShelf.cards(LibraryTab.Bucket(LibraryBucket.PlanToWatch), titles, listOf(zero))
        assertNull(withZero.single().progress)
    }

    @Test
    fun `a row in no list and with no position is forgettable — one with either is not`() {
        val titles = listOf(
            title(dune, null),
            title(arrival, LibraryBucket.Dropped),
        )
        assertEquals(listOf(dune), LibraryShelf.forgettable(titles, emptyList()))
        assertEquals(emptyList(), LibraryShelf.forgettable(titles, listOf(position(dune))))
    }

    @Test
    fun `every bucket has a tab — continue watching comes first`() {
        assertEquals(LibraryTab.ContinueWatching, LibraryTab.all.first())
        assertEquals(LibraryBucket.entries.size + 1, LibraryTab.all.size)
        assertEquals(
            LibraryBucket.entries.toList(),
            LibraryTab.all.filterIsInstance<LibraryTab.Bucket>().map { it.bucket },
        )
    }

    @Test
    fun `a filter ignores case and blank keeps everything`() {
        val titles = listOf(title(dune, LibraryBucket.Watching), title(arrival, LibraryBucket.Watching))
        val tab = LibraryTab.Bucket(LibraryBucket.Watching)
        assertEquals(listOf(dune), LibraryShelf.cards(tab, titles, emptyList(), "/DUNE").map { it.id })
        assertEquals(2, LibraryShelf.cards(tab, titles, emptyList(), "  ").size)
    }

    @Test
    fun `an id in two lists appears on each with its own count`() {
        val titles = listOf(title(dune, LibraryBucket.Watching), title(dune, LibraryBucket.Favourites))
        assertEquals(mapOf(LibraryBucket.Watching to 1, LibraryBucket.Favourites to 1), LibraryShelf.counts(titles))
        assertEquals(1, LibraryShelf.totalTitles(titles))
        assertEquals(1, LibraryShelf.cards(LibraryTab.Bucket(LibraryBucket.Favourites), titles, emptyList()).size)
    }

    @Test
    fun `a card carries the host's type and poster headers`() {
        val kept = title(dune, LibraryBucket.Watching).copy(typeLabel = "MOVIE", posterHeaders = mapOf("a" to "b"))
        val card = LibraryShelf.cards(LibraryTab.Bucket(LibraryBucket.Watching), listOf(kept), emptyList()).single()
        assertEquals("MOVIE", card.typeLabel)
        assertEquals(mapOf("a" to "b"), card.posterHeaders)
    }

    private fun synced(id: LibraryTitleId, keptAt: Long, watched: Int? = null, total: Int? = null) =
        LibraryTitle(id = id, title = id.value, bucket = null, keptAt = keptAt, episodesWatched = watched, episodesTotal = total)

    @Test
    fun `a service's list is newest kept first, one card per title`() {
        val list = SyncedList("w", "Watching", listOf(synced(dune, 1), synced(arrival, 3), synced(dune, 1)))
        assertEquals(listOf(arrival, dune), LibraryShelf.syncedCards(list).map { it.id })
    }

    @Test
    fun `a service's bar is episodes seen over episodes in all, and none where either is missing`() {
        val list = SyncedList(
            "w",
            "Watching",
            listOf(synced(dune, 2, watched = 3, total = 12), synced(arrival, 1, watched = 0, total = 12)),
        )
        val cards = LibraryShelf.syncedCards(list).associateBy { it.id }
        assertEquals(0.25f, cards.getValue(dune).progress)
        assertNull(cards.getValue(arrival).progress, "nothing seen is no bar")
        val unknown = SyncedList("w", "Watching", listOf(synced(dune, 1, watched = 3, total = 0)))
        val card = LibraryShelf.syncedCards(unknown).single()
        assertNull(card.progress)
        assertNull(card.episodesTotal, "a total of 0 is not known")
        assertEquals(3, card.episodesWatched)
    }

    @Test
    fun `a service's list narrows by its filter, and a store tab never shows a service's titles`() {
        val list = SyncedList("w", "Watching", listOf(synced(dune, 1), synced(arrival, 2)))
        assertEquals(listOf(arrival), LibraryShelf.syncedCards(list, "ARRIV").map { it.id })
        assertTrue(LibraryShelf.cards(LibraryTab.Synced("w", "Watching"), listOf(title(dune, null)), emptyList()).isEmpty())
    }

    @Test
    fun `a service's titles are counted once across its lists`() {
        val lists = listOf(
            SyncedList("w", "Watching", listOf(synced(dune, 1))),
            SyncedList("r", "Rewatching", listOf(synced(dune, 1), synced(arrival, 1))),
        )
        assertEquals(2, LibraryShelf.syncedTotal(lists))
    }
}

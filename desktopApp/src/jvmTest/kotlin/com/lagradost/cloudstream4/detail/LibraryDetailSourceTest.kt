package com.lagradost.cloudstream4.detail

import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.DetailSeason
import com.lagradost.cloudstream4.feature.detail.DetailSource
import com.lagradost.cloudstream4.feature.detail.DetailWatchStatus
import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import com.lagradost.cloudstream4.feature.library.LibraryBucket
import com.lagradost.cloudstream4.feature.library.LibraryTitle
import com.lagradost.cloudstream4.feature.library.LibraryTitleId
import com.lagradost.cloudstream4.feature.library.WatchPosition
import com.lagradost.cloudstream4.library.InMemoryLibraryStore
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

/** `KMP-58`: a title's page with what this computer remembers about it. */
class LibraryDetailSourceTest {

    private val id = "Prov\u0000https://example.com/show"
    private val key = LibraryTitleId(id)

    private val episodes = listOf(
        DetailEpisode("Pilot", "s1e1", 1, 1),
        DetailEpisode("Two", "s1e2", 1, 2),
        DetailEpisode("Three", "s2e1", 2, 1),
        DetailEpisode("Recap", "x", null, null),
    )

    private val show = TitleDetail("Show", "Prov", "TvSeries", 2019, null, "https://example.com/p.jpg", null, episodes)

    private class Plugins(private val detail: TitleDetail) : DetailSource {
        override suspend fun details(id: String) = detail

        override fun links(id: String, data: String): Flow<List<StreamLink>> = emptyFlow()
    }

    private fun position(data: String?, at: Long, watchedAt: Long, length: Long = 2_400_000L) =
        WatchPosition(key, data, at, length, watchedAt)

    @Test
    fun `Resume is the newest position worth coming back to, on an episode still listed`() = runBlocking {
        val store = InMemoryLibraryStore(
            positions = listOf(
                position("s1e1", 600_000L, watchedAt = 1L),
                position("s1e2", 900_000L, watchedAt = 3L),
                // Newer, but finished: nothing to come back to.
                position("s2e1", 2_390_000L, watchedAt = 5L),
                // Newer still, but a handle the provider no longer lists.
                position("gone", 600_000L, watchedAt = 9L),
            ),
        )
        val resume = LibraryDetailSource(Plugins(show), store).details(id).resume
        // The newest resumable one is "gone", which cannot be played, so there is no Resume rather than a wrong one.
        assertNull(resume)

        val playable = InMemoryLibraryStore(positions = listOf(position("s1e1", 600_000L, 1L), position("s1e2", 900_000L, 3L)))
        val shown = LibraryDetailSource(Plugins(show), playable).details(id).resume
        assertEquals("s1e2", shown?.data)
        assertEquals(900_000L, shown?.positionMs)
        assertEquals(episodes[1], shown?.episode)
    }

    @Test
    fun `a film resumes on its own data`() = runBlocking {
        val film = show.copy(playData = "film", episodes = emptyList())
        val store = InMemoryLibraryStore(positions = listOf(position(null, 600_000L, 1L)))
        val resume = LibraryDetailSource(Plugins(film), store).details(id).resume
        assertEquals("film", resume?.data)
        assertNull(resume?.episode)
    }

    @Test
    fun `seasons are counted from the episodes, and one with no season is in none`() = runBlocking {
        val detail = LibraryDetailSource(Plugins(show), InMemoryLibraryStore()).details(id)
        assertEquals(listOf(DetailSeason(1, 2), DetailSeason(2, 1)), detail.seasons)
    }

    @Test
    fun `the watch list is the title's list, and None where it is on none`() = runBlocking {
        val none = LibraryDetailSource(Plugins(show), InMemoryLibraryStore()).details(id)
        assertEquals(DetailWatchStatus.None, none.watchStatus)
        val kept = InMemoryLibraryStore(listOf(LibraryTitle(key, "Show", LibraryBucket.OnHold, keptAt = 1L)))
        assertEquals(DetailWatchStatus.OnHold, LibraryDetailSource(Plugins(show), kept).details(id).watchStatus)
    }

    @Test
    fun `putting a title on a list the first time keeps its name, poster and provider`() = runBlocking {
        val store = InMemoryLibraryStore()
        val source = LibraryDetailSource(Plugins(show), store, clock = { 42L })
        source.details(id)
        source.setWatchStatus(id, DetailWatchStatus.PlanToWatch)
        assertEquals(
            listOf(LibraryTitle(key, "Show", LibraryBucket.PlanToWatch, 42L, show.posterUrl, "Prov")),
            store.titles(),
        )
        source.setWatchStatus(id, DetailWatchStatus.None)
        assertNull(store.titles().single().bucket)
    }

    @Test
    fun `None does not take a title out of Favourites, which the page cannot show`() = runBlocking {
        val store = InMemoryLibraryStore(listOf(LibraryTitle(key, "Show", LibraryBucket.Favourites, keptAt = 1L)))
        val source = LibraryDetailSource(Plugins(show), store)
        assertEquals(DetailWatchStatus.None, source.details(id).watchStatus)
        source.setWatchStatus(id, DetailWatchStatus.None)
        assertEquals(LibraryBucket.Favourites, store.titles().single().bucket)
    }

    @Test
    fun `a library that cannot be read still opens the page`() = runBlocking {
        val broken = object : InMemoryLibraryStore() {
            override suspend fun titles(): List<LibraryTitle> = throw IOException("disk")

            override suspend fun positions(): List<WatchPosition> = throw IOException("disk")
        }
        val detail = LibraryDetailSource(Plugins(show), broken).details(id)
        assertEquals("Show", detail.title)
        assertNull(detail.resume)
        assertEquals(DetailWatchStatus.None, detail.watchStatus)
    }
}

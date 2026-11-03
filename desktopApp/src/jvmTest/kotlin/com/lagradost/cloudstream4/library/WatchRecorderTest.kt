package com.lagradost.cloudstream4.library

import com.lagradost.cloudstream4.feature.library.LibraryBucket
import com.lagradost.cloudstream4.feature.library.LibraryTitle
import com.lagradost.cloudstream4.feature.library.LibraryTitleId
import com.lagradost.cloudstream4.feature.library.WatchPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * The recorder's writes run on [Dispatchers.Unconfined], so each one has happened by the time the
 * assertion after it runs. `kotlinx-coroutines-test` is not on this module's test classpath and this
 * needs none of it: there is no virtual time here, only an injected clock.
 */
class WatchRecorderTest {

    private val id = LibraryTitleId("Prov\u0000https://example.com/dune")
    private val kept = LibraryTitle(id, "Dune", bucket = null, keptAt = 0L, providerName = "Prov")

    private var now = 1_000L

    private fun recorder(store: InMemoryLibraryStore, episodeData: String? = "e1") =
        WatchRecorder(store, kept, episodeData, CoroutineScope(Dispatchers.Unconfined)) { now }

    @Test
    fun `a position is written, with the title beside it so a card can be drawn`() = runBlocking {
        val store = InMemoryLibraryStore()
        recorder(store).onProgress(60_000L, 600_000L)
        assertEquals(
            listOf(WatchPosition(id, "e1", 60_000L, 600_000L, 1_000L)),
            store.positions,
        )
        assertEquals("Dune", store.titles.single().title)
    }

    @Test
    fun `a title merely played claims no list`() = runBlocking {
        val store = InMemoryLibraryStore()
        recorder(store).onProgress(60_000L, 600_000L)
        assertNull(store.titles.single().bucket)
    }

    @Test
    fun `playing something already in a list does not move it`() = runBlocking {
        val store = InMemoryLibraryStore(listOf(kept.copy(bucket = LibraryBucket.PlanToWatch, keptAt = 7L)))
        recorder(store).onProgress(60_000L, 600_000L)
        assertEquals(LibraryBucket.PlanToWatch, store.titles.single().bucket)
        assertEquals(7L, store.titles.single().keptAt)
    }

    @Test
    fun `four reports a second do not become four writes a second`() = runBlocking {
        val store = InMemoryLibraryStore()
        val recorder = recorder(store)
        repeat(40) {
            now += 250L
            recorder.onProgress(60_000L + it * 250L, 600_000L)
        }
        // Ten seconds of reports is one write per ten seconds.
        assertEquals(1, store.positions.size)
        assertTrue(store.titles.size == 1)
    }

    @Test
    fun `past the interval it writes again`() = runBlocking {
        val store = InMemoryLibraryStore()
        val recorder = recorder(store)
        recorder.onProgress(60_000L, 600_000L)
        now += 10_000L
        recorder.onProgress(70_000L, 600_000L)
        assertEquals(70_000L, store.positions.single().positionMillis)
        assertEquals(11_000L, store.positions.single().watchedAt)
    }

    @Test
    fun `leaving the player keeps the last position the throttle would have dropped`() = runBlocking {
        val store = InMemoryLibraryStore()
        val recorder = recorder(store)
        recorder.onProgress(60_000L, 600_000L)
        now += 2_000L
        recorder.onProgress(62_000L, 600_000L)
        assertEquals(60_000L, store.positions.single().positionMillis)

        recorder.flush()
        assertEquals(62_000L, store.positions.single().positionMillis)
    }

    @Test
    fun `a length the player never reported is not recorded`() = runBlocking {
        val store = InMemoryLibraryStore()
        val recorder = recorder(store)
        recorder.onProgress(60_000L, 0L)
        recorder.flush()
        assertEquals(emptyList(), store.positions)
        assertEquals(emptyList(), store.titles)
    }

    @Test
    fun `the first half minute is not recorded, so a title opened by accident is not kept`() = runBlocking {
        val store = InMemoryLibraryStore()
        val recorder = recorder(store)
        recorder.onProgress(29_999L, 600_000L)
        recorder.flush()
        assertEquals(emptyList(), store.positions)
    }

    @Test
    fun `a finished film is still recorded, so its card can say so`() = runBlocking {
        val store = InMemoryLibraryStore()
        recorder(store, episodeData = null).onProgress(600_000L, 600_000L)
        val held = store.positions.single()
        assertTrue(held.finished)
        assertTrue(!held.resumable)
        assertNull(held.episodeData)
    }

    @Test
    fun `a store that cannot be written does not take the player down`() = runBlocking {
        val store = object : InMemoryLibraryStore() {
            override suspend fun record(position: WatchPosition) = throw IllegalStateException("read only")
        }
        recorder(store).onProgress(60_000L, 600_000L)
        assertEquals(emptyList(), store.positions)
    }
}

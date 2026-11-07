package com.lagradost.cloudstream4.feature.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val a = HomeSourceId("a")
    private val b = HomeSourceId("b")

    private class Call(val source: HomeSourceId, val section: Int, val page: Int)

    private class Fake(
        var options: List<HomeSourceOption>,
        val installed: Int = options.size,
        var answer: suspend (HomeSourceId, Int, Int) -> HomePageReply,
    ) : HomeSource {
        val calls = mutableListOf<Call>()
        var failSources: Exception? = null

        override suspend fun sources(): HomeSources {
            failSources?.let { throw it }
            return HomeSources(options, installed)
        }

        override suspend fun page(source: HomeSourceId, section: Int, page: Int): HomePageReply {
            calls += Call(source, section, page)
            return answer(source, section, page)
        }
    }

    private fun option(id: HomeSourceId, sections: Int = 1, gap: Long? = null) =
        HomeSourceOption(id, id.value, sections = sections, sequentialDelayMillis = gap)

    private fun page(title: String, vararg ids: String, hasNext: Boolean = false) =
        HomePageReply.Loaded(listOf(HomeRowPage(title, false, ids.map { HomeItem(it, it) })), hasNext)

    private fun TestScope.model(source: HomeSource, memory: HomeSourceMemory = HomeSourceMemory.None) =
        HomeViewModel(source, TestScope(UnconfinedTestDispatcher(testScheduler)), memory)

    private class Kept(var id: HomeSourceId? = null) : HomeSourceMemory {
        override fun last() = id

        override fun remember(id: HomeSourceId) {
            this.id = id
        }
    }

    @Test
    fun `the first source with a main page loads`() = runTest {
        val vm = model(Fake(listOf(option(a), option(b))) { id, _, _ -> page(id.value, "1") })
        assertEquals(a, vm.state.value.selected)
        assertEquals(listOf("a"), assertIs<HomeStatus.Showing>(vm.state.value.status).rows.map { it.title })
    }

    @Test
    fun `no sources is its own state and asks nothing`() = runTest {
        val source = Fake(emptyList(), installed = 3) { _, _, _ -> error("not asked") }
        val vm = model(source)
        assertEquals(HomeStatus.NoSources(3), vm.state.value.status)
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun `sources failing to read is a failure, and retry reads them again`() = runTest {
        val source = Fake(listOf(option(a))) { _, _, _ -> page("A", "1") }
        source.failSources = IllegalStateException("broken")
        val vm = model(source)
        assertEquals(HomeStatus.Failed("broken"), vm.state.value.status)

        source.failSources = null
        vm.onAction(HomeAction.Retry)
        assertIs<HomeStatus.Showing>(vm.state.value.status)
    }

    @Test
    fun `a host that throws instead of replying is a failure and not a crash`() = runTest {
        val vm = model(Fake(listOf(option(a))) { _, _, _ -> throw IllegalStateException("boom") })
        assertEquals(HomeStatus.Failed("boom"), vm.state.value.status)
    }

    @Test
    fun `choosing a source closes the picker and loads it`() = runTest {
        val source = Fake(listOf(option(a), option(b))) { id, _, _ -> page(id.value, "1") }
        val vm = model(source)
        vm.onAction(HomeAction.PickerOpened)
        assertTrue(vm.state.value.pickerOpen)
        vm.onAction(HomeAction.SourceSelected(b))
        assertFalse(vm.state.value.pickerOpen)
        assertEquals(b, vm.state.value.selected)
        assertEquals(listOf("b"), assertIs<HomeStatus.Showing>(vm.state.value.status).rows.map { it.title })
    }

    @Test
    fun `the source chosen last opens again, whatever order the sources come in`() = runTest {
        val memory = Kept()
        val first = model(Fake(listOf(option(a), option(b))) { id, _, _ -> page(id.value, "1") }, memory)
        first.onAction(HomeAction.SourceSelected(b))
        assertEquals(b, memory.id)

        val source = Fake(listOf(option(a), option(b))) { id, _, _ -> page(id.value, "1") }
        val reopened = model(source, memory)
        assertEquals(b, reopened.state.value.selected)
        assertEquals(listOf(b), source.calls.map { it.source }, "and only that one was asked")
        assertEquals(b, model(Fake(listOf(option(b), option(a))) { id, _, _ -> page(id.value, "1") }, memory).state.value.selected)
    }

    @Test
    fun `a source that has gone opens the first, and comes back when it does`() = runTest {
        val memory = Kept(b)
        val without = model(Fake(listOf(option(a))) { id, _, _ -> page(id.value, "1") }, memory)
        assertEquals(a, without.state.value.selected)
        assertEquals(b, memory.id, "falling back is not a choice")
        assertEquals(b, model(Fake(listOf(option(a), option(b))) { id, _, _ -> page(id.value, "1") }, memory).state.value.selected)
    }

    @Test
    fun `a source the host never offered is not remembered`() = runTest {
        val memory = Kept(a)
        val vm = model(Fake(listOf(option(a))) { id, _, _ -> page(id.value, "1") }, memory)
        vm.onAction(HomeAction.SourceSelected(b))
        assertEquals(a, memory.id)
    }

    @Test
    fun `choosing the one already showing does not reload it`() = runTest {
        val source = Fake(listOf(option(a))) { _, _, _ -> page("A", "1") }
        val vm = model(source)
        vm.onAction(HomeAction.PickerOpened)
        vm.onAction(HomeAction.SourceSelected(a))
        assertFalse(vm.state.value.pickerOpen)
        assertEquals(1, source.calls.size)
    }

    @Test
    fun `a source the host never offered is ignored`() = runTest {
        val source = Fake(listOf(option(a))) { _, _, _ -> page("A", "1") }
        val vm = model(source)
        vm.onAction(HomeAction.SourceSelected(HomeSourceId("ghost")))
        assertEquals(a, vm.state.value.selected)
        assertEquals(1, source.calls.size)
    }

    /** The proof a late reply cannot land is that it completes after the choice moved on. */
    @Test
    fun `a slow source cannot overwrite the one chosen after it`() = runTest {
        val slow = CompletableDeferred<HomePageReply>()
        val vm = model(Fake(listOf(option(a), option(b))) { id, _, _ -> if (id == a) slow.await() else page("B", "2") })
        assertEquals(HomeStatus.Loading, vm.state.value.status)

        vm.onAction(HomeAction.SourceSelected(b))
        slow.complete(page("A", "1"))
        runCurrent()
        assertEquals(listOf("B"), assertIs<HomeStatus.Showing>(vm.state.value.status).rows.map { it.title })
    }

    @Test
    fun `a slow next page cannot land on the source chosen after it`() = runTest {
        val slow = CompletableDeferred<HomePageReply>()
        val vm = model(
            Fake(listOf(option(a), option(b))) { id, _, p ->
                if (p == 2) slow.await() else page(id.value, "$id", hasNext = true)
            },
        )
        val key = HomeRowKey(0, "a")
        vm.onAction(HomeAction.RowMoreRequested(key, columns = 8))
        vm.onAction(HomeAction.SourceSelected(b))
        slow.complete(page("a", "late"))
        runCurrent()
        val rows = assertIs<HomeStatus.Showing>(vm.state.value.status).rows
        assertEquals(listOf("b"), rows.map { it.title })
        assertEquals(HomeMore.Idle, rows.single().more)
    }

    @Test
    fun `sections are asked one at a time and apart when the source says so`() = runTest {
        val source = Fake(listOf(option(a, sections = 3, gap = 1_000))) { _, s, _ -> page("R$s", "$s") }
        val vm = model(source)
        assertEquals(1, source.calls.size)
        advanceTimeBy(1_001)
        assertEquals(2, source.calls.size)
        advanceTimeBy(1_001)
        assertEquals(listOf(0, 1, 2), source.calls.map { it.section })
        assertEquals(3, assertIs<HomeStatus.Showing>(vm.state.value.status).rows.size)
    }

    @Test
    fun `sections are all asked at once otherwise`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = Fake(listOf(option(a, sections = 3))) { _, s, _ ->
            gate.await()
            page("R$s", "$s")
        }
        val vm = model(source)
        assertEquals(3, source.calls.size, "all three started before any answered")
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("R0", "R1", "R2"), assertIs<HomeStatus.Showing>(vm.state.value.status).rows.map { it.title })
    }

    @Test
    fun `retrying a partial page asks only what failed and keeps what is on screen`() = runTest {
        var broken = true
        val source = Fake(listOf(option(a, sections = 2))) { _, s, p ->
            if (s == 1 && broken) HomePageReply.Failed("503") else page("R$s", "$s-$p", hasNext = true)
        }
        val vm = model(source)
        vm.onAction(HomeAction.RowMoreRequested(HomeRowKey(0, "R0"), columns = 8))
        val before = assertIs<HomeStatus.Partial>(vm.state.value.status)
        assertEquals(listOf(1), before.failedSections)
        assertEquals(listOf("0-1", "0-2"), before.rows.single().items.map { it.id })

        broken = false
        source.calls.clear()
        vm.onAction(HomeAction.Retry)
        assertEquals(listOf(1), source.calls.map { it.section })
        val after = assertIs<HomeStatus.Showing>(vm.state.value.status).rows
        assertEquals(listOf("R0", "R1"), after.map { it.title })
        assertEquals(listOf("0-1", "0-2"), after[0].items.map { it.id }, "the page already loaded survived")
    }

    @Test
    fun `a retry in flight keeps the page and ignores a second press`() = runTest {
        val gate = CompletableDeferred<HomePageReply>()
        var attempt = 0
        val source = Fake(listOf(option(a, sections = 2))) { _, s, _ ->
            when {
                s == 0 -> page("R0", "0")
                attempt++ == 0 -> HomePageReply.Failed("503")
                else -> gate.await()
            }
        }
        val vm = model(source)
        source.calls.clear()
        vm.onAction(HomeAction.Retry)
        vm.onAction(HomeAction.Retry)
        assertEquals(1, source.calls.size)
        assertTrue(assertIs<HomeStatus.Partial>(vm.state.value.status).retrying)
        gate.complete(page("R1", "1"))
        runCurrent()
        assertEquals(2, assertIs<HomeStatus.Showing>(vm.state.value.status).rows.size)
    }

    @Test
    fun `a full failure retries everything from a skeleton`() = runTest {
        var broken = true
        val source = Fake(listOf(option(a))) { _, _, _ -> if (broken) HomePageReply.Offline else page("A", "1") }
        val vm = model(source)
        assertEquals(HomeStatus.Offline, vm.state.value.status)
        broken = false
        vm.onAction(HomeAction.Retry)
        assertIs<HomeStatus.Showing>(vm.state.value.status)
    }

    @Test
    fun `showing more reveals what is loaded before asking for more`() = runTest {
        val ids = Array(10) { "$it" }
        val source = Fake(listOf(option(a))) { _, _, _ -> page("A", *ids, hasNext = true) }
        val vm = model(source)
        val key = HomeRowKey(0, "A")
        source.calls.clear()

        // Three lines of three is nine, of ten loaded: no request.
        vm.onAction(HomeAction.RowMoreRequested(key, columns = 3))
        assertTrue(source.calls.isEmpty())
        assertEquals(3, assertIs<HomeStatus.Showing>(vm.state.value.status).rows.single().lines)

        // Five lines of three is fifteen, more than are loaded: the next page.
        vm.onAction(HomeAction.RowMoreRequested(key, columns = 3))
        assertEquals(listOf(2), source.calls.map { it.page })
    }

    @Test
    fun `no next page is asked for when the source has none`() = runTest {
        val source = Fake(listOf(option(a))) { _, _, _ -> page("A", "1", hasNext = false) }
        val vm = model(source)
        source.calls.clear()
        vm.onAction(HomeAction.RowMoreRequested(HomeRowKey(0, "A"), columns = 5))
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun `a row asks for one next page at a time, and its siblings wait with it`() = runTest {
        val gate = CompletableDeferred<HomePageReply>()
        val source = Fake(listOf(option(a))) { _, _, p ->
            if (p == 1) {
                HomePageReply.Loaded(
                    listOf(
                        HomeRowPage("A", false, listOf(HomeItem("1", "1"))),
                        HomeRowPage("B", false, listOf(HomeItem("2", "2"))),
                    ),
                    hasNext = true,
                )
            } else {
                gate.await()
            }
        }
        val vm = model(source)
        source.calls.clear()
        vm.onAction(HomeAction.RowMoreRequested(HomeRowKey(0, "A"), columns = 5))
        vm.onAction(HomeAction.RowMoreRequested(HomeRowKey(0, "A"), columns = 5))
        vm.onAction(HomeAction.RowMoreRequested(HomeRowKey(0, "B"), columns = 5))
        assertEquals(1, source.calls.size)
        assertTrue(assertIs<HomeStatus.Showing>(vm.state.value.status).rows.all { it.more == HomeMore.Loading })
    }

    @Test
    fun `a failed next page stays on the row, and pressing again asks the same page`() = runTest {
        var broken = true
        val source = Fake(listOf(option(a))) { _, _, p ->
            when {
                p == 1 -> page("A", "1", hasNext = true)
                broken -> HomePageReply.Failed("503")
                else -> page("A", "2")
            }
        }
        val vm = model(source)
        val key = HomeRowKey(0, "A")
        vm.onAction(HomeAction.RowMoreRequested(key, columns = 5))
        val failed = assertIs<HomeStatus.Showing>(vm.state.value.status).rows.single()
        assertEquals(HomeMore.Failed("503"), failed.more)
        assertEquals(listOf("1"), failed.items.map { it.id })

        broken = false
        source.calls.clear()
        vm.onAction(HomeAction.RowMoreRequested(key, columns = 5))
        assertEquals(listOf(2), source.calls.map { it.page })
        val done = assertIs<HomeStatus.Showing>(vm.state.value.status).rows.single()
        assertEquals(failed.lines, done.lines, "the retry did not also open more lines")
        assertEquals(listOf("1", "2"), done.items.map { it.id })
    }

    @Test
    fun `an offline next page is told apart in the row's line`() = runTest {
        val source = Fake(listOf(option(a))) { _, _, p ->
            if (p == 1) page("A", "1", hasNext = true) else HomePageReply.Offline
        }
        val vm = model(source)
        vm.onAction(HomeAction.RowMoreRequested(HomeRowKey(0, "A"), columns = 5))
        val row = assertIs<HomeStatus.Showing>(vm.state.value.status).rows.single()
        assertEquals(HomeMore.Failed(HomePaging.OFFLINE_DETAIL), row.more)
    }

    @Test
    fun `opening a title and asking for search are effects`() = runTest {
        val vm = model(Fake(listOf(option(a))) { _, _, _ -> page("A", "1") })
        val first = launch { assertEquals(HomeEffect.OpenTitle("1"), vm.effect.first()) }
        vm.onAction(HomeAction.TitleActivated("1"))
        runCurrent()
        first.join()
        val second = launch { assertEquals(HomeEffect.OpenSearch, vm.effect.first()) }
        vm.onAction(HomeAction.SearchRequested)
        runCurrent()
        second.join()
    }

    // ---- Sources arriving in stages, on a host that loads plugins behind a page already drawn ------------------------

    private class Staged(
        var options: List<HomeSourceOption>,
        var loading: Boolean,
        val answer: (HomeSourceId) -> HomePageReply,
    ) : HomeSource {
        val asked = mutableListOf<HomeSourceId>()
        var failSources = false

        override suspend fun sources(): HomeSources {
            if (failSources) error("broken")
            return HomeSources(options, options.size, stillLoading = loading)
        }

        override suspend fun page(source: HomeSourceId, section: Int, page: Int): HomePageReply {
            asked += source
            return answer(source)
        }
    }

    @Test
    fun `nothing yet while still loading is loading, not nothing installed`() = runTest {
        val source = Staged(emptyList(), loading = true) { page(it.value, "1") }
        val vm = model(source)
        assertEquals(HomeStatus.Loading, vm.state.value.status)

        source.loading = false
        vm.onAction(HomeAction.SourcesChanged)
        assertEquals(HomeStatus.NoSources(0), vm.state.value.status)
    }

    @Test
    fun `a remembered source still on its way is waited for, and nothing else is fetched`() = runTest {
        val source = Staged(listOf(option(a)), loading = true) { page(it.value, "1") }
        val vm = model(source, Kept(b))
        assertEquals(HomeStatus.Loading, vm.state.value.status)
        assertTrue(source.asked.isEmpty())

        source.options = listOf(option(a), option(b))
        vm.onAction(HomeAction.SourcesChanged)
        assertEquals(b, vm.state.value.selected)
        assertEquals(listOf(b), source.asked)
    }

    @Test
    fun `a remembered source that never comes falls back without being forgotten`() = runTest {
        val memory = Kept(b)
        val source = Staged(listOf(option(a)), loading = true) { page(it.value, "1") }
        val vm = model(source, memory)

        source.loading = false
        vm.onAction(HomeAction.SourcesChanged)
        assertEquals(a, vm.state.value.selected)
        assertEquals(b, memory.id)
    }

    @Test
    fun `the remembered source replaces a fallback when it turns up after all`() = runTest {
        val source = Staged(listOf(option(a)), loading = false) { page(it.value, "1") }
        val vm = model(source, Kept(b))
        assertEquals(a, vm.state.value.selected)

        source.options = listOf(option(a), option(b))
        vm.onAction(HomeAction.SourcesChanged)
        assertEquals(b, vm.state.value.selected)
        assertEquals(listOf("b"), assertIs<HomeStatus.Showing>(vm.state.value.status).rows.map { it.title })
    }

    @Test
    fun `no source remembered and one on offer loads it even while others are still loading`() = runTest {
        val source = Staged(listOf(option(a)), loading = true) { page(it.value, "1") }
        val vm = model(source)
        assertEquals(a, vm.state.value.selected)
        assertIs<HomeStatus.Showing>(vm.state.value.status)
    }

    @Test
    fun `sources changing leaves rows for the same source alone and asks nothing`() = runTest {
        val source = Staged(listOf(option(a)), loading = false) { page(it.value, "1") }
        val vm = model(source)
        val before = vm.state.value.status

        source.options = listOf(option(a), option(b))
        vm.onAction(HomeAction.SourcesChanged)

        assertTrue(before === vm.state.value.status, "the same rows, so nothing recomposes away")
        assertEquals(listOf(a), source.asked)
        assertEquals(2, vm.state.value.sources.size)
    }

    @Test
    fun `sources changing during a first page does not start it again or lose it`() = runTest {
        val waiting = CompletableDeferred<HomePageReply>()
        var asked = 0
        val source = object : HomeSource {
            override suspend fun sources() = HomeSources(listOf(option(a)), 1)
            override suspend fun page(source: HomeSourceId, section: Int, page: Int): HomePageReply {
                asked++
                return waiting.await()
            }
        }
        val vm = model(source)
        vm.onAction(HomeAction.SourcesChanged)
        assertEquals(1, asked)

        waiting.complete(page("a", "1"))
        assertIs<HomeStatus.Showing>(vm.state.value.status)
    }

    @Test
    fun `a failure is asked again when sources change, and sources failing to read leaves the page alone`() = runTest {
        var fail = true
        val source = Staged(listOf(option(a)), loading = false) {
            if (fail) HomePageReply.Failed("503") else page(it.value, "1")
        }
        val vm = model(source)
        assertIs<HomeStatus.Failed>(vm.state.value.status)

        fail = false
        vm.onAction(HomeAction.SourcesChanged)
        val showing = assertIs<HomeStatus.Showing>(vm.state.value.status)

        source.failSources = true
        vm.onAction(HomeAction.SourcesChanged)
        assertTrue(showing === vm.state.value.status)
    }

    // ---- What a TV needs to put focus back ---------------------------------------------------------------------------

    @Test
    fun `a title opened with a place to come back to waits for the screen to resume`() = runTest {
        val vm = model(Fake(listOf(option(a))) { id, _, _ -> page(id.value, "1") })
        val from = HomeReturn("1", HomeRowKey(0, "a"), firstVisibleRow = 2, firstVisibleCard = 3)

        vm.onAction(HomeAction.TitleActivated("1", from))
        assertEquals(from, vm.state.value.returnTo)
        assertEquals(false, vm.state.value.returnTo?.ready)

        vm.onAction(HomeAction.Reappeared)
        assertEquals(true, vm.state.value.returnTo?.ready)
        assertEquals(true, vm.state.value.appeared)

        vm.onAction(HomeAction.ReturnHandled)
        assertNull(vm.state.value.returnTo)
    }

    @Test
    fun `choosing another source drops the place to come back to`() = runTest {
        val vm = model(Fake(listOf(option(a), option(b))) { id, _, _ -> page(id.value, "1") })
        vm.onAction(HomeAction.TitleActivated("1", HomeReturn("1", HomeRowKey(0, "a"))))
        vm.onAction(HomeAction.SourceSelected(b))
        assertNull(vm.state.value.returnTo)
    }

    @Test
    fun `a landing is owed once, and pausing withdraws the right to take focus`() = runTest {
        val vm = model(Fake(listOf(option(a))) { id, _, _ -> page(id.value, "1") })
        assertEquals(true, vm.state.value.landingOwed)
        vm.onAction(HomeAction.LandingHandled)
        assertEquals(false, vm.state.value.landingOwed)

        vm.onAction(HomeAction.Reappeared)
        vm.onAction(HomeAction.Disappeared)
        assertEquals(false, vm.state.value.appeared)
    }

    // ---- The hero and Continue watching (3c) -----------------------------------------------------------------------

    /** [Fake] that also answers for the hero, by item id, after [heroDelay]. */
    private class Featuring(
        options: List<HomeSourceOption>,
        answer: suspend (HomeSourceId, Int, Int) -> HomePageReply,
        val featured: (String) -> HomeFeatured?,
        val heroDelay: Long = 0,
    ) : HomeSource {
        private val inner = Fake(options, answer = answer)
        val asked = mutableListOf<String>()

        override suspend fun sources() = inner.sources()

        override suspend fun page(source: HomeSourceId, section: Int, page: Int) = inner.page(source, section, page)

        override suspend fun featured(item: HomeItem): HomeFeatured? {
            asked += item.id
            if (heroDelay > 0) kotlinx.coroutines.delay(heroDelay)
            return featured(item.id)
        }
    }

    private class History(var read: HomeHistory = HomeHistory(), var fail: Boolean = false) : HomeWatchHistory {
        var reads = 0

        override suspend fun read(): HomeHistory {
            reads++
            if (fail) throw IllegalStateException("unreadable")
            return read
        }
    }

    private fun TestScope.model(source: HomeSource, history: HomeWatchHistory) =
        HomeViewModel(source, TestScope(UnconfinedTestDispatcher(testScheduler)), HomeSourceMemory.None, history)

    @Test
    fun `the hero is the first title whose own page answers, asked one at a time`() = runTest {
        val source = Featuring(listOf(option(a)), { _, _, _ -> page("Row", "1", "2", "3", "4") }, featured = { id ->
            if (id == "2") HomeFeatured(id, "Two") else null
        })
        val vm = model(source, HomeWatchHistory.None)

        val hero = assertIs<HomeHero.Shown>(vm.state.value.hero)
        assertEquals("Two", hero.featured.title)
        assertEquals("Row", hero.eyebrow)
        assertEquals(listOf("1", "2"), source.asked)
    }

    @Test
    fun `no title answering is no hero, after at most three are asked`() = runTest {
        val source = Featuring(listOf(option(a)), { _, _, _ -> page("Row", "1", "2", "3", "4") }, featured = { null })
        val vm = model(source, HomeWatchHistory.None)
        assertEquals(HomeHero.None, vm.state.value.hero)
        assertEquals(listOf("1", "2", "3"), source.asked)
    }

    @Test
    fun `a host that cannot ask has no hero, and that is settled at once`() = runTest {
        val vm = model(Fake(listOf(option(a))) { id, _, _ -> page(id.value, "1") })
        assertEquals(HomeHero.None, vm.state.value.hero)
    }

    @Test
    fun `the hero is loading while asked, and a source that never answers is given up`() = runTest {
        val source = Featuring(
            listOf(option(a)),
            { _, _, _ -> page("Row", "1") },
            featured = { HomeFeatured(it, "Late") },
            heroDelay = 60_000,
        )
        val vm = model(source, HomeWatchHistory.None)
        assertIs<HomeStatus.Showing>(vm.state.value.status)
        assertEquals(HomeHero.Loading, vm.state.value.hero)

        advanceTimeBy(6_001)
        runCurrent()
        assertEquals(HomeHero.None, vm.state.value.hero)
    }

    @Test
    fun `a page that failed has no hero to wait for`() = runTest {
        val source = Featuring(listOf(option(a)), { _, _, _ -> HomePageReply.Offline }, featured = { HomeFeatured(it, "X") })
        val vm = model(source, HomeWatchHistory.None)
        assertEquals(HomeStatus.Offline, vm.state.value.status)
        assertEquals(HomeHero.None, vm.state.value.hero)
        assertTrue(source.asked.isEmpty())
    }

    @Test
    fun `a hero for the source chosen before cannot land on the one chosen after`() = runTest {
        val source = Featuring(
            listOf(option(a), option(b)),
            { id, _, _ -> page(id.value, "${id.value}1") },
            featured = { HomeFeatured(it, it) },
            heroDelay = 1_000,
        )
        val vm = model(source, HomeWatchHistory.None)
        vm.onAction(HomeAction.SourceSelected(b))
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals("b1", assertIs<HomeHero.Shown>(vm.state.value.hero).featured.id)
    }

    @Test
    fun `history is read when the page opens, comes back and its sources change, and is kept offline`() = runTest {
        val entry = HomeResume("t", "Title", progress = 0.5f)
        val history = History(HomeHistory(listOf(entry), mapOf("t" to 0.5f)))
        val vm = model(Featuring(listOf(option(a)), { _, _, _ -> HomePageReply.Offline }, featured = { null }), history)
        assertEquals(listOf(entry), vm.state.value.history.resume)
        assertEquals(HomeStatus.Offline, vm.state.value.status)

        history.read = HomeHistory()
        vm.onAction(HomeAction.Reappeared)
        assertEquals(emptyList(), vm.state.value.history.resume)
        vm.onAction(HomeAction.SourcesChanged)
        assertEquals(3, history.reads)
    }

    @Test
    fun `history that cannot be read leaves what is on screen`() = runTest {
        val entry = HomeResume("t", "Title")
        val history = History(HomeHistory(listOf(entry)))
        val vm = model(Fake(listOf(option(a))) { id, _, _ -> page(id.value, "1") }, history)
        history.fail = true
        vm.onAction(HomeAction.Reappeared)
        assertEquals(listOf(entry), vm.state.value.history.resume)
    }

    // ---- Bookmarks (`TV Home Extras` 3e, 3f) -----------------------------------------------------------------------

    private class Shelf(var all: List<HomeBookmark>, var kept: HomeWatchStatus? = null) : HomeBookmarkStore {
        var reads = 0
        var fail = false
        val chosenLog = mutableListOf<HomeWatchStatus?>()

        override suspend fun read(): List<HomeBookmark> {
            reads++
            if (fail) error("unreadable")
            return all
        }

        override fun chosen() = kept

        override fun choose(status: HomeWatchStatus?) {
            kept = status
            chosenLog += status
        }
    }

    private fun mark(id: String, status: HomeWatchStatus, at: Long) = HomeBookmark(HomeItem(id, id), status, at)

    private fun TestScope.withShelf(shelf: HomeBookmarkStore, answer: HomePageReply = page("A", "1")) = HomeViewModel(
        Fake(listOf(option(a))) { _, _, _ -> answer },
        TestScope(UnconfinedTestDispatcher(testScheduler)),
        bookmarkStore = shelf,
    )

    @Test
    fun `bookmarks show the kept status's titles, newest first, with a chip only for a status that holds one`() = runTest {
        val shelf = Shelf(
            listOf(
                mark("old", HomeWatchStatus.Watching, 1),
                mark("new", HomeWatchStatus.Watching, 3),
                mark("later", HomeWatchStatus.Completed, 2),
            ),
            kept = HomeWatchStatus.Watching,
        )
        val vm = withShelf(shelf)
        val bookmarks = vm.state.value.bookmarks
        assertEquals(listOf(HomeWatchStatus.Watching, HomeWatchStatus.Completed), bookmarks.statuses)
        assertEquals(listOf("new", "old"), bookmarks.shown.map { it.id })
    }

    @Test
    fun `pressing another chip shows its titles and keeps it, and pressing the chosen one clears it`() = runTest {
        val shelf = Shelf(
            listOf(mark("w", HomeWatchStatus.Watching, 1), mark("c", HomeWatchStatus.Completed, 2)),
            kept = HomeWatchStatus.Watching,
        )
        val vm = withShelf(shelf)
        vm.onAction(HomeAction.BookmarkFilterPressed(HomeWatchStatus.Completed))
        assertEquals(listOf("c"), vm.state.value.bookmarks.shown.map { it.id })
        vm.onAction(HomeAction.BookmarkFilterPressed(HomeWatchStatus.Completed))
        assertNull(vm.state.value.bookmarks.applied)
        assertEquals(emptyList(), vm.state.value.bookmarks.shown)
        // The rail is still there, with its chips: 3f.
        assertEquals(2, vm.state.value.bookmarks.statuses.size)
        assertEquals(listOf(HomeWatchStatus.Completed, null), shelf.chosenLog)
    }

    @Test
    fun `a kept status that holds nothing now is no choice, and nothing kept is none chosen`() = runTest {
        val gone = withShelf(Shelf(listOf(mark("w", HomeWatchStatus.Watching, 1)), kept = HomeWatchStatus.Dropped))
        assertNull(gone.state.value.bookmarks.applied)
        val never = withShelf(Shelf(listOf(mark("w", HomeWatchStatus.Watching, 1))))
        assertNull(never.state.value.bookmarks.applied)
        assertEquals(listOf(HomeWatchStatus.Watching), never.state.value.bookmarks.statuses)
    }

    @Test
    fun `bookmarks are read when history is, stay offline, and a failed read leaves what is on screen`() = runTest {
        val shelf = Shelf(listOf(mark("w", HomeWatchStatus.Watching, 1)), kept = HomeWatchStatus.Watching)
        val vm = withShelf(shelf, HomePageReply.Offline)
        assertEquals(HomeStatus.Offline, vm.state.value.status)
        assertEquals(listOf("w"), vm.state.value.bookmarks.shown.map { it.id })

        vm.onAction(HomeAction.Reappeared)
        vm.onAction(HomeAction.SourcesChanged)
        assertEquals(3, shelf.reads)

        shelf.fail = true
        vm.onAction(HomeAction.Reappeared)
        assertEquals(listOf("w"), vm.state.value.bookmarks.shown.map { it.id })
    }

    @Test
    fun `one title in one status twice is one card`() = runTest {
        val vm = withShelf(
            Shelf(
                listOf(mark("w", HomeWatchStatus.Watching, 1), mark("w", HomeWatchStatus.Watching, 2)),
                kept = HomeWatchStatus.Watching,
            ),
        )
        assertEquals(listOf("w"), vm.state.value.bookmarks.shown.map { it.id })
    }

    // ---- The chrome, and None (`TV Home Extras` 3h to 3j, 3m) -----------------------------------------------------

    private suspend fun TestScope.effectAfter(vm: HomeViewModel, action: HomeAction): HomeEffect {
        val effect = CompletableDeferred<HomeEffect>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { effect.complete(vm.effect.first()) }
        vm.onAction(action)
        runCurrent()
        job.cancel()
        return effect.await()
    }

    @Test
    fun `choosing None asks nothing, keeps it, and survives the sources changing`() = runTest {
        val memory = Kept()
        val source = Fake(listOf(option(a))) { id, _, _ -> page(id.value, "1") }
        val vm = model(source, memory)
        val asked = source.calls.size
        vm.onAction(HomeAction.SourceSelected(HomeSourceId.None))
        assertEquals(HomeStatus.Unsourced, vm.state.value.status)
        assertTrue(vm.state.value.unsourced)
        assertEquals(HomeSourceId.None, memory.id)
        vm.onAction(HomeAction.SourcesChanged)
        vm.onAction(HomeAction.Retry)
        assertEquals(HomeStatus.Unsourced, vm.state.value.status)
        assertEquals(asked, source.calls.size)
    }

    @Test
    fun `None remembered opens as None and does not wait for or fall back to a source`() = runTest {
        val source = Fake(listOf(option(a))) { id, _, _ -> page(id.value, "1") }
        val vm = model(source, Kept(HomeSourceId.None))
        assertEquals(HomeStatus.Unsourced, vm.state.value.status)
        assertTrue(source.calls.isEmpty())
        vm.onAction(HomeAction.SourceSelected(a))
        assertIs<HomeStatus.Showing>(vm.state.value.status)
    }

    @Test
    fun `a reload keeps the rows while it runs, ignores a second press, and swaps them in place`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var round = 0
        val source = Fake(listOf(option(a))) { _, _, _ ->
            if (round > 0) gate.await()
            page("A", "r$round")
        }
        val vm = model(source)
        round = 1
        vm.onAction(HomeAction.ReloadRequested)
        assertTrue(vm.state.value.reloading)
        assertEquals(listOf("r0"), vm.state.value.status.rows.single().items.map { it.id })
        val asked = source.calls.size
        vm.onAction(HomeAction.ReloadRequested)
        assertEquals(asked, source.calls.size)
        gate.complete(Unit)
        runCurrent()
        assertFalse(vm.state.value.reloading)
        assertEquals(listOf("r1"), vm.state.value.status.rows.single().items.map { it.id })
    }

    @Test
    fun `a reload that fails is 3m's failure, and with nothing loaded there is nothing to reload`() = runTest {
        var fail = false
        val source = Fake(listOf(option(a))) { _, _, _ -> if (fail) HomePageReply.Failed("HTTP 503") else page("A", "1") }
        val vm = model(source)
        fail = true
        vm.onAction(HomeAction.ReloadRequested)
        val stale = assertIs<HomeStatus.Stale>(vm.state.value.status)
        assertEquals(HomeStatus.Failed("HTTP 503"), stale.shown)
        assertTrue(stale.rows.isEmpty())

        val never = model(Fake(listOf(option(a))) { _, _, _ -> HomePageReply.Failed("HTTP 503") })
        assertEquals(HomeStatus.Failed("HTTP 503"), never.state.value.status)
        val asked = source.calls.size
        never.onAction(HomeAction.ReloadRequested)
        assertEquals(HomeStatus.Failed("HTTP 503"), never.state.value.status)
        assertEquals(asked, source.calls.size)
    }

    @Test
    fun `a reload that fails after one that loaded keeps the old rows and when they loaded, and can be reloaded`() = runTest {
        var clock = 1_000L
        var reply: HomePageReply = page("A", "r0")
        val source = Fake(listOf(option(a))) { _, _, _ -> reply }
        val vm = HomeViewModel(source, TestScope(UnconfinedTestDispatcher(testScheduler)), now = { clock })
        val before = vm.state.value.status.rows

        clock = 61_000L
        reply = HomePageReply.Offline
        vm.onAction(HomeAction.ReloadRequested)
        val stale = assertIs<HomeStatus.Stale>(vm.state.value.status)
        assertEquals(HomeStatus.Offline, stale.failure)
        assertEquals(before, stale.kept)
        assertEquals(1_000L, stale.loadedAt)

        clock = 121_000L
        reply = HomePageReply.Failed("HTTP 503")
        vm.onAction(HomeAction.ReloadRequested)
        val again = assertIs<HomeStatus.Stale>(vm.state.value.status)
        assertEquals(HomeStatus.Failed("HTTP 503"), again.failure)
        assertEquals(before, again.kept)
        assertEquals(1_000L, again.loadedAt)

        reply = page("A", "r1")
        vm.onAction(HomeAction.ReloadRequested)
        assertEquals(listOf("r1"), assertIs<HomeStatus.Showing>(vm.state.value.status).rows.single().items.map { it.id })
    }

    @Test
    fun `random opens one loaded title and keeps where to come back to, and nothing loaded opens nothing`() = runTest {
        val vm = HomeViewModel(
            Fake(listOf(option(a, sections = 2))) { _, section, _ -> page("S$section", "s$section-1", "s$section-2") },
            TestScope(UnconfinedTestDispatcher(testScheduler)),
            random = kotlin.random.Random(7),
        )
        val back = HomeReturn("random", HomeRowKey.Chrome)
        val effect = assertIs<HomeEffect.OpenTitle>(effectAfter(vm, HomeAction.RandomRequested(back)))
        assertTrue(effect.id in setOf("s0-1", "s0-2", "s1-1", "s1-2"), effect.id)
        assertEquals(back, vm.state.value.returnTo)

        val none = model(Fake(listOf(option(a))) { _, _, _ -> HomePageReply.Offline })
        none.onAction(HomeAction.RandomRequested(back))
        assertNull(none.state.value.returnTo)
    }

    @Test
    fun `search from the chrome is of the chosen source alone`() = runTest {
        val vm = model(Fake(listOf(option(a), option(b))) { id, _, _ -> page(id.value, "1") })
        vm.onAction(HomeAction.SourceSelected(b))
        assertEquals(HomeEffect.OpenSourceSearch(b), effectAfter(vm, HomeAction.SourceSearchRequested))
    }
}

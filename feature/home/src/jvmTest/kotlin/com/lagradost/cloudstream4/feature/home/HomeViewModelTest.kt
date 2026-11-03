package com.lagradost.cloudstream4.feature.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

    private fun TestScope.model(source: HomeSource) =
        HomeViewModel(source, TestScope(UnconfinedTestDispatcher(testScheduler)))

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
}

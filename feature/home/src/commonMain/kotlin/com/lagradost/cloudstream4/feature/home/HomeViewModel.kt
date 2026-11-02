package com.lagradost.cloudstream4.feature.home

import androidx.lifecycle.ViewModel
import com.lagradost.cloudstream4.compose.ActionHandler
import com.lagradost.cloudstream4.compose.DefaultEffectContainer
import com.lagradost.cloudstream4.compose.DefaultStateContainer
import com.lagradost.cloudstream4.compose.EffectContainer
import com.lagradost.cloudstream4.compose.StateContainer
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One source's main page at a time. No display strings and no navigation.
 *
 * What it exists to get right:
 *
 * - **A late reply cannot overwrite a newer choice.** Everything for a source runs under one job that
 *   choosing another cancels, and a reply that got past the last suspension checks [generation].
 * - **Sections fail alone.** Each is asked separately, so a partial page shows what arrived.
 * - **A retry keeps what is on screen,** and the pages already loaded.
 *
 * The scope comes from the host, because desktop has no `Dispatchers.Main` for `viewModelScope`.
 */
class HomeViewModel(
    private val source: HomeSource,
    private val scope: CoroutineScope,
) : ViewModel(),
    StateContainer<HomeState> by DefaultStateContainer(HomeState()),
    EffectContainer<HomeEffect> by DefaultEffectContainer(),
    ActionHandler<HomeAction> {

    /** Which choice may still write. Bumped only from [onAction]; read from replies on other threads. */
    @Volatile
    private var generation = 0

    private var work: CompletableJob = newWork()

    init {
        readSources()
    }

    override fun onAction(action: HomeAction) {
        when (action) {
            HomeAction.PickerOpened -> updateState { copy(pickerOpen = true) }
            HomeAction.PickerDismissed -> updateState { copy(pickerOpen = false) }
            is HomeAction.SourceSelected -> select(action.id)
            HomeAction.Retry -> retry()
            is HomeAction.RowMoreRequested -> showMore(action.key, action.columns)
            is HomeAction.TitleActivated -> scope.launch { postEffect { HomeEffect.OpenTitle(action.id) } }
            HomeAction.SearchRequested -> scope.launch { postEffect { HomeEffect.OpenSearch } }
        }
    }

    private fun newWork() = SupervisorJob(scope.coroutineContext[Job])

    /** Stops everything for the previous choice, and returns the token that says a reply is current. */
    private fun restart(): Int {
        work.cancel()
        work = newWork()
        return ++generation
    }

    private fun readSources() {
        val request = restart()
        updateState { copy(status = HomeStatus.Loading) }
        scope.launch(work) {
            val found = try {
                source.sources()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                updateCurrent(request) { copy(status = HomeStatus.Failed(failure.reason())) }
                return@launch
            }
            val keep = state.value.selected?.takeIf { id -> found.options.any { it.id == id } }
            val chosen = found.options.firstOrNull { it.id == keep } ?: found.options.firstOrNull()
            updateCurrent(request) {
                copy(
                    sources = found.options,
                    selected = chosen?.id,
                    status = if (chosen == null) HomeStatus.NoSources(found.installed) else HomeStatus.Loading,
                )
            }
            if (chosen != null) loadFirstPages(chosen, request)
        }
    }

    private fun select(id: HomeSourceId) {
        val current = state.value
        val option = current.sources.firstOrNull { it.id == id }
        updateState { copy(pickerOpen = false) }
        if (option == null || id == current.selected) return
        val request = restart()
        updateState { copy(selected = id, status = HomeStatus.Loading) }
        scope.launch(work) { loadFirstPages(option, request) }
    }

    private suspend fun loadFirstPages(option: HomeSourceOption, request: Int) {
        val replies = ask(option, (0 until option.sections).toList())
        val next = HomePaging.fold(emptyList(), replies, option.sections)
        updateCurrent(request) { copy(status = next) }
    }

    private fun retry() {
        val current = state.value
        val status = current.status
        val option = current.selectedOption
        when {
            status is HomeStatus.Partial -> if (option != null && !status.retrying) retryFailed(option, status)
            option == null -> readSources()
            else -> {
                val request = restart()
                updateState { copy(status = HomeStatus.Loading) }
                scope.launch(work) { loadFirstPages(option, request) }
            }
        }
    }

    /** Leaves the current generation alone: what is on screen, and its paging, are still valid. */
    private fun retryFailed(option: HomeSourceOption, partial: HomeStatus.Partial) {
        val request = generation
        updateState { copy(status = partial.copy(retrying = true)) }
        scope.launch(work) {
            val replies = ask(option, partial.failedSections)
            updateCurrent(request) {
                copy(status = HomePaging.fold(status.rows, replies, partial.total))
            }
        }
    }

    private fun showMore(key: HomeRowKey, columns: Int) {
        val current = state.value
        val selected = current.selected ?: return
        val rows = current.status.rows
        val row = rows.firstOrNull { it.key == key } ?: return
        if (row.more == HomeMore.Loading) return
        // A failed page is asked for again without showing more, because the press already did that.
        val lines = if (row.more is HomeMore.Failed) row.lines else row.lines + HomePaging.LINES_PER_PRESS
        val fetch = row.hasMore && lines * columns.coerceAtLeast(1) > row.items.size
        updateRows { list ->
            list.map {
                when {
                    it.key == key && fetch -> it.copy(lines = lines, more = HomeMore.Loading)
                    it.key == key -> it.copy(lines = lines)
                    fetch && it.key.section == key.section -> it.copy(more = HomeMore.Loading)
                    else -> it
                }
            }
        }
        if (!fetch) return
        val request = generation
        val section = key.section
        val page = row.page + 1
        scope.launch(work) {
            val reply = pageOf(selected, section, page)
            updateCurrent(request) {
                val list = status.rows
                val next = when (reply) {
                    is HomePageReply.Loaded -> HomePaging.merge(list, section, page, reply)
                    is HomePageReply.Failed -> list.failedAt(section, reply.detail)
                    HomePageReply.Offline -> list.failedAt(section, HomePaging.OFFLINE_DETAIL)
                }
                copy(status = status.withRows(next))
            }
        }
    }

    private fun List<HomeRow>.failedAt(section: Int, detail: String) =
        map { if (it.key.section == section) it.copy(more = HomeMore.Failed(detail)) else it }

    private fun updateRows(change: (List<HomeRow>) -> List<HomeRow>) =
        updateState { copy(status = status.withRows(change(status.rows))) }

    private fun updateCurrent(request: Int, change: HomeState.() -> HomeState) =
        updateState { if (request == generation) change() else this }

    /** All at once, unless the source asked for one at a time. */
    private suspend fun ask(option: HomeSourceOption, sections: List<Int>): List<Pair<Int, HomePageReply>> {
        val gap = option.sequentialDelayMillis
        return if (gap != null) {
            sections.mapIndexed { index, section ->
                if (index > 0) delay(gap)
                section to pageOf(option.id, section, HomePaging.FIRST_PAGE)
            }
        } else {
            coroutineScope {
                sections.map { section ->
                    async { section to pageOf(option.id, section, HomePaging.FIRST_PAGE) }
                }.awaitAll()
            }
        }
    }

    /** The seam reports failure as data, but a host's bug must not take the screen down with it. */
    private suspend fun pageOf(id: HomeSourceId, section: Int, page: Int): HomePageReply = try {
        source.page(id, section, page)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        HomePageReply.Failed(failure.reason())
    }

    private fun Exception.reason() = message.orEmpty().ifEmpty { this::class.simpleName.orEmpty() }
}

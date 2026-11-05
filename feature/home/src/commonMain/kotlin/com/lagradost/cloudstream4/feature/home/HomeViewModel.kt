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
import kotlinx.coroutines.withTimeoutOrNull

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
 *
 * @param memory what the person chose last, so it opens again. Only a choice of theirs is kept: falling back because
 *   that source is gone does not overwrite it, so it comes back if the source does.
 * @param history what this device has played. Read when the page opens, comes back, or its sources change, because the
 *   player and an account switch both change it from elsewhere. It is independent of the source, so a failure or being
 *   offline leaves it on screen.
 */
class HomeViewModel(
    private val source: HomeSource,
    private val scope: CoroutineScope,
    private val memory: HomeSourceMemory = HomeSourceMemory.None,
    private val history: HomeWatchHistory = HomeWatchHistory.None,
) : ViewModel(),
    StateContainer<HomeState> by DefaultStateContainer(HomeState()),
    EffectContainer<HomeEffect> by DefaultEffectContainer(),
    ActionHandler<HomeAction> {

    /** Which choice may still write. Bumped only from [onAction]; read from replies on other threads. */
    @Volatile
    private var generation = 0

    private var work: CompletableJob = newWork()

    /** The latest read of [history]. A newer one replaces it, so an older answer cannot land last. */
    private var historyRead: Job? = null

    init {
        readSources()
        readHistory()
    }

    override fun onAction(action: HomeAction) {
        when (action) {
            HomeAction.PickerOpened -> updateState { copy(pickerOpen = true) }
            HomeAction.PickerDismissed -> updateState { copy(pickerOpen = false) }
            is HomeAction.SourceSelected -> select(action.id)
            HomeAction.Retry -> retry()
            is HomeAction.RowMoreRequested -> showMore(action.key, action.columns)
            is HomeAction.TitleActivated -> {
                action.from?.let { from -> updateState { copy(returnTo = from) } }
                scope.launch { postEffect { HomeEffect.OpenTitle(action.id) } }
            }
            HomeAction.SearchRequested -> scope.launch { postEffect { HomeEffect.OpenSearch } }
            HomeAction.ExtensionsRequested -> scope.launch { postEffect { HomeEffect.OpenExtensions } }
            HomeAction.AccountsRequested -> scope.launch { postEffect { HomeEffect.OpenAccounts } }
            HomeAction.SourcesChanged -> {
                sourcesChanged()
                readHistory()
            }
            HomeAction.Reappeared -> {
                updateState { copy(appeared = true, returnTo = returnTo?.copy(ready = true)) }
                readHistory()
            }
            HomeAction.Disappeared -> updateState { copy(appeared = false) }
            HomeAction.ReturnHandled -> updateState { copy(returnTo = null) }
            HomeAction.LandingHandled -> updateState { copy(landingOwed = false) }
        }
    }

    private fun newWork() = SupervisorJob(scope.coroutineContext[Job])

    private fun readHistory() {
        historyRead?.cancel()
        historyRead = scope.launch {
            val read = try {
                history.read()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // What is on screen is the best there is, and the next read asks again.
                return@launch
            }
            updateState { copy(history = read) }
        }
    }

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
            applySources(found, request)
        }
    }

    /** Asks again, leaving everything as it is unless the answer changes what should be on screen. */
    private fun sourcesChanged() {
        val request = generation
        scope.launch(work) {
            val found = try {
                source.sources()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // What is on screen is still the best there is, and the next change asks again.
                return@launch
            }
            if (request == generation) applySources(found, request)
        }
    }

    /**
     * Decides which source shows.
     *
     * The remembered source wins over one the page fell back to. Plugins arrive in stages, so a fallback may already be
     * showing when the source the person chose turns up, and it must not then outlive that choice.
     *
     * While the host is still loading, a remembered source that is missing is waited for, rather than fetching some
     * other source's page only to throw it away, and "nothing here" is not said yet.
     */
    private fun applySources(found: HomeSources, request: Int) {
        val current = state.value
        val remembered = memory.last()
        val offered = { id: HomeSourceId? -> found.options.any { it.id == id } }
        val chosen = when {
            offered(remembered) -> remembered
            offered(current.selected) -> current.selected
            else -> found.options.firstOrNull()?.id
        }
        val waiting = found.stillLoading && current.status.rows.isEmpty() &&
            (chosen == null || (remembered != null && !offered(remembered)))
        when {
            waiting -> updateCurrent(request) { copy(sources = found.options, selected = null, status = HomeStatus.Loading) }
            chosen == null -> updateCurrent(request) {
                copy(sources = found.options, selected = null, status = HomeStatus.NoSources(found.installed))
            }
            chosen == current.selected && !current.status.wantsReload -> updateCurrent(request) {
                copy(sources = found.options)
            }
            else -> {
                val next = restart()
                updateState {
                    copy(sources = found.options, selected = chosen, status = HomeStatus.Loading, returnTo = null)
                }
                val option = found.options.first { it.id == chosen }
                scope.launch(work) { loadFirstPages(option, next) }
            }
        }
    }

    /** Rows, or a first page on its way, stay. Anything else was the source's last word, and is worth asking again. */
    private val HomeStatus.wantsReload: Boolean
        get() = this is HomeStatus.NoSources || this is HomeStatus.Failed || this is HomeStatus.Offline ||
            this is HomeStatus.Empty

    private fun select(id: HomeSourceId) {
        val current = state.value
        val option = current.sources.firstOrNull { it.id == id }
        updateState { copy(pickerOpen = false) }
        if (option == null) return
        memory.remember(id)
        if (id == current.selected) return
        val request = restart()
        updateState { copy(selected = id, status = HomeStatus.Loading, returnTo = null) }
        scope.launch(work) { loadFirstPages(option, request) }
    }

    private suspend fun loadFirstPages(option: HomeSourceOption, request: Int) {
        val replies = ask(option, (0 until option.sections).toList())
        val next = HomePaging.fold(emptyList(), replies, option.sections)
        val rows = next.rows
        updateCurrent(request) { copy(status = next, hero = if (rows.isEmpty()) HomeHero.None else HomeHero.Loading) }
        if (rows.isNotEmpty()) loadHero(rows, request)
    }

    /**
     * The first of the source's first titles whose own page answers, as the old home's hero takes the titles that loaded
     * (`HomeViewModel.kt:314` in `:app`). One at a time, so a page that answers costs one request and not three.
     *
     * Bounded as a whole, because focus waits for this, and a source that never answers must not hold it.
     */
    private suspend fun loadHero(rows: List<HomeRow>, request: Int) {
        val candidates = rows.asSequence()
            .flatMap { row -> row.items.asSequence().map { item -> item to row.title } }
            .distinctBy { (item, _) -> item.id }
            .take(HERO_CANDIDATES)
            .toList()
        val shown = withTimeoutOrNull(HERO_WAIT_MILLIS) {
            candidates.firstNotNullOfOrNull { (item, rowName) ->
                featuredOf(item)?.let { HomeHero.Shown(it, eyebrow = rowName) }
            }
        }
        updateCurrent(request) { copy(hero = shown ?: HomeHero.None) }
    }

    /** Like [pageOf]: a host's bug is no hero rather than no screen. */
    private suspend fun featuredOf(item: HomeItem): HomeFeatured? = try {
        source.featured(item)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
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

    private companion object {
        /** The old home asks for three. */
        const val HERO_CANDIDATES = 3

        /** Past this the page goes on without a hero, and focus lands on what is there. */
        const val HERO_WAIT_MILLIS = 6_000L
    }
}

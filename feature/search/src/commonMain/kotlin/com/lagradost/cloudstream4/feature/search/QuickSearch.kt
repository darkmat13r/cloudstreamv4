package com.lagradost.cloudstream4.feature.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream4.compose.ActionHandler
import com.lagradost.cloudstream4.compose.DefaultEffectContainer
import com.lagradost.cloudstream4.compose.DefaultStateContainer
import com.lagradost.cloudstream4.compose.EffectContainer
import com.lagradost.cloudstream4.compose.StateContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Where artboard **8d**'s rows come from: one search for one query, over a set of sources the caller fixed, reported
 * each time a source answers. That is what lets the panel say `3 OF 12 ANSWERED · 2 FAILED` while it is still filling.
 *
 * A separate seam from [SearchSource] because the panel has no field, no chips and no source picker. The caller
 * chooses the sources (the old `QuickSearchFragment.pushSearch` takes them as an argument) and the query.
 */
fun interface QuickSearchSource {
    /**
     * Emits after every answer, each time with everything so far. The last emission has
     * [QuickSearchProgress.finished]. Never throws: a source that fails is counted in [QuickSearchProgress.failed].
     */
    fun search(query: String): Flow<QuickSearchProgress>
}

/**
 * The panel's numbers and rows so far.
 *
 * @property items every answered source's results, in the order the sources were asked.
 * @property offline every source failed and every failure was the network's.
 */
@Immutable
data class QuickSearchProgress(
    val items: List<SearchResultItem>,
    val answered: Int,
    val failed: Int,
    val total: Int,
    val offline: Boolean = false,
) {
    val finished: Boolean get() = answered + failed >= total
}

/**
 * @property progress `null` until the first source answers, or when there is nothing to search.
 */
@Immutable
data class QuickSearchState(
    val query: String = "",
    val progress: QuickSearchProgress? = null,
)

sealed interface QuickSearchAction {
    /** 8d's `Done`, and BACK. */
    data object Done : QuickSearchAction
    data class ResultActivated(val id: SearchResultId) : QuickSearchAction

    /** 8e's `Open full search`. */
    data object FullSearchRequested : QuickSearchAction

    /** Every source failed: ask them all again. */
    data object Retry : QuickSearchAction
}

sealed interface QuickSearchEffect {
    data object Close : QuickSearchEffect
    data class OpenResult(val id: SearchResultId) : QuickSearchEffect
    data class OpenFullSearch(val query: String) : QuickSearchEffect
}

/**
 * The panel's state: one search, started when the panel is made.
 *
 * [scope] is a parameter for the reason `SearchViewModel` gives: the default is `viewModelScope`, and a host or a test
 * that owns a scope can pass it.
 */
class QuickSearchViewModel(
    query: String,
    private val source: QuickSearchSource,
    private val scope: CoroutineScope? = null,
) : ViewModel(),
    StateContainer<QuickSearchState> by DefaultStateContainer(QuickSearchState(query = query.trim())),
    EffectContainer<QuickSearchEffect> by DefaultEffectContainer(),
    ActionHandler<QuickSearchAction> {

    private val work: CoroutineScope get() = scope ?: viewModelScope
    private var running: Job? = null

    init {
        start()
    }

    override fun onAction(action: QuickSearchAction) {
        when (action) {
            QuickSearchAction.Done -> work.launch { postEffect { QuickSearchEffect.Close } }
            is QuickSearchAction.ResultActivated -> work.launch { postEffect { QuickSearchEffect.OpenResult(action.id) } }
            QuickSearchAction.FullSearchRequested -> {
                val query = state.value.query
                work.launch { postEffect { QuickSearchEffect.OpenFullSearch(query) } }
            }
            QuickSearchAction.Retry -> start()
        }
    }

    private fun start() {
        val query = state.value.query
        running?.cancel()
        updateState { copy(progress = null) }
        if (query.isBlank()) return
        running = work.launch {
            source.search(query).collect { progress -> updateState { copy(progress = progress) } }
        }
    }
}

package com.lagradost.cloudstream4.feature.detail

import androidx.lifecycle.ViewModel
import com.lagradost.cloudstream4.compose.ActionHandler
import com.lagradost.cloudstream4.compose.DefaultEffectContainer
import com.lagradost.cloudstream4.compose.DefaultStateContainer
import com.lagradost.cloudstream4.compose.EffectContainer
import com.lagradost.cloudstream4.compose.StateContainer
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * One title's page.
 *
 * The scope comes from the host, because desktop has no `Dispatchers.Main` for `viewModelScope`
 * (`Main.kt`).
 *
 * @param id opaque to this module; the value [DetailSource] was given to resolve.
 * @param playBestAfter when set, asking to play also plays: the best link found so far ([bestFirst]) once this long
 *   has passed since the first one arrived, or as soon as the search ends if that is sooner. Once per request, and
 *   not at all if someone chose a row first. Null leaves the choice to the person.
 */
class DetailViewModel(
    private val id: String,
    private val source: DetailSource,
    private val scope: CoroutineScope,
    private val playBestAfter: Duration? = null,
) : ViewModel(),
    StateContainer<DetailState> by DefaultStateContainer(DetailState()),
    EffectContainer<DetailEffect> by DefaultEffectContainer(),
    ActionHandler<DetailAction> {

    private var linksJob: Job? = null
    private var lastData: String? = null

    /** Which request may still write. A finished job cannot be cancelled, so a late result checks this. */
    private var linksRequest = 0

    /**
     * The last request something has played for, by itself or by someone's choice. A state flow for its
     * `compareAndSet`: the wait ends on another thread from the one a click arrives on.
     */
    private val playedRequest = MutableStateFlow(0)

    init {
        load()
    }

    override fun onAction(action: DetailAction) {
        when (action) {
            DetailAction.Retry -> load()
            is DetailAction.PlayRequested -> requestLinks(action.data)
            DetailAction.RetryLinks -> lastData?.let(::requestLinks)
            is DetailAction.LinkActivated -> {
                // The search goes on, as it does on Android: its later subtitles are for this stream too, and
                // its later links are on the list when someone comes back to it.
                // A choice made inside the wait is the choice: nothing then plays over it.
                playedRequest.value = linksRequest
                scope.launch { play(action.link) }
            }
            // Cancelled, so a slow extractor that answers later cannot reopen the list.
            DetailAction.LinksDismissed -> {
                stopLinks()
                updateState { copy(links = null) }
            }
        }
    }

    private fun load() {
        updateState { copy(content = DetailLoad.Loading) }
        scope.launch {
            val next = attempt<TitleDetail> { DetailLoad.Loaded(source.details(id)) }
            updateState { copy(content = next) }
        }
    }

    private fun stopLinks() {
        linksRequest++
        linksJob?.cancel()
        updateState { copy(linksArriving = false) }
    }

    private fun requestLinks(data: String) {
        lastData = data
        val request = ++linksRequest
        linksJob?.cancel()
        updateState { copy(links = DetailLoad.Loading, linksArriving = true) }
        linksJob = scope.launch {
            var latest = emptyList<StreamLink>()
            val ending = attempt<List<StreamLink>> {
                // The wait is a child of this job, so dismissing the list or asking again ends it as well.
                coroutineScope {
                    var wait: Job? = null
                    source.links(id, data).collect { links ->
                        latest = links
                        updateState { if (request == linksRequest) copy(links = DetailLoad.Loaded(links)) else this }
                        if (playBestAfter != null && wait == null && links.isNotEmpty()) {
                            wait = launch {
                                delay(playBestAfter)
                                // From the state, which another thread can read safely, and not from `latest`.
                                playBest(request, (state.value.links as? DetailLoad.Loaded)?.value.orEmpty())
                            }
                        }
                    }
                    wait?.cancel()
                }
                if (latest.isEmpty()) DetailLoad.Empty else DetailLoad.Loaded(latest)
            }
            // Streams on the screen are worth more than the reason the rest never came.
            val next = if (ending is DetailLoad.Failed && latest.isNotEmpty()) DetailLoad.Loaded(latest) else ending
            updateState { if (request == linksRequest) copy(links = next, linksArriving = false) else this }
            // Ended inside the wait: there is nothing better coming to wait for.
            if (playBestAfter != null) playBest(request, latest)
        }
    }

    /** Plays the best of [found], unless [request] is no longer the one asked for, or has already played. */
    private suspend fun playBest(request: Int, found: List<StreamLink>) {
        val best = found.bestFirst().firstOrNull() ?: return
        val played = playedRequest.value
        if (request == linksRequest && played != request && playedRequest.compareAndSet(played, request)) play(best)
    }

    private suspend fun play(link: StreamLink) {
        val title = (state.value.content as? DetailLoad.Loaded)?.value?.title.orEmpty()
        postEffect { DetailEffect.Play(title, link) }
    }

    private inline fun <T> attempt(work: () -> DetailLoad<T>): DetailLoad<T> = try {
        work()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        DetailLoad.Failed(failure.message.orEmpty().ifEmpty { failure::class.simpleName.orEmpty() })
    }
}

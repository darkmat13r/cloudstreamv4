package com.lagradost.cloudstream4.feature.downloads.series

import androidx.lifecycle.ViewModel
import com.lagradost.cloudstream4.compose.ActionHandler
import com.lagradost.cloudstream4.compose.DefaultEffectContainer
import com.lagradost.cloudstream4.compose.DefaultStateContainer
import com.lagradost.cloudstream4.compose.EffectContainer
import com.lagradost.cloudstream4.compose.StateContainer
import com.lagradost.cloudstream4.core.connectivity.Connectivity
import com.lagradost.cloudstream4.core.connectivity.ConnectivityFlow
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One series' downloads page (`TV Episodes Downloads` 6d): the source's episodes, the network, and where focus goes back
 * to. The scope comes from the host, as in `DownloadsViewModel`.
 */
class DownloadSeriesViewModel(
    private val source: DownloadSeriesSource,
    connectivity: ConnectivityFlow,
    private val scope: CoroutineScope,
) : ViewModel(),
    StateContainer<SeriesState> by DefaultStateContainer(SeriesState()),
    EffectContainer<SeriesEffect> by DefaultEffectContainer(),
    ActionHandler<SeriesAction> {

    private var reading: Job? = null

    /** Whether the page has shown an episode: an empty page that has, closes, and one that never has, says so. */
    private var hadEpisodes = false

    init {
        scope.launch {
            connectivity.status.collect { status ->
                updateState { copy(offline = status == Connectivity.OFFLINE) }
            }
        }
        read()
    }

    override fun onAction(action: SeriesAction) {
        when (action) {
            is SeriesAction.PlayRequested -> {
                remember(SeriesStop.Episode(action.id, action.from), action.firstVisibleIndex)
                post(SeriesEffect.Play(action.id))
            }

            is SeriesAction.StateVerbRequested -> verb(action.id)
            is SeriesAction.DeleteRequested -> {
                remember(SeriesStop.Episode(action.id, SeriesVerb.Delete), action.firstVisibleIndex)
                post(SeriesEffect.ConfirmDelete(action.id))
            }

            SeriesAction.PlayNextRequested -> SeriesShelf.next(state.value.episodes)?.let {
                remember(SeriesStop.PlayNext)
                post(SeriesEffect.Play(it.id))
            }

            SeriesAction.DeleteAllRequested -> state.value.episodes.takeIf { it.isNotEmpty() }?.let { episodes ->
                remember(SeriesStop.DeleteAll)
                post(SeriesEffect.ConfirmDeleteAll(episodes.map { it.id }))
            }

            SeriesAction.RetryFailedRequested -> state.value.episodes
                .filter { it.state == DownloadEpisodeState.Failed }
                .forEach { source.resume(it.id) }

            SeriesAction.Reappeared -> {
                updateState { copy(returnTo = returnTo?.copy(ready = true)) }
                source.refresh()
            }

            SeriesAction.ReturnHandled -> updateState { copy(returnTo = null) }
            SeriesAction.BackRequested -> post(SeriesEffect.Close)
            SeriesAction.Retry -> {
                updateState { copy(status = SeriesStatus.Loading) }
                source.refresh()
                read()
            }
        }
    }

    /** Column B: what it does follows the row's state, and a finished row has none. */
    private fun verb(id: DownloadEpisodeId) {
        val episode = state.value.episodes.firstOrNull { it.id == id } ?: return
        when (episode.state) {
            DownloadEpisodeState.Running -> source.pause(id)
            DownloadEpisodeState.Paused, DownloadEpisodeState.Failed -> source.resume(id)
            DownloadEpisodeState.Queued -> source.cancel(id)
            DownloadEpisodeState.Done -> Unit
        }
    }

    private fun remember(stop: SeriesStop, firstVisibleIndex: Int = 0) =
        updateState { copy(returnTo = SeriesReturn(stop, firstVisibleIndex)) }

    private fun post(effect: SeriesEffect) {
        scope.launch { postEffect { effect } }
    }

    private fun read() {
        reading?.cancel()
        reading = scope.launch {
            try {
                source.contents.collect(::show)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val detail = failure.message.orEmpty().ifEmpty { failure::class.simpleName.orEmpty() }
                updateState { copy(status = SeriesStatus.Failed(detail)) }
            }
        }
    }

    private fun show(contents: DownloadSeriesContents) {
        val episodes = SeriesShelf.sorted(contents.episodes)
        if (episodes.isEmpty() && hadEpisodes) post(SeriesEffect.Close)
        if (episodes.isNotEmpty()) hadEpisodes = true
        updateState {
            copy(
                name = contents.name,
                status = if (episodes.isEmpty()) SeriesStatus.Empty else SeriesStatus.Showing(episodes),
            )
        }
    }
}

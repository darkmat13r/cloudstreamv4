package com.lagradost.cloudstream4.feature.detail

import androidx.lifecycle.ViewModel
import com.lagradost.cloudstream4.compose.ActionHandler
import com.lagradost.cloudstream4.compose.DefaultEffectContainer
import com.lagradost.cloudstream4.compose.DefaultStateContainer
import com.lagradost.cloudstream4.compose.EffectContainer
import com.lagradost.cloudstream4.compose.StateContainer
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One title's page.
 *
 * The scope comes from the host, because desktop has no `Dispatchers.Main` for `viewModelScope`
 * (`Main.kt`).
 *
 * @param id opaque to this module; the value [DetailSource] was given to resolve.
 */
class DetailViewModel(
    private val id: String,
    private val source: DetailSource,
    private val scope: CoroutineScope,
) : ViewModel(),
    StateContainer<DetailState> by DefaultStateContainer(DetailState()),
    EffectContainer<DetailEffect> by DefaultEffectContainer(),
    ActionHandler<DetailAction> {

    private var linksJob: Job? = null
    private var lastData: String? = null

    /** Which request may still write. A finished job cannot be cancelled, so a late result checks this. */
    private var linksRequest = 0

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
                scope.launch {
                    val title = (state.value.content as? DetailLoad.Loaded)?.value?.title.orEmpty()
                    postEffect { DetailEffect.Play(title, action.link) }
                }
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
                source.links(id, data).collect { links ->
                    latest = links
                    updateState { if (request == linksRequest) copy(links = DetailLoad.Loaded(links)) else this }
                }
                if (latest.isEmpty()) DetailLoad.Empty else DetailLoad.Loaded(latest)
            }
            // Streams on the screen are worth more than the reason the rest never came.
            val next = if (ending is DetailLoad.Failed && latest.isNotEmpty()) DetailLoad.Loaded(latest) else ending
            updateState { if (request == linksRequest) copy(links = next, linksArriving = false) else this }
        }
    }

    private inline fun <T> attempt(work: () -> DetailLoad<T>): DetailLoad<T> = try {
        work()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        DetailLoad.Failed(failure.message.orEmpty().ifEmpty { failure::class.simpleName.orEmpty() })
    }
}

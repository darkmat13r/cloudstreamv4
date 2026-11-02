package com.lagradost.cloudstream4.feature.detail

import androidx.lifecycle.ViewModel
import com.lagradost.cloudstream4.compose.ActionHandler
import com.lagradost.cloudstream4.compose.DefaultEffectContainer
import com.lagradost.cloudstream4.compose.DefaultStateContainer
import com.lagradost.cloudstream4.compose.EffectContainer
import com.lagradost.cloudstream4.compose.StateContainer
import com.lagradost.cloudstream4.core.designsystem.state.UiState
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
    private val prompts: DetailPrompts,
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
            is DetailAction.LinkActivated -> scope.launch {
                val title = (state.value.content as? UiState.Content)?.value?.title.orEmpty()
                postEffect { DetailEffect.Play(title, action.link) }
            }
            // Cancelled, so a slow extractor that answers later cannot reopen the list.
            DetailAction.LinksDismissed -> {
                linksRequest++
                linksJob?.cancel()
                updateState { copy(links = null) }
            }
        }
    }

    private fun load() {
        updateState { copy(content = UiState.Loading) }
        scope.launch {
            val next = attempt(
                work = { UiState.Content(source.details(id)) },
                failed = { message -> prompts.failed(message) { onAction(DetailAction.Retry) } },
            )
            updateState { copy(content = next) }
        }
    }

    private fun requestLinks(data: String) {
        lastData = data
        val request = ++linksRequest
        linksJob?.cancel()
        updateState { copy(links = UiState.Loading) }
        linksJob = scope.launch {
            val retry = { lastData?.let(::requestLinks); Unit }
            val next = attempt<UiState<List<StreamLink>>>(
                work = {
                    val links = source.links(id, data)
                    if (links.isEmpty()) prompts.noLinks(retry) else UiState.Content(links)
                },
                failed = { message -> prompts.linksFailed(message, retry) },
            )
            updateState { if (request == linksRequest) copy(links = next) else this }
        }
    }

    private inline fun <T> attempt(work: () -> T, failed: (String) -> T): T = try {
        work()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        failed(failure.message.orEmpty().ifEmpty { failure::class.simpleName.orEmpty() })
    }
}

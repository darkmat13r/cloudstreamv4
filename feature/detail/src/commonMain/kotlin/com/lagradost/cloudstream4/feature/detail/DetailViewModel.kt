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

            // The host's own player. Nothing is read, so there is no loading state and no failure: the
            // page is left exactly as it is, under the screen that is about to cover it.
            is DetailAction.PlaybackRequested -> {
                updateState { copy(returnTo = DetailReturn(playbackTarget(action.data))) }
                scope.launch { postEffect { DetailEffect.OpenPlayer(action.data, action.fromStart) } }
            }

            // The trailer plays over the page and focus comes back to the primary button, which is where
            // both boards put entry focus. `DetailFocus` has no stop of its own for the trailer button.
            DetailAction.TrailerRequested -> {
                updateState { copy(returnTo = DetailReturn(DetailFocus.Primary)) }
                scope.launch { postEffect { DetailEffect.PlayTrailer } }
            }

            is DetailAction.RecommendationActivated -> {
                updateState { copy(returnTo = DetailReturn(DetailFocus.Recommendations)) }
                scope.launch { postEffect { DetailEffect.OpenTitle(action.id) } }
            }

            // Re-read, because a bookmark or a favourite may have been changed on another screen while this
            // one was covered. The page keeps what it has until the answer lands, so nothing flickers.
            DetailAction.Reappeared -> {
                updateState { copy(returnTo = returnTo?.copy(ready = true)) }
                scope.launch {
                    val next = attempt<TitleDetail> { DetailLoad.Loaded(source.details(id)) }
                    // A failure here is not worth replacing a page that is already on screen with an error.
                    if (next is DetailLoad.Loaded) updateState { copy(content = next) }
                }
            }

            DetailAction.ReturnHandled -> updateState { copy(returnTo = null) }

            is DetailAction.WatchStatusPicked -> {
                // Picking is the commit on a single-choice panel (DEC-17), so it also closes it.
                updateState { withDetail { copy(watchStatus = action.status) }.copy(watchStatusPicking = false) }
                write { source.setWatchStatus(id, action.status) }
            }

            DetailAction.WatchStatusPickerOpened -> updateState { copy(watchStatusPicking = true) }

            // Closes whichever is up. Not a toggle: a host's back callback does not know which.
            DetailAction.PanelDismissed ->
                updateState { copy(synopsisOpen = false, watchStatusPicking = false) }

            DetailAction.FavouriteToggled -> {
                val next = state.value.detail?.isFavourite?.not() ?: return
                updateState { withDetail { copy(isFavourite = next) } }
                write { source.setFavourite(id, next) }
            }

            DetailAction.SynopsisToggled -> updateState { copy(synopsisOpen = !synopsisOpen) }

            is DetailAction.CastMemberActivated -> updateState {
                copy(
                    swappedRoles = if (action.index in swappedRoles) swappedRoles - action.index
                    else swappedRoles + action.index,
                )
            }
        }
    }

    /**
     * Which stop the page comes back to after playing [data].
     *
     * The action carries no origin, so it is read from the data: the primary button plays
     * [TitleDetail.primaryData] and nothing else does, so anything else came from an episode row.
     */
    private fun playbackTarget(data: String): DetailFocus =
        if (data == state.value.detail?.primaryData) DetailFocus.Primary else DetailFocus.Episodes

    /**
     * Writes a watch list or a favourite, having already shown it.
     *
     * The cell is redrawn before the write so the press is answered at once, and a throw is swallowed
     * rather than replacing a loaded page with an error: a failed bookmark is not a failed title. The
     * next [DetailAction.Reappeared] re-reads the truth.
     */
    private fun write(work: suspend () -> Unit) {
        scope.launch {
            try {
                work()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // See above.
            }
        }
    }

    /** [change] applied to the loaded title, or the state untouched when none is loaded. */
    private inline fun DetailState.withDetail(change: TitleDetail.() -> TitleDetail): DetailState {
        val loaded = (content as? DetailLoad.Loaded)?.value ?: return this
        return copy(content = DetailLoad.Loaded(loaded.change()))
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

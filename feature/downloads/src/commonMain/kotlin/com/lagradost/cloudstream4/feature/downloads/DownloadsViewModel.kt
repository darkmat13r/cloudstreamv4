package com.lagradost.cloudstream4.feature.downloads

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
 * The downloads screen's state machine: the source's contents, the network, and what is being chosen for deleting.
 *
 * Choosing lives here rather than in the source, because nothing but this screen ever asks what is chosen. The
 * scope comes from the host, as in `LibraryViewModel`.
 */
class DownloadsViewModel(
    private val source: DownloadsSource,
    connectivity: ConnectivityFlow,
    private val scope: CoroutineScope,
) : ViewModel(),
    StateContainer<DownloadsState> by DefaultStateContainer(DownloadsState()),
    EffectContainer<DownloadsEffect> by DefaultEffectContainer(),
    ActionHandler<DownloadsAction> {

    private var reading: Job? = null

    init {
        scope.launch {
            connectivity.status.collect { status ->
                // `UNKNOWN` is not offline: the seam's own rule.
                updateState { copy(offline = status == Connectivity.OFFLINE) }
            }
        }
        read()
    }

    override fun onAction(action: DownloadsAction) {
        when (action) {
            is DownloadsAction.TitleActivated -> activate(action)
            is DownloadsAction.ResumeTitle -> source.resume(action.id)
            DownloadsAction.QueueRequested -> leaveFor(DownloadsStop.Queue, DownloadsEffect.OpenQueue)
            DownloadsAction.PlayFromUrlRequested -> leaveFor(DownloadsStop.PlayFromUrl, DownloadsEffect.OpenPlayFromUrl)
            DownloadsAction.SelectionStarted -> updateState { copy(selection = emptySet()) }
            DownloadsAction.FreeUpSpace ->
                if (state.value.titles.isEmpty()) {
                    post(DownloadsEffect.OpenStorageSettings)
                } else {
                    updateState { copy(selection = emptySet()) }
                }

            DownloadsAction.SelectionCancelled -> updateState { copy(selection = null) }
            DownloadsAction.SelectAllToggled -> updateState {
                val all = titles.map { it.id }.toSet()
                copy(selection = if (selection == all) emptySet() else all)
            }

            DownloadsAction.DeleteSelected -> state.value.selection?.takeIf { it.isNotEmpty() }?.let {
                post(DownloadsEffect.ConfirmDelete(it))
            }

            DownloadsAction.LibraryRequested -> post(DownloadsEffect.OpenLibrary)
            DownloadsAction.ReturnHandled -> updateState { copy(returnTo = null) }
            DownloadsAction.Reappeared -> {
                updateState { copy(returnTo = returnTo?.copy(ready = true)) }
                source.refresh()
            }

            DownloadsAction.Retry -> {
                updateState { copy(status = DownloadsStatus.Loading) }
                source.refresh()
                read()
            }
        }
    }

    private fun activate(action: DownloadsAction.TitleActivated) {
        val chosen = state.value.selection
        if (chosen == null) {
            updateState { copy(returnTo = DownloadsReturn(DownloadsStop.Title(action.id), action.firstVisibleIndex)) }
            post(DownloadsEffect.OpenTitle(action.id))
        } else {
            updateState { copy(selection = if (action.id in chosen) chosen - action.id else chosen + action.id) }
        }
    }

    private fun leaveFor(stop: DownloadsStop, effect: DownloadsEffect) {
        updateState { copy(returnTo = DownloadsReturn(stop)) }
        post(effect)
    }

    private fun post(effect: DownloadsEffect) {
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
                updateState { copy(status = DownloadsStatus.Failed(detail), selection = null) }
            }
        }
    }

    private fun show(contents: DownloadsContents) = updateState {
        copy(
            status = if (contents.titles.isEmpty()) DownloadsStatus.Empty else DownloadsStatus.Showing(contents.titles),
            storage = contents.storage,
            queue = contents.queue,
            storageFull = contents.storageFull,
            selection = selection?.prunedTo(contents.titles),
        )
    }

    /** Chosen rows that have gone are no longer chosen, and choosing ends when the last chosen one has gone. */
    private fun Set<DownloadTitleId>.prunedTo(titles: List<DownloadTitle>): Set<DownloadTitleId>? {
        if (titles.isEmpty()) return null
        val ids = titles.map { it.id }.toSet()
        val kept = this intersect ids
        return if (isNotEmpty() && kept.isEmpty()) null else kept
    }
}

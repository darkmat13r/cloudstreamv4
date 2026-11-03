package com.lagradost.cloudstream4.feature.library

import androidx.lifecycle.ViewModel
import com.lagradost.cloudstream4.compose.ActionHandler
import com.lagradost.cloudstream4.compose.DefaultEffectContainer
import com.lagradost.cloudstream4.compose.DefaultStateContainer
import com.lagradost.cloudstream4.compose.EffectContainer
import com.lagradost.cloudstream4.compose.StateContainer
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The library's state machine: one list at a time, out of what the store holds.
 *
 * Both halves of the store are read together and kept, so changing list is instant and does not touch the
 * disk. A write re-reads, because the store is the only thing that knows what it now holds — the rows are
 * sorted and pruned there, and guessing the result of a write is how two sources of truth start.
 *
 * The scope comes from the host, because desktop has no `Dispatchers.Main` for `viewModelScope`.
 */
class LibraryViewModel(
    private val store: LibraryStore,
    private val scope: CoroutineScope,
) : ViewModel(),
    StateContainer<LibraryState> by DefaultStateContainer(LibraryState()),
    EffectContainer<LibraryEffect> by DefaultEffectContainer(),
    ActionHandler<LibraryAction> {

    /** Which read may still write. Bumped only from [onAction]; read from a coroutine. */
    @Volatile
    private var generation = 0

    private var titles: List<LibraryTitle> = emptyList()
    private var positions: List<WatchPosition> = emptyList()

    init {
        read()
    }

    override fun onAction(action: LibraryAction) {
        when (action) {
            is LibraryAction.TabSelected -> showTab(action.tab)
            is LibraryAction.TitleActivated -> scope.launch { postEffect { LibraryEffect.OpenTitle(action.id) } }
            is LibraryAction.ResumeActivated -> resume(action.id)
            is LibraryAction.PickerOpened -> updateState { copy(picking = action.id) }
            LibraryAction.PickerDismissed -> updateState { copy(picking = null) }
            is LibraryAction.BucketChosen -> choose(action.id, action.bucket)
            is LibraryAction.RemoveRequested -> remove(action.id)
            LibraryAction.SearchRequested -> scope.launch { postEffect { LibraryEffect.OpenSearch } }
            LibraryAction.Refresh, LibraryAction.Retry -> read()
        }
    }

    private fun showTab(tab: LibraryTab) = updateState {
        // Loading would blink a skeleton over a list already read; the cards for the new tab are known.
        if (status is LibraryStatus.Loading) copy(tab = tab) else copy(tab = tab, status = statusFor(tab))
    }

    private fun resume(id: LibraryTitleId) {
        val resume = LibraryShelf.cards(state.value.tab, titles, positions).firstOrNull { it.id == id }?.resume
            ?: return
        scope.launch { postEffect { LibraryEffect.ResumeTitle(id, resume) } }
    }

    /**
     * On a bucket tab, out of every list. On continue watching, off that list.
     *
     * Two things live under one control because that is what the card offers in each place, and a Remove
     * that meant "out of the list you are looking at" everywhere else would mean nothing here — continue
     * watching is derived, so there is no membership to drop.
     */
    private fun remove(id: LibraryTitleId) {
        val request = ++generation
        val continueWatching = state.value.tab == LibraryTab.ContinueWatching
        updateState { copy(picking = null) }
        scope.launch {
            write { if (continueWatching) store.clearPositions(id) else store.setBucket(id, null) }
            load(request)
        }
    }

    /** Puts a title in a list. Closes the picker at once: the write and the read follow. */
    private fun choose(id: LibraryTitleId, bucket: LibraryBucket) {
        val request = ++generation
        updateState { copy(picking = null) }
        scope.launch {
            write { store.setBucket(id, bucket) }
            load(request)
        }
    }

    private fun read() {
        val request = ++generation
        updateState { if (status is LibraryStatus.Showing) this else copy(status = LibraryStatus.Loading) }
        scope.launch { load(request) }
    }

    private suspend fun load(request: Int) {
        val read = runCatching { store.titles() to store.positions() }
        val failure = read.exceptionOrNull()
        if (failure is CancellationException) throw failure
        if (request != generation) return
        val (loadedTitles, loadedPositions) = read.getOrElse {
            updateState { copy(status = LibraryStatus.Failed(it.reason())) }
            return
        }
        titles = loadedTitles
        positions = loadedPositions
        updateState { copy(status = statusFor(tab)) }
    }

    private fun statusFor(tab: LibraryTab): LibraryStatus {
        val cards = LibraryShelf.cards(tab, titles, positions)
        return if (cards.isEmpty()) LibraryStatus.Empty else LibraryStatus.Showing(cards)
    }

    /**
     * Runs a write and lets it fail quietly.
     *
     * Quietly because the read that always follows says what the store now holds, which is the only
     * honest answer: a write that failed leaves the row on screen rather than reporting a change that
     * did not happen.
     */
    private suspend fun write(change: suspend () -> Unit) {
        try {
            change()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // See above.
        }
    }

    private fun Throwable.reason() = message.orEmpty().ifEmpty { this::class.simpleName.orEmpty() }
}

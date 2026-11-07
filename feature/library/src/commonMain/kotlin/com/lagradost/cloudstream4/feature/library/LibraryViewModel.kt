package com.lagradost.cloudstream4.feature.library

import androidx.lifecycle.ViewModel
import com.lagradost.cloudstream4.compose.ActionHandler
import com.lagradost.cloudstream4.compose.DefaultEffectContainer
import com.lagradost.cloudstream4.compose.DefaultStateContainer
import com.lagradost.cloudstream4.compose.EffectContainer
import com.lagradost.cloudstream4.compose.StateContainer
import com.lagradost.cloudstream4.core.connectivity.Connectivity
import com.lagradost.cloudstream4.core.connectivity.ConnectivityFlow
import com.lagradost.cloudstream4.core.connectivity.UnknownConnectivityFlow
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The library's state machine: one list at a time, out of what the store holds.
 *
 * Both halves of the store are read together and kept, so changing list is instant and does not touch the
 * disk. A write re-reads, because the store is the only thing that knows what it now holds — the rows are
 * sorted and pruned there, and guessing the result of a write is how two sources of truth start.
 *
 * The scope comes from the host, because desktop has no `Dispatchers.Main` for `viewModelScope`.
 *
 * @param initialTab which list opens first. The TV boards draw no Continue watching, so that screen opens
 *   on its first list. The default is what the desktop window opens on.
 * @param sources where the library can also come from, a tracking service's lists (`TV Library` 5e). Null, the
 *   default, is a screen that shows only [store], which is desktop's: nothing about it changes.
 * @param connectivity whether a tracking service can be read at all (`KMP-51`). Offline, a service shows the read held
 *   from earlier in this session, or says it needs a connection, and is not asked. The default never says offline.
 */
class LibraryViewModel(
    private val store: LibraryStore,
    private val scope: CoroutineScope,
    private val initialTab: LibraryTab = LibraryTab.ContinueWatching,
    private val sources: LibrarySources? = null,
    private val connectivity: ConnectivityFlow = UnknownConnectivityFlow,
) : ViewModel(),
    StateContainer<LibraryState> by DefaultStateContainer(LibraryState(tab = initialTab)),
    EffectContainer<LibraryEffect> by DefaultEffectContainer(),
    ActionHandler<LibraryAction> {

    /** Which read may still write. Bumped only from [onAction]; read from a coroutine. */
    @Volatile
    private var generation = 0

    private var titles: List<LibraryTitle> = emptyList()
    private var positions: List<WatchPosition> = emptyList()

    /** The shown tracking service's lists, or null while the local list is shown. */
    private var synced: List<SyncedList>? = null

    /**
     * Each service's last good read **in this session**, shown again where a new one fails or cannot be tried (5b). In
     * memory only: a service's lists are the user's account on someone else's site, and nothing here writes them down.
     */
    private val held = mutableMapOf<LibrarySourceId, List<SyncedList>>()

    init {
        read()
        scope.launch {
            // Latest only: a flap cancels the wait, so one return to ONLINE that holds is one read, not one per flap.
            connectivity.status.collectLatest { status ->
                if (status == Connectivity.ONLINE) {
                    delay(RECONNECT_SETTLE_MS)
                    reconnected()
                }
            }
        }
    }

    /**
     * Back online: read the shown service again, where what it shows is held or it said it needed a connection. Only the
     * shown one, and only then: a service the user has not looked at is not asked for anything.
     */
    private fun reconnected() {
        val state = state.value
        if (state.shownSource?.local != false) return
        if (state.stale != null || state.status is LibraryStatus.Offline) read()
    }

    override fun onAction(action: LibraryAction) {
        when (action) {
            is LibraryAction.TabSelected -> showTab(action.tab)
            is LibraryAction.TitleActivated -> {
                action.firstVisibleIndex?.let { index ->
                    updateState { copy(returnTo = LibraryReturn(action.id, index)) }
                }
                scope.launch { postEffect { LibraryEffect.OpenTitle(action.id) } }
            }

            LibraryAction.ReturnHandled -> updateState { copy(returnTo = null) }
            is LibraryAction.FilterChanged -> filter(action.text)
            is LibraryAction.ResumeActivated -> resume(action.id)
            is LibraryAction.PickerOpened -> updateState { copy(picking = action.id) }
            LibraryAction.PickerDismissed -> updateState { copy(picking = null) }
            is LibraryAction.BucketChosen -> choose(action.id, action.bucket)
            is LibraryAction.RemoveRequested -> remove(action.id)
            LibraryAction.SearchRequested -> scope.launch { postEffect { LibraryEffect.OpenSearch } }
            LibraryAction.Refresh, LibraryAction.Retry -> read()
            LibraryAction.Reappeared -> reread()
            // Nothing to choose between before the sources are read, or on a screen without them.
            LibraryAction.SourcesOpened -> updateState { copy(sourcesOpen = this.sources.isNotEmpty()) }
            LibraryAction.SourcesDismissed -> updateState { copy(sourcesOpen = false) }
            is LibraryAction.SourceChosen -> chooseSource(action.id)
            LibraryAction.SortOpened -> updateState { copy(sortOpen = true) }
            LibraryAction.SortDismissed -> updateState { copy(sortOpen = false) }
            is LibraryAction.SortChosen -> chooseSort(action.criterion)
        }
    }

    /**
     * Applies a sort at once over what was read, and keeps the panel open, so pressing the same row again turns it the
     * other way round. The host remembers it for every source.
     */
    private fun chooseSort(criterion: LibrarySortCriterion) {
        val state = state.value
        val shown = state.shownSource
        if (shown != null && criterion !in shown.sorts) return
        val applied = state.appliedSort
        val sort = if (applied.criterion == criterion) applied.flipped() else LibrarySort.of(criterion)
        updateState {
            val next = copy(sort = sort)
            if (status.isUnread()) next else next.copy(status = next.statusFor(tab, filter))
        }
        sources?.let { host -> scope.launch { write { host.sortBy(sort) } } }
    }

    /**
     * Shows another source's lists. The old screen reloads on a switch (`LibraryViewModel.switchList`), and so does
     * this, through Loading: a list read from a service a moment ago is not the one about to be shown.
     */
    private fun chooseSource(id: LibrarySourceId) {
        val host = sources ?: return
        val state = state.value
        val chosen = state.sources.firstOrNull { it.id == id }
        if (chosen == null || !chosen.connected || id == state.source) {
            updateState { copy(sourcesOpen = false) }
            return
        }
        val request = ++generation
        updateState {
            copy(
                sourcesOpen = false,
                source = id,
                status = LibraryStatus.Loading,
                stale = null,
                counts = emptyMap(),
                syncedCounts = emptyMap(),
                totalTitles = null,
                returnTo = null,
            )
        }
        scope.launch {
            write { host.choose(id) }
            load(request)
        }
    }

    private fun showTab(tab: LibraryTab) = updateState {
        // Loading would blink a skeleton over a list already read; the cards for the new tab are known. After a failed
        // read nothing is known, and the failure stays: an empty list would say the list is empty (`BUG-33`).
        if (status.isUnread()) copy(tab = tab) else copy(tab = tab, status = statusFor(tab, filter))
    }

    private fun filter(text: String) = updateState {
        // Over Loading or a failure there is nothing read yet to narrow.
        if (status.isUnread()) copy(filter = text) else copy(filter = text, status = statusFor(tab, text))
    }

    /** Nothing was read to show: still loading, failed, or offline with nothing held. */
    private fun LibraryStatus.isUnread() =
        this is LibraryStatus.Loading || this is LibraryStatus.Failed || this is LibraryStatus.Offline

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

    private fun reread() {
        updateState { copy(returnTo = returnTo?.copy(ready = true)) }
        val request = ++generation
        scope.launch { load(request) }
    }

    private suspend fun load(request: Int) {
        val host = sources
        if (host == null) {
            loadStore(request)
            return
        }
        val read = runCatching { Triple(host.sources(), host.current(), host.sort()) }
        val failure = read.exceptionOrNull()
        if (failure is CancellationException) throw failure
        if (request != generation) return
        val (known, current, chosen) = read.getOrElse {
            // Without the sources there is still the store, which is what the screen showed before it had any.
            loadStore(request)
            return
        }
        val shown = known.firstOrNull { it.id == current }
        updateState { copy(sources = known, source = shown?.id, sort = chosen ?: sort) }
        if (shown == null || shown.local) loadStore(request) else loadSynced(host, shown.id, request)
    }

    /**
     * A tracking service's lists, as tabs in its order, with the panel's rows read again for the time it recorded.
     *
     * Offline, the service is not asked: it shows the read held from earlier in this session, or [LibraryStatus.Offline].
     * A read that fails shows the held one marked [LibraryStale.Unreachable], or the failure where none is held.
     */
    private suspend fun loadSynced(host: LibrarySources, id: LibrarySourceId, request: Int) {
        // The flow's current value: this ViewModel collects it for as long as it lives, so it is never stale here.
        if (connectivity.status.value == Connectivity.OFFLINE) {
            val kept = held[id]
            if (kept == null) {
                synced = null
                updateState {
                    copy(
                        status = LibraryStatus.Offline,
                        stale = null,
                        syncedTabs = null,
                        syncedCounts = emptyMap(),
                        counts = emptyMap(),
                        totalTitles = null,
                    )
                }
            } else {
                showSynced(kept, known = null, stale = LibraryStale.Offline)
            }
            return
        }
        val read = runCatching { host.lists(id) }
        val failure = read.exceptionOrNull()
        if (failure is CancellationException) throw failure
        if (request != generation) return
        val lists = read.getOrElse {
            val kept = held[id]
            if (kept != null) {
                showSynced(kept, known = null, stale = LibraryStale.Unreachable)
                return
            }
            synced = null
            updateState {
                copy(
                    status = LibraryStatus.Failed(it.reason()),
                    stale = null,
                    syncedTabs = null,
                    syncedCounts = emptyMap(),
                    counts = emptyMap(),
                    totalTitles = null,
                )
            }
            return
        }
        val known = runCatching { host.sources() }.getOrNull()
        if (request != generation) return
        held[id] = lists
        showSynced(lists, known, stale = null)
    }

    private fun showSynced(lists: List<SyncedList>, known: List<LibrarySource>?, stale: LibraryStale?) {
        synced = lists
        val tabs = lists.map { LibraryTab.Synced(it.key, it.label) }
        updateState {
            val tab = tab.takeIf { it in tabs } ?: tabs.firstOrNull() ?: tab
            val next = copy(
                sources = known ?: sources,
                tab = tab,
                syncedTabs = tabs,
                syncedCounts = lists.associate { it.key to it.titles.distinctBy { title -> title.id }.size },
                counts = emptyMap(),
                totalTitles = LibraryShelf.syncedTotal(lists),
                stale = stale,
            )
            next.copy(status = next.statusFor(tab, filter))
        }
    }

    private suspend fun loadStore(request: Int) {
        val read = runCatching { store.titles() to store.positions() }
        val failure = read.exceptionOrNull()
        if (failure is CancellationException) throw failure
        if (request != generation) return
        val (loadedTitles, loadedPositions) = read.getOrElse {
            updateState {
                copy(status = LibraryStatus.Failed(it.reason()), counts = emptyMap(), totalTitles = null, stale = null)
            }
            return
        }
        titles = loadedTitles
        positions = loadedPositions
        synced = null
        updateState {
            // The local list is never stale: it is on this device.
            // Back from a service's lists, the store's own tabs.
            val tab = if (tab is LibraryTab.Synced) initialTab else tab
            copy(
                tab = tab,
                syncedTabs = null,
                syncedCounts = emptyMap(),
                stale = null,
                status = statusFor(tab, filter),
                counts = LibraryShelf.counts(loadedTitles),
                totalTitles = LibraryShelf.totalTitles(loadedTitles),
            )
        }
    }

    /** The status for [tab] in this state's sort: the one it applies to the source it shows. */
    private fun LibraryState.statusFor(tab: LibraryTab, filter: String): LibraryStatus {
        val lists = synced
        val sort = appliedSort
        val cards = if (tab is LibraryTab.Synced && lists != null) {
            lists.firstOrNull { it.key == tab.key }?.let { LibraryShelf.syncedCards(it, filter, sort) }.orEmpty()
        } else {
            LibraryShelf.cards(tab, titles, positions, filter, sort)
        }
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

    private companion object {
        /**
         * How long the connection has to hold before a service is read again. A network that flaps goes ONLINE and back
         * several times a second, and this makes that one read rather than one per flap.
         */
        const val RECONNECT_SETTLE_MS = 2_000L
    }
}

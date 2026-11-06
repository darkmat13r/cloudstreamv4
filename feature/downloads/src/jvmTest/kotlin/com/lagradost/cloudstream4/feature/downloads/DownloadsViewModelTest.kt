package com.lagradost.cloudstream4.feature.downloads

import com.lagradost.cloudstream4.core.connectivity.Connectivity
import com.lagradost.cloudstream4.core.connectivity.ConnectivityFlow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val network = MutableStateFlow(Connectivity.UNKNOWN)
    private val source = FakeSource()
    private val viewModel by lazy {
        DownloadsViewModel(source, object : ConnectivityFlow { override val status = network }, scope)
    }

    @AfterTest
    fun stop() = scope.cancel()

    private fun title(id: Int, state: DownloadTitleState = DownloadTitleState.Done) = DownloadTitle(
        id = DownloadTitleId(id),
        name = "Title $id",
        kind = DownloadTitleKind.Film,
        episodes = 1,
        bytesDone = 100L * id,
        bytesTotal = 100L * id,
        state = state,
    )

    private fun show(vararg titles: DownloadTitle) {
        source.emit(DownloadsContents(titles = titles.toList()))
    }

    private fun nextEffect() = runBlocking { withTimeout(WAIT_MS) { viewModel.effect.first() } }

    @Test
    fun loadingUntilTheFirstReadThenEmptyOrShowing() {
        assertEquals(DownloadsStatus.Loading, viewModel.state.value.status)
        show()
        assertEquals(DownloadsStatus.Empty, viewModel.state.value.status)
        show(title(1))
        assertEquals(listOf(title(1)), viewModel.state.value.titles)
    }

    @Test
    fun onlyOfflineReadsAsOfflineNotUnknown() {
        assertEquals(false, viewModel.state.value.offline)
        network.value = Connectivity.OFFLINE
        assertEquals(true, viewModel.state.value.offline)
        network.value = Connectivity.UNKNOWN
        assertEquals(false, viewModel.state.value.offline)
    }

    @Test
    fun aFailedReadSaysSoAndRetryReadsAgain() {
        source.failure = IllegalStateException("store unreadable")
        assertEquals(DownloadsStatus.Failed("store unreadable"), viewModel.state.value.status)

        source.failure = null
        viewModel.onAction(DownloadsAction.Retry)
        assertEquals(DownloadsStatus.Loading, viewModel.state.value.status)
        assertEquals(1, source.refreshes)
        show(title(1))
        assertIs<DownloadsStatus.Showing>(viewModel.state.value.status)
    }

    @Test
    fun openingARowKeepsAReturnPointThatWaitsForTheScreenToComeBack() {
        show(title(1), title(2))
        viewModel.onAction(DownloadsAction.TitleActivated(DownloadTitleId(2), firstVisibleIndex = 1))
        assertEquals(DownloadsEffect.OpenTitle(DownloadTitleId(2)), nextEffect())
        assertEquals(DownloadsReturn(DownloadsStop.Title(DownloadTitleId(2)), 1, ready = false), viewModel.state.value.returnTo)

        viewModel.onAction(DownloadsAction.Reappeared)
        assertEquals(true, viewModel.state.value.returnTo?.ready)
        assertEquals(1, source.refreshes)
        viewModel.onAction(DownloadsAction.ReturnHandled)
        assertNull(viewModel.state.value.returnTo)
    }

    @Test
    fun whileChoosingARowIsToggledAndOpensNothing() {
        show(title(1), title(2))
        viewModel.onAction(DownloadsAction.SelectionStarted)
        viewModel.onAction(DownloadsAction.TitleActivated(DownloadTitleId(2)))
        assertEquals(setOf(DownloadTitleId(2)), viewModel.state.value.selection)
        assertEquals(200L, viewModel.state.value.selectedBytes)
        assertNull(viewModel.state.value.returnTo)
        viewModel.onAction(DownloadsAction.TitleActivated(DownloadTitleId(2)))
        assertEquals(emptySet(), viewModel.state.value.selection)
    }

    @Test
    fun selectAllTogglesAndDeleteAsksForWhatIsChosen() {
        show(title(1), title(2))
        viewModel.onAction(DownloadsAction.SelectionStarted)
        viewModel.onAction(DownloadsAction.SelectAllToggled)
        viewModel.onAction(DownloadsAction.DeleteSelected)
        assertEquals(DownloadsEffect.ConfirmDelete(setOf(DownloadTitleId(1), DownloadTitleId(2))), nextEffect())
        viewModel.onAction(DownloadsAction.SelectAllToggled)
        assertEquals(emptySet(), viewModel.state.value.selection)
    }

    @Test
    fun choosingEndsWhenWhatWasChosenHasGoneAndNotBefore() {
        show(title(1), title(2))
        viewModel.onAction(DownloadsAction.SelectionStarted)
        show(title(1))
        assertEquals(emptySet(), viewModel.state.value.selection)

        viewModel.onAction(DownloadsAction.TitleActivated(DownloadTitleId(1)))
        show(title(1), title(3))
        assertEquals(setOf(DownloadTitleId(1)), viewModel.state.value.selection)
        show(title(3))
        assertNull(viewModel.state.value.selection)
    }

    @Test
    fun freeingSpaceChoosesWhereThereAreTitlesAndOpensTheSystemsScreenWhereThereAreNone() {
        show()
        viewModel.onAction(DownloadsAction.FreeUpSpace)
        assertEquals(DownloadsEffect.OpenStorageSettings, nextEffect())
        assertNull(viewModel.state.value.selection)

        show(title(1))
        viewModel.onAction(DownloadsAction.FreeUpSpace)
        assertEquals(emptySet(), viewModel.state.value.selection)
    }

    @Test
    fun resumeGoesToTheSource() {
        show(title(1, DownloadTitleState.Failed))
        viewModel.onAction(DownloadsAction.ResumeTitle(DownloadTitleId(1)))
        assertEquals(listOf(DownloadTitleId(1)), source.resumed)
    }

    private class FakeSource : DownloadsSource {
        private val latest = MutableStateFlow<DownloadsContents?>(null)
        @Volatile var failure: Exception? = null
        var refreshes = 0
        val resumed = mutableListOf<DownloadTitleId>()

        fun emit(contents: DownloadsContents) {
            latest.value = contents
        }

        override val contents: Flow<DownloadsContents> = flow {
            failure?.let { throw it }
            latest.filterNotNull().collect { emit(it) }
        }

        override fun refresh() {
            refreshes++
        }

        override fun resume(id: DownloadTitleId) {
            resumed += id
        }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}

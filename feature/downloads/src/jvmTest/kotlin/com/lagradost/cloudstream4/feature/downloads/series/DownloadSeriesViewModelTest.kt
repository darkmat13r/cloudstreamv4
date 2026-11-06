package com.lagradost.cloudstream4.feature.downloads.series

import com.lagradost.cloudstream4.core.connectivity.Connectivity
import com.lagradost.cloudstream4.core.connectivity.ConnectivityFlow
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DownloadSeriesViewModelTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val network = MutableStateFlow(Connectivity.UNKNOWN)
    private val source = FakeSource()
    private val viewModel by lazy {
        DownloadSeriesViewModel(source, object : ConnectivityFlow { override val status = network }, scope)
    }

    @AfterTest
    fun stop() = scope.cancel()

    private fun episode(id: Int, state: DownloadEpisodeState, bytes: Long = 100L) = DownloadEpisode(
        id = DownloadEpisodeId(id), season = 1, episode = id, name = "E$id",
        state = state, bytesDone = bytes, bytesTotal = 100L,
    )

    private fun show(vararg episodes: DownloadEpisode) = source.emit(DownloadSeriesContents("Show", episodes.toList()))

    private fun nextEffect() = runBlocking { withTimeout(WAIT_MS) { viewModel.effect.first() } }

    @Test
    fun loadingUntilTheFirstReadThenShowingSortedBySeasonAndEpisode() {
        assertEquals(SeriesStatus.Loading, viewModel.state.value.status)
        show(episode(2, DownloadEpisodeState.Done), episode(1, DownloadEpisodeState.Done))
        assertEquals(listOf(1, 2), viewModel.state.value.episodes.map { it.id.value })
        assertEquals("Show", viewModel.state.value.name)
    }

    @Test
    fun columnBDoesWhatTheRowStateSays() {
        show(
            episode(1, DownloadEpisodeState.Running), episode(2, DownloadEpisodeState.Paused),
            episode(3, DownloadEpisodeState.Failed), episode(4, DownloadEpisodeState.Queued), episode(5, DownloadEpisodeState.Done),
        )
        (1..5).forEach { viewModel.onAction(SeriesAction.StateVerbRequested(DownloadEpisodeId(it))) }
        assertEquals(listOf("pause 1", "resume 2", "resume 3", "cancel 4"), source.calls)
    }

    @Test
    fun retryFailedResumesEveryFailureAndNothingElse() {
        show(episode(1, DownloadEpisodeState.Failed), episode(2, DownloadEpisodeState.Paused), episode(3, DownloadEpisodeState.Failed))
        viewModel.onAction(SeriesAction.RetryFailedRequested)
        assertEquals(listOf("resume 1", "resume 3"), source.calls)
    }

    @Test
    fun playingRemembersTheStopItWasPressedOn() {
        show(episode(1, DownloadEpisodeState.Done))
        viewModel.onAction(SeriesAction.PlayRequested(DownloadEpisodeId(1), SeriesVerb.Play, firstVisibleIndex = 3))
        assertEquals(SeriesEffect.Play(DownloadEpisodeId(1)), nextEffect())
        assertEquals(SeriesReturn(SeriesStop.Episode(DownloadEpisodeId(1), SeriesVerb.Play), 3), viewModel.state.value.returnTo)
        viewModel.onAction(SeriesAction.Reappeared)
        assertEquals(true, viewModel.state.value.returnTo?.ready)
    }

    @Test
    fun deleteAllAsksForEveryEpisode() {
        show(episode(1, DownloadEpisodeState.Done), episode(2, DownloadEpisodeState.Failed))
        viewModel.onAction(SeriesAction.DeleteAllRequested)
        assertEquals(SeriesEffect.ConfirmDeleteAll(listOf(DownloadEpisodeId(1), DownloadEpisodeId(2))), nextEffect())
    }

    @Test
    fun aPageThatEmptiesClosesAndOneThatWasNeverFullSaysSo() {
        show()
        assertEquals(SeriesStatus.Empty, viewModel.state.value.status)
        show(episode(1, DownloadEpisodeState.Done))
        show()
        assertEquals(SeriesEffect.Close, nextEffect())
    }

    @Test
    fun aFailedReadSaysWhyAndRetryReadsAgain() {
        source.failure = IllegalStateException("unreadable")
        viewModel.state.value
        assertIs<SeriesStatus.Failed>(viewModel.state.value.status)
        source.failure = null
        viewModel.onAction(SeriesAction.Retry)
        show(episode(1, DownloadEpisodeState.Done))
        assertIs<SeriesStatus.Showing>(viewModel.state.value.status)
    }

    @Test
    fun onlyOfflineReadsAsOffline() {
        assertEquals(false, viewModel.state.value.offline)
        network.value = Connectivity.OFFLINE
        assertEquals(true, viewModel.state.value.offline)
    }

    private class FakeSource : DownloadSeriesSource {
        private val latest = MutableStateFlow<DownloadSeriesContents?>(null)
        val calls = CopyOnWriteArrayList<String>()

        @Volatile var failure: Exception? = null

        fun emit(value: DownloadSeriesContents) {
            latest.value = value
        }

        override val contents: Flow<DownloadSeriesContents> = flow {
            failure?.let { throw it }
            latest.filterNotNull().collect { emit(it) }
        }

        override fun refresh() = Unit
        override fun pause(id: DownloadEpisodeId) { calls += "pause ${id.value}" }
        override fun resume(id: DownloadEpisodeId) { calls += "resume ${id.value}" }
        override fun cancel(id: DownloadEpisodeId) { calls += "cancel ${id.value}" }
    }

    private companion object {
        const val WAIT_MS = 2_000L
    }
}

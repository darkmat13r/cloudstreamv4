package com.lagradost.cloudstream4.media.download

import com.lagradost.cloudstream4.media.model.DownloadFailure
import com.lagradost.cloudstream4.media.model.DownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun DownloadState.isTerminal() =
    this == DownloadState.COMPLETED || this == DownloadState.FAILED || this == DownloadState.STOPPED

private sealed interface Command {
    data object Pause : Command
    data object Resume : Command
    data object Cancel : Command
    class Finished(val job: Job) : Command
}

/**
 * A running download that can be paused, resumed and cancelled. Created by [SegmentedDownloader.start].
 *
 * Commands are queued and applied in order by one coroutine on the session's scope, so the calls
 * below never block and never race each other. Pause is the same as a collector cancelling the flow:
 * the temporary files stay and a later [resume] continues from what is on disk. [cancel] removes
 * them, and ends in [DownloadState.STOPPED].
 */
class DownloadSession internal constructor(
    scope: CoroutineScope,
    private val downloader: SegmentedDownloader,
    private val request: DownloadRequest,
    private val key: String,
) {
    private val state = MutableStateFlow(
        DownloadSnapshot(DownloadState.DOWNLOADING, 0L, null, 0L, null, emptyList(), null, null, false),
    )

    /** The latest snapshot. Its [DownloadSnapshot.state] is PAUSED while paused and ends in COMPLETED, FAILED or STOPPED. */
    val snapshots: StateFlow<DownloadSnapshot> = state.asStateFlow()

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val terminal = CompletableDeferred<DownloadSnapshot>()

    init {
        scope.launch {
            var run: Job? = launchRun(this, initial = true)
            try {
                for (command in commands) {
                    when (command) {
                        Command.Pause -> {
                            val current = run
                            if (current != null && !state.value.state.isTerminal()) {
                                run = null
                                current.cancelAndJoin()
                                state.value = state.value.copy(state = DownloadState.PAUSED, bytesPerSecond = 0L, etaSeconds = null)
                            }
                        }
                        Command.Resume -> if (run == null) {
                            if (!downloader.tryAcquire(key)) {
                                finish(failed(DownloadFailure.Unknown))
                                return@launch
                            }
                            state.value = state.value.copy(state = DownloadState.DOWNLOADING)
                            run = launchRun(this, initial = false)
                        }
                        Command.Cancel -> {
                            if (state.value.state.isTerminal()) {
                                finish(state.value)
                                return@launch
                            }
                            run?.cancelAndJoin()
                            run = null
                            runCatching { downloader.discardInternal(request.folder, request.fileName) }
                            finish(state.value.copy(state = DownloadState.STOPPED, bytesPerSecond = 0L, etaSeconds = null, failure = null))
                            return@launch
                        }
                        is Command.Finished -> if (command.job === run) {
                            finish(state.value)
                            return@launch
                        }
                    }
                }
            } finally {
                // The scope died with the download unfinished: it is paused, as far as anyone can tell.
                if (!terminal.isCompleted) {
                    state.value = state.value.copy(state = DownloadState.PAUSED, bytesPerSecond = 0L, etaSeconds = null)
                    terminal.complete(state.value)
                }
            }
        }
    }

    private fun failed(failure: DownloadFailure) =
        state.value.copy(state = DownloadState.FAILED, failure = failure, bytesPerSecond = 0L, etaSeconds = null)

    private fun finish(snapshot: DownloadSnapshot) {
        state.value = snapshot
        terminal.complete(snapshot)
        commands.close()
    }

    private fun launchRun(scope: CoroutineScope, initial: Boolean): Job {
        lateinit var job: Job
        job = scope.launch {
            try {
                downloader.runAcquired(request) { snapshot ->
                    // A snapshot published after a pause has taken effect must not undo it.
                    if (isActive) state.value = snapshot
                }
                commands.trySend(Command.Finished(job))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                state.value = failed(DownloadFailure.Unknown)
                commands.trySend(Command.Finished(job))
            } finally {
                withContext(NonCancellable) { downloader.release(key) }
            }
        }
        return job
    }

    /** Stops the transfer and keeps what was downloaded. A no-op once the download has ended. */
    fun pause() {
        commands.trySend(Command.Pause)
    }

    /** Continues a paused download from what is on disk. A no-op while running or after it has ended. */
    fun resume() {
        commands.trySend(Command.Resume)
    }

    /** Stops the transfer and deletes the temporary files. Never touches a finished file. */
    fun cancel() {
        commands.trySend(Command.Cancel)
    }

    /**
     * Waits until the download has ended in COMPLETED, FAILED or STOPPED, and returns that snapshot.
     * Waits through a pause. If the scope the session runs in is cancelled first, returns the last
     * (paused) snapshot.
     */
    suspend fun join(): DownloadSnapshot = terminal.await()
}

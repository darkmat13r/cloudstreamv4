package com.lagradost.cloudstream4.player

import com.lagradost.cloudstream4.media.model.SubtitleTrack
import com.lagradost.cloudstream4.media.subtitle.SubtitleTimeline
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What is on, with no words in it. The player turns it into a row and a notice where it draws them. */
internal sealed interface SubtitleSelection {
    data object Off : SubtitleSelection
    data class Loading(val track: SubtitleTrack) : SubtitleSelection
    data class Showing(val track: SubtitleTrack, val timeline: SubtitleTimeline) : SubtitleSelection
    data class Failed(val track: SubtitleTrack, val reason: SubtitleFailure) : SubtitleSelection
}

/**
 * The tracks of one stream, and which is chosen. Starts off, and is forgotten with the player: nothing
 * remembers a choice between streams yet (`KMP-25`).
 *
 * Nothing here can disturb playback. Every way a track can go wrong ends in [SubtitleSelection.Failed], and
 * choosing another track cancels the fetch in progress, so a slow file cannot replace a later choice.
 */
internal class SubtitleSession(
    val tracks: List<SubtitleTrack>,
    private val loader: SubtitleLoader,
    private val scope: CoroutineScope,
) {
    private val _selection = MutableStateFlow<SubtitleSelection>(SubtitleSelection.Off)
    private val _failures = MutableStateFlow<Map<String, SubtitleFailure>>(emptyMap())
    private var job: Job? = null

    val selection: StateFlow<SubtitleSelection> = _selection.asStateFlow()

    /** Why each track that was tried and failed did, so its row can say so after the notice has gone. */
    val failures: StateFlow<Map<String, SubtitleFailure>> = _failures.asStateFlow()

    /** @param track null turns subtitles off. Choosing the track already on does nothing. */
    fun select(track: SubtitleTrack?) {
        val current = _selection.value
        if (track == null) {
            job?.cancel()
            _selection.value = SubtitleSelection.Off
            return
        }
        if (current is SubtitleSelection.Showing && current.track == track) return
        if (current is SubtitleSelection.Loading && current.track == track) return
        job?.cancel()
        _failures.update { it - track.id }
        _selection.value = SubtitleSelection.Loading(track)
        job = scope.launch {
            val load = try {
                loader.load(track)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A loader promises not to throw. This is a screen with a video on it: if one does, it is a
                // failed track and not a crashed scope.
                SubtitleLoad.Failed(SubtitleFailure.Fetch)
            }
            _selection.value = when (load) {
                is SubtitleLoad.Ready -> SubtitleSelection.Showing(track, load.timeline)
                is SubtitleLoad.Failed -> {
                    _failures.update { it + (track.id to load.reason) }
                    SubtitleSelection.Failed(track, load.reason)
                }
            }
        }
    }
}

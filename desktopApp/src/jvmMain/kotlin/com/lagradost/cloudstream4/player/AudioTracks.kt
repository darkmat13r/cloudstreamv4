package com.lagradost.cloudstream4.player

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextDirection
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.audio_tracks
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import org.jetbrains.compose.resources.stringResource

/**
 * One of a stream's audio tracks, all of it from the file, through a host that parses hostile media.
 *
 * @param name libVLC's description: `Commentary - [Hindi]`, `Track 2 - [German]`, or `Track 1`.
 * @param channels zero when libVLC cannot say.
 * @param codec libVLC's four character code, or empty when it cannot say.
 */
internal data class AudioTrack(val id: Int, val name: String, val channels: Int = 0, val codec: String = "") {
    private val named = DESCRIPTION.matchEntire(name)
    private val language = named?.groupValues?.get(2)?.trim().orEmpty()
    private val title = (named?.groupValues?.get(1) ?: name).trim()

    /** What the design's selector reads out: `English · 5.1 · EAC3`, as much of it as libVLC could say. */
    val readout: String
        get() = listOf(language.ifEmpty { title }, CHANNEL_LAYOUTS[channels], CODECS[codec.lowercase()] ?: codec.uppercase())
            .filterNot { it.isNullOrEmpty() }
            .joinToString(SEPARATOR)

    /** The file's own title, when the readout has a language in its place and the title is not libVLC's filler. */
    val detail: String?
        get() = title.takeIf { language.isNotEmpty() && it.isNotEmpty() && !FILLER.matches(it) }

    private companion object {
        val DESCRIPTION = Regex("(.*) - \\[(.*)\\]")
        val FILLER = Regex("Track \\d+")
        const val SEPARATOR = " · "

        /** Only the layouts these counts nearly always are. Three could be 2.1 or 3.0, so it says nothing. */
        val CHANNEL_LAYOUTS = mapOf(1 to "1.0", 2 to "2.0", 6 to "5.1", 8 to "7.1")
        val CODECS = mapOf(
            "mp4a" to "AAC", "a52" to "AC3", "ac3" to "AC3", "eac3" to "EAC3", "dts" to "DTS", "opus" to "Opus",
            "flac" to "FLAC", "mpga" to "MP3", "mp3" to "MP3", "vorb" to "Vorbis", "trhd" to "TrueHD", "mlp" to "MLP",
        )
    }
}

/** @param selected the [AudioTrack.id] playing, or [NONE] while libVLC has not started one. */
internal data class AudioTracks(val tracks: List<AudioTrack> = emptyList(), val selected: Int = NONE) {
    /** There is nothing to choose between until there are two. */
    val choosable: Boolean get() = tracks.size > 1

    val playing: AudioTrack? get() = tracks.firstOrNull { it.id == selected }

    companion object {
        const val NONE = -1
    }
}

/**
 * Makes a name from the host safe to draw. Control and format characters go, which is what removes a bidirectional
 * override that would make one track read as another, and a name with nothing left says its position instead.
 */
internal fun audioTrackName(raw: String, position: Int): String =
    raw.filterNot { it.isISOControl() || it.category in UNDRAWN }.trim().take(MediaProtocol.MAX_AUDIO_NAME_CHARS)
        .ifEmpty { position.toString() }

private val UNDRAWN = setOf(CharCategory.FORMAT, CharCategory.LINE_SEPARATOR, CharCategory.PARAGRAPH_SEPARATOR)

/**
 * The stream's audio tracks, above the controls, as the subtitle list is (artboard 7e). Pressing a row is the commit:
 * there is no Apply, and libVLC changes track without touching the position. A track's words are the file's and not
 * this app's, so they are quieter than the app's own unless playing, and run left to right whatever they contain.
 */
@Composable
internal fun AudioTrackPicker(
    audio: AudioTracks,
    onSelect: (AudioTrack) -> Unit,
    onDismiss: () -> Unit,
) {
    PlayerListPanel(stringResource(Res.string.audio_tracks), onDismiss) {
        val selectedRow = remember(audio.tracks) { audio.tracks.indexOfFirst { it.id == audio.selected }.coerceAtLeast(0) }
        val list = rememberLazyListState(initialFirstVisibleItemIndex = selectedRow)
        val focus = remember { FocusRequester() }
        // After a frame: a lazy list composes its rows during layout, so the row is not attached before that.
        LaunchedEffect(focus) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
        }
        LazyColumn(state = list, contentPadding = PaddingValues(all = PlayerScreenDefaults.PickerRingRoom)) {
            itemsIndexed(audio.tracks) { index, track ->
                val selected = track.id == audio.selected
                TrackRow(
                    title = track.readout,
                    detail = track.detail,
                    selected = selected,
                    onClick = { onSelect(track) },
                    modifier = if (index == selectedRow) Modifier.focusRequester(focus) else Modifier,
                    titleStyle = CloudStreamTheme.typography.label.copy(
                        color = if (selected) CloudStreamTheme.colors.ink else CloudStreamTheme.colors.ink2,
                        textDirection = TextDirection.Ltr,
                    ),
                    detailStyle = CloudStreamTheme.typography.caption.copy(
                        color = CloudStreamTheme.colors.muted,
                        textDirection = TextDirection.Ltr,
                    ),
                )
            }
        }
    }
}

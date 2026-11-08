package com.lagradost.cloudstream4.feature.player

import androidx.compose.runtime.Immutable

/**
 * What artboard **7d** draws, as the host knows it at one moment.
 *
 * **Labels only.** Every text here is a title, a source's display name, a quality or a track's label, already resolved
 * by the host. There is no field a URL, a request header or a file path could go in, and the host's mapper is tested to
 * keep them out (`KMP-64`): a stream's address is a secret of the provider's, and the HUD is on a shared screen.
 *
 * @property title the show or film, 7d's `.ptitle`.
 * @property meta the line under it, already joined: `S1:E4 · Powder · 1080p · AVC · English 5.1`. Empty for none.
 * @property downloaded what plays is a file on this device: 7d's `DOWNLOADED` badge.
 * @property positionMs where playback is. Never past [durationMs] when that is known.
 * @property durationMs `0` while the length is unknown; the track then draws nothing played or buffered.
 * @property bufferedMs how far ahead the player holds, for the muted band inside the one track.
 * @property marks where the skip stamps start, drawn as ticks on the track. The player has no chapters of its own.
 * @property skip the one control that may show while the HUD is hidden, 7d's `Skip intro`.
 * @property upNext the next episode's words for `Up next · …`, or `null` when there is none.
 * @property source what plays, `Source One · 1080p`: the SOURCE selector's value.
 * @property subtitles the subtitle track's label, or `null` for none.
 * @property audio the audio track, `English · 5.1 · EAC3`, or `null` when the player has not said.
 * @property speed the playback speed, for the SPEED selector.
 */
@Immutable
data class PlayerHudState(
    val title: String = "",
    val meta: String = "",
    val downloaded: Boolean = false,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val marks: List<Long> = emptyList(),
    val skip: HudSkip? = null,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val upNext: String? = null,
    val source: String? = null,
    val subtitles: String? = null,
    val audio: String? = null,
    val speed: Float = 1f,
    val banner: HudBanner? = null,
)

/** 7d's `Skip intro`: the label the skip stamp carries, `Skip opening` or `Next episode`. */
@Immutable
data class HudSkip(val label: String)

/** The banner slot at the top of the HUD. Never a region replacement while frames are still decoding (7d's notes). */
sealed interface HudBanner {
    /** A playback error over a mounted HUD. Its action is the SOURCE selector, already in the focus order. */
    data object Failed : HudBanner

    /** No connection, playing the downloaded copy. Paired with the `DOWNLOADED` badge. */
    data object OfflineCopy : HudBanner
}

/** What the HUD asks the host to do. Each maps onto something the player already does. */
sealed interface PlayerHudAction {
    data object Back : PlayerHudAction
    data object PlayPause : PlayerHudAction
    data object SeekBackward : PlayerHudAction
    data object SeekForward : PlayerHudAction
    data object PreviousEpisode : PlayerHudAction
    data object NextEpisode : PlayerHudAction
    data object Skip : PlayerHudAction

    /** From the seek cell or a hidden scrub: move by [deltaMs], negative for back. */
    data class SeekBy(val deltaMs: Long) : PlayerHudAction

    data object OpenSource : PlayerHudAction
    data object OpenSubtitles : PlayerHudAction
    data object OpenAudio : PlayerHudAction
    data object OpenSpeed : PlayerHudAction
}

package com.lagradost.cloudstream4.media.model

/**
 * What is playing, as far as anything outside the player needs to know.
 *
 * **There is no type for this today.** `:app` carries it as `GeneratorState.meta: Any?` and
 * recovers it with `when (meta) { is ResultEpisode -> …; is ExtractorUri -> … }` at about ten call
 * sites. Each consumer — the media notification, the Chromecast payload, the overlay title, the
 * subtitle search — re-derives the same four or five strings from whichever of the two it got, and
 * they do not agree: the notification falls back to `"Unknown"`, the overlay formats a title from
 * Android string resources, and cast builds a third shape, `MetadataHolder`.
 *
 * Neither existing carrier can move here. `ResultEpisode` holds `:library`'s `Score` and `TvType`
 * plus `:app`'s `VideoWatchState`; `ExtractorUri` holds an `android.net.Uri`. So this is a new
 * type, and it holds only what a *playback surface* needs — not what a result page needs.
 *
 * ## No `TvType`
 *
 * `TvType` is `:library`'s, and it is the single most pervasive leak across the shapes this
 * replaces. It is left out rather than restated: nothing a notification, a lock screen or a cast
 * receiver does depends on whether the thing is an anime or a documentary. [seriesTitle] being
 * null is what distinguishes a film from an episode, which is the only distinction these surfaces
 * actually draw.
 *
 * ## Artwork is a URL and nothing else
 *
 * `:app`'s notification adapter falls back from a poster URL to `IPlayer.getPreview(0.5f)`, an
 * `android.graphics.Bitmap`, retried ten times a second apart. A decoded bitmap cannot cross this
 * boundary and should not: [artworkUrl] is the portable answer, and the frame-grab fallback stays
 * an Android detail of whoever builds the notification.
 */
data class NowPlaying(
    /**
     * The title of the work — a film's name, or the series name for an episode.
     *
     * This is `ResultEpisode.headerName`, which every consumer already treats as the headline.
     */
    val title: String,
    /** The episode's own name, where it has one distinct from [title]. */
    val subtitle: String? = null,
    /** Set when this is an episode of something; `null` for a film or a one-off. */
    val seriesTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val artworkUrl: String? = null,
    /** Which provider this came from, for display and for resuming. */
    val providerName: String? = null,
    /** `:app`'s episode id, so a surface can call back into it. Opaque here. */
    val id: Int? = null,
)

/**
 * Where playback currently is.
 *
 * Separate from [NowPlaying] because it changes many times a second while [NowPlaying] changes once
 * an episode. Folding them together makes every position tick allocate the metadata again, and
 * makes "has the episode changed?" an expensive comparison.
 */
data class PlaybackPosition(
    val positionMs: Long,
    /** `null` while the duration is still unknown, which is normal early in a live or HLS stream. */
    val durationMs: Long? = null,
) {
    /**
     * How far through, in `0f..1f`, or `null` when the duration is unknown or zero.
     *
     * Zero duration returns `null` rather than `0f`: a progress bar should stay indeterminate
     * rather than snap to empty.
     */
    val fraction: Float?
        get() {
            val total = durationMs ?: return null
            if (total <= 0L) return null
            return (positionMs.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }
}

/** What the engine is doing. Restates `:app`'s `CSPlayerLoading`. */
enum class PlaybackState {
    IDLE,
    BUFFERING,
    PLAYING,
    PAUSED,
    ENDED,
}

/**
 * Everything a playback surface renders, in one value.
 *
 * A notification, a lock-screen control, a cast receiver and a PiP window all need exactly this and
 * nothing more, which is the test this type is designed against.
 */
data class PlaybackStatus(
    val nowPlaying: NowPlaying,
    val position: PlaybackPosition,
    val state: PlaybackState,
    val playbackSpeed: Float = 1f,
    val isMuted: Boolean = false,
)

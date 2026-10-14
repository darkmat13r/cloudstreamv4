package com.lagradost.cloudstream4.media.model

/**
 * Everything needed to start playing something.
 *
 * Restates the portable half of `IPlayer.loadPlayer`, whose nine parameters are the current
 * contract:
 *
 * ```
 * loadPlayer(context, sameEpisode, link, data, startPosition, subtitles, subtitle, autoPlay, preview)
 * ```
 *
 * Four shape changes, each because the parameter list encodes something a type should:
 *
 * - `link: ExtractorLink?` and `data: ExtractorUri?` are **two nullable parameters of which exactly
 *   one must be set** — `CS3IPlayer` throws `IllegalArgumentException("Requires link or uri")` when
 *   neither is. That is a sum type written as two nulls, and it is [StreamSource] here, so the
 *   invalid case cannot be constructed.
 * - `subtitles: Set<SubtitleData>` and `subtitle: SubtitleData?` are a set and a selection into it,
 *   with nothing checking the selection is a member. [preferredSubtitleId] refers to
 *   [SubtitleTrack.id], so a selection that is not in [subtitles] is detectable rather than
 *   silently ignored.
 * - `context: Context` is the platform's, not the request's.
 * - `sameEpisode: Boolean` describes the *transition* from whatever was playing before, not the
 *   thing to play. It stays an argument of whatever applies the request.
 *
 * `preview` is likewise left out: it selects whether the engine generates seek-bar thumbnails,
 * which is an engine setting rather than a property of the stream.
 */
data class PlaybackRequest(
    val stream: StreamRequest,
    val subtitles: List<SubtitleTrack> = emptyList(),
    /** [SubtitleTrack.id] of the track to show, or `null` for none. */
    val preferredSubtitleId: String? = null,
    /** Where to start. `null` means the beginning; `:app` resolves "resume" to a number first. */
    val startPositionMs: Long? = null,
    val autoPlay: Boolean = true,
    /** What the notification, lock screen and cast receiver should show. */
    val nowPlaying: NowPlaying? = null,
) {
    /**
     * Whether [preferredSubtitleId] names a track in [subtitles].
     *
     * `true` when nothing is selected. This is a query rather than a constructor `require`
     * deliberately: a request can legitimately be built with a remembered selection before the
     * track list is known, and the caller decides whether to fall back or to wait.
     */
    fun hasResolvablePreferredSubtitle(): Boolean {
        val id = preferredSubtitleId ?: return true
        return subtitles.any { it.id == id }
    }
}

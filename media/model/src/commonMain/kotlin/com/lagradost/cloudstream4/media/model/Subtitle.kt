package com.lagradost.cloudstream4.media.model

/**
 * One timed subtitle line.
 *
 * `:app` already has a type of exactly this shape and it is already free of platform types —
 * `ui/player/SubtitleOffsetItemAdapter.kt:14-16`. It is restated rather than moved because it is in
 * `app/api/classes-jar.api`, so moving it is an ABI event, and because `:app` keeps producing the
 * old one from Media3's `CuesWithTiming` until `:media:subtitle` lands.
 *
 * [text] is a list because one cue can carry several rendered lines, which a renderer must keep
 * separate to lay out.
 */
data class SubtitleCue(
    val startTimeMs: Long,
    val durationMs: Long,
    val text: List<String>,
) {
    val endTimeMs: Long get() = startTimeMs + durationMs

    init {
        require(durationMs >= 0) { "durationMs must not be negative, was $durationMs" }
    }
}

/** Where a subtitle track's bytes come from. Mirrors `:app`'s `SubtitleOrigin`. */
sealed interface SubtitleSource {

    /** Fetched over HTTP(S), like `SubtitleOrigin.URL`. */
    data class Remote(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
    ) : SubtitleSource

    /** A file on the device, like `SubtitleOrigin.DOWNLOADED_FILE`. [locator] is opaque, as [StreamSource.Local]'s is. */
    data class Local(val locator: String) : SubtitleSource

    /**
     * Muxed into the video container, like `SubtitleOrigin.EMBEDDED_IN_VIDEO`.
     *
     * [trackId] is the engine's own identifier for the track and is meaningless to anything else.
     */
    data class Embedded(val trackId: String) : SubtitleSource
}

/**
 * A subtitle track offered to the user, before any of it is decoded.
 *
 * Restates `:app`'s `SubtitleData`, minus two things that do not belong in a contract: its
 * `mimeType`, which is always a `androidx.media3.common.MimeTypes` constant and so is the engine's
 * business, and its `getIETF_tag()`, which calls `:library`'s `SubtitleHelper`.
 *
 * [languageTag] is BCP-47 where one is known. `:app` already resolves extension-supplied language
 * names to tags before the player sees them, so doing it again here would duplicate `:library`'s
 * language table in a module that must not depend on it.
 */
data class SubtitleTrack(
    val source: SubtitleSource,
    /** What the user sees. `SubtitleData` builds this by joining its name and suffix. */
    val label: String,
    val languageTag: String? = null,
) {
    /**
     * A stable identity for "the same track", used to re-select a track across a reload.
     *
     * `SubtitleData.getId()` does the same thing and has the same subtlety: an embedded track is
     * identified by its track id alone, because it has no url, while every other track is
     * identified by where it came from *and* its label — two providers can serve the same file.
     */
    val id: String
        get() = when (source) {
            is SubtitleSource.Embedded -> source.trackId
            is SubtitleSource.Remote -> "${source.url}|$label"
            is SubtitleSource.Local -> "${source.locator}|$label"
        }
}

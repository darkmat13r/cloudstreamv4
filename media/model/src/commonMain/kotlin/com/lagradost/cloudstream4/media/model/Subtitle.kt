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
 *
 * [placement] is where the file asked for it, and is what `SubtitleCue` lacked until `KMP-25`.
 */
data class SubtitleCue(
    val startTimeMs: Long,
    val durationMs: Long,
    val text: List<String>,
    val placement: CuePlacement = CuePlacement.Default,
) {
    val endTimeMs: Long get() = startTimeMs + durationMs

    init {
        require(durationMs >= 0) { "durationMs must not be negative, was $durationMs" }
    }
}

/** Which part of a cue's box sits on its reference point: its top or left, its middle, or its bottom or right. */
enum class CueAnchor {
    START,
    MIDDLE,
    END,
}

/**
 * How a cue's lines align inside its box. [START] and [END] follow the text's direction, as WebVTT's
 * `align` does, where [LEFT] and [RIGHT] do not.
 */
enum class CueTextAlign {
    START,
    CENTER,
    END,
    LEFT,
    RIGHT,
}

/**
 * Where on the picture a cue goes, as a share of the picture and never a pixel.
 *
 * Every format's idea of placement reduces to this: `{\an7}` is the top left corner, a WebVTT `line` and
 * `position` are these numbers already, and a TTML region is a rectangle whose side or middle is the point.
 * A renderer keeps the whole box inside its safe area, so `0` means "as far up as is legible" and not
 * "half off the screen".
 *
 * @param horizontalPercent 0 is the picture's left edge. Always 0..100.
 * @param verticalPercent 0 is the picture's top edge. Always 0..100.
 * @param widthPercent how wide the box may grow, or null for as wide as the safe area.
 */
data class CuePlacement(
    val horizontalPercent: Float = PERCENT_CENTER,
    val horizontalAnchor: CueAnchor = CueAnchor.MIDDLE,
    val verticalPercent: Float = PERCENT_FULL,
    val verticalAnchor: CueAnchor = CueAnchor.END,
    val textAlign: CueTextAlign = CueTextAlign.CENTER,
    val widthPercent: Float? = null,
) {
    init {
        require(horizontalPercent in 0f..PERCENT_FULL) { "horizontalPercent out of range: $horizontalPercent" }
        require(verticalPercent in 0f..PERCENT_FULL) { "verticalPercent out of range: $verticalPercent" }
        require(widthPercent == null || widthPercent in 0f..PERCENT_FULL) { "widthPercent out of range: $widthPercent" }
    }

    companion object {
        private const val PERCENT_FULL = 100f
        private const val PERCENT_CENTER = 50f
        private const val NUMPAD_COLUMNS = 3

        /** Bottom centre, where a cue goes when nothing says otherwise. */
        val Default = CuePlacement()

        /**
         * An SSA `\an` code, or [CaptionStyle.alignment]: 1 to 9, laid out like a numeric keypad, so 1 is
         * bottom left and 9 is top right. Null for anything else.
         */
        fun fromSsaAlignment(code: Int): CuePlacement? {
            if (code !in 1..NUMPAD_COLUMNS * NUMPAD_COLUMNS) return null
            val column = (code - 1) % NUMPAD_COLUMNS
            val row = (code - 1) / NUMPAD_COLUMNS
            val horizontal = CueAnchor.entries[column]
            val vertical = CueAnchor.entries[NUMPAD_COLUMNS - 1 - row]
            return CuePlacement(
                horizontalPercent = PERCENT_FULL * column / (NUMPAD_COLUMNS - 1),
                horizontalAnchor = horizontal,
                verticalPercent = PERCENT_FULL * (NUMPAD_COLUMNS - 1 - row) / (NUMPAD_COLUMNS - 1),
                verticalAnchor = vertical,
                textAlign = when (horizontal) {
                    CueAnchor.START -> CueTextAlign.LEFT
                    CueAnchor.MIDDLE -> CueTextAlign.CENTER
                    CueAnchor.END -> CueTextAlign.RIGHT
                },
            )
        }
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

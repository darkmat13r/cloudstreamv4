package com.lagradost.cloudstream4.media.subtitle

import com.lagradost.cloudstream4.media.model.SubtitleCue

/** The text formats this module parses. Everything else (SSA, TTML, bitmap) stays with the engine. */
enum class SubtitleFormat {
    SRT,
    VTT,
}

/**
 * The bounds a parse stays inside. A subtitle file is third-party input, so none of these is a
 * tuning knob: each one caps work or memory that a hostile file would otherwise choose.
 *
 * The defaults are far above any real file. A feature-length SRT is a few thousand cues and a few
 * hundred KiB, with lines under 100 characters.
 *
 * An invalid limit is a programmer error and fails at construction. Input can never cause it.
 */
data class SubtitleLimits(
    /** Bytes of a [ByteArray] input, or chars of a [String] input. Anything past it is ignored. */
    val maxInputSize: Int = 8 * 1024 * 1024,
    val maxCues: Int = 50_000,
    /** A longer text line is cut here. A longer line is never read as a timing line. */
    val maxLineChars: Int = 2_048,
    val maxLinesPerCue: Int = 32,
) {
    init {
        require(maxInputSize > 0) { "maxInputSize must be positive, was $maxInputSize" }
        require(maxCues > 0) { "maxCues must be positive, was $maxCues" }
        require(maxLineChars > 0) { "maxLineChars must be positive, was $maxLineChars" }
        require(maxLinesPerCue > 0) { "maxLinesPerCue must be positive, was $maxLinesPerCue" }
    }
}

/** Which [SubtitleLimits] bound was actually hit. */
enum class SubtitleTruncation {
    INPUT_SIZE,
    CUE_COUNT,
    LINE_LENGTH,
    LINES_PER_CUE,
}

/**
 * What a parse produced. A parse has no failure case: an input with nothing usable in it gives an
 * empty [cues], which is also what a caller shows for a track that has no cues.
 *
 * [SubtitleCue.text] holds **plain text, not markup**. Tags were removed and character references
 * decoded exactly once, so `&lt;b&gt;` legitimately comes out as the four characters `<b>`, which is
 * what the author asked to display. Anything that hands a cue to an HTML parser or a WebView must
 * escape it first.
 */
data class SubtitleParseResult(
    /** In file order, which is not always time order. [SubtitleTimeline] sorts. */
    val cues: List<SubtitleCue>,
    /** The format used. `null` only when none was given and [SubtitleParser.detect] found none. */
    val format: SubtitleFormat?,
    /** Lines that claimed to be timings and were not usable, including an end before its start. */
    val malformedCues: Int = 0,
    val truncations: Set<SubtitleTruncation> = emptySet(),
)

package com.lagradost.cloudstream4.media.subtitle

import com.lagradost.cloudstream4.media.model.CuePlacement
import com.lagradost.cloudstream4.media.model.SubtitleCue

/**
 * Pure SRT, WebVTT and TTML parsing over untrusted input. Stateless, so safe from any thread.
 *
 * **Never throws on input, and runs in time linear in the input.** Everything is scanned by hand;
 * there is no `Regex` here to backtrack.
 *
 * ## Tolerance, measured against `:app`'s `CustomSubripParser`
 *
 * Kept from it: BOM (UTF-8, UTF-16 LE and BE), LF / CRLF / CR, optional hours, minutes and seconds
 * past 59, `,` or `.` before the fraction, 1-3+ fraction digits scaled then truncated, whitespace
 * around the arrow, and files whose `WEBVTT` sits behind invisible characters (`getSubtitleParser`).
 *
 * More tolerant than it, because each was a cue silently lost or garbled:
 * - the index line is optional and need not be an `Int` (`currentLine.toInt()` skips the cue);
 * - a missing blank line between cues does not merge them, and the next cue's index is not shown;
 * - whitespace-only lines end a cue, not just empty ones;
 * - a line of spaces right after the timing does not end the cue. That is how every YouTube-derived VTT
 *   on the Internet Archive starts a cue (measured, `KMP-25`), and ending there lost the cue's words.
 * - SRT `X1:` coordinates after the end time are ignored.
 *
 * Deliberately stricter: a timestamp field over [MAX_FIELD_DIGITS] digits or a time past
 * [MAX_TIMESTAMP_MS] is malformed, where `hours.toLong()` throws `NumberFormatException` out of
 * `parse`.
 *
 * ## Placement
 *
 * `{\anN}` in any line of a cue, then VTT cue settings. See [CueSettings] for which of those are honoured,
 * and [TtmlParser] for TTML, which has its own.
 *
 * ## Not done
 *
 * - **Charset detection.** A file with no BOM is read as UTF-8. `:app` uses `UniversalDetector`, a
 *   JVM library, so a caller holding windows-1256 bytes decodes them and calls the [String] overload.
 * - **`X-TIMESTAMP-MAP`.** Segmented HLS WebVTT needs it; this parser reads whole files.
 */
object SubtitleParser {

    /** No real file comes close. Keeps `start + duration` and any clamped delay far from overflow. */
    internal const val MAX_TIMESTAMP_MS: Long = 1_000L * 60 * 60 * 1000

    /** Nine digits of hours still fits a `Long` in ms, so accumulating a field cannot overflow. */
    private const val MAX_FIELD_DIGITS = 9
    private const val SNIFF_WINDOW = 4_096
    private const val VTT_MAGIC_WINDOW = 10
    private const val ARROW = "-->"

    /** [bytes] may start with a BOM. Without one it is read as UTF-8; bad sequences become U+FFFD. */
    fun parse(
        bytes: ByteArray,
        format: SubtitleFormat? = null,
        limits: SubtitleLimits = SubtitleLimits(),
    ): SubtitleParseResult {
        val size = minOf(bytes.size, limits.maxInputSize)
        val result = parse(decode(bytes, size), format, limits)
        return if (bytes.size > size) {
            result.copy(truncations = result.truncations + SubtitleTruncation.INPUT_SIZE)
        } else {
            result
        }
    }

    fun parse(
        text: String,
        format: SubtitleFormat? = null,
        limits: SubtitleLimits = SubtitleLimits(),
    ): SubtitleParseResult {
        val resolved = format ?: detect(text) ?: return SubtitleParseResult(emptyList(), null)
        return if (resolved == SubtitleFormat.TTML) {
            TtmlParser(text, limits).execute()
        } else {
            Run(text, resolved, limits).execute()
        }
    }

    /**
     * What [text] looks like, or `null` for a format this module does not parse, in which case a
     * caller falls back on the track's declared type as `getSubtitleParser` does.
     *
     * `:app` requires an SRT to start with `1`, so a file numbered from 0 or from a cut-down set
     * goes unrecognised. Here an SRT is anything with an arrow near the start.
     */
    fun detect(text: String): SubtitleFormat? {
        val start = firstVisibleIndex(text)
        val window = text.substring(start, minOf(text.length, start + SNIFF_WINDOW))
        return when {
            // Within the first few chars because "WEBVTT can be hidden behind invisible characters
            // not filtered by trim" (getSubtitleParser). Its substring(0, 10) throws on a shorter
            // file; this does not.
            window.take(VTT_MAGIC_WINDOW).contains("WEBVTT", ignoreCase = true) -> SubtitleFormat.VTT
            window.startsWith("[Script Info]", ignoreCase = true) -> null
            window.startsWith("Title:", ignoreCase = true) -> null
            window.startsWith("<") -> SubtitleFormat.TTML.takeIf { TtmlParser.isRootOf(window) }
            ARROW in window -> SubtitleFormat.SRT
            else -> null
        }
    }

    /** Past everything `getSubtitleParser` treats as invisible: whitespace, Cntrl and Cf. */
    private fun firstVisibleIndex(text: String): Int {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (!c.isWhitespace() && !c.isISOControl() && c.category != CharCategory.FORMAT) return i
            i++
        }
        return i
    }

    private fun decode(bytes: ByteArray, size: Int): String {
        fun at(i: Int) = bytes[i].toInt() and 0xFF
        return when {
            size >= 3 && at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF ->
                bytes.decodeToString(3, size)
            size >= 2 && at(0) == 0xFF && at(1) == 0xFE -> decodeUtf16(bytes, size, bigEndian = false)
            size >= 2 && at(0) == 0xFE && at(1) == 0xFF -> decodeUtf16(bytes, size, bigEndian = true)
            else -> bytes.decodeToString(0, size)
        }
    }

    /** `decodeToString` is UTF-8 only in common code. A lone surrogate is dropped later, by [CueText]. */
    private fun decodeUtf16(bytes: ByteArray, size: Int, bigEndian: Boolean): String {
        val chars = CharArray((size - 2) / 2)
        for (index in chars.indices) {
            val first = bytes[2 + index * 2].toInt() and 0xFF
            val second = bytes[3 + index * 2].toInt() and 0xFF
            chars[index] = (if (bigEndian) (first shl 8) or second else (second shl 8) or first).toChar()
        }
        return chars.concatToString()
    }

    /** One parse's mutable state, so [SubtitleParser] itself holds none. */
    private class Run(
        private val text: String,
        private val format: SubtitleFormat,
        private val limits: SubtitleLimits,
    ) {
        private val end = minOf(text.length, limits.maxInputSize)
        private var position = minOf(firstVisibleIndex(text), end)
        private var lineStart = position

        /** The current line, cut at [SubtitleLimits.maxLineChars]. */
        private var line = ""
        private var lineTooLong = false

        private val cues = ArrayList<SubtitleCue>()
        private val truncations = HashSet<SubtitleTruncation>()
        private var malformed = 0

        fun execute(): SubtitleParseResult {
            if (text.length > limits.maxInputSize) truncations += SubtitleTruncation.INPUT_SIZE

            var atBlockStart = true
            while (nextLine()) {
                if (line.isBlank()) {
                    atBlockStart = true
                    continue
                }
                if (format == SubtitleFormat.VTT && atBlockStart && isVttMetadataBlock(line)) {
                    skipBlock()
                    continue
                }
                atBlockStart = false

                val timing = currentTiming()
                if (timing == null) {
                    if (ARROW in line) malformed++
                    continue
                }
                if (cues.size >= limits.maxCues) {
                    truncations += SubtitleTruncation.CUE_COUNT
                    break
                }
                addCue(timing, readCueLines())
                atBlockStart = true
            }
            return SubtitleParseResult(cues, format, malformed, truncations)
        }

        private fun currentTiming(): Timing? = if (lineTooLong) null else parseTiming(line)

        /** Reads to the end of the cue: a blank line, end of input, or the next cue's timing line. */
        private fun readCueLines(): List<String> {
            val lines = ArrayList<String>()
            var lastReadWasKept = false
            while (nextLine()) {
                if (line.isBlank()) {
                    // Spaces before any text are not the end of a cue. A truly empty line is.
                    if (lines.isEmpty() && line.isNotEmpty()) continue
                    break
                }
                if (currentTiming() != null) {
                    position = lineStart
                    // No blank line before this timing, so the digits above it are its index.
                    if (lastReadWasKept && lines.last().all { it in '0'..'9' }) lines.removeAt(lines.lastIndex)
                    break
                }
                if (lines.size < limits.maxLinesPerCue) {
                    lines += line
                    lastReadWasKept = true
                } else {
                    truncations += SubtitleTruncation.LINES_PER_CUE
                    lastReadWasKept = false
                }
            }
            return lines
        }

        private fun addCue(timing: Timing, rawLines: List<String>) {
            if (timing.endMs < timing.startMs) {
                // SubtitleCue rejects a negative duration by throwing; this is the guard for it.
                malformed++
                return
            }
            val lines = ArrayList<String>()
            for (raw in rawLines) {
                for (plain in CueText.toPlainLines(raw)) {
                    if (lines.size < limits.maxLinesPerCue) {
                        lines += plain
                    } else {
                        // `<br>` can split one raw line into many, so the cap is enforced here too.
                        truncations += SubtitleTruncation.LINES_PER_CUE
                    }
                }
            }
            if (lines.isNotEmpty()) {
                cues += SubtitleCue(timing.startMs, timing.endMs - timing.startMs, lines, placementOf(timing, rawLines))
            }
        }

        private fun placementOf(timing: Timing, rawLines: List<String>): CuePlacement {
            val override = rawLines.firstNotNullOfOrNull(CueText::alignmentOverride)
                ?.let(CuePlacement::fromSsaAlignment)
            return override ?: if (format == SubtitleFormat.VTT) CueSettings.parse(timing.settings) else CuePlacement.Default
        }

        /** STYLE holds CSS and NOTE holds comments. Neither is ever shown, whatever they contain. */
        private fun isVttMetadataBlock(line: String): Boolean {
            val word = line.substringBefore(' ').substringBefore('\t')
            return word == "NOTE" || word == "STYLE" || word == "REGION"
        }

        private fun skipBlock() {
            while (nextLine()) {
                if (line.isBlank()) return
            }
        }

        private fun nextLine(): Boolean {
            if (position >= end) return false
            lineStart = position
            var i = position
            while (i < end && text[i] != '\n' && text[i] != '\r') i++
            val length = i - lineStart
            lineTooLong = length > limits.maxLineChars
            if (lineTooLong) truncations += SubtitleTruncation.LINE_LENGTH
            // Only the kept part is copied, so a file that is one enormous line costs one scan.
            line = text.substring(lineStart, lineStart + minOf(length, limits.maxLineChars))
            position = when {
                i >= end -> end
                text[i] == '\r' && i + 1 < end && text[i + 1] == '\n' -> i + 2
                else -> i + 1
            }
            return true
        }
    }

    internal class Timing(val startMs: Long, val endMs: Long, val settings: String)

    /** `null` unless [line] is exactly `timecode --> timecode`, optionally followed by settings. */
    internal fun parseTiming(line: String): Timing? {
        val arrow = line.indexOf(ARROW)
        if (arrow < 0 || line.lastIndexOf(ARROW) != arrow) return null

        val start = Cursor(line, skipWhitespace(line, 0))
        val startMs = start.timecode() ?: return null
        if (skipWhitespace(line, start.index) != arrow) return null

        val finish = Cursor(line, skipWhitespace(line, arrow + ARROW.length))
        val endMs = finish.timecode() ?: return null
        if (finish.index < line.length && !line[finish.index].isWhitespace()) return null

        return Timing(startMs, endMs, line.substring(finish.index))
    }

    private fun skipWhitespace(line: String, from: Int): Int {
        var i = from
        while (i < line.length && line[i].isWhitespace()) i++
        return i
    }

    private class Cursor(private val line: String, var index: Int) {

        /** `[hours:]minutes:seconds[(,|.)fraction]` in ms, or `null`. ASCII digits only. */
        fun timecode(): Long? {
            var total = field() ?: return null
            var fields = 1
            while (index < line.length && line[index] == ':') {
                if (fields == 3) return null
                index++
                val next = field() ?: return null
                total = total * 60 + next
                fields++
            }
            if (fields < 2) return null
            var ms = total * 1000

            if (index < line.length && (line[index] == ',' || line[index] == '.')) {
                index++
                ms += fraction() ?: return null
            }
            return ms.takeIf { it <= MAX_TIMESTAMP_MS }
        }

        private fun field(): Long? {
            val first = index
            var value = 0L
            while (index < line.length && line[index] in '0'..'9') {
                if (index - first == MAX_FIELD_DIGITS) return null
                value = value * 10 + (line[index] - '0')
                index++
            }
            return value.takeIf { index > first }
        }

        /** One digit is tenths and two are hundredths; a fourth digit onwards is cut, not rounded. */
        private fun fraction(): Long? {
            val first = index
            var value = 0L
            while (index < line.length && line[index] in '0'..'9') {
                if (index - first < 3) value = value * 10 + (line[index] - '0')
                index++
            }
            return when (index - first) {
                0 -> null
                1 -> value * 100
                2 -> value * 10
                else -> value
            }
        }
    }
}

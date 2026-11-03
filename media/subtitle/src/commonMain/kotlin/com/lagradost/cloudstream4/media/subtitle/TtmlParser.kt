package com.lagradost.cloudstream4.media.subtitle

import com.lagradost.cloudstream4.media.model.CueAnchor
import com.lagradost.cloudstream4.media.model.CuePlacement
import com.lagradost.cloudstream4.media.model.CueTextAlign
import com.lagradost.cloudstream4.media.model.SubtitleCue

/**
 * TTML over untrusted input: every track YouTube's plugin offers and one of ARD's two (`KMP-25`).
 *
 * **Not an XML parser, on purpose.** There is none in common code, and one reads a DTD. This is one forward
 * pass over `<…>`, in time linear in the input, with no `Regex` and no entity table beyond [CueText]'s. A
 * `<!DOCTYPE` is skipped and nothing it declares ever expands. Malformed input loses what it damaged.
 *
 * ## What is honoured
 *
 * - `p` with `begin` and `end` or `dur`: clock times, with frames at the root's `ttp:frameRate`, and offset
 *   times in `h`, `m`, `s`, `ms`, `f`, `t`. A `begin` on `body` or `div` adds to what is inside it.
 * - Text in `p` and `span`, and `br`. Runs of whitespace collapse, as XML does by default here.
 * - `region`, on `p`, `div` or `body`, and `style` by reference (a style may refer to earlier ones). From them
 *   `tts:origin` and `tts:extent` as percentages, `tts:displayAlign` and `tts:textAlign`. A region with none
 *   of those stays at the bottom centre. **Pixel values are not honoured**: they need a root extent.
 * - **The EBU programme start.** ARD's EBU-TT-D cues begin at `10:00:02.120` for a video whose VTT twin says
 *   `00:00:02.120` (measured), because broadcast timecode starts a programme at 10:00:00. So in an EBU file
 *   whose first cue is an hour in or later, the whole hours before it are removed.
 *
 * ## What is not
 *
 * Colours, fonts and sizes (plain text, drawn in the app's style), `span` timing, `tts:writingMode`, nested
 * regions, `ttp:frameRateMultiplier`, `ttp:subFrameRate`, and `timeContainer="seq"`: children are always `par`.
 */
internal class TtmlParser(private val text: String, private val limits: SubtitleLimits) {

    private val end = minOf(text.length, limits.maxInputSize)
    private val cues = ArrayList<SubtitleCue>()
    private val truncations = HashSet<SubtitleTruncation>()
    private var malformed = 0

    private val styles = HashMap<String, Style>()
    private val regions = HashMap<String, Style>()
    private var openRegion: String? = null

    private var frameRate = DEFAULT_FRAME_RATE
    private var tickRate = DEFAULT_TICK_RATE
    private var isEbu = false

    /** Each open `body` and `div`: its time offset, region and style. */
    private val containers = ArrayList<Container>()
    private var droppedContainers = 0

    private var paragraph: Paragraph? = null
    private var ignoredDepth = 0

    fun execute(): SubtitleParseResult {
        if (text.length > limits.maxInputSize) truncations += SubtitleTruncation.INPUT_SIZE
        var i = 0
        while (i < end) {
            val open = text.indexOf('<', i)
            if (open < 0 || open >= end) {
                appendText(i, end)
                break
            }
            appendText(i, open)
            i = when {
                text.startsWith(COMMENT_OPEN, open) -> skipPast(COMMENT_CLOSE, open)
                text.startsWith(CDATA_OPEN, open) -> {
                    val close = text.indexOf(CDATA_CLOSE, open + CDATA_OPEN.length)
                    if (close < 0 || close >= end) end else {
                        appendText(open + CDATA_OPEN.length, close)
                        close + CDATA_CLOSE.length
                    }
                }
                text.startsWith("<?", open) -> skipPast("?>", open)
                text.startsWith("<!", open) -> skipPast(">", open)
                else -> readTag(open)
            }
            if (cues.size >= limits.maxCues) {
                truncations += SubtitleTruncation.CUE_COUNT
                break
            }
        }
        paragraph?.let(::finish)
        return SubtitleParseResult(withoutProgrammeStart(), SubtitleFormat.TTML, malformed, truncations)
    }

    private fun skipPast(close: String, from: Int): Int {
        val found = text.indexOf(close, from)
        return if (found < 0 || found >= end) end else found + close.length
    }

    private fun appendText(from: Int, until: Int) {
        val current = paragraph ?: return
        if (ignoredDepth > 0 || from >= until) return
        current.append(text, from, until, limits, truncations)
    }

    /** @return where to go on from: past the `>`, or the end of input when the tag never closes. */
    private fun readTag(open: Int): Int {
        val close = tagEnd(open) ?: return end
        val closing = text[open + 1] == '/'
        val selfClosing = !closing && text[close - 1] == '/'
        val nameStart = open + if (closing) 2 else 1
        var nameEnd = nameStart
        val limit = if (selfClosing) close - 1 else close
        while (nameEnd < limit && !text[nameEnd].isWhitespace()) nameEnd++
        val name = localName(text.substring(nameStart, nameEnd))

        when {
            closing -> closeElement(name)
            else -> {
                openElement(name, nameEnd, limit)
                if (selfClosing) closeElement(name)
            }
        }
        return close + 1
    }

    /** The `>` that ends the tag at [open], skipping any inside a quoted value. Null when none does. */
    private fun tagEnd(open: Int): Int? {
        var quote: Char? = null
        var i = open + 1
        while (i < end) {
            val c = text[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i
            }
            i++
        }
        return null
    }

    private fun localName(qualified: String): String = qualified.substringAfterLast(':')

    private fun openElement(name: String, from: Int, until: Int) {
        if (ignoredDepth > 0) {
            ignoredDepth++
            return
        }
        when (name) {
            "tt" -> readRoot(from, until)
            "style" -> readStyle(from, until)
            "region" -> readRegion(from, until)
            "body", "div" -> pushContainer(from, until)
            "p" -> {
                paragraph?.let(::finish)
                paragraph = readParagraph(from, until)
            }
            "br" -> paragraph?.breakLine(limits, truncations)
            "metadata" -> ignoredDepth = 1
        }
    }

    private fun closeElement(name: String) {
        if (ignoredDepth > 0) {
            ignoredDepth--
            return
        }
        when (name) {
            "p" -> paragraph?.let(::finish)
            "region" -> openRegion = null
            "body", "div" -> when {
                droppedContainers > 0 -> droppedContainers--
                containers.isNotEmpty() -> containers.removeAt(containers.lastIndex)
            }
        }
    }

    private fun readRoot(from: Int, until: Int) {
        val attributes = Attributes(text, from, until)
        attributes.forEach { name, value ->
            when (name) {
                "ttp:frameRate" -> value.toPositiveIntOrNull()?.let { frameRate = it }
                "ttp:tickRate" -> value.toPositiveIntOrNull()?.let { tickRate = it }
            }
            if (name.startsWith("xmlns") && value.startsWith(EBU_NAMESPACE)) isEbu = true
        }
    }

    private fun readStyle(from: Int, until: Int) {
        val attributes = Attributes(text, from, until)
        // A style inside a region describes that region, and has no id of its own to look up.
        openRegion?.let { id ->
            val merged = (regions[id] ?: Style.NONE).mergedWith(referenced(attributes["style"])).mergedWith(Style.of(attributes))
            regions[id] = merged
            return
        }
        if (styles.size >= MAX_DEFINITIONS) return
        val id = attributes["xml:id"] ?: return
        styles[id] = Style.NONE.mergedWith(referenced(attributes["style"])).mergedWith(Style.of(attributes))
    }

    private fun readRegion(from: Int, until: Int) {
        if (regions.size >= MAX_DEFINITIONS) return
        val attributes = Attributes(text, from, until)
        val id = attributes["xml:id"] ?: return
        openRegion = id
        regions[id] = Style.NONE.mergedWith(referenced(attributes["style"])).mergedWith(Style.of(attributes))
    }

    /** A style attribute is a list of ids, later ones winning. Each id was flattened when it was defined. */
    private fun referenced(ids: String?): Style {
        if (ids == null) return Style.NONE
        var merged = Style.NONE
        var i = 0
        while (i < ids.length) {
            while (i < ids.length && ids[i].isWhitespace()) i++
            val start = i
            while (i < ids.length && !ids[i].isWhitespace()) i++
            if (start < i) styles[ids.substring(start, i)]?.let { merged = merged.mergedWith(it) }
        }
        return merged
    }

    private fun pushContainer(from: Int, until: Int) {
        if (containers.size >= MAX_NESTING) {
            droppedContainers++
            return
        }
        val attributes = Attributes(text, from, until)
        val parent = containers.lastOrNull()
        containers += Container(
            offsetMs = (parent?.offsetMs ?: 0L) + (attributes["begin"]?.let(::timeOf) ?: 0L),
            region = attributes["region"] ?: parent?.region,
            style = (parent?.style ?: Style.NONE).mergedWith(referenced(attributes["style"])),
        )
    }

    private fun readParagraph(from: Int, until: Int): Paragraph? {
        val attributes = Attributes(text, from, until)
        val parent = containers.lastOrNull()
        val offset = parent?.offsetMs ?: 0L
        val begin = attributes["begin"]?.let(::timeOf)
        val finish = attributes["end"]?.let(::timeOf)
        val duration = attributes["dur"]?.let(::timeOf)
        val startMs = begin?.let { it + offset }
        val endMs = when {
            finish != null -> finish + offset
            duration != null && startMs != null -> startMs + duration
            else -> null
        }
        if (startMs == null || endMs == null || endMs < startMs || endMs > SubtitleParser.MAX_TIMESTAMP_MS) {
            malformed++
            return null
        }
        val region = (attributes["region"] ?: parent?.region)?.let { regions[it] } ?: Style.NONE
        val style = region
            .mergedWith(parent?.style ?: Style.NONE)
            .mergedWith(referenced(attributes["style"]))
            .mergedWith(Style.of(attributes))
        return Paragraph(startMs, endMs, style.toPlacement())
    }

    private fun finish(current: Paragraph) {
        paragraph = null
        current.breakLine(limits, truncations)
        if (current.lines.isEmpty()) return
        cues += SubtitleCue(current.startMs, current.endMs - current.startMs, current.lines, current.placement)
    }

    private fun withoutProgrammeStart(): List<SubtitleCue> {
        if (!isEbu || cues.isEmpty()) return cues
        val first = cues.minOf { it.startTimeMs }
        val hours = first - first % MS_PER_HOUR
        if (hours == 0L) return cues
        return cues.map { it.copy(startTimeMs = it.startTimeMs - hours) }
    }

    /** One TTML time expression in ms, or null when it is not one, and that costs the paragraph. */
    private fun timeOf(value: String): Long? {
        val time = value.trim()
        if (time.isEmpty() || time.length > MAX_TIME_CHARS) return null
        return if (':' in time) clockTime(time) else offsetTime(time)
    }

    /** `hh:mm:ss`, `hh:mm:ss.fraction` or `hh:mm:ss:frames`. */
    private fun clockTime(time: String): Long? {
        val parts = time.split(':')
        if (parts.size !in CLOCK_PARTS) return null
        val hours = parts[0].toDigitsOrNull() ?: return null
        val minutes = parts[1].toDigitsOrNull() ?: return null
        val wholeSeconds = parts[2].substringBefore('.').toDigitsOrNull() ?: return null
        val fractionMs = fractionMsOf(parts[2].substringAfter('.', ""))
        val frames = if (parts.size == CLOCK_PARTS.last) {
            (parts[3].substringBefore('.').toDigitsOrNull() ?: return null) * MS_PER_SECOND / frameRate
        } else {
            0L
        }
        return ((hours * MINUTES_PER_HOUR + minutes) * SECONDS_PER_MINUTE + wholeSeconds) * MS_PER_SECOND + fractionMs + frames
    }

    /** `12.5s`, `500ms`, `2m`, `1h`, `30f`, `100t`. */
    private fun offsetTime(time: String): Long? {
        val unit = when {
            time.endsWith("ms") -> "ms"
            time.last() in OFFSET_UNITS -> time.last().toString()
            else -> return null
        }
        val number = time.dropLast(unit.length)
        if (number.isEmpty() || number.length > MAX_TIME_CHARS) return null
        val whole = number.substringBefore('.').toDigitsOrNull() ?: return null
        val fractionMs = fractionMsOf(number.substringAfter('.', ""))
        return when (unit) {
            "ms" -> whole
            "s" -> whole * MS_PER_SECOND + fractionMs
            "m" -> whole * SECONDS_PER_MINUTE * MS_PER_SECOND + fractionMs * SECONDS_PER_MINUTE
            "h" -> whole * MS_PER_HOUR + fractionMs * MS_PER_HOUR / MS_PER_SECOND
            "f" -> whole * MS_PER_SECOND / frameRate
            else -> whole * MS_PER_SECOND / tickRate
        }
    }

    /** The first three digits as ms, the rest cut. Anything not a digit makes it no fraction at all. */
    private fun fractionMsOf(fraction: String): Long {
        var ms = 0L
        var scale = MS_PER_SECOND / 10
        for (c in fraction) {
            if (c !in '0'..'9') return 0
            ms += (c - '0') * scale
            scale /= 10
            if (scale == 0L) break
        }
        return ms
    }

    /** One `style` or `region`, or what they add up to. Null means this one says nothing about it. */
    private class Style(
        val textAlign: String?,
        val displayAlign: String?,
        val origin: Pair<Float, Float>?,
        val extent: Pair<Float, Float>?,
    ) {
        fun mergedWith(over: Style) = Style(
            over.textAlign ?: textAlign,
            over.displayAlign ?: displayAlign,
            over.origin ?: origin,
            over.extent ?: extent,
        )

        fun toPlacement(): CuePlacement {
            if (displayAlign == null && origin == null && extent == null && textAlign == null) return CuePlacement.Default
            val hasBox = displayAlign != null || origin != null || extent != null
            val (x, y) = origin ?: (0f to 0f)
            val (width, height) = extent ?: (FULL - x to FULL - y)
            val align = textAlign ?: if (hasBox) "start" else CENTER
            val (horizontal, horizontalAnchor) = when (align) {
                CENTER -> x + width / 2 to CueAnchor.MIDDLE
                "right", "end" -> x + width to CueAnchor.END
                else -> x to CueAnchor.START
            }
            val (vertical, verticalAnchor) = when {
                !hasBox -> FULL to CueAnchor.END
                displayAlign == "after" -> y + height to CueAnchor.END
                displayAlign == CENTER -> y + height / 2 to CueAnchor.MIDDLE
                else -> y to CueAnchor.START
            }
            return CuePlacement(
                horizontalPercent = horizontal.coerceIn(0f, FULL),
                horizontalAnchor = horizontalAnchor,
                verticalPercent = vertical.coerceIn(0f, FULL),
                verticalAnchor = verticalAnchor,
                textAlign = when (align) {
                    CENTER -> CueTextAlign.CENTER
                    "left" -> CueTextAlign.LEFT
                    "right" -> CueTextAlign.RIGHT
                    "end" -> CueTextAlign.END
                    else -> CueTextAlign.START
                },
                widthPercent = extent?.first?.coerceIn(0f, FULL),
            )
        }

        companion object {
            val NONE = Style(null, null, null, null)

            fun of(attributes: Attributes) = Style(
                textAlign = attributes["tts:textAlign"],
                displayAlign = attributes["tts:displayAlign"],
                origin = attributes["tts:origin"]?.let(::percentPair),
                extent = attributes["tts:extent"]?.let(::percentPair),
            )

            private fun percentPair(value: String): Pair<Float, Float>? {
                val parts = value.trim().split(' ').filter { it.isNotEmpty() }
                if (parts.size != 2) return null
                return (CueSettings.percentOf(parts[0]) ?: return null) to (CueSettings.percentOf(parts[1]) ?: return null)
            }
        }
    }

    private class Container(val offsetMs: Long, val region: String?, val style: Style)

    private class Paragraph(val startMs: Long, val endMs: Long, val placement: CuePlacement) {
        val lines = ArrayList<String>(2)
        private val current = StringBuilder()

        fun append(text: String, from: Int, until: Int, limits: SubtitleLimits, truncations: MutableSet<SubtitleTruncation>) {
            var i = from
            while (i < until) {
                if (current.length >= limits.maxLineChars) {
                    truncations += SubtitleTruncation.LINE_LENGTH
                    return
                }
                val c = text[i]
                if (c.isWhitespace()) {
                    // Collapsed here, because CueText drops a newline outright and that would join two words.
                    if (current.isNotEmpty() && current.last() != ' ') current.append(' ')
                } else {
                    current.append(c)
                }
                i++
            }
        }

        fun breakLine(limits: SubtitleLimits, truncations: MutableSet<SubtitleTruncation>) {
            if (current.isEmpty()) return
            for (plain in CueText.toPlainLines(current.toString())) {
                if (lines.size < limits.maxLinesPerCue) lines += plain else truncations += SubtitleTruncation.LINES_PER_CUE
            }
            current.clear()
        }
    }

    /** The name-and-value pairs of one tag, kept to [MAX_ATTRIBUTES] of them. */
    private class Attributes(text: String, from: Int, until: Int) {
        private val pairs = ArrayList<Pair<String, String>>(4)

        init {
            var i = from
            while (i < until && pairs.size < MAX_ATTRIBUTES) {
                while (i < until && text[i].isWhitespace()) i++
                val nameStart = i
                while (i < until && text[i] != '=' && !text[i].isWhitespace()) i++
                val name = text.substring(nameStart, i)
                while (i < until && text[i].isWhitespace()) i++
                if (i >= until || text[i] != '=') continue
                i++
                while (i < until && text[i].isWhitespace()) i++
                if (i >= until) break
                val quote = text[i]
                if (quote != '"' && quote != '\'') continue
                val valueStart = ++i
                while (i < until && text[i] != quote) i++
                pairs += name to text.substring(valueStart, i)
                i++
            }
        }

        operator fun get(name: String): String? = pairs.firstOrNull { it.first == name }?.second

        fun forEach(block: (String, String) -> Unit) = pairs.forEach { (name, value) -> block(name, value) }
    }

    companion object {
        private const val COMMENT_OPEN = "<!--"
        private const val COMMENT_CLOSE = "-->"
        private const val CDATA_OPEN = "<![CDATA["
        private const val CDATA_CLOSE = "]]>"
        private const val EBU_NAMESPACE = "urn:ebu:tt"
        private const val CENTER = "center"
        private const val FULL = 100f
        private const val DEFAULT_FRAME_RATE = 30
        private const val DEFAULT_TICK_RATE = 1
        private const val MAX_DEFINITIONS = 256
        private const val MAX_NESTING = 32
        private const val MAX_ATTRIBUTES = 32
        private const val MAX_TIME_CHARS = 24
        private const val MAX_DIGITS = 9
        private const val MS_PER_SECOND = 1000L
        private const val SECONDS_PER_MINUTE = 60L
        private const val MINUTES_PER_HOUR = 60L
        private const val MS_PER_HOUR = 3_600_000L
        private const val OFFSET_UNITS = "hmsft"
        private val CLOCK_PARTS = 3..4

        /** Whether [window], the start of a file, has `tt` as its root element, prefixed or not. */
        fun isRootOf(window: String): Boolean {
            var i = 0
            while (i < window.length) {
                val open = window.indexOf('<', i)
                if (open < 0 || open + 1 >= window.length) return false
                val next = window[open + 1]
                when {
                    window.startsWith(COMMENT_OPEN, open) -> {
                        val close = window.indexOf(COMMENT_CLOSE, open)
                        if (close < 0) return false
                        i = close + COMMENT_CLOSE.length
                    }
                    next == '?' || next == '!' -> {
                        val close = window.indexOf('>', open)
                        if (close < 0) return false
                        i = close + 1
                    }
                    else -> {
                        var nameEnd = open + 1
                        while (nameEnd < window.length && !window[nameEnd].isWhitespace() && window[nameEnd] != '>' && window[nameEnd] != '/') nameEnd++
                        return window.substring(open + 1, nameEnd).substringAfterLast(':') == "tt"
                    }
                }
            }
            return false
        }

        private fun String.toDigitsOrNull(): Long? {
            if (isEmpty() || length > MAX_DIGITS || any { it !in '0'..'9' }) return null
            return toLong()
        }

        private fun String.toPositiveIntOrNull(): Int? = toDigitsOrNull()?.toInt()?.takeIf { it > 0 }
    }
}

package com.lagradost.cloudstream4.media.subtitle

import com.lagradost.cloudstream4.media.model.CueAnchor
import com.lagradost.cloudstream4.media.model.CuePlacement
import com.lagradost.cloudstream4.media.model.CueTextAlign

/**
 * WebVTT cue settings as a [CuePlacement]. Anything not understood is ignored, never an error, so a bad
 * setting costs its own effect and not the cue.
 *
 * | Setting | Honoured as |
 * |---|---|
 * | `line:N%`, `line:N%,start\|center\|end` | the vertical point, and which part of the box sits on it |
 * | `line:N` | approximately: rows of [LINE_PERCENT] from the top, or from the bottom when negative |
 * | `position:N%`, `position:N%,line-left\|center\|line-right` | the horizontal point, and the same |
 * | `align:start\|center\|middle\|end\|left\|right` | text alignment, and the position when none is given |
 * | `size:N%` | the box's width |
 * | `vertical:`, `region:` | **ignored**: the cue is drawn horizontal, in the default region |
 */
internal object CueSettings {

    /** One row, for `line:N`. A row's real height depends on the font, and this is Media3's default size. */
    private const val LINE_PERCENT = 5.33f
    private const val FULL = 100f
    private const val CENTER = 50f
    private const val MAX_NUMBER_CHARS = 16

    fun parse(settings: String): CuePlacement {
        var line: Float? = null
        var lineAnchor = CueAnchor.START
        var position: Float? = null
        var positionAnchor: CueAnchor? = null
        var align: String? = null
        var size: Float? = null

        var i = 0
        while (i < settings.length) {
            while (i < settings.length && settings[i].isWhitespace()) i++
            val start = i
            while (i < settings.length && !settings[i].isWhitespace()) i++
            if (start == i) break
            val colon = settings.indexOf(':', start)
            if (colon < 0 || colon >= i) continue
            val value = settings.substring(colon + 1, i)
            when (settings.substring(start, colon)) {
                "line" -> parseLine(value)?.let { (percent, anchor) ->
                    line = percent
                    lineAnchor = anchor
                }
                "position" -> percentOf(value.substringBefore(','))?.let { percent ->
                    position = percent
                    positionAnchor = when (value.substringAfter(',', "")) {
                        "line-left" -> CueAnchor.START
                        "center", "middle" -> CueAnchor.MIDDLE
                        "line-right" -> CueAnchor.END
                        else -> null
                    }
                }
                "align" -> if (value in ALIGNMENTS) align = value
                "size" -> size = percentOf(value)
            }
        }

        val horizontal: Float
        val horizontalAnchor: CueAnchor
        val implied = when (align) {
            "start", "left" -> CueAnchor.START
            "end", "right" -> CueAnchor.END
            else -> CueAnchor.MIDDLE
        }
        val at = position
        if (at != null) {
            horizontal = at
            horizontalAnchor = positionAnchor ?: implied
        } else {
            horizontalAnchor = implied
            horizontal = when (implied) {
                CueAnchor.START -> 0f
                CueAnchor.MIDDLE -> CENTER
                CueAnchor.END -> FULL
            }
        }
        val vertical = line
        return CuePlacement(
            horizontalPercent = horizontal,
            horizontalAnchor = horizontalAnchor,
            verticalPercent = vertical ?: FULL,
            verticalAnchor = if (vertical == null) CueAnchor.END else lineAnchor,
            textAlign = when (align) {
                "start" -> CueTextAlign.START
                "end" -> CueTextAlign.END
                "left" -> CueTextAlign.LEFT
                "right" -> CueTextAlign.RIGHT
                else -> CueTextAlign.CENTER
            },
            widthPercent = size,
        )
    }

    /** `line:N%[,align]`, or a row number with no `%`. */
    private fun parseLine(value: String): Pair<Float, CueAnchor>? {
        val number = value.substringBefore(',')
        val anchor = when (value.substringAfter(',', "")) {
            "center", "middle" -> CueAnchor.MIDDLE
            "end" -> CueAnchor.END
            else -> CueAnchor.START
        }
        percentOf(number)?.let { return it to anchor }
        val row = number.toRowOrNull() ?: return null
        return if (row >= 0) {
            (row * LINE_PERCENT).coerceAtMost(FULL) to CueAnchor.START
        } else {
            (FULL + (row + 1) * LINE_PERCENT).coerceAtLeast(0f) to CueAnchor.END
        }
    }

    private fun String.toRowOrNull(): Int? {
        if (length !in 1..MAX_NUMBER_CHARS) return null
        return toIntOrNull()?.coerceIn(-MAX_ROWS, MAX_ROWS)
    }

    private const val MAX_ROWS = 100

    /** Clamped to 0..100, or null unless [value] is plain digits and a `%`: `toFloatOrNull` would take `NaN%`. */
    fun percentOf(value: String): Float? {
        if (!value.endsWith('%')) return null
        return decimalOf(value.dropLast(1))?.coerceIn(0f, FULL)
    }

    /** Digits with at most one `.`. Never NaN or infinite, and the length bound keeps it finite. */
    fun decimalOf(digits: String): Float? {
        if (digits.isEmpty() || digits.length > MAX_NUMBER_CHARS) return null
        var whole = 0f
        var fraction = 0f
        var scale = 1f
        var seenPoint = false
        var seenDigit = false
        for (c in digits) {
            when {
                c == '.' && !seenPoint -> seenPoint = true
                c in '0'..'9' -> {
                    seenDigit = true
                    if (seenPoint) {
                        scale /= DECIMAL_BASE
                        fraction += (c - '0') * scale
                    } else {
                        whole = whole * DECIMAL_BASE + (c - '0')
                    }
                }
                else -> return null
            }
        }
        return if (seenDigit) whole + fraction else null
    }

    private const val DECIMAL_BASE = 10f
    private val ALIGNMENTS = setOf("start", "center", "middle", "end", "left", "right")
}

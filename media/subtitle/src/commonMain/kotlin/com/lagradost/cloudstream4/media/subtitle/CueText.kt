package com.lagradost.cloudstream4.media.subtitle

/**
 * Turns one raw cue line into plain text: markup is **removed, never interpreted**.
 *
 * It replaces what `CustomSubripParser` does with `android.text.Html.fromHtml` and its
 * `\{\\.*?\}` pattern, keeping what a viewer would have read and none of the styling. It is one
 * forward pass, linear in the line, because a `Regex` or a rescan per `<` is quadratic on `<a<a<a…`.
 *
 * What is treated as markup:
 * - `{\…}` SSA override blocks, including `{\an8}`.
 * - `<…>` where `<` is followed by a letter, `/`, `!` or `?`, which covers `<i>`, `<font …>`, VTT's
 *   `<c.yellow>` and `<v Bob>`, and also `<script>` (its contents stay, as harmless text).
 * - `<00:00:01.000>`, VTT's timestamp tag: a digit, with only digits, `:` and `.` before the `>`.
 *
 * `<3` and `1 < 2` are text. So is a tag with no `>` on its line: a viewer sees it, and nothing is
 * lost that the file meant to show. `<br>` becomes a line break, as `Html.fromHtml` made it.
 *
 * Output is plain text and is not guaranteed free of `<`; see `SubtitleParseResult`.
 */
internal object CueText {

    /** The longest reference decoded, `&#x10FFFF;`. Past this an `&` is text, keeping the scan bounded. */
    private const val MAX_ENTITY_LENGTH = 10
    private const val MAX_CODE_POINT = 0x10FFFF
    private const val MIN_SUPPLEMENTARY = 0x10000
    private const val OVERRIDE_OPEN = "{\\"
    private const val ALIGNMENT_TAG = "\\an"

    /**
     * The first `{\anN}` in [raw] with N from 1 to 9, or null. What [toPlainLines] removes, read first.
     *
     * Only a block [toPlainLines] would remove counts, so `{\an8` with no closer stays text and places
     * nothing. A code out of range is ignored rather than taken for the default, as SSA renderers do.
     */
    fun alignmentOverride(raw: String): Int? {
        var open = raw.indexOf(OVERRIDE_OPEN)
        while (open >= 0) {
            val close = raw.indexOf('}', open)
            if (close < 0) return null
            var at = raw.indexOf(ALIGNMENT_TAG, open)
            while (at in 0 until close) {
                val digit = raw.getOrNull(at + ALIGNMENT_TAG.length)
                val next = raw.getOrNull(at + ALIGNMENT_TAG.length + 1)
                if (digit != null && digit in '1'..'9' && (next == null || next !in '0'..'9')) return digit - '0'
                at = raw.indexOf(ALIGNMENT_TAG, at + ALIGNMENT_TAG.length)
            }
            open = raw.indexOf(OVERRIDE_OPEN, close)
        }
        return null
    }

    fun toPlainLines(raw: String): List<String> {
        val out = StringBuilder(raw.length)
        // A `<` or `{\` past its last possible closer is text without scanning. This guard is what
        // makes `<a<a<a…` linear rather than one full scan per `<`.
        val lastAngle = raw.lastIndexOf('>')
        val lastBrace = raw.lastIndexOf('}')

        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '<' && i < lastAngle) {
                val close = tagEnd(raw, i)
                if (close >= 0) {
                    if (isLineBreak(raw, i, close)) out.append('\n')
                    i = close + 1
                    continue
                }
            }
            if (c == '{' && i + 1 < raw.length && raw[i + 1] == '\\' && i < lastBrace) {
                i = raw.indexOf('}', i) + 1
                continue
            }
            if (c == '&') {
                val consumed = appendEntity(raw, i, out)
                if (consumed > 0) {
                    i += consumed
                    continue
                }
            }
            if (c.isHighSurrogate() && i + 1 < raw.length && raw[i + 1].isLowSurrogate()) {
                out.append(c).append(raw[i + 1])
                i += 2
                continue
            }
            appendSanitised(c, out)
            i++
        }
        return splitLines(out)
    }

    private fun tagEnd(raw: String, open: Int): Int {
        val next = raw[open + 1]
        if (next in 'a'..'z' || next in 'A'..'Z' || next == '/' || next == '!' || next == '?') {
            return raw.indexOf('>', open + 1)
        }
        if (next !in '0'..'9') return -1
        var i = open + 1
        var sawColon = false
        while (i < raw.length && (raw[i] in '0'..'9' || raw[i] == ':' || raw[i] == '.')) {
            if (raw[i] == ':') sawColon = true
            i++
        }
        return if (i < raw.length && raw[i] == '>' && sawColon) i else -1
    }

    /** `<br>`, `<BR/>`, `<br />`. */
    private fun isLineBreak(raw: String, open: Int, close: Int): Boolean {
        if (close - open < 3) return false
        if (!raw[open + 1].equals('b', ignoreCase = true)) return false
        if (!raw[open + 2].equals('r', ignoreCase = true)) return false
        for (i in open + 3 until close) {
            if (raw[i] != '/' && !raw[i].isWhitespace()) return false
        }
        return true
    }

    /**
     * Decodes the reference at [start] and returns how many chars it spanned, or 0 if it is not one.
     * The output is never rescanned, so a reference decodes once: `&amp;lt;` shows as `&lt;`.
     */
    private fun appendEntity(raw: String, start: Int, out: StringBuilder): Int {
        val limit = minOf(raw.length, start + MAX_ENTITY_LENGTH)
        var semicolon = -1
        for (i in start + 1 until limit) {
            if (raw[i] == ';') {
                semicolon = i
                break
            }
        }
        if (semicolon < 0) return 0

        val name = raw.substring(start + 1, semicolon)
        val decoded: Int = when (name) {
            "amp" -> '&'.code
            "lt" -> '<'.code
            "gt" -> '>'.code
            "quot" -> '"'.code
            "apos" -> '\''.code
            "nbsp" -> 0xA0
            "lrm" -> 0x200E
            "rlm" -> 0x200F
            else -> numericReference(name)
        }
        if (decoded < 0) return 0

        if (decoded >= MIN_SUPPLEMENTARY) {
            val offset = decoded - MIN_SUPPLEMENTARY
            out.append((0xD800 + (offset ushr 10)).toChar())
            out.append((0xDC00 + (offset and 0x3FF)).toChar())
        } else {
            // Sanitised like any other char, so `&#0;` and `&#10;` cannot smuggle in a control char.
            appendSanitised(decoded.toChar(), out)
        }
        return semicolon - start + 1
    }

    /** The code point of `#123` or `#x7B`, or -1. Surrogates are not characters, so they are -1. */
    private fun numericReference(name: String): Int {
        if (name.length < 2 || name[0] != '#') return -1
        val hex = name[1] == 'x' || name[1] == 'X'
        val digits = if (hex) name.substring(2) else name.substring(1)
        if (digits.isEmpty()) return -1
        // MAX_ENTITY_LENGTH caps the digits at 8, so this cannot overflow an Int; 8 hex digits can
        // set the sign bit, which the range check below rejects.
        var value = 0
        for (c in digits) {
            val digit = when {
                c in '0'..'9' -> c - '0'
                hex && c in 'a'..'f' -> c - 'a' + 10
                hex && c in 'A'..'F' -> c - 'A' + 10
                else -> return -1
            }
            value = value * (if (hex) 16 else 10) + digit
        }
        if (value < 0 || value > MAX_CODE_POINT) return -1
        if (value in 0xD800..0xDFFF) return -1
        return value
    }

    /**
     * Drops control characters, stray BOMs and unpaired surrogates, and turns the unusual spaces
     * into plain ones, as `CustomDecoder.trimStr` does.
     *
     * U+200B and the bidi controls stay. They are real text: Thai line-breaking and RTL subtitles
     * use them.
     */
    private fun appendSanitised(c: Char, out: StringBuilder) {
        when {
            c == '\t' -> out.append(' ')
            c.code < 0x20 || c.code in 0x7F..0x9F -> Unit
            c == '\uFEFF' -> Unit
            c.isSurrogate() -> Unit
            c == '\u00A0' || c in '\u2000'..'\u200A' || c == '\u205F' -> out.append(' ')
            c == '\u2028' || c == '\u2029' -> out.append(' ')
            else -> out.append(c)
        }
    }

    private fun splitLines(text: CharSequence): List<String> {
        val lines = ArrayList<String>(1)
        var start = 0
        for (i in 0..text.length) {
            if (i == text.length || text[i] == '\n') {
                val line = text.substring(start, i).trim { it.isWhitespace() || it == '\u200B' }
                if (line.isNotEmpty()) lines += line
                start = i + 1
            }
        }
        return lines
    }
}

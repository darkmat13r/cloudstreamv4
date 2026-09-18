package com.lagradost.cloudstream4.core.storage

/**
 * Escaping for the one-file record format [TextRecordFile] reads and writes.
 *
 * A record line is `escapedKey=escapedValue`. Backslash, LF, CR, `=` and `,` are escaped in both
 * halves, so a key or value containing a delimiter, a newline or a backslash round-trips character
 * for character — which is what lets a JSON record with embedded quotes and newlines be stored
 * verbatim. Splitting therefore has to ignore escaped delimiters: see [indexOfUnescaped] and
 * [splitUnescaped].
 */

/**
 * Backslash-escapes the five characters the format reserves, leaving every other character —
 * including quotes, tabs and anything outside the BMP — exactly as it is.
 */
internal fun escape(raw: String): String = buildString(raw.length) {
    for (character in raw) {
        when (character) {
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '=' -> append("\\=")
            ',' -> append("\\,")
            else -> append(character)
        }
    }
}

/** Inverse of [escape]. Strict: an unknown or dangling escape is corruption, not a literal backslash. */
internal fun unescape(escaped: String): String = buildString(escaped.length) {
    var index = 0
    while (index < escaped.length) {
        val character = escaped[index]
        if (character != '\\') {
            append(character)
            index++
            continue
        }
        if (index + 1 == escaped.length) throw StorageFormatException("a line ends in a backslash")
        when (val escapedCharacter = escaped[index + 1]) {
            '\\', '=', ',' -> append(escapedCharacter)
            'n' -> append('\n')
            'r' -> append('\r')
            else -> throw StorageFormatException("unknown escape '\\$escapedCharacter'")
        }
        index += 2
    }
}

/** The index of the first [delimiter] that is not part of an escape, or `null` when there is none. */
internal fun String.indexOfUnescaped(delimiter: Char): Int? {
    var index = 0
    while (index < length) {
        when (this[index]) {
            '\\' -> index += 2
            delimiter -> return index
            else -> index++
        }
    }
    return null
}

/**
 * Splits on unescaped [delimiter]s, keeping escaped ones inside the parts. The parts are still
 * escaped; the caller runs [unescape] on each.
 */
internal fun String.splitUnescaped(delimiter: Char): List<String> {
    val parts = mutableListOf<String>()
    var start = 0
    var index = 0
    while (index < length) {
        when (this[index]) {
            '\\' -> index += 2
            delimiter -> {
                parts += substring(start, index)
                index++
                start = index
            }
            else -> index++
        }
    }
    parts += substring(start.coerceAtMost(length))
    return parts
}

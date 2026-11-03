package com.lagradost.cloudstream4.extensions

/**
 * A file name, or a scan finding with a class name in it, made fit to show. Both come from the plugin.
 *
 * Removes what is invisible or moves other text: controls (an ANSI escape in a terminal), format characters
 * (U+202E reverses what follows, which can make `fdp.jar` read as something else), line and paragraph
 * separators, and code points with no meaning. Works in code points, so a cut cannot leave half a pair.
 *
 * This is plain text from here on. Nothing that shows it treats it as markup or as a format string.
 */
internal fun String.untrustedText(maxCodePoints: Int = MAX_UNTRUSTED_CODE_POINTS): String {
    val kept = codePoints().filter { Character.getType(it).toByte() !in INVISIBLE }.toArray()
    val shown = String(kept, 0, minOf(kept.size, maxCodePoints)).trim()
    return if (kept.size > maxCodePoints) "$shown…" else shown
}

internal const val MAX_UNTRUSTED_CODE_POINTS = 160

private val INVISIBLE = setOf(
    Character.CONTROL,
    Character.FORMAT,
    Character.LINE_SEPARATOR,
    Character.PARAGRAPH_SEPARATOR,
    Character.SURROGATE,
    Character.PRIVATE_USE,
    Character.UNASSIGNED,
)

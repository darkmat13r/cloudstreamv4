package com.lagradost.cloudstream4.downloads

/**
 * Folder and file names made from what a plugin said a title is called.
 *
 * The engine's own `sanitizeFileName` is internal to `:media:download`, so its rules are restated here and
 * tightened: a segment from here is one the engine leaves exactly as it is, which is what lets this class find the
 * engine's temporary directory (`.<name>.csdl`) beside it.
 */
internal object DownloadPaths {
    private const val FORBIDDEN = "/\\<>:\"|?*"
    private const val MAX_SEGMENT_BYTES = 150
    private const val FALLBACK = "Download"
    private val RESERVED_STEMS = setOf("CON", "PRN", "AUX", "NUL") +
        (0..9).map { "COM$it" } + (0..9).map { "LPT$it" }

    /** The one extension a VIDEO link is saved with, as on Android (`EngineVideoDownload.EXTENSION`). */
    const val EXTENSION = "mp4"

    /**
     * One path segment, whatever [name] held: never empty, never `.` or `..`, no separator, no control, format
     * (bidi, zero-width) or line-separator character, no lone surrogate, at most 150 UTF-8 bytes.
     */
    fun segment(name: String, fallback: String = FALLBACK): String {
        val cleaned = buildString {
            var i = 0
            while (i < name.length) {
                val c = name[i]
                when {
                    c.isHighSurrogate() && i + 1 < name.length && name[i + 1].isLowSurrogate() -> {
                        append(c).append(name[i + 1])
                        i++
                    }
                    c.isSurrogate() -> Unit
                    c in FORBIDDEN -> append('_')
                    c.isDropped() -> Unit
                    c.isWhitespace() -> append(' ')
                    else -> append(c)
                }
                i++
            }
        }
        val cut = limitUtf8(cleaned.trimmed())
        if (cut.isEmpty()) return if (fallback == name) FALLBACK else segment(fallback, FALLBACK)
        val stem = cut.substringBefore('.').trimEnd().uppercase()
        return if (stem in RESERVED_STEMS) "_$cut" else cut
    }

    /** Whether a stored [value] is still a segment this would have made. A file that says otherwise was edited. */
    fun isSegment(value: String): Boolean = value.isNotEmpty() && segment(value) == value

    /** `S01E02 Name.mp4`, `E02.mp4`, `Name.mp4` or the title's own name for a film, made safe and unique by [taken]. */
    fun fileName(
        titleName: String,
        season: Int?,
        episode: Int?,
        episodeName: String?,
        isFilm: Boolean,
        taken: (String) -> Boolean,
    ): String {
        val label = buildString {
            if (!isFilm) {
                if (season != null) append("S").append(season.toString().padStart(2, '0'))
                if (episode != null) append("E").append(episode.toString().padStart(2, '0'))
                episodeName?.takeIf { it.isNotBlank() }?.let { if (isNotEmpty()) append(' '); append(it) }
            }
            if (isEmpty()) append(titleName)
        }
        // At most 150 bytes, so the longest candidate, with " (n).mp4", stays under the engine's 200.
        val stem = segment(label)
        var candidate = "$stem.$EXTENSION"
        var n = 2
        while (taken(candidate)) candidate = "$stem ($n).$EXTENSION".also { n++ }
        return candidate
    }

    private fun String.trimmed(): String = trim { it == '.' || it.isWhitespace() }

    private fun Char.isDropped(): Boolean = when (category) {
        CharCategory.CONTROL, CharCategory.FORMAT, CharCategory.LINE_SEPARATOR, CharCategory.PARAGRAPH_SEPARATOR -> true
        else -> false
    }

    private fun limitUtf8(text: String): String {
        if (text.toByteArray(Charsets.UTF_8).size <= MAX_SEGMENT_BYTES) return text
        val out = StringBuilder()
        var used = 0
        var i = 0
        while (i < text.length) {
            val width = if (text[i].isHighSurrogate() && i + 1 < text.length) 2 else 1
            val piece = text.substring(i, i + width)
            val bytes = piece.toByteArray(Charsets.UTF_8).size
            if (used + bytes > MAX_SEGMENT_BYTES) break
            out.append(piece)
            used += bytes
            i += width
        }
        return out.toString().trimmed()
    }

    /**
     * What a list shows of a plugin's title or episode name, and what names a folder or file and is written to the
     * downloads file: no control or format character, trimmed, at most 200 characters. Null when nothing is left, or
     * when it reads as an address: a plugin can put a signed link there, and it would be stored and become a file name
     * (`KMP-71`). An episode without a name is listed by its code and saved as `S01E05.mp4`.
     */
    fun displayText(text: String?): String? = cleaned(text)?.takeUnless { looksLikeAddress(it) }

    /** [displayText] for a title, never null: `Download` when the title was an address, and empty when it was blank. */
    fun titleText(text: String?): String = displayText(text) ?: if (cleaned(text) != null) FALLBACK else ""

    private fun cleaned(text: String?): String? =
        text?.filterNot { it.isISOControl() || it.category == CharCategory.FORMAT }?.trim()?.take(MAX_TEXT_CHARS)
            ?.takeIf { it.isNotEmpty() }

    /**
     * The TV HUD's rule for a title (`TvHudFacts.hudText`, as `KMP-66` tightened it), restated: a scheme anywhere, a
     * path, `content:` or `file:` at the start, or a host at the start with a port, path, query or fragment after it.
     * A dotted title on its own, `Dr.Stone` or `Mr.Robot`, is a title and stays.
     */
    internal fun looksLikeAddress(text: String): Boolean {
        val lower = text.lowercase()
        return "://" in lower || lower.startsWith("/") || lower.startsWith("content:") || lower.startsWith("file:") ||
            HOST_WITH_TAIL.containsMatchIn(lower)
    }

    private val HOST_WITH_TAIL = Regex("""^[\w.-]+\.[a-z]{2,}(:\d+|[/?#])""")
    private const val MAX_TEXT_CHARS = 200
}

package com.lagradost.cloudstream4.media.download

private const val FALLBACK_NAME = "download"
private const val MAX_NAME_BYTES = 200
private const val MAX_EXTENSION_BYTES = 16
private const val FORBIDDEN = "<>:\"|?*"
private val RESERVED_STEMS = setOf("CON", "PRN", "AUX", "NUL") +
    (0..9).map { "COM$it" } + (0..9).map { "LPT$it" }

/**
 * A name that is safe to create inside a folder, whatever [name] held. Never throws, never returns an
 * empty string, never contains a separator.
 *
 * In order: everything up to the last `/` or `\` is dropped (so `../../etc/passwd` is `passwd`); control
 * characters, Unicode format characters (which include the bidi overrides such as U+202E and zero-width
 * characters), line and paragraph separators and lone surrogates are removed; `< > : " | ? *` become `_`
 * (FAT and Windows refuse them); leading dots and white space and trailing dots and white space are
 * trimmed, which also kills `.` and `..`; a Windows reserved stem (`CON`, `NUL`, `COM1`, `LPT1`, and so
 * on, with or without an extension) gets a `_` in front; the result is cut to 200 UTF-8 bytes at a
 * character boundary, keeping an extension of up to 16 bytes; and an empty result is `download`.
 *
 * Not the last word on safety: the engine also checks that the resulting file's parent is the folder.
 */
internal fun sanitizeFileName(name: String): String {
    val lastComponent = name.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
    val cleaned = buildString {
        var i = 0
        while (i < lastComponent.length) {
            val c = lastComponent[i]
            when {
                c.isHighSurrogate() && i + 1 < lastComponent.length && lastComponent[i + 1].isLowSurrogate() -> {
                    append(c).append(lastComponent[i + 1])
                    i++
                }
                c.isSurrogate() -> Unit
                c in FORBIDDEN -> append('_')
                c.isDroppedCharacter() -> Unit
                else -> append(c)
            }
            i++
        }
    }
    val trimmed = cleaned.trim { it == '.' || it.isWhitespace() }
    if (trimmed.isEmpty()) return FALLBACK_NAME
    val stem = trimmed.substringBefore('.').trimEnd().uppercase()
    val safe = if (stem in RESERVED_STEMS) "_$trimmed" else trimmed
    return limitUtf8(safe)
}

private fun Char.isDroppedCharacter(): Boolean = when (category) {
    CharCategory.CONTROL, CharCategory.FORMAT, CharCategory.LINE_SEPARATOR, CharCategory.PARAGRAPH_SEPARATOR -> true
    else -> false
}

/** Bytes of [text] in UTF-8, for text with no lone surrogates. */
internal fun utf8Length(text: String): Int {
    var bytes = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        bytes += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            c.isHighSurrogate() -> {
                i++
                4
            }
            else -> 3
        }
        i++
    }
    return bytes
}

private fun limitUtf8(name: String): String {
    if (utf8Length(name) <= MAX_NAME_BYTES) return name
    val dot = name.lastIndexOf('.')
    val extension = if (dot > 0) name.substring(dot) else ""
    val keepExtension = extension.isNotEmpty() && utf8Length(extension) <= MAX_EXTENSION_BYTES
    val suffix = if (keepExtension) extension else ""
    val stem = if (keepExtension) name.substring(0, dot) else name
    val budget = MAX_NAME_BYTES - utf8Length(suffix)
    val builder = StringBuilder()
    var used = 0
    var i = 0
    while (i < stem.length) {
        val width = if (stem[i].isHighSurrogate()) 2 else 1
        val piece = stem.substring(i, i + width)
        val bytes = utf8Length(piece)
        if (used + bytes > budget) break
        builder.append(piece)
        used += bytes
        i += width
    }
    val cut = builder.toString().trimEnd { it == '.' || it.isWhitespace() }
    return if (cut.isEmpty()) FALLBACK_NAME + suffix else cut + suffix
}

/** The hidden working directory beside the finished file: `.<name>.csdl`. [safeName] is already sanitised. */
internal fun tempDirectoryName(safeName: String): String = ".$safeName.csdl"

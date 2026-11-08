package com.lagradost.cloudstream4.feature.extensions.sandbox

/**
 * BUG-64: Unicode's Default_Ignorable_Code_Point (DerivedCoreProperties.txt), the code points a screen draws nothing
 * for. Cc and Cf miss some of them: U+034F, the variation selectors, the Hangul fillers. One of these inside `://` or
 * `/Users/` hides it from a pattern while the text reads the same. The same list is in every copy of this check.
 */
private fun isDefaultIgnorable(codePoint: Int): Boolean = when (codePoint) {
    0x00AD, 0x034F, 0x061C, 0x3164, 0xFEFF, 0xFFA0 -> true
    in 0x115F..0x1160, in 0x17B4..0x17B5, in 0x180B..0x180F, in 0x200B..0x200F, in 0x202A..0x202E,
    in 0x2060..0x206F, in 0xFE00..0xFE0F, in 0xFFF0..0xFFF8, in 0x1BCA0..0x1BCA3, in 0x1D173..0x1D17A,
    in 0xE0000..0xE0FFF -> true
    else -> false
}

/** Without [isDefaultIgnorable]'s code points, read a code point at a time, so a surrogate pair goes or stays whole. */
internal fun String.withoutDefaultIgnorable(): String {
    val text = this
    return buildString(text.length) {
        var i = 0
        while (i < text.length) {
            val pair = text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
            val codePoint =
                if (pair) 0x10000 + ((text[i].code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00) else text[i].code
            val end = if (pair) i + 2 else i + 1
            if (!isDefaultIgnorable(codePoint)) appendRange(text, i, end)
            i = end
        }
    }
}

package com.lagradost.cloudstream4.core.strings

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale

/**
 * The two `java.text` sources Android's own `java.util.Formatter.localizedMagnitude` reads, for the same locale.
 *
 * libcore's `Formatter` is the OpenJDK one, and its locale data is `java.text`'s (ICU-backed on Android), so
 * reading `DecimalFormatSymbols` and `NumberFormat` here is reading exactly what `String.format` would have read.
 */
internal actual fun localeNumberSymbols(): NumberSymbols {
    val locale = Locale.getDefault()
    val symbols = DecimalFormatSymbols.getInstance(locale)
    val format = NumberFormat.getNumberInstance(locale) as? DecimalFormat
    return NumberSymbols(
        zeroDigit = symbols.zeroDigit,
        decimalSeparator = symbols.decimalSeparator,
        groupingSeparator = symbols.groupingSeparator,
        // Formatter resets the separator to '\0' — no grouping at all — when the locale's number pattern has none.
        groupingSize = when {
            format == null -> NumberSymbols.DEFAULT_GROUPING_SIZE
            !format.isGroupingUsed -> 0
            else -> format.groupingSize
        },
    )
}

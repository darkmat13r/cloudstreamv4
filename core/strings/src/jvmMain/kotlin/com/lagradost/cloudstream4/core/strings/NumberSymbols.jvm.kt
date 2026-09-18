package com.lagradost.cloudstream4.core.strings

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale

/** The two `java.text` sources `java.util.Formatter.localizedMagnitude` itself reads, for the same locale. */
internal actual fun localeNumberSymbols(): NumberSymbols = javaNumberSymbols(Locale.getDefault())

internal fun javaNumberSymbols(locale: Locale): NumberSymbols {
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

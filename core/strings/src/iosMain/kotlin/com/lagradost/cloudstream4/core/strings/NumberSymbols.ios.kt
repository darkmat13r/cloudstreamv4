package com.lagradost.cloudstream4.core.strings

import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.currentLocale

/**
 * `NSNumberFormatter` in the decimal style, for `NSLocale.currentLocale`.
 *
 * There is no `java.util.Formatter` on iOS to be byte-identical to; this is the platform's own answer to the same
 * question, and it is what `NSNumberFormatter` would print. The zero digit is read by formatting `0` — Foundation
 * exposes the separators as properties but not the digit set.
 */
internal actual fun localeNumberSymbols(): NumberSymbols {
    val formatter = NSNumberFormatter().apply {
        locale = NSLocale.currentLocale
        numberStyle = NSNumberFormatterDecimalStyle
    }
    return NumberSymbols(
        zeroDigit = formatter.stringFromNumber(NSNumber(int = 0))?.firstOrNull() ?: '0',
        decimalSeparator = formatter.decimalSeparator?.firstOrNull() ?: '.',
        groupingSeparator = formatter.groupingSeparator?.firstOrNull() ?: ',',
        groupingSize = if (formatter.usesGroupingSeparator) {
            formatter.groupingSize.toInt()
        } else {
            0
        },
    )
}

package com.lagradost.cloudstream4.core.strings

/**
 * `value` (finite, >= 0) with exactly `precision` fraction digits.
 *
 * `java.util.Formatter` rounds half-up over the *shortest decimal representation* of the double — the digits
 * `Double.toString` produces — not over its exact binary value, so `%.2f` of `8.475` is `8.48`. Working from
 * `toString()` reproduces that, and keeps values that do not survive a `* 100` round trip exact.
 */
internal fun fixedDigits(value: Double, precision: Int): String {
    val text = value.toString()
    val exponentAt = text.indexOfFirst { it == 'e' || it == 'E' }
    val mantissa = if (exponentAt < 0) text else text.substring(0, exponentAt)
    val exponent = if (exponentAt < 0) 0 else text.substring(exponentAt + 1).toInt()
    val dot = mantissa.indexOf('.')
    val digits = StringBuilder(if (dot < 0) mantissa else mantissa.removeRange(dot, dot + 1))
    // Digits before the decimal point; may be <= 0 (0.001 is "1" at -2) or past the end (1e20 is "1" at 21).
    var point = (if (dot < 0) mantissa.length else dot) + exponent

    var leading = 0
    while (leading < digits.length - 1 && digits[leading] == '0') leading++
    digits.deleteRange(0, leading)
    point -= leading
    // One guard zero so the rounding position is always inside the string and a carry never grows it.
    while (point < 1) {
        digits.insert(0, '0')
        point++
    }
    digits.insert(0, '0')
    point++

    val cut = point + precision
    while (digits.length < cut) digits.append('0')
    val kept = StringBuilder(digits.substring(0, cut))
    if (cut < digits.length && digits[cut] >= '5') {
        var at = cut - 1
        while (at >= 0) {
            if (kept[at] == '9') {
                kept[at] = '0'
                at--
            } else {
                kept[at] = kept[at] + 1
                break
            }
        }
    }
    val whole = kept.substring(0, point).trimStart('0').ifEmpty { "0" }
    val fraction = kept.substring(point)
    return if (fraction.isEmpty()) whole else "$whole.$fraction"
}

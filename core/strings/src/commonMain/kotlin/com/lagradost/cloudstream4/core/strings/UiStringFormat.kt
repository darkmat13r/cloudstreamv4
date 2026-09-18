package com.lagradost.cloudstream4.core.strings

/**
 * The commonMain `String.format` replacement `UiString` uses (migration plan §7 Phase 3 S-b).
 *
 * ## Why this exists
 *
 * Compose Multiplatform's argument overloads (`stringResource(res, *args)`, `getPluralString(res, q, *args)`) run
 * `SimpleStringFormatRegex`, which substitutes **only** `%N$s` and `%N$d`. `app/src/main/res` uses `%s`, `%d`,
 * `%1$s`…`%3$s`, `%1$d`…`%3$d`, `%.1f` and `%.2f`, so those overloads would leave `%s`, `%d` and `%.2f` in the
 * text. `UiString` therefore always fetches the **raw pattern** from Compose and formats it here. Feature code must
 * never call Compose's argument overloads; that is a rule, not something the compiler enforces.
 *
 * ## What it covers
 *
 * `%[argument_index$][flags][width][.precision]conversion`, matching `java.util.Formatter` **with the platform
 * locale** (see `## Locale`):
 *
 * - conversions `s`, `S`, `d`, `f` and the literal `%%`;
 * - flags `-` (left justify), `0` (zero pad), `+`, `' '` (space), `,` (grouping) and `(` (negatives in parentheses);
 * - explicit `N$` indices, which do not advance the ordinary argument counter, exactly as `java.util.Formatter`.
 *
 * Anything else — `%b`, `%x`, `%o`, `%n`, `%<`, `#`, a trailing `%`, a `%` in front of ordinary text — **throws**
 * [IllegalArgumentException] rather than being passed through. The corpus contains such sequences only inside
 * argument-less strings (`…100%`, `100 % de brillo`), and [format] never touches a pattern when `args` is empty, so
 * they never reach the parser. `FormatterParityTest` pins both halves of that: every value that formats agrees with
 * `java.util.Formatter`, and every value that does not is one `java.util.Formatter` rejects too (or one of the four
 * listed `% d` accidents, which both accept).
 *
 * ## Locale
 *
 * Numbers follow the platform locale, as `UiText.asString`'s `String.format` did: `%.2f` writes `1,50` in `de`,
 * `%,d` groups with `.`, and `ar` writes Arabic-Indic digits. The digits and separators come from
 * [localeNumberSymbols], read **once per [format] call** and threaded down as a parameter; see [NumberSymbols] for
 * what was measured against `java.util.Formatter` and on which JDK. An earlier revision of this file was
 * locale-invariant and said so here; that was reverted by owner decision on 2026-09-18.
 *
 * `%f` rounds the way `java.util.Formatter` does — half-up over the **shortest decimal representation** of the
 * double (`8.475` -> `8.48`), not half-up over its exact binary value (`8.47`, what `BigDecimal` would give); the
 * digits themselves come from [fixedDigits], which is the one part of this that is neither flags nor locale.
 */
internal object UiStringFormat {

    /**
     * [pattern] with [args] substituted, or [pattern] itself when [args] is empty.
     *
     * The empty case is not an optimisation: `TextUtil.kt` does the same, and it is what keeps a literal `100%`
     * from throwing. A string with no arguments is never a format string.
     */
    fun format(pattern: String, args: List<Any>): String {
        if (args.isEmpty()) return pattern
        // Once per call, never in a property: an in-app language change moves the default locale under us.
        val symbols = localeNumberSymbols()
        val out = StringBuilder(pattern.length + ESTIMATED_GROWTH)
        var index = 0
        var ordinaryArgument = 0
        while (index < pattern.length) {
            val char = pattern[index]
            if (char != '%') {
                out.append(char)
                index++
                continue
            }
            index++
            require(index < pattern.length) { "Dangling '%' at the end of \"$pattern\"" }
            if (pattern[index] == '%') {
                out.append('%')
                index++
                continue
            }

            var argument = -1
            val digitsEnd = digitsEnd(pattern, index)
            if (digitsEnd > index && digitsEnd < pattern.length && pattern[digitsEnd] == '$') {
                argument = pattern.substring(index, digitsEnd).toInt()
                require(argument > 0) { "Argument index 0 in \"$pattern\"" }
                index = digitsEnd + 1
            }

            var flags = 0
            while (index < pattern.length) {
                val flag = FLAG_BITS[pattern[index]] ?: break
                require(!flags.has(flag)) { "Duplicate flag '${pattern[index]}' in \"$pattern\"" }
                flags = flags or flag
                index++
            }

            val widthEnd = digitsEnd(pattern, index)
            val width = if (widthEnd > index) pattern.substring(index, widthEnd).toInt() else -1
            index = widthEnd

            var precision = -1
            if (index < pattern.length && pattern[index] == '.') {
                val precisionEnd = digitsEnd(pattern, index + 1)
                require(precisionEnd > index + 1) { "'.' with no precision in \"$pattern\"" }
                precision = pattern.substring(index + 1, precisionEnd).toInt()
                index = precisionEnd
            }

            require(index < pattern.length) { "Format specifier with no conversion in \"$pattern\"" }
            val conversion = pattern[index]
            index++

            val position = if (argument > 0) argument - 1 else ordinaryArgument++
            require(position in args.indices) {
                "\"$pattern\" wants argument ${position + 1}, got ${args.size}"
            }
            out.append(render(conversion, args[position], flags, width, precision, pattern, symbols))
        }
        return out.toString()
    }

    private fun digitsEnd(pattern: String, from: Int): Int {
        var end = from
        while (end < pattern.length && pattern[end] in '0'..'9') end++
        return end
    }

    private fun render(
        conversion: Char,
        argument: Any,
        flags: Int,
        width: Int,
        precision: Int,
        pattern: String,
        symbols: NumberSymbols,
    ): String = when (conversion) {
        's', 'S' -> renderString(conversion, argument, flags, width, precision, pattern)
        'd' -> renderInteger(argument, flags, width, precision, pattern, symbols)
        'f' -> renderFixed(argument, flags, width, precision, pattern, symbols)
        else -> throw IllegalArgumentException(
            "Unsupported conversion '%$conversion' in \"$pattern\": only %s, %S, %d, %f and %% are formatted. " +
                "Add it to UiStringFormat (and to FormatterParityTest) rather than working around it at the call site."
        )
    }

    private fun renderString(
        conversion: Char,
        argument: Any,
        flags: Int,
        width: Int,
        precision: Int,
        pattern: String,
    ): String {
        require(flags.only(MINUS)) { "Flags other than '-' on %$conversion in \"$pattern\"" }
        var text = argument.toString()
        if (conversion == 'S') text = text.uppercase()
        if (precision >= 0 && text.length > precision) text = text.substring(0, precision)
        return pad(text, width, flags.has(MINUS))
    }

    private fun renderInteger(
        argument: Any,
        flags: Int,
        width: Int,
        precision: Int,
        pattern: String,
        symbols: NumberSymbols,
    ): String {
        require(precision < 0) { "Precision on %d in \"$pattern\"" }
        val value = when (argument) {
            is Int -> argument.toLong()
            is Long -> argument
            is Short -> argument.toLong()
            is Byte -> argument.toLong()
            else -> throw IllegalArgumentException(
                "%d in \"$pattern\" got ${argument::class.simpleName}; pass an Int or a Long"
            )
        }
        // Long.MIN_VALUE has no positive counterpart, so the magnitude comes from the text, not from abs().
        val magnitude = value.toString().removePrefix("-")
        return signAndPad(magnitude, value < 0, flags, width, pattern, symbols)
    }

    private fun renderFixed(
        argument: Any,
        flags: Int,
        width: Int,
        precision: Int,
        pattern: String,
        symbols: NumberSymbols,
    ): String {
        val value = when (argument) {
            is Double -> argument
            is Float -> argument.toDouble()
            else -> throw IllegalArgumentException(
                "%f in \"$pattern\" got ${argument::class.simpleName}; pass a Double or a Float"
            )
        }
        val negative = value < 0.0 || (value == 0.0 && 1.0 / value < 0.0)
        if (value.isNaN()) return pad("NaN", width, flags.has(MINUS))
        // java.util.Formatter applies the sign flags to Infinity too, '(' included: "%(f" of -Infinity
        // is "(Infinity)". Zero padding and grouping are not applied to a non-numeric value.
        if (value.isInfinite()) {
            return signAndPad("Infinity", negative, flags and ZERO.inv() and COMMA.inv(), width, pattern, symbols)
        }
        val digits = if (precision < 0) DEFAULT_PRECISION else precision
        return signAndPad(fixedDigits(if (negative) -value else value, digits), negative, flags, width, pattern, symbols)
    }

    /**
     * `magnitude` (already unsigned, ASCII) with the sign the flags ask for, grouped, zero or space padded to
     * `width`, and finally written in `symbols`' digits — the order `java.util.Formatter` uses. The sign is never
     * localised and the zero padding is: `%,010d` of `1234567` in `de` is `01.234.567`.
     */
    private fun signAndPad(
        magnitude: String,
        negative: Boolean,
        flags: Int,
        width: Int,
        pattern: String,
        symbols: NumberSymbols,
    ): String {
        require(!(flags.has(PLUS) && flags.has(SPACE))) { "Both '+' and ' ' in \"$pattern\"" }
        require(!(flags.has(MINUS) && flags.has(ZERO))) { "Both '-' and '0' in \"$pattern\"" }
        require(!(flags.has(ZERO) && width < 0)) { "'0' with no width in \"$pattern\"" }
        val grouped = if (flags.has(COMMA)) symbols.group(magnitude) else magnitude
        val prefix = when {
            negative && flags.has(PAREN) -> "("
            negative -> "-"
            else -> signOf(flags)
        }
        val suffix = if (negative && flags.has(PAREN)) ")" else ""
        if (flags.has(ZERO)) {
            val zeros = width - prefix.length - grouped.length - suffix.length
            return prefix + symbols.localize("0".repeat(maxOf(zeros, 0)) + grouped) + suffix
        }
        return pad(prefix + symbols.localize(grouped) + suffix, width, flags.has(MINUS))
    }

    private fun signOf(flags: Int): String = when {
        flags.has(PLUS) -> "+"
        flags.has(SPACE) -> " "
        else -> ""
    }

    private fun pad(text: String, width: Int, left: Boolean): String {
        val missing = width - text.length
        if (missing <= 0) return text
        return if (left) text + " ".repeat(missing) else " ".repeat(missing) + text
    }

    private const val DEFAULT_PRECISION = 6
    private const val ESTIMATED_GROWTH = 16

    private const val MINUS = 1
    private const val ZERO = 2
    private const val PLUS = 4
    private const val SPACE = 8
    private const val COMMA = 16
    private const val PAREN = 32

    private val FLAG_BITS = mapOf('-' to MINUS, '0' to ZERO, '+' to PLUS, ' ' to SPACE, ',' to COMMA, '(' to PAREN)

    /** The parsed flag set of one specifier, as a bit set: no allocation while parsing. */
    private fun Int.has(flag: Int): Boolean = this and flag != 0

    private fun Int.only(flag: Int): Boolean = this and flag.inv() == 0
}

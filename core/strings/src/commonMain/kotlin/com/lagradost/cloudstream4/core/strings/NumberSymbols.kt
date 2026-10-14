package com.lagradost.cloudstream4.core.strings

/**
 * The digits and separators one locale writes a number with, as [UiStringFormat] needs them to match
 * `String.format(Locale.getDefault(), …)` byte for byte.
 *
 * ## What was measured, and on what
 *
 * Measured on **Temurin JDK 17.0.20.1** (CLDR locale provider, the default since JDK 9) by running `String.format(locale, …)` over all 1017 `Locale.getAvailableLocales()` and
 * over the 62 locales this module generates. `java.util.Formatter.localizedMagnitude` is the method being mirrored.
 *
 * **That JDK is not pinned for this module** (`INF-06`). `jdkToolchain = "17"` in
 * `gradle/libs.versions.toml` is consumed in exactly one place — `app/build.gradle.kts`, for legacy
 * `:app`'s javac. No `jvmToolchain(…)` exists anywhere in `build-logic/convention/`, so every
 * `:core:*` module compiles and tests on **whatever JVM started the Gradle daemon**. An earlier
 * version of this KDoc cited that property as if it governed this module; it does not, which is why
 * the table below can disagree with a local run. Set `JAVA_HOME` to the Temurin 17 named above, or
 * expect drift: JDK 24 bumped CLDR to 46, moving bare `ar` from Arabic-Indic digits to ASCII
 * (JDK-8333582, CLDR-17553).
 *
 * - **Digits are substituted for `%d` and `%f`, never for `%s`.** `ar` writes `%d` of `1234567` as
 *   `١٢٣٤٥٦٧` and `%s` of the same `Int` as `1234567`, because `%s` is `toString()`. Of the generated locales,
 *   `ar` and `ckb` use `٠` (U+0660), `fa` uses `۰` (U+06F0), `as`/`bn` use `০` (U+09E6), `ne` uses `०` (U+0966)
 *   and `my` uses `၀` (U+1040). [zeroDigit] is the digit `0` maps to; `1`…`9` follow it in code-point order.
 * - **Zero padding uses the localised zero.** `%05d` of `42` in `ar` is `٠٠٠٤٢`, and the pad is *not* re-grouped:
 *   `%,010d` of `1234567` in `de` is `01.234.567`.
 * - **Signs are never localised.** `-`, `+`, `(`, `)` and the space flag stay ASCII even where
 *   `DecimalFormatSymbols.getMinusSign()` is `−` (U+2212, e.g. `fa`), because `Formatter.leadingSign` appends a
 *   literal `'-'`. So do `NaN` and `Infinity`.
 * - **Grouping is uniform, at [groupingSize] digits.** `Formatter` reads
 *   `(NumberFormat.getNumberInstance(locale) as DecimalFormat).groupingSize`, and `java.text.DecimalFormat` has no
 *   secondary grouping — so the lakh/crore worry does **not** materialise: `hi` and `en-IN` both format `%,d` of
 *   `1234567` as `1,234,567`, not `12,34,567`. All 1017 JDK 17 locales report size 3 with grouping used, except
 *   `en-US-POSIX` (`#0.######`), which reports size 0 and grouping unused; `Formatter` then emits no separator at
 *   all. [groupingSize] `<= 0` models exactly that case.
 *
 * Android runs the same OpenJDK-derived `Formatter` over the same `java.text` locale data, so the `androidMain`
 * and `jvmMain` actuals read the same two APIs.
 */
internal data class NumberSymbols(
    val zeroDigit: Char,
    val decimalSeparator: Char,
    val groupingSeparator: Char,
    val groupingSize: Int,
) {
    internal companion object {
        /** ASCII digits, `.` and `,` in groups of three: what `java.util.Formatter` does with `Locale.ROOT`. */
        val ROOT = NumberSymbols(zeroDigit = '0', decimalSeparator = '.', groupingSeparator = ',', groupingSize = 3)

        /** The grouping size of the CLDR `#,##0.###` pattern, used when a platform exposes no size of its own. */
        const val DEFAULT_GROUPING_SIZE = 3
    }
}

/**
 * The symbols of the platform's *current* locale.
 *
 * Read once per [UiStringFormat.format] call and threaded down as a parameter — never cached in a property. An
 * in-app language change (`CommonActivity.setLocale` + `recreate()`) moves `Locale.getDefault()` under a live
 * process, and a cached copy would keep writing the old locale's digits until restart.
 */
internal expect fun localeNumberSymbols(): NumberSymbols

/**
 * [magnitude] — an unsigned decimal in ASCII digits, `.` for the point — with an ASCII `,` every [groupingSize]
 * digits of its whole part, or unchanged when this locale does not group.
 *
 * The separator inserted here is the ASCII placeholder; [localize] turns it into [groupingSeparator]. Working in
 * ASCII until the last step keeps the digit arithmetic in one place.
 */
internal fun NumberSymbols.group(magnitude: String): String {
    if (groupingSize <= 0) return magnitude
    val dot = magnitude.indexOf('.')
    val whole = if (dot < 0) magnitude else magnitude.substring(0, dot)
    val rest = if (dot < 0) "" else magnitude.substring(dot)
    val out = StringBuilder(whole.length + whole.length / groupingSize + rest.length)
    whole.forEachIndexed { at, digit ->
        if (at > 0 && (whole.length - at) % groupingSize == 0) out.append(',')
        out.append(digit)
    }
    return out.append(rest).toString()
}

/**
 * [text]'s ASCII digits, `.` and `,` rewritten as this locale writes them.
 *
 * Every other character is left alone, which is what keeps `NaN`, `Infinity` and the sign characters ASCII, as
 * `java.util.Formatter` leaves them.
 */
internal fun NumberSymbols.localize(text: String): String {
    if (this == NumberSymbols.ROOT) return text
    val out = StringBuilder(text.length)
    for (char in text) {
        out.append(
            when {
                char in '0'..'9' -> zeroDigit + (char - '0')
                char == '.' -> decimalSeparator
                char == ',' -> groupingSeparator
                else -> char
            }
        )
    }
    return out.toString()
}

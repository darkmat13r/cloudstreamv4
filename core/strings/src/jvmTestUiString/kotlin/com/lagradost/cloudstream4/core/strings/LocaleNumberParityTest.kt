package com.lagradost.cloudstream4.core.strings

import com.lagradost.cloudstream4.core.strings.FormatCorpus.ARGUMENT_SETS
import com.lagradost.cloudstream4.core.strings.FormatCorpus.argumentsFor
import com.lagradost.cloudstream4.core.strings.StringsOracle.show
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [UiStringFormat] against `java.util.Formatter` **constructed with the same locale**, over the whole generated
 * corpus, in every locale whose digits or separators differ from ASCII.
 *
 * This is the half `FormatterParityTest` cannot cover: that suite pins one locale (`Locale.ROOT`) over every
 * *shape*, this one pins one shape set over every *locale*. Both are needed — the corpus contains no `%,d` at all
 * (measured: zero grouping specifiers across all 63 `values*` directories), so only the hand-written cases here
 * exercise the grouping separator, and only the non-ASCII-digit locales exercise the digit substitution.
 *
 * [LOCALES] is the seven the owner asked for plus five chosen for what they prove: `bn`, `fa`, `my` and `ne` are
 * the remaining distinct zero digits among the generated locales, and `en-IN` is the lakh/crore question —
 * `java.text.DecimalFormat` has no secondary grouping, so it groups in threes like everyone else. See
 * [NumberSymbols] for the full measurement.
 */
class LocaleNumberParityTest {

    private val previousLocale = Locale.getDefault()

    @AfterTest
    fun restoreTheDefaultLocale() = Locale.setDefault(previousLocale)

    @Test
    fun everyGeneratedValueAgreesWithJavaFormatterInEveryLocale() {
        assertTrue(FormatCorpus.values.size > 1000, "only ${FormatCorpus.values.size} values with a '%'")
        val failures = mutableListOf<String>()
        for (tag in LOCALES) {
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            var agreed = 0
            var rejected = 0
            for ((key, pattern) in FormatCorpus.values) {
                for (arguments in ARGUMENT_SETS) {
                    val args = argumentsFor(pattern, arguments)
                    val ours = runCatching { UiStringFormat.format(pattern, args) }
                    val javas = runCatching { FormatCorpus.javaFormat(locale, pattern, args) }
                    when {
                        ours.isSuccess && javas.isSuccess -> {
                            agreed++
                            if (ours.getOrThrow() != javas.getOrThrow()) {
                                failures += "[$tag] $key args=$args\n    ours=${show(ours.getOrThrow())}" +
                                    "\n    java=${show(javas.getOrThrow())}"
                            }
                        }
                        ours.isFailure && javas.isFailure -> rejected++
                        ours.isFailure -> failures += "[$tag] $key args=$args: java formatted " +
                            "${show(javas.getOrThrow())}, we threw ${ours.exceptionOrNull()}"
                        else -> failures += "[$tag] $key args=$args: we formatted ${show(ours.getOrThrow())}, " +
                            "java threw ${javas.exceptionOrNull()}"
                    }
                }
            }
            println("[locale] $tag: ${FormatCorpus.values.size} values, $agreed agreed, $rejected rejected by both")
        }
        if (failures.isNotEmpty()) {
            fail("${failures.size} disagreements with java.util.Formatter:\n" + failures.take(20).joinToString("\n"))
        }
    }

    /**
     * The specifiers the corpus does not contain, in every locale: grouping, zero padding over localised digits,
     * the sign flags (never localised), and the non-finite values (never localised either).
     *
     * This is the case list that catches a broken grouping separator — a green corpus run does not, because the
     * corpus has no `%,d`.
     */
    @Test
    fun handWrittenSpecifiersMatchJavaInEveryLocale() {
        val failures = mutableListOf<String>()
        for (tag in LOCALES) {
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            for ((pattern, args) in CASES) {
                val ours = runCatching { UiStringFormat.format(pattern, args) }
                val javas = runCatching { FormatCorpus.javaFormat(locale, pattern, args) }
                if (ours.isFailure && javas.isFailure) continue
                if (ours.isSuccess && javas.isSuccess && ours.getOrThrow() == javas.getOrThrow()) continue
                failures += "[$tag] ${show(pattern)} args=$args " +
                    "ours=${ours.map(::show)} java=${javas.map(::show)}"
            }
        }
        println("[locale] ${CASES.size} hand-written cases across ${LOCALES.size} locales")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /**
     * The symbols are read **per call**, not once into a property.
     *
     * Three `format` calls in one JVM, with the default locale moved between them: the output follows. This is the
     * only thing that catches hoisting `localeNumberSymbols()` into a `val` on [UiStringFormat], which is otherwise
     * invisible to a suite that runs each locale in its own pass.
     */
    @Test
    fun theSymbolsAreReadOnEveryCall() {
        val pattern = "%,.2f"
        val args = listOf<Any>(1234567.891)
        Locale.setDefault(Locale.forLanguageTag("de"))
        val german = UiStringFormat.format(pattern, args)
        Locale.setDefault(Locale.forLanguageTag("ar"))
        val arabic = UiStringFormat.format(pattern, args)
        Locale.setDefault(Locale.forLanguageTag("en"))
        val english = UiStringFormat.format(pattern, args)

        assertEquals("1.234.567,89", german)
        assertEquals("١٬٢٣٤٬٥٦٧٫٨٩", arabic)
        assertEquals("1,234,567.89", english)
    }

    /**
     * The measured symbol table, pinned. A JDK or CLDR bump that moves a separator or a digit set fails here
     * first, with the locale named, rather than as a wall of corpus diffs.
     *
     * Measured on Temurin 17.0.20.1; see the [NumberSymbols] KDoc.
     */
    @Test
    fun theMeasuredSymbolsAreWhatThePlatformStillReports() {
        val expected = mapOf(
            "en" to NumberSymbols('0', '.', ',', 3),
            "de" to NumberSymbols('0', ',', '.', 3),
            "fr" to NumberSymbols('0', ',', ' ', 3),
            "ru" to NumberSymbols('0', ',', ' ', 3),
            "tr" to NumberSymbols('0', ',', '.', 3),
            "ar" to NumberSymbols('٠', '٫', '٬', 3),
            "fa" to NumberSymbols('۰', '٫', '٬', 3),
            "bn" to NumberSymbols('০', '.', ',', 3),
            "ne" to NumberSymbols('०', '.', ',', 3),
            "my" to NumberSymbols('၀', '.', ',', 3),
            // The lakh/crore question, answered: java.text.DecimalFormat has no secondary grouping.
            "hi" to NumberSymbols('0', '.', ',', 3),
            "en-IN" to NumberSymbols('0', '.', ',', 3),
        )
        for ((tag, symbols) in expected) {
            Locale.setDefault(Locale.forLanguageTag(tag))
            assertEquals(symbols, localeNumberSymbols(), "symbols moved for $tag")
        }
        Locale.setDefault(Locale.ROOT)
        assertEquals(NumberSymbols.ROOT, localeNumberSymbols(), "Locale.ROOT is no longer the ASCII baseline")
    }

    /** `%,d` in `hi` and `en-IN` is `1,234,567`, not `12,34,567`: the answer is java's, not CLDR's. */
    @Test
    fun indianLocalesGroupInThreesLikeJavaDoes() {
        for (tag in listOf("hi", "en-IN")) {
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            assertEquals("1,234,567", FormatCorpus.javaFormat(locale, "%,d", listOf(1234567)))
            assertEquals("1,234,567", UiStringFormat.format("%,d", listOf(1234567)))
        }
    }

    /** A locale whose number pattern has no grouping at all: `java.util.Formatter` emits no separator for `%,d`. */
    @Test
    fun aLocaleWithNoGroupingEmitsNoSeparator() {
        val locale = Locale.forLanguageTag("en-US-POSIX")
        assertEquals(0, javaNumberSymbols(locale).groupingSize, "en-US-POSIX stopped being the ungrouped locale")
        Locale.setDefault(locale)
        assertEquals("1234567", FormatCorpus.javaFormat(locale, "%,d", listOf(1234567)))
        assertEquals("1234567", UiStringFormat.format("%,d", listOf(1234567)))
    }

    private companion object {
        /** The seven the owner named, plus the remaining distinct zero digits and the Indian grouping case. */
        val LOCALES = listOf("en", "de", "fr", "ru", "ar", "hi", "tr", "bn", "fa", "my", "ne", "en-IN")

        val CASES: List<Pair<String, List<Any>>> = listOf(
            "%,d" to listOf<Any>(1234567),
            "%,d" to listOf<Any>(-1234567),
            "%,d" to listOf<Any>(1234567890),
            "%,d" to listOf<Any>(123),
            "%,d" to listOf<Any>(Long.MIN_VALUE),
            "%,010d" to listOf<Any>(1234567),
            "%,(d" to listOf<Any>(-1234567),
            "%,.2f" to listOf<Any>(1234567.891),
            "%,.0f" to listOf<Any>(1234567.891),
            "%,15.2f|" to listOf<Any>(1234.5),
            "%,-15.2f|" to listOf<Any>(1234.5),
            "%d" to listOf<Any>(0),
            "%d" to listOf<Any>(-42),
            "%05d" to listOf<Any>(42),
            "%05d" to listOf<Any>(-42),
            "%+d" to listOf<Any>(42),
            "% d" to listOf<Any>(42),
            "% d" to listOf<Any>(-42),
            "%(d" to listOf<Any>(-42),
            "%10d|" to listOf<Any>(42),
            "%-10d|" to listOf<Any>(42),
            "%.2f" to listOf<Any>(1.5),
            "%.2f" to listOf<Any>(-0.0),
            "%.2f" to listOf<Any>(8.475),
            "%.6f" to listOf<Any>(1.0 / 3.0),
            "%.2f" to listOf<Any>(1.0e20),
            "%012.2f" to listOf<Any>(-1.5),
            "%f" to listOf<Any>(Double.NaN),
            "%f" to listOf<Any>(Double.POSITIVE_INFINITY),
            "%(f" to listOf<Any>(Double.NEGATIVE_INFINITY),
            "%012f" to listOf<Any>(Double.POSITIVE_INFINITY),
            // %s is toString(): never localised, even for a number.
            "%s" to listOf<Any>(1234567),
            "%s" to listOf<Any>(1.5),
            "%10s|" to listOf<Any>(42),
            "%1\$d %1\$d %2\$s" to listOf<Any>(1234567, "x"),
        )
    }
}

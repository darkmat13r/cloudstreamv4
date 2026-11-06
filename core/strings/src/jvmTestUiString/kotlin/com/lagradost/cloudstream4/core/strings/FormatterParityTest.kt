package com.lagradost.cloudstream4.core.strings

import com.lagradost.cloudstream4.core.strings.FormatCorpus.ARGUMENT_SETS
import com.lagradost.cloudstream4.core.strings.FormatCorpus.argumentsFor
import com.lagradost.cloudstream4.core.strings.FormatCorpus.scan
import com.lagradost.cloudstream4.core.strings.StringsOracle.show
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [UiStringFormat] against `java.util.Formatter` (`Locale.ROOT`) over **every generated value in every generated
 * locale** that contains a `%`, with three sets of representative arguments, plus a hand-written suite for the
 * specifiers the corpus does not happen to contain.
 *
 * The assertion is *agreement*, not "no exception": a value formats to the same characters as `java.util.Formatter`,
 * or both reject it. That is what makes it survive Weblate — a translator who introduces `%x` or `%b` fails the
 * test if we would format it differently from Android's `String.format`, and a translator who writes `100 %` in an
 * argument-less string changes nothing, because nothing formats an argument-less string.
 *
 * [UiStringFormat] reads the **process default locale**, so every case here runs with it pinned to `Locale.ROOT`:
 * this suite is the shape-by-shape half, and `LocaleNumberParityTest` is the locale-by-locale half.
 */
class FormatterParityTest {

    private val corpus = FormatCorpus.values
    private val previousLocale = Locale.getDefault()

    @BeforeTest
    fun formatInTheRootLocale() = Locale.setDefault(Locale.ROOT)

    @AfterTest
    fun restoreTheDefaultLocale() = Locale.setDefault(previousLocale)

    /**
     * The values `%f` reaches that are not finite numbers. `java.util.Formatter` still applies the sign flags to
     * them — `"%(f"` of `-Infinity` is `"(Infinity)"` — and does not zero-pad a non-numeric value.
     */
    @Test
    fun nonFiniteFloatsAgreeWithJavaFormatter() {
        val patterns = listOf("%f", "%(f", "%+f", "% f", "%12f|", "%-12f|", "%012f|", "%(12f|", "%,f")
        val values = listOf(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN, -0.0, 0.0)
        val failures = mutableListOf<String>()
        for (pattern in patterns) {
            for (value in values) {
                val ours = runCatching { UiStringFormat.format(pattern, listOf(value)) }
                val javas = runCatching { javaFormat(pattern, listOf(value)) }
                when {
                    ours.isSuccess && javas.isSuccess && ours.getOrThrow() != javas.getOrThrow() ->
                        failures += "$pattern with $value: ours ${show(ours.getOrThrow())}, java ${show(javas.getOrThrow())}"
                    ours.isSuccess != javas.isSuccess ->
                        failures += "$pattern with $value: ours ${ours.exceptionOrNull()?.let { "threw" } ?: "formatted"}, " +
                            "java ${javas.exceptionOrNull()?.let { "threw" } ?: "formatted"}"
                }
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n", prefix = "\n"))
    }

    @Test
    fun everyGeneratedValueAgreesWithJavaFormatter() {
        assertTrue(corpus.size > 1000, "only ${corpus.size} generated values contain a '%'; the oracle looks wrong")
        val failures = mutableListOf<String>()
        var formatted = 0
        var rejected = 0
        for ((key, pattern) in corpus) {
            for (arguments in ARGUMENT_SETS) {
                val args = argumentsFor(pattern, arguments)
                val ours = runCatching { UiStringFormat.format(pattern, args) }
                val javas = runCatching { javaFormat(pattern, args) }
                when {
                    ours.isSuccess && javas.isSuccess -> {
                        formatted++
                        if (ours.getOrThrow() != javas.getOrThrow()) {
                            failures += "$key args=$args\n    ours=${show(ours.getOrThrow())}" +
                                "\n    java=${show(javas.getOrThrow())}"
                        }
                    }
                    ours.isFailure && javas.isFailure -> rejected++
                    ours.isFailure -> failures += "$key args=$args: java formatted " +
                        "${show(javas.getOrThrow())}, we threw ${ours.exceptionOrNull()}"
                    else -> failures += "$key args=$args: we formatted ${show(ours.getOrThrow())}, " +
                        "java threw ${javas.exceptionOrNull()}"
                }
            }
        }
        println("[format] ${corpus.size} values with a '%': $formatted agreed, $rejected rejected by both")
        if (failures.isNotEmpty()) {
            fail("${failures.size} of ${corpus.size * ARGUMENT_SETS.size} disagree with java.util.Formatter:\n" +
                failures.take(20).joinToString("\n"))
        }
    }

    /**
     * Every value whose specifiers are all ones we claim to support really formats — a formatter that threw on
     * everything would satisfy "both reject it" above, and this is what stops it.
     */
    @Test
    fun everyValueWithOnlySupportedSpecifiersFormats() {
        val supported = corpus.filter { (_, pattern) ->
            // No flags: `% s` in "…100 % svjetline ekrana" is a space flag on %s, which java.util.Formatter rejects
            // as well. Every deliberate specifier in app/src/main/res is plain.
            scan(pattern).all { it.conversion in "sSdf%" && it.flags.isEmpty() }
        }
        assertTrue(supported.size > 1000, "only ${supported.size} supported values; the corpus looks wrong")
        val conversions = supported.flatMap { scan(it.value) }.map { it.conversion }.toSortedSet()
        // One `%%` since `KMP-43`, `downloads_watched` ("%d%% watched"), in all 62 directories; every other literal percent is bare,
        // as in `…100%`.
        assertEquals(sortedSetOf('%', 'd', 'f', 's'), conversions, "the corpus stopped exercising a conversion")
        for ((key, pattern) in supported) {
            for (arguments in ARGUMENT_SETS) {
                val args = argumentsFor(pattern, arguments)
                runCatching { UiStringFormat.format(pattern, args) }
                    .onFailure { fail("$key ${show(pattern)} args=$args threw $it") }
            }
        }
        println("[format] ${supported.size} of ${corpus.size} values use only plain %s/%d/%f")
    }

    /** The census the plan's survey asked for, over the generated corpus rather than the raw XML. */
    @Test
    fun specifierCensus() {
        val census = corpus.flatMap { scan(it.value) }
            .groupingBy { it.shape }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
        println("[format] specifier shapes across ${StringsOracle.emitted.size} generated values:")
        census.forEach { (shape, count) -> println("  ${shape.padEnd(10)} $count") }
        assertTrue(census.any { it.first == "%s" } && census.any { it.first == "%d" }, "census is empty")
    }

    @Test
    fun handWrittenSpecifiersMatchJava() {
        val cases = listOf(
            "%s" to listOf<Any>("x"),
            "%S" to listOf<Any>("mixedCase"),
            "%10s|" to listOf<Any>("x"),
            "%-10s|" to listOf<Any>("x"),
            "%.3s" to listOf<Any>("abcdef"),
            "%d" to listOf<Any>(0),
            "%d" to listOf<Any>(-42),
            "%05d" to listOf<Any>(42),
            "%05d" to listOf<Any>(-42),
            "%,d" to listOf<Any>(1234567),
            "%,d" to listOf<Any>(-1234567),
            "%+d" to listOf<Any>(42),
            "% d" to listOf<Any>(42),
            "% d" to listOf<Any>(-42),
            "%(d" to listOf<Any>(-42),
            "%10d|" to listOf<Any>(42),
            "%-10d|" to listOf<Any>(42),
            "%d" to listOf<Any>(Long.MIN_VALUE),
            "%d" to listOf<Any>(Long.MAX_VALUE),
            "%f" to listOf<Any>(1.5),
            "%.0f" to listOf<Any>(0.5),
            "%.0f" to listOf<Any>(1.5),
            "%.0f" to listOf<Any>(2.5),
            "%.2f" to listOf<Any>(8.475),
            "%.2f" to listOf<Any>(1.005),
            "%.2f" to listOf<Any>(0.125),
            "%.2f" to listOf<Any>(-0.001),
            "%.1f" to listOf<Any>(0.06),
            "%.2f" to listOf<Any>(9.999),
            "%.2f" to listOf<Any>(1.0e20),
            "%.2f" to listOf<Any>(1.0e-20),
            "%.6f" to listOf<Any>(1.0 / 3.0),
            "%.2f" to listOf<Any>(-0.0),
            "%,.2f" to listOf<Any>(1234567.891),
            "%08.2f" to listOf<Any>(-1.5),
            "%.2f" to listOf<Any>(1.5f),
            "%1\$s %1\$s %2\$d" to listOf<Any>("a", 1),
            "%2\$d %1\$s" to listOf<Any>("a", 1),
            "%s %1\$s %s" to listOf<Any>("a", "b"),
            "100%%" to listOf<Any>("unused"),
            "%s%%%d" to listOf<Any>("a", 1),
            // Rejected by both.
            "%" to listOf<Any>("a"),
            "%<s" to listOf<Any>("a"),
            "%#s" to listOf<Any>("a"),
            "% s" to listOf<Any>("a"),
            "%0d" to listOf<Any>(1),
            "%--d" to listOf<Any>(1),
            "%+ d" to listOf<Any>(1),
            "%-05d" to listOf<Any>(1),
            "%d" to listOf<Any>("not a number"),
            "%f" to listOf<Any>(1),
            "%.2d" to listOf<Any>(1),
            "%2\$s" to listOf<Any>("only one"),
            "%s %s" to listOf<Any>("only one"),
        )
        val failures = mutableListOf<String>()
        for ((pattern, args) in cases) {
            val ours = runCatching { UiStringFormat.format(pattern, args) }
            val javas = runCatching { javaFormat(pattern, args) }
            if (ours.isFailure && javas.isFailure) continue
            if (ours.isSuccess && javas.isSuccess && ours.getOrThrow() == javas.getOrThrow()) continue
            failures += "${show(pattern)} args=$args ours=${ours.map(::show)} java=${javas.map(::show)}"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /**
     * The conversions `java.util.Formatter` has and we deliberately do not. None occurs in `app/src/main/res`, and
     * the corpus test above would fail the moment one did: we throw where Android's `String.format` would not.
     */
    @Test
    fun unsupportedConversionsFailLoudly() {
        listOf("%b" to listOf<Any>("a"), "%x" to listOf<Any>(255), "%n" to listOf<Any>("a"),
            "%o" to listOf<Any>(8), "%e" to listOf<Any>(1.0), "%h" to listOf<Any>("a"),
            "%c" to listOf<Any>('a'), "%tY" to listOf<Any>(0L)).forEach { (pattern, args) ->
            val ours = runCatching { UiStringFormat.format(pattern, args) }
            assertTrue(ours.isFailure, "$pattern must fail loudly, got ${ours.getOrNull()}")
            assertTrue(
                ours.exceptionOrNull() is IllegalArgumentException,
                "$pattern threw ${ours.exceptionOrNull()}",
            )
        }
    }

    @Test
    fun emptyArgumentsNeverTouchThePattern() {
        listOf("100%", "100 % de brillo", "%s", "%d", "%", "%%", "%1\$s Ep %2\$d").forEach {
            assertEquals(it, UiStringFormat.format(it, emptyList()), "empty args must pass $it through")
        }
    }

    private fun javaFormat(pattern: String, args: List<Any>): String =
        FormatCorpus.javaFormat(Locale.ROOT, pattern, args)
}

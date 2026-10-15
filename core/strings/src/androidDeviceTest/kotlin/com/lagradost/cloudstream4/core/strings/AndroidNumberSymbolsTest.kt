package com.lagradost.cloudstream4.core.strings

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `I18N-06`: the **android** actual of [localeNumberSymbols], measured on a device rather than argued.
 *
 * `LocaleNumberParityTest.theMeasuredSymbolsAreWhatThePlatformStillReports` pins the same table on the JVM, where
 * `java.text` is backed by the JDK's CLDR. Android's `java.text` is **ICU-backed**, and the measurement that
 * closed `I18N-06` found the two **do not agree**: nine of the twelve locales match, three do not.
 *
 * | locale | JVM, Temurin 17.0.20.1 | Android, API 36 |
 * |---|---|---|
 * | `fr` grouping separator | `U+0020` SPACE | `U+202F` NARROW NO-BREAK SPACE |
 * | `ru` grouping separator | `U+0020` SPACE | `U+00A0` NO-BREAK SPACE |
 * | `ar` zero / decimal / grouping | `U+0660 ٠` / `U+066B ٫` / `U+066C ٬` | `U+0030 0` / `U+002E .` / `U+002C ,` |
 *
 * **None of this is a defect in [UiStringFormat].** Its contract is to match `String.format` on the platform it
 * runs on, and the android actual reads the same two `java.text` APIs Android's own `Formatter` reads — so on
 * Android it matches Android. What was wrong was the assumption that the two platforms therefore produce the same
 * *output*; they read the same APIs over different locale data.
 *
 * The `ar` row is the one with a user-visible consequence, and the [NumberSymbols] KDoc already predicted it for
 * the JVM: CLDR 46 moved bare `ar` from Arabic-Indic digits to ASCII (JDK-8333582, CLDR-17553), which the JDK
 * picks up at 24. Android API 36 ships an ICU already past that point, so **Arabic on a modern Android writes
 * `1,234,567.89`, not `١٬٢٣٤٬٥٦٧٫٨٩`** — and has done all along, because this is what `String.format` does there.
 * `fa` is the instructive contrast: CLDR kept Persian digits as its default, so `fa` still writes `۱۲۳`.
 *
 * The `fr` and `ru` rows are the reason this needed measuring rather than reasoning: both separators are spaces
 * that print identically to `U+0020` in any log or assertion message. That is the "diverge silently" case
 * `I18N-06` named, and it is why [show] reports code points.
 *
 * This module has no Android unit-test target — adding one would need Robolectric, which the version catalog does
 * not carry — so a device test is the only way to read Android's tables. It is **not** part of `check` and not run
 * on CI, which has no emulator (`DEC-05`). Run it with:
 *
 * ```
 * ./gradlew :core:strings:connectedAndroidDeviceTest
 * ```
 */
class AndroidNumberSymbolsTest {

    private val previousLocale: Locale = Locale.getDefault()

    @AfterTest
    fun restoreTheDefaultLocale() = Locale.setDefault(previousLocale)

    /**
     * Android's own table, pinned. An ICU or CLDR bump that moves a digit set or a separator fails here first,
     * with the locale and the code point named.
     */
    @Test
    fun androidReportsTheSymbolsThisPlatformWasMeasuredToReport() {
        val drift = mutableListOf<String>()
        for ((tag, expected) in ANDROID_MEASURED_SYMBOLS) {
            Locale.setDefault(Locale.forLanguageTag(tag))
            val actual = localeNumberSymbols()
            if (actual != expected) drift += "$tag: measured ${expected.show()}, now reports ${actual.show()}"
        }
        assertEquals(emptyList<String>(), drift, "Android's locale data moved since I18N-06 measured it")
    }

    /**
     * The divergence from the JVM is **exactly** the three known rows.
     *
     * Asserted as a set, so this fails both when a new locale starts to differ and when a known one stops. Either
     * is a platform change worth knowing about, and either way the two tables and the KDoc above need updating
     * together.
     */
    @Test
    fun theDivergenceFromTheJvmIsExactlyTheThreeKnownLocales() {
        val diverging = mutableListOf<String>()
        for ((tag, jvm) in JVM_MEASURED_SYMBOLS) {
            Locale.setDefault(Locale.forLanguageTag(tag))
            if (localeNumberSymbols() != jvm) diverging += tag
        }
        assertEquals(KNOWN_DIVERGENT_LOCALES, diverging.sorted(), "the JVM/Android divergence set changed")
    }

    /** `Locale.ROOT` must still be the ASCII baseline here, as it is on the JVM. */
    @Test
    fun localeRootIsStillTheAsciiBaseline() {
        Locale.setDefault(Locale.ROOT)
        assertEquals(NumberSymbols.ROOT, localeNumberSymbols())
    }

    /**
     * Arabic on Android writes ASCII digits, end to end through [UiStringFormat].
     *
     * The JVM suite asserts `١٬٢٣٤٬٥٦٧٫٨٩` for this exact input. **Do not "fix" either one** — each matches
     * `String.format` on its own platform, which is the contract.
     */
    @Test
    fun arabicOnAndroidWritesAsciiDigits() {
        Locale.setDefault(Locale.forLanguageTag("ar"))
        assertEquals("1,234,567.89", UiStringFormat.format("%,.2f", listOf(1234567.891)))
    }

    /** Persian keeps its native digits where Arabic lost them — the per-locale nature of the CLDR 46 change. */
    @Test
    fun persianOnAndroidStillWritesPersianDigits() {
        Locale.setDefault(Locale.forLanguageTag("fa"))
        assertEquals("۱٬۲۳۴٬۵۶۷٫۸۹", UiStringFormat.format("%,.2f", listOf(1234567.891)))
    }

    /**
     * The lakh/crore question, asked again on Android.
     *
     * ICU *does* model Indian grouping where `java.text.DecimalFormat` has no secondary grouping, so this was the
     * most likely place for the platforms to part company. Measured: they do not — both group in threes.
     */
    @Test
    fun indianLocalesStillGroupInThrees() {
        for (tag in listOf("hi", "en-IN")) {
            Locale.setDefault(Locale.forLanguageTag(tag))
            assertEquals(3, localeNumberSymbols().groupingSize, "grouping size moved for $tag")
            assertEquals("1,234,567", UiStringFormat.format("%,d", listOf(1234567)), "grouping moved for $tag")
        }
    }

    /** Code points, not glyphs: `fr` and `ru` differ from the JVM in characters that print the same. */
    private fun NumberSymbols.show(): String =
        "zero=${zeroDigit.hex()} decimal=${decimalSeparator.hex()} grouping=${groupingSeparator.hex()} size=$groupingSize"

    private fun Char.hex(): String = "U+%04X(%s)".format(code, this)

    private companion object {
        /** Measured on this module's `QA_36_1` AVD, API 36, by the run that closed `I18N-06`. */
        val ANDROID_MEASURED_SYMBOLS = mapOf(
            "en" to NumberSymbols('0', '.', ',', 3),
            "de" to NumberSymbols('0', ',', '.', 3),
            // U+202F NARROW NO-BREAK SPACE, where the JVM reports U+0020.
            "fr" to NumberSymbols('0', ',', ' ', 3),
            // U+00A0 NO-BREAK SPACE, where the JVM reports U+0020. Note it differs from `fr`'s, too.
            "ru" to NumberSymbols('0', ',', ' ', 3),
            "tr" to NumberSymbols('0', ',', '.', 3),
            // ASCII, where the JVM reports Arabic-Indic: CLDR 46 moved bare `ar`.
            "ar" to NumberSymbols('0', '.', ',', 3),
            "fa" to NumberSymbols('۰', '٫', '٬', 3),
            "bn" to NumberSymbols('০', '.', ',', 3),
            "ne" to NumberSymbols('०', '.', ',', 3),
            "my" to NumberSymbols('၀', '.', ',', 3),
            "hi" to NumberSymbols('0', '.', ',', 3),
            "en-IN" to NumberSymbols('0', '.', ',', 3),
        )

        /**
         * Copied verbatim from `LocaleNumberParityTest.theMeasuredSymbolsAreWhatThePlatformStillReports`, measured
         * on Temurin 17.0.20.1. Kept as a literal rather than shared with that test: the point is to compare two
         * platforms, and a shared constant that either one edits would compare a platform with itself.
         */
        val JVM_MEASURED_SYMBOLS = mapOf(
            "en" to NumberSymbols('0', '.', ',', 3),
            "de" to NumberSymbols('0', ',', '.', 3),
            "fr" to NumberSymbols('0', ',', ' ', 3),
            "ru" to NumberSymbols('0', ',', ' ', 3),
            "tr" to NumberSymbols('0', ',', '.', 3),
            "ar" to NumberSymbols('٠', '٫', '٬', 3),
            "fa" to NumberSymbols('۰', '٫', '٬', 3),
            "bn" to NumberSymbols('০', '.', ',', 3),
            "ne" to NumberSymbols('०', '.', ',', 3),
            "my" to NumberSymbols('၀', '.', ',', 3),
            "hi" to NumberSymbols('0', '.', ',', 3),
            "en-IN" to NumberSymbols('0', '.', ',', 3),
        )

        /** Sorted, because the assertion compares sorted lists. */
        val KNOWN_DIVERGENT_LOCALES = listOf("ar", "fr", "ru")
    }
}

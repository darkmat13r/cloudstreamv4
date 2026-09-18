package com.lagradost.cloudstream4.core.strings

import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.links_hidden
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getPluralString

/**
 * The canary for the plural guard: **Compose Multiplatform still throws.**
 *
 * `PluralRuleList.getInstance` returns an empty rule list for a language that is not a key of
 * `cldrPluralRuleListIndexByLocale`, and `getCategory` then runs `first {}` over it. When a Compose release fixes
 * that, this test fails — and [CmpPluralGuard] and everything that mentions it should go with it, rather than
 * quietly staying as a layer that no longer does anything.
 *
 * `LocaleLookupParityTest.publicPluralLookupReturnsAnAndroidCategoryText` is the wider half of the same pin: it
 * asserts the throwing set over *every* generated locale is exactly `apc`, `arz`, `qt`.
 */
class CmpPluralGuardCanaryTest {

    @Test
    fun composeStillThrowsForEveryGuardedLanguage() = runBlocking {
        for (language in CmpPluralGuard.LANGUAGES_WITHOUT_CLDR_RULES) {
            val environment = ComposeRuntime.environment(language, "", "")
            for (quantity in listOf(0, 1, 2, 5, 100)) {
                assertFailsWith<NoSuchElementException>("$language quantity=$quantity no longer throws") {
                    getPluralString(environment, Res.plurals.links_hidden, quantity)
                }
            }
        }
    }

    @Test
    fun composeResolvesPluralsForALanguageItHasRulesFor() = runBlocking {
        val environment = ComposeRuntime.environment("en", "", "")
        assertEquals("%d hidden link", getPluralString(environment, Res.plurals.links_hidden, 1))
        assertEquals("%d hidden links", getPluralString(environment, Res.plurals.links_hidden, 7))
    }

    @Test
    fun theGuardedSetIsTheOneTheParitySuitePins() {
        assertEquals(setOf("apc", "arz", "qt"), CmpPluralGuard.LANGUAGES_WITHOUT_CLDR_RULES)
        assertTrue(
            CmpPluralGuard.LANGUAGES_WITHOUT_CLDR_RULES.all { CmpPluralGuard.appliesTo(it) } &&
                !CmpPluralGuard.appliesTo("ar") && !CmpPluralGuard.appliesTo("en"),
        )
    }

    /** The record reader the guard falls back to, against the `.cvr` the Compose plugin packaged. */
    @Test
    fun theRecordReaderFindsTheOtherItem() {
        val text = """
            version:0
            plurals|links_hidden|ONE:JWQgaGlkZGVuIGxpbms=,OTHER:JWQgaGlkZGVuIGxpbmtz
            string|cast_format|Q2FzdDogJXM=
        """.trimIndent()
        assertEquals("%d hidden links", CmpPluralGuard.otherItemOf(text, "links_hidden"))
        assertEquals(null, CmpPluralGuard.otherItemOf(text, "downloads_active"))
        assertEquals(null, CmpPluralGuard.otherItemOf(text, "cast_format"))
    }
}

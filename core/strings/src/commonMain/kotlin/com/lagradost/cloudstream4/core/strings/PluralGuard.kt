package com.lagradost.cloudstream4.core.strings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import kotlin.io.encoding.Base64
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.pluralStringResource

/**
 * The plural guard of migration plan §7 Phase 3 S-b: Compose Multiplatform 1.12.0 **throws** on a plural lookup in a
 * language it has no CLDR rules for.
 *
 * `PluralRuleList.getInstance` returns an empty rule list when neither `<language>_<region>` nor `<language>` is a
 * key of `cldrPluralRuleListIndexByLocale`, and `getCategory` then calls `first {}` on an empty array:
 * `NoSuchElementException`, from a composable, before any item of the bag is read. Of the locales this module
 * generates, `apc`, `arz` and `qt` hit it — `LocaleLookupParityTest.publicPluralLookupReturnsAnAndroidCategoryText`
 * pins exactly that set, and [CmpPluralGuardCanaryTest] pins that Compose still throws, so both fail when a Compose
 * release fixes it and the guard can go.
 *
 * For those languages we use the bag's `other` item, as the plan specifies. That is a real loss for `arz`
 * (Egyptian Arabic has six Android categories); it is the accepted cost of not crashing.
 *
 * ## Why it reads the packaged record instead of asking Compose again
 *
 * Compose's own fallback (`item.items[category] ?: item.items[OTHER]`) is unreachable: the throw happens before the
 * bag is consulted, `ResourceEnvironment`'s constructor is `internal` so no substitute environment can be built, and
 * `Resource.items` and `getStringItem` are `internal` too. The only public way to the `other` item is the packaged
 * `.cvr` record, through `Res.readBytes` — the same records `GeneratedResourcesParityTest` reads with public API.
 *
 * Consequences, both deliberate:
 * - the record format (`version:0`, then `plurals|<key>|<CATEGORY>:<base64>,…`) is pinned to the Compose version
 *   `GeneratedResourcesParityTest.composeVersionIsTheVerifiedOne` asserts;
 * - the fallback reads **this module's** `Res`, so a `PluralStringResource` from another module does not resolve in
 *   a guarded language. `:core:strings` is meant to be the one string module; consolidate rather than work around.
 */
internal object CmpPluralGuard {

    /**
     * The languages this module generates that Compose 1.12.0 has no CLDR plural rules for.
     *
     * Checked before the lookup rather than caught after it because [pluralPattern] cannot catch: unwinding out of
     * a half-composed group corrupts the slot table. [loadPluralPattern] catches as well, so a language that starts
     * throwing before anyone updates this set degrades to `other` instead of crashing.
     */
    val LANGUAGES_WITHOUT_CLDR_RULES: Set<String> = setOf("apc", "arz", "qt")

    fun appliesTo(language: String): Boolean = language in LANGUAGES_WITHOUT_CLDR_RULES

    /** The `other` item of [res] in [language], or the base `values` one when that language has no bag. */
    @OptIn(ExperimentalResourceApi::class)
    suspend fun otherItem(res: PluralStringResource, language: String): String {
        for (directory in listOf("values-b+$language", "values")) {
            val file = runCatching { Res.readBytes("$directory/$RECORDS_FILE") }.getOrNull() ?: continue
            return otherItemOf(file.decodeToString(), res.key) ?: continue
        }
        error("No `other` item for plural `${res.key}` in `$language` or in the base `values`")
    }

    /** The `other` text of [key] in one `.cvr` file's [text], or null when the file has no such bag. */
    fun otherItemOf(text: String, key: String): String? {
        val prefix = "plurals|$key|"
        val record = text.lineSequence().firstOrNull { it.startsWith(prefix) } ?: return null
        val item = record.substring(prefix.length).splitToSequence(',')
            .firstOrNull { it.startsWith(OTHER_PREFIX) } ?: return null
        return Base64.decode(item.substring(OTHER_PREFIX.length)).decodeToString()
    }

    private const val RECORDS_FILE = "strings.commonMain.cvr"
    private const val OTHER_PREFIX = "OTHER:"
}

/**
 * The raw plural pattern for [quantity] in the composition's locale, with the [CmpPluralGuard] applied.
 *
 * The guarded branch is asynchronous — `Res.readBytes` is `suspend` and there is no common `runBlocking` — so it
 * renders empty for one frame. Compose's own path stays synchronous on every target this module builds for.
 */
@Composable
internal fun pluralPattern(res: PluralStringResource, quantity: Int): String {
    val language = currentLanguage()
    if (!CmpPluralGuard.appliesTo(language)) return pluralStringResource(res, quantity)
    val other = produceState<String?>(null, res, language) { value = CmpPluralGuard.otherItem(res, language) }
    return other.value.orEmpty()
}

/** The raw plural pattern for [quantity] in the process default locale, with the [CmpPluralGuard] applied. */
internal suspend fun loadPluralPattern(res: PluralStringResource, quantity: Int): String {
    val language = currentLanguage()
    if (CmpPluralGuard.appliesTo(language)) return CmpPluralGuard.otherItem(res, language)
    return try {
        getPluralString(res, quantity)
    } catch (_: NoSuchElementException) {
        CmpPluralGuard.otherItem(res, language)
    }
}

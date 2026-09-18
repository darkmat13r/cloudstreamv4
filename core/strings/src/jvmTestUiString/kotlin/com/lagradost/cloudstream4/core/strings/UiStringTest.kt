package com.lagradost.cloudstream4.core.strings

import com.lagradost.cloudstream4.core.strings.StringsOracle.Key
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.app_dub_sub_episode_text_format
import com.lagradost.cloudstream4.core.strings.generated.resources.cast_format
import com.lagradost.cloudstream4.core.strings.generated.resources.links_hidden
import com.lagradost.cloudstream4.core.strings.generated.resources.player_speed_text_format
import com.lagradost.cloudstream4.core.strings.generated.resources.volume_exceeded_100
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** [UiString.resolve] and [UiString.load]: formatting, the plural guard, argument-less values and equality. */
class UiStringTest {

    @Test
    fun rawIsItsOwnText() = runBlocking {
        assertEquals("already text", UiString.Raw("already text").load())
        assertEquals("100%", UiString.Raw("100%").load())
    }

    @Test
    fun resourcesFormatWithOurOwnFormatter() = SystemEnvironment.of("en") {
        runBlocking {
            // Compose's own argument overloads substitute only %N$s and %N$d, so each of these would come back
            // with its specifier intact if UiString used them.
            assertEquals("Cast: Naruto", UiString.Resource(Res.string.cast_format, listOf("Naruto")).load())
            assertEquals("Speed (1.25x)", UiString.Resource(Res.string.player_speed_text_format, listOf(1.25)).load())
            assertEquals(
                "Dub Ep 3",
                UiString.Resource(Res.string.app_dub_sub_episode_text_format, listOf("Dub", 3)).load(),
            )
        }
    }

    @Test
    fun argumentLessValuesPassThroughUnchanged() = SystemEnvironment.of("en") {
        runBlocking {
            // A literal trailing % is not a format string, and String.format would throw on it.
            val text = UiString.Resource(Res.string.volume_exceeded_100).load()
            assertTrue(text.endsWith("100%"), "expected a literal trailing %, got $text")
            assertEquals(StringsOracle.emitted[Key("values", "string", "volume_exceeded_100", "-")], text)
        }
    }

    @Test
    fun pluralUsesTheQuantityAsItsDefaultArgument() = SystemEnvironment.of("en") {
        runBlocking {
            assertEquals("1 hidden link", UiString.Plural(Res.plurals.links_hidden, 1).load())
            assertEquals("7 hidden links", UiString.Plural(Res.plurals.links_hidden, 7).load())
            assertEquals(
                "0 hidden links",
                UiString.Plural(Res.plurals.links_hidden, 0, args = listOf(0)).load(),
            )
        }
    }

    /**
     * The §7 S-b guard: Compose 1.12.0 throws for `apc`, `arz` and `qt`; `load()` returns the bag's `other` item.
     * `CmpPluralGuardCanaryTest` is the other half — it pins that Compose still throws.
     */
    @Test
    fun pluralsInLanguagesWithoutCldrRulesUseTheOtherItem() {
        for (language in CmpPluralGuard.LANGUAGES_WITHOUT_CLDR_RULES) {
            val other = itemOf(language, "other")
            val one = itemOf(language, "one")
            assertTrue(other.isNotEmpty(), "$language has no `other` item to fall back to")
            for (quantity in listOf(0, 1, 2, 3, 5, 11, 100)) {
                val text = SystemEnvironment.of(language) {
                    runBlocking { UiString.Plural(Res.plurals.links_hidden, quantity).load() }
                }
                assertEquals(UiStringFormat.format(other, listOf(quantity)), text, "$language quantity=$quantity")
            }
            // quantity 1 too: the guard is "always other", not "Compose's category when it has one".
            if (one != other) {
                val singular = SystemEnvironment.of(language) {
                    runBlocking { UiString.Plural(Res.plurals.links_hidden, 1).load() }
                }
                assertNotEquals(UiStringFormat.format(one, listOf(1)), singular, "$language used the `one` item")
            }
        }
    }

    /**
     * `load()` is exactly "Compose's raw value, then our formatter" for every shape, which is the one thing
     * `resolve()` also is — the two differ only in which Compose accessor fetches the raw value
     * (`stringResource`/`pluralStringResource` against `getString`/`getPluralString`).
     *
     * `resolve()` itself is **not** exercised here: composing it needs `DefaultComposeEnvironment`, which calls
     * `isSystemInDarkTheme()`, which on the JVM target asks skiko for the system theme and dies in a headless test
     * JVM — and Compose UI is not on this module's classpath to provide `LocalSystemTheme` or `LocalDensity`
     * around it. Covering the composable half needs a Robolectric `androidUnitTest` or a Compose UI test, both of
     * which are new dependencies; it is in the PR's open questions.
     */
    @Test
    fun loadIsTheRawValuePlusOurFormatter() = SystemEnvironment.of("en") {
        val samples = listOf(
            UiString.Raw("already text") to "already text",
            UiString.Resource(Res.string.cast_format, listOf("Naruto")) to
                UiStringFormat.format(rawString("cast_format"), listOf("Naruto")),
            UiString.Resource(Res.string.volume_exceeded_100) to rawString("volume_exceeded_100"),
            UiString.Resource(Res.string.app_dub_sub_episode_text_format, listOf("Dub", 3)) to
                UiStringFormat.format(rawString("app_dub_sub_episode_text_format"), listOf("Dub", 3)),
            UiString.Resource(Res.string.player_speed_text_format, listOf(1.25)) to
                UiStringFormat.format(rawString("player_speed_text_format"), listOf(1.25)),
            UiString.Plural(Res.plurals.links_hidden, 1) to
                UiStringFormat.format(itemOf("en", "one"), listOf(1)),
            UiString.Plural(Res.plurals.links_hidden, 7) to
                UiStringFormat.format(itemOf("en", "other"), listOf(7)),
        )
        val loaded = runBlocking { samples.map { it.first.load() } }
        assertEquals(samples.map { it.second }, loaded)
        assertTrue(loaded.none { it.contains("%s") || it.contains("%d") }, "a specifier survived: $loaded")
    }

    /** The base `values` text of a `<string>`, straight from the generator's aapt2 oracle. */
    private fun rawString(name: String): String =
        requireNotNull(StringsOracle.emitted[Key("values", "string", name, "-")]) { "no oracle value for $name" }

    @Test
    fun equalityIsStructural() {
        assertEquals(UiString.Raw("a"), UiString.Raw("a"))
        assertEquals(UiString.Raw("a").hashCode(), UiString.Raw("a").hashCode())
        assertNotEquals(UiString.Raw("a"), UiString.Raw("b"))

        val cast = UiString.Resource(Res.string.cast_format, listOf("x"))
        assertEquals(cast, UiString.Resource(Res.string.cast_format, listOf("x")))
        assertEquals(cast.hashCode(), UiString.Resource(Res.string.cast_format, listOf("x")).hashCode())
        assertNotEquals(cast, UiString.Resource(Res.string.cast_format, listOf("y")))
        assertNotEquals<UiString>(cast, UiString.Resource(Res.string.volume_exceeded_100, listOf("x")))
        assertEquals(emptyList(), UiString.Resource(Res.string.cast_format).args)

        val hidden = UiString.Plural(Res.plurals.links_hidden, 2)
        assertEquals(hidden, UiString.Plural(Res.plurals.links_hidden, 2))
        assertEquals(hidden.hashCode(), UiString.Plural(Res.plurals.links_hidden, 2).hashCode())
        assertNotEquals(hidden, UiString.Plural(Res.plurals.links_hidden, 3))
        assertEquals(listOf<Any>(2), hidden.args)

        assertNotEquals<UiString>(UiString.Raw("a"), cast)
    }

    /** The `links_hidden` item of [category] as the generator emitted it for [language], or the base one. */
    private fun itemOf(language: String, category: String): String =
        StringsOracle.emitted[Key("values-b+$language", "plurals", "links_hidden", category)]
            ?: StringsOracle.emitted[Key("values", "plurals", "links_hidden", category)].orEmpty()

}

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
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * [UiString.resolve] — the composable half — inside a real composition, hosted by [HeadlessComposition].
 *
 * `UiStringTest` covers [UiString.load], which is the same two steps (Compose's raw value, then our formatter)
 * reached through the suspend accessors. What only this suite can show is that the *composable* accessors are
 * wired the same way, and that the [CmpPluralGuard] branch really does render empty for one frame before the
 * `other` item arrives — `produceState` behaviour that has no counterpart on the `load()` path.
 */
class UiStringResolveTest {

    @Test
    fun rawIsItsOwnText() {
        assertEquals(listOf("already text"), distinct(UiString.Raw("already text")))
        assertEquals(listOf("100%"), distinct(UiString.Raw("100%")))
    }

    @Test
    fun resourceWithoutArgumentsIsTheRawValueUnchanged() {
        val text = single(UiString.Resource(Res.string.volume_exceeded_100))
        // A literal trailing % is not a format string, and String.format would throw on it.
        assertTrue(text.endsWith("100%"), "expected a literal trailing %, got $text")
        assertEquals(rawString("volume_exceeded_100"), text)
    }

    @Test
    fun resourceWithArgumentsGoesThroughOurOwnFormatter() {
        // Compose's own argument overloads substitute only %N$s and %N$d, so each of these would come back with
        // its specifier intact if resolve() used them.
        assertEquals("Cast: Naruto", single(UiString.Resource(Res.string.cast_format, listOf("Naruto"))))
        assertEquals("Speed (1.25x)", single(UiString.Resource(Res.string.player_speed_text_format, listOf(1.25))))
        assertEquals(
            "Dub Ep 3",
            single(UiString.Resource(Res.string.app_dub_sub_episode_text_format, listOf("Dub", 3))),
        )
    }

    @Test
    fun pluralPicksTheCategoryAndUsesTheQuantityAsItsArgument() {
        assertEquals("1 hidden link", single(UiString.Plural(Res.plurals.links_hidden, 1)))
        assertEquals("7 hidden links", single(UiString.Plural(Res.plurals.links_hidden, 7)))
        assertEquals("0 hidden links", single(UiString.Plural(Res.plurals.links_hidden, 0, args = listOf(0))))
    }

    /**
     * The guarded branch, frame by frame: `Res.readBytes` is `suspend` and there is no common `runBlocking`, so
     * `pluralPattern` returns `null.orEmpty()` on the first composition and the `other` item on the next.
     *
     * The empty first frame is the accepted cost recorded in [CmpPluralGuard]'s KDoc. It is asserted rather than
     * tolerated so that a future Compose release that makes the guard unnecessary shows up here too.
     */
    @Test
    fun pluralInAGuardedLanguageIsEmptyForOneFrameThenTheOtherItem() {
        for (language in CmpPluralGuard.LANGUAGES_WITHOUT_CLDR_RULES) {
            val other = itemOf(language, "other")
            assertTrue(other.isNotEmpty(), "$language has no `other` item to fall back to")
            for (quantity in listOf(0, 1, 7)) {
                val frames = SystemEnvironment.of(language) {
                    HeadlessComposition.frames(language, frames = 2) {
                        UiString.Plural(Res.plurals.links_hidden, quantity).resolve()
                    }
                }
                val expected = UiStringFormat.format(other, listOf(quantity))
                assertTrue(frames.size >= 2, "$language quantity=$quantity never recomposed: $frames")
                assertEquals("", frames.first(), "$language quantity=$quantity was not empty on the first frame")
                assertEquals(
                    List(frames.size - 1) { expected },
                    frames.drop(1),
                    "$language quantity=$quantity did not settle on the `other` item",
                )
            }
        }
    }

    /**
     * `resolve()` and `load()` agree for every shape. This is the assertion a `resolve()` that returned a constant
     * — or that stopped calling [UiStringFormat] — cannot satisfy.
     */
    @Test
    fun resolveAgreesWithLoadForEveryShape() {
        val samples = listOf(
            UiString.Raw("already text"),
            UiString.Resource(Res.string.volume_exceeded_100),
            UiString.Resource(Res.string.cast_format, listOf("Naruto")),
            UiString.Resource(Res.string.app_dub_sub_episode_text_format, listOf("Dub", 3)),
            UiString.Resource(Res.string.player_speed_text_format, listOf(1.25)),
            UiString.Plural(Res.plurals.links_hidden, 1),
            UiString.Plural(Res.plurals.links_hidden, 7),
        )
        val resolved = samples.map { single(it) }
        val loaded = SystemEnvironment.of(LANGUAGE) { runBlocking { samples.map { it.load() } } }
        assertEquals(loaded, resolved)
        assertTrue(resolved.none { it.contains("%s") || it.contains("%d") }, "a specifier survived: $resolved")
        assertEquals(resolved.distinct().size, resolved.size, "resolve() returned the same text for every shape")
    }

    /** The one value [text] composes to, asserted to be stable across the frames the host drives. */
    private fun single(text: UiString): String {
        val frames = distinct(text)
        assertEquals(1, frames.size, "expected one settled value for $text, got $frames")
        return frames.single()
    }

    private fun distinct(text: UiString): List<String> =
        SystemEnvironment.of(LANGUAGE) {
            HeadlessComposition.frames(LANGUAGE, frames = 1) { text.resolve() }
        }.distinct()

    /** The base `values` text of a `<string>`, straight from the generator's aapt2 oracle. */
    private fun rawString(name: String): String =
        requireNotNull(StringsOracle.emitted[Key("values", "string", name, "-")]) { "no oracle value for $name" }

    /** The `links_hidden` item of [category] as the generator emitted it for [language], or the base one. */
    private fun itemOf(language: String, category: String): String =
        StringsOracle.emitted[Key("values-b+$language", "plurals", "links_hidden", category)]
            ?: StringsOracle.emitted[Key("values", "plurals", "links_hidden", category)].orEmpty()

    private companion object {
        const val LANGUAGE = "en"
    }
}

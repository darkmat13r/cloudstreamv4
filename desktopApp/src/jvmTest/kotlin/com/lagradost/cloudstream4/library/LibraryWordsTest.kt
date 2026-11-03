package com.lagradost.cloudstream4.library

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import com.lagradost.cloudstream4.feature.library.LibraryBucket
import com.lagradost.cloudstream4.feature.library.LibraryCard
import com.lagradost.cloudstream4.feature.library.LibraryCardWords
import com.lagradost.cloudstream4.feature.library.LibraryStatus
import com.lagradost.cloudstream4.feature.library.LibraryTab
import com.lagradost.cloudstream4.feature.library.label
import com.lagradost.cloudstream4.feature.library.libraryCardWords
import com.lagradost.cloudstream4.feature.library.toUiState
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Runs here because this module is where the resources are packaged for a real render on the JVM.
 *
 * What it is really guarding: `:feature:library` adds **no string keys**, so every word it shows has
 * to already exist and already be translated. A missing key is a compile error; a key that exists but
 * reads wrong in another language is what these assertions are for.
 */
class LibraryWordsTest {

    private fun render(status: LibraryStatus): UiState<List<LibraryCard>> {
        var state: UiState<List<LibraryCard>>? = null
        val scene = ImageComposeScene(width = 100, height = 100, density = Density(1f)) {
            state = status.toUiState {}
        }
        scene.close()
        return checkNotNull(state)
    }

    private fun labels(): List<String> {
        var out: List<String>? = null
        val scene = ImageComposeScene(width = 100, height = 100, density = Density(1f)) {
            out = LibraryTab.all.map { it.label() }
        }
        scene.close()
        return checkNotNull(out)
    }

    private fun <T> inLanguage(language: String, block: () -> T): T {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag(language))
        try {
            return block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    private fun assertHasWords(prompt: StatePrompt) {
        assertTrue(prompt.headline.isNotBlank())
        assertTrue(prompt.body.isNotBlank())
        assertTrue(prompt.action.label.isNotBlank())
        // An unfilled positional argument is the defect `UiString` exists to prevent.
        assertFalse('%' in prompt.body, "unformatted: ${prompt.body}")
        assertFalse('%' in prompt.headline, "unformatted: ${prompt.headline}")
    }

    @Test
    fun `every list has a name, and they are the ones the Android app already uses`() {
        inLanguage("en") {
            assertEquals(
                listOf("Continue Watching", "Watching", "Completed", "On-Hold", "Dropped", "Plan to Watch", "Favorites"),
                labels(),
            )
        }
    }

    @Test
    fun `the names follow the language`() {
        val english = inLanguage("en") { labels() }
        val german = inLanguage("de") { labels() }
        assertEquals(english.size, german.size)
        assertTrue(english != german, "no name changed in German, so these are not translated")
        assertTrue(german.none { it.isBlank() })
    }

    @Test
    fun `an empty list says so and offers search`() {
        val empty = assertIs<UiState.Empty>(render(LibraryStatus.Empty))
        inLanguage("en") { assertHasWords(empty.prompt) }
    }

    @Test
    fun `a failure names the library and keeps the reason separate`() {
        val failed = assertIs<UiState.Error>(render(LibraryStatus.Failed("permission denied")))
        inLanguage("en") {
            assertHasWords(failed.prompt)
            // The reason is machine-readable detail, not body copy.
            assertEquals("permission denied", failed.detail)
            assertFalse("permission denied" in failed.prompt.body)
        }
    }

    @Test
    fun `the failure body is filled, in every language it was taken from`() {
        for (language in listOf("en", "de", "fr", "ru", "ar")) {
            inLanguage(language) {
                val failed = assertIs<UiState.Error>(render(LibraryStatus.Failed("x")))
                assertFalse('%' in failed.prompt.body, "$language: ${failed.prompt.body}")
                assertTrue(failed.prompt.body.isNotBlank(), language)
            }
        }
    }

    @Test
    fun `a card's controls are worded, and Remove is not the same word as the list button`() {
        val words = inLanguage("en") {
            var out: LibraryCardWords? = null
            val scene = ImageComposeScene(width = 100, height = 100, density = Density(1f)) {
                out = libraryCardWords()
            }
            scene.close()
            checkNotNull(out)
        }
        assertEquals("Remove", words.remove)
        assertEquals("Set watch status", words.setList)
        assertEquals("Resume", words.resume)
    }

    /**
     * In English several of these strings happen to read exactly like the enum constant, so English
     * cannot tell a resolved string from a leaked `name`. Russian can.
     */
    @Test
    fun `no list name is the enum constant's own spelling`() {
        inLanguage("ru") {
            val names = labels()
            LibraryBucket.entries.forEach { bucket ->
                assertFalse(names.contains(bucket.name), "${bucket.name} reached the screen untranslated")
            }
            assertTrue(names.none { name -> name.all { it.code < 128 } }, "nothing was translated: $names")
        }
    }
}

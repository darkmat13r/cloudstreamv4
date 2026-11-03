package com.lagradost.cloudstream4.extensions

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_warning_other
import com.lagradost.cloudstream4.core.strings.generated.resources.extensions_waiting_count
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.feature.extensions.sandbox.ExtensionScanner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Runs here because this module is where the resources are packaged for a real render on the JVM. */
class ExtensionReviewWordsTest {

    private fun words(resource: StringResource, language: String = "en"): String {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag(language))
        try {
            var text = ""
            val scene = ImageComposeScene(100, 100, Density(1f)) { text = stringResource(resource) }
            scene.close()
            return text
        } finally {
            Locale.setDefault(previous)
        }
    }

    /** Fails the day a rule is added to the scanner without a sentence here. */
    @Test
    fun `every warning the scanner can raise has a sentence of its own`() {
        val rules = ExtensionScanner.warnRules
        assertEquals(10, rules.size, "$rules")
        rules.forEach { rule -> assertNotEquals(Res.string.extension_warning_other, warningSentence(rule), rule) }
        assertEquals(rules.size, rules.map { warningSentence(it) }.toSet().size, "two rules share a sentence")
    }

    @Test
    fun `an unknown rule is still shown, and says it has no description`() {
        assertEquals(Res.string.extension_warning_other, warningSentence("from-the-future"))
    }

    @Test
    fun `sentences resolve in the language in use`() {
        val english = words(warningSentence("reflection"))
        assertTrue("reflection" in english, english)
        assertNotEquals(english, words(warningSentence("reflection"), "de"))
    }

    /** Found by looking at a render: Compose's own `stringResource(res, n)` leaves a plain `%d` as it is. */
    @Test
    fun `the notice's count is a number on screen, not a placeholder`() {
        val state = ExtensionReviewState(
            listOf(
                ExtensionReview("A.jar", "A.jar", null, ReviewStatus.Held, emptyList()),
                ExtensionReview("B.jar", "B.jar", null, ReviewStatus.Held, emptyList()),
            ),
        )
        var label = ""
        val scene = ImageComposeScene(100, 100, Density(1f)) {
            label = UiString.Resource(Res.string.extensions_waiting_count, listOf(state.waiting)).resolve()
        }
        scene.close()
        assertFalse("%" in label, label)
        assertTrue("2" in label, label)
    }

    /**
     * The strings that describe a verdict: "safe" may appear only where it is being denied. There is no status
     * word for a plugin being fine, and that is the point.
     */
    @Test
    fun `no status ever calls an extension safe`() {
        val statuses = listOf(
            ReviewStatus.Held, ReviewStatus.KeptOff, ReviewStatus.Allowed, ReviewStatus.Starting,
            ReviewStatus.StartFailed("x"), ReviewStatus.Blocked, ReviewStatus.HashMismatch,
        )
        statuses.forEach { status -> assertFalse("safe" in words(statusWords(status)).lowercase(), "$status") }
        ExtensionScanner.warnRules.forEach { rule -> assertFalse("safe" in words(warningSentence(rule)).lowercase(), rule) }
    }
}

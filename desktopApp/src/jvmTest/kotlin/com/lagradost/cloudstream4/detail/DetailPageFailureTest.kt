package com.lagradost.cloudstream4.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.lagradost.cloudstream4.core.designsystem.desktop.forDesktop
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.LocalCloudStreamTypography
import com.lagradost.cloudstream4.feature.detail.DetailLoad
import com.lagradost.cloudstream4.feature.detail.DetailState
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import com.lagradost.cloudstream4.feature.detail.desktop.DetailScreenDesktop
import com.lagradost.cloudstream4.feature.detail.toLinksUiState
import com.lagradost.cloudstream4.feature.detail.toPageUiState
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * `BUG-21`: a plugin whose `load()` returned null showed "Source error / Error Loading Links /
 * IllegalStateException: provider returned nothing". The page had failed, not a list of links, and a class
 * name is not for a person.
 *
 * Runs here because this module is where the resources are packaged for a real render on the JVM.
 */
class DetailPageFailureTest {

    private val raw = "IllegalStateException: provider returned nothing"

    private fun <T> inLanguage(language: String, block: () -> T): T {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag(language))
        try {
            return block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    private fun <T> composed(block: @Composable () -> T): T {
        var out: T? = null
        val scene = ImageComposeScene(width = 100, height = 100, density = Density(1f)) { out = block() }
        scene.close()
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private fun page(load: DetailLoad<TitleDetail>, language: String = "en") =
        inLanguage(language) { composed { load.toPageUiState {} } }

    @Test
    fun `a failed title page says the title could not be opened and shows nothing of the failure`() {
        val error = page(DetailLoad.Failed(raw)) as UiState.Error
        assertEquals("Couldn't open this title", error.prompt.headline)
        assertEquals("Its source may be unavailable.", error.prompt.body)
        assertEquals("Try again", error.prompt.action.label)
        assertNull(error.detail, "the exception text is not drawn")
        val everything = listOf(error.prompt.headline, error.prompt.body, error.prompt.action.label)
        everything.forEach {
            assertFalse("Exception" in it || "provider returned" in it || "Error" in it, it)
            assertFalse("link" in it.lowercase(), "no links were asked for: $it")
        }
    }

    @Test
    fun `a title page with nothing says the same`() {
        val empty = page(DetailLoad.Empty) as UiState.Empty
        val error = page(DetailLoad.Failed(raw)) as UiState.Error
        assertEquals(error.prompt.headline, empty.prompt.headline)
        assertEquals(error.prompt.body, empty.prompt.body)
        assertNull(empty.detail)
    }

    @Test
    fun `a class name from any failure stays off the page`() {
        val error = page(DetailLoad.Failed("NoClassDefFoundError: okhttp3/Foo")) as UiState.Error
        assertNull(error.detail)
        assertFalse("NoClassDef" in error.prompt.body + error.prompt.headline)
    }

    @Test
    fun `the links list keeps its own words`() {
        val failed = inLanguage("en") { composed { DetailLoad.Failed(raw).toLinksUiState {} } } as UiState.Error
        assertEquals("Error Loading Links", failed.prompt.headline)
        assertEquals(raw, failed.detail)
    }

    @Test
    fun `the words exist in another language and are not the English ones`() {
        for (language in listOf("de", "ar", "ja", "ru")) {
            val error = page(DetailLoad.Failed(raw), language) as UiState.Error
            assertTrue(error.prompt.headline.isNotBlank() && error.prompt.body.isNotBlank(), language)
            assertNotEquals("Couldn't open this title", error.prompt.headline, language)
            assertNotEquals("Its source may be unavailable.", error.prompt.body, language)
        }
        assertEquals(
            "Titel konnte nicht geöffnet werden",
            (page(DetailLoad.Failed(raw), "de") as UiState.Error).prompt.headline,
        )
    }

    @Test
    fun `a url in a plugin's message is not logged`() {
        // The host stays, as in the other three copies of the rules (`KMP-56`); the path and the query never do.
        assertEquals(
            "refused https://x.example/<…> then http://10.0.0.1:8080/<…>",
            "refused https://x.example/a?token=abc then http://10.0.0.1:8080/b".withoutUrls(),
        )
        assertEquals("provider returned nothing", "provider returned nothing".withoutUrls())
    }

    private fun render(name: String, language: String) {
        val directory = System.getProperty("cloudstream.home.renders") ?: return
        inLanguage(language) {
            val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
                CloudStreamTheme {
                    CompositionLocalProvider(
                        LocalCloudStreamTypography provides CloudStreamTheme.typography.forDesktop(),
                    ) {
                        Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                            DetailScreenDesktop(DetailState(DetailLoad.Failed(raw)), {}, {}, {})
                        }
                    }
                }
            }
            repeat(SETTLE_FRAMES) { scene.render(it * FRAME_NANOS) }
            val png = checkNotNull(scene.render(SETTLE_FRAMES * FRAME_NANOS).encodeToData(EncodedImageFormat.PNG)).bytes
            File(directory, "$name.png").writeBytes(png)
            scene.close()
        }
    }

    @Test
    fun `the failed page renders in english and arabic`() {
        render("bug21-title-failed-en", "en")
        render("bug21-title-failed-ar", "ar")
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 800
        const val SETTLE_FRAMES = 30
        const val FRAME_NANOS = 50_000_000L
    }
}

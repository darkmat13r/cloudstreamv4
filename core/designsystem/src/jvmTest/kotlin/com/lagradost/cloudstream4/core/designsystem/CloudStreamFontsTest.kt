package com.lagradost.cloudstream4.core.designsystem

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopTypography
import com.lagradost.cloudstream4.core.theme.CloudStreamTypography
import com.lagradost.cloudstream4.core.theme.cloudStreamTypography
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whether the bundled faces are the ones that draw (`KMP-97`), and not the platform's in their place.
 *
 * A family that fails to load falls back to the system face without an error, and every test that only reads a layout
 * keeps passing. So this measures text against the files' own advance widths, read from their `hmtx` tables: at 100 px,
 * ten `H` in Manrope Regular are 10 × 1369 / 2000 em, in Manrope ExtraBold 10 × 1468 / 2000 em, and every glyph of
 * JetBrains Mono is 600 / 1000 em. The platform's own faces measure otherwise, which the last test shows.
 *
 * The line's own width, not the paragraph's: a paragraph rounds its width up to a whole pixel.
 */
class CloudStreamFontsTest {

    private fun width(text: String, style: (CloudStreamTypography) -> TextStyle, app: Boolean = true): Float {
        var measured = Float.NaN
        val scene = ImageComposeScene(2000, 200, Density(1f)) {
            val typography = if (app) cloudStreamAppTypography() else cloudStreamTypography()
            val measurer = rememberTextMeasurer()
            measured = measurer.measure(text, style(typography).copy(fontSize = SIZE)).multiParagraph.getLineWidth(0)
        }
        try {
            scene.render()
        } finally {
            scene.close()
        }
        return measured
    }

    @Test
    fun manropeDrawsTheWordsAtItsOwnWidths() {
        assertEquals(684.5f, width(TEN_H, { it.body }), ROUNDING)
        assertEquals(734.0f, width(TEN_H, { it.body.copy(fontWeight = FontWeight.ExtraBold) }), ROUNDING)
    }

    @Test
    fun jetBrainsMonoDrawsTheNumbersOneWidthEach() {
        assertEquals(600.0f, width("MMMMMMMMMM", { it.labelMono }), ROUNDING)
        assertEquals(600.0f, width("iiiiiiiiii", { it.labelMono }), ROUNDING)
        assertEquals(600.0f, width("0123456789", { it.captionMono }), ROUNDING)
    }

    /** The Mac's ramp asks for the boards' weights, and they reach a face: display is ExtraBold, body Regular. */
    @Test
    fun theDesktopRampDrawsAtTheBoardsWeights() {
        var display = Float.NaN
        var body = Float.NaN
        val scene = ImageComposeScene(2000, 200, Density(1f)) {
            val typography = desktopTypography()
            val measurer = rememberTextMeasurer()
            display = measurer.measure(TEN_H, typography.display.copy(fontSize = SIZE)).multiParagraph.getLineWidth(0)
            body = measurer.measure(TEN_H, typography.body.copy(fontSize = SIZE)).multiParagraph.getLineWidth(0)
        }
        try {
            scene.render()
        } finally {
            scene.close()
        }
        assertEquals(734.0f, display, ROUNDING)
        assertEquals(684.5f, body, ROUNDING)
    }

    /** The control: without the bundled families the same text measures otherwise, so the checks above can fail. */
    @Test
    fun thePlatformFacesMeasureOtherwise() {
        val sans = width(TEN_H, { it.body }, app = false)
        val mono = width("MMMMMMMMMM", { it.labelMono }, app = false)
        assertTrue(abs(sans - 684.5f) > ROUNDING, "the platform's sans measured $sans, Manrope's width")
        assertTrue(abs(mono - 600.0f) > ROUNDING, "the platform's mono measured $mono, JetBrains Mono's width")
    }

    private companion object {
        val SIZE = 100.sp
        const val TEN_H = "HHHHHHHHHH"

        /** Half a pixel over a 600-pixel run: a different face is tens of pixels off, not one. */
        const val ROUNDING = 0.5f
    }
}

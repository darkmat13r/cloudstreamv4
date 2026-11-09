package com.lagradost.cloudstream4.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopTypography
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.LocalCloudStreamTypography
import com.lagradost.cloudstream4.media.model.CaptionStyle
import com.lagradost.cloudstream4.media.model.CuePlacement
import com.lagradost.cloudstream4.media.model.SubtitleCue
import com.lagradost.cloudstream4.media.subtitle.SubtitleParser
import com.lagradost.cloudstream4.media.subtitle.SubtitleTimeline
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/** Where a cue really lands, read back from the pixels: white text on a black picture, with no edge to blur it. */
@OptIn(ExperimentalComposeUiApi::class)
class SubtitleOverlayTest {

    private val plain = CaptionStyle(edge = com.lagradost.cloudstream4.media.model.CaptionEdge.NONE)

    private fun render(
        cues: List<SubtitleCue>,
        width: Int = 1280,
        height: Int = 800,
        picture: IntSize? = IntSize(width, height),
        top: Dp = 0.dp,
        bottom: Dp = 0.dp,
        style: CaptionStyle = plain,
    ): Image {
        val scene = ImageComposeScene(width, height, Density(1f)) {
            CloudStreamTheme {
                CompositionLocalProvider(LocalCloudStreamTypography provides desktopTypography()) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        SubtitleOverlay(cues, picture, top, bottom, style = style)
                    }
                }
            }
        }
        scene.render(0)
        val image = scene.render(16_000_000)
        scene.close()
        return image
    }

    private fun Image.bright(): IntRect? {
        val pixels = toComposeImageBitmap().toPixelMap()
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (pixels[x, y].red > 0.5f) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        return if (right < 0) null else IntRect(left, top, right, bottom)
    }

    private fun cue(placement: CuePlacement = CuePlacement.Default, vararg text: String) =
        SubtitleCue(0, 1000, text.toList().ifEmpty { listOf("subtitle text") }, placement)

    private fun an(code: Int) = checkNotNull(CuePlacement.fromSsaAlignment(code))

    private fun savePng(image: Image, name: String) {
        val dir = System.getProperty("cloudstream.home.renders") ?: return
        File(dir).mkdirs()
        File(dir, "$name.png").writeBytes(checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
    }

    @Test
    fun `every numpad cell lands in its third of the picture, at both window sizes`() {
        for ((width, height) in listOf(1280 to 800, 800 to 520)) {
            for (code in 1..9) {
                val box = checkNotNull(render(listOf(cue(an(code))), width, height).bright()) { "an$code at $width" }
                val column = (code - 1) % 3
                val row = 2 - (code - 1) / 3
                val centreX = (box.left + box.right) / 2
                val centreY = (box.top + box.bottom) / 2
                assertEquals(column, centreX * 3 / width, "an$code column at $width: $box")
                assertEquals(row, centreY * 3 / height, "an$code row at $width: $box")
            }
        }
    }

    @Test
    fun `a cue keeps the safe margin at every edge`() {
        val margin = 24
        for (code in listOf(1, 3, 7, 9)) {
            val box = checkNotNull(render(listOf(cue(an(code)))).bright())
            assertTrue(box.left >= margin && box.top >= margin, "an$code: $box")
            assertTrue(box.right <= 1280 - margin && box.bottom <= 800 - margin, "an$code: $box")
        }
    }

    @Test
    fun `cues lift above the controls when they show, and come down clear of the top bar too`() {
        val without = checkNotNull(render(listOf(cue())).bright())
        val with = checkNotNull(render(listOf(cue()), bottom = PlayerScreenDefaults.HudBottomInset).bright())
        assertTrue(with.bottom < without.bottom, "$with against $without")
        assertTrue(with.bottom <= 800 - 84, "$with")

        val top = checkNotNull(render(listOf(cue(an(8))), top = PlayerScreenDefaults.HudTopInset).bright())
        assertTrue(top.top >= 84, "$top")
    }

    @Test
    fun `placement is on the picture, not the letterbox around it`() {
        val wide = IntSize(2100, 900) // Bands above and below, at 1280 x 800.
        val rect = pictureRect(IntSize(1280, 800), wide)
        assertEquals(1280, rect.width)
        assertTrue(rect.top > 100)
        val box = checkNotNull(render(listOf(cue(an(8))), picture = wide).bright())
        assertTrue(box.top >= rect.top, "$box against $rect")
        val foot = checkNotNull(render(listOf(cue()), picture = wide).bright())
        assertTrue(foot.bottom <= rect.bottom, "$foot against $rect")
    }

    @Test
    fun `a controls height of letterbox means no lift is needed`() {
        val wide = IntSize(2100, 700)
        val without = checkNotNull(render(listOf(cue()), picture = wide).bright())
        val with = checkNotNull(render(listOf(cue()), picture = wide, bottom = PlayerScreenDefaults.HudBottomInset).bright())
        assertEquals(without.bottom, with.bottom)
    }

    @Test
    fun `before a frame arrives the window is the picture`() {
        assertEquals(IntRect(0, 0, 1280, 800), pictureRect(IntSize(1280, 800), null))
        assertEquals(IntRect(0, 0, 1280, 800), pictureRect(IntSize(1280, 800), IntSize(0, 0)))
    }

    @Test
    fun `a box too big for what is left is still on screen`() {
        val region = IntRect(24, 84, 776, 436)
        val at = cueOrigin(an(9), IntSize(2000, 2000), IntRect(0, 0, 800, 520), region)
        assertEquals(IntOffset(24, 84), at)
    }

    @Test
    fun `a width percent wraps the cue`() {
        val narrow = CuePlacement(widthPercent = 20f)
        val long = "a line long enough to need wrapping at this width"
        val free = checkNotNull(render(listOf(cue(CuePlacement.Default, long))).bright())
        val wrapped = checkNotNull(render(listOf(cue(narrow, long))).bright())
        assertTrue(wrapped.width <= 1280 / 5, "$wrapped")
        assertTrue(wrapped.height > free.height, "$wrapped against $free")
    }

    @Test
    fun `overlapping cues at one place stack in time order and do not overdraw`() {
        val both = checkNotNull(render(listOf(cue(CuePlacement.Default, "first"), cue(CuePlacement.Default, "second"))).bright())
        val one = checkNotNull(render(listOf(cue(CuePlacement.Default, "first"))).bright())
        assertTrue(both.height > one.height * 3 / 2, "$both against $one")
    }

    @Test
    fun `no cues draw nothing`() {
        assertEquals(null, render(emptyList()).bright())
    }

    @Test
    fun `an rtl override reverses its own line and cannot reach the next`() {
        val hostile = "‮abc def ghi"
        val line2 = "second line stays"
        val clean = render(listOf(cue(an(7), "abc def ghi", line2)))
        val attacked = render(listOf(cue(an(7), hostile, line2)))
        savePng(attacked, "subtitle-rtl-override")
        val a = clean.toComposeImageBitmap().toPixelMap()
        val b = attacked.toComposeImageBitmap().toPixelMap()
        val box = checkNotNull(clean.bright())
        val middle = (box.top + box.bottom) / 2
        fun differs(from: Int, to: Int): Boolean {
            for (y in from..to) for (x in 0 until a.width) if (a[x, y] != b[x, y]) return true
            return false
        }
        assertTrue(differs(box.top, middle), "the override does reverse its own line: it is text, as CueText records")
        assertFalse(differs(middle + 1, box.bottom), "and the next line is untouched")
    }

    @Test
    fun `markup in a cue is shown as the characters it is`() {
        val cues = SubtitleParser.parse("00:00:01,000 --> 00:00:02,000\n&lt;a href=&quot;x&quot;&gt;click&lt;/a&gt;\n").cues
        assertEquals(listOf("""<a href="x">click</a>"""), cues.single().text)
        savePng(render(cues), "subtitle-markup-as-text")
        assertTrue(render(cues).bright() != null)
    }

    @Test
    fun `right to left and a long stack of lines draw without trouble, on the screen`() {
        val arabic = cue(CuePlacement.Default, "مرحبا بالعالم", "שלום עולם")
        savePng(render(listOf(arabic)), "subtitle-rtl-text")
        val many = SubtitleCue(0, 1000, List(32) { "line $it" })
        val box = checkNotNull(render(listOf(many), 800, 520).bright())
        assertTrue(box.bottom < 520, "$box")
    }

    @Test
    fun `the outline shows over a white picture`() {
        // Legibility, read from the pixels: black must appear next to the white text.
        val scene = ImageComposeScene(800, 200, Density(1f)) {
            CloudStreamTheme {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    SubtitleOverlay(listOf(cue()), IntSize(800, 200), 0.dp, 0.dp)
                }
            }
        }
        scene.render(0)
        val image = scene.render(16_000_000)
        scene.close()
        savePng(image, "subtitle-over-white")
        val pixels = image.toComposeImageBitmap().toPixelMap()
        var dark = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) if (pixels[x, y].red < 0.2f) dark++
        assertTrue(dark > 200, "$dark dark pixels")
    }

    @Test
    fun `a size follows the picture on the 4dp step, and a fixed one wins`() {
        assertEquals(32f, captionSizeFor(720.dp))
        assertEquals(20f, captionSizeFor(450.dp))
        assertEquals(16f, captionSizeFor(100.dp))
        assertEquals(48f, captionSizeFor(4000.dp))
        for (height in 100..2000 step 7) {
            assertEquals(0f, captionSizeFor(height.dp) % 4f, "$height")
        }
        val fixed = checkNotNull(render(listOf(cue()), style = plain.copy(textSizeSp = 64f)).bright())
        val scaled = checkNotNull(render(listOf(cue())).bright())
        assertNotEquals(scaled.height, fixed.height)
    }

    @Test
    fun `a style's alignment moves what asked for nothing, and only that`() {
        val style = plain.copy(alignment = 8)
        val top = checkNotNull(render(listOf(cue()), style = style).bright())
        assertTrue(top.bottom < 400, "$top")
        val asked = checkNotNull(render(listOf(cue(an(1))), style = style).bright())
        assertTrue(asked.top > 400, "$asked")
    }

    /** Which cue shows at T, through the lookup the screen uses: the pure half of "what is on screen". */
    @Test
    fun `which cue shows at a time`() {
        val timeline = SubtitleTimeline(
            listOf(
                SubtitleCue(5_000, 1_000, listOf("late")),
                SubtitleCue(1_000, 2_000, listOf("a")),
                SubtitleCue(2_000, 2_000, listOf("b")),
            ),
        )
        fun at(ms: Long) = timeline.activeAt(ms).map { it.text.single() }
        assertEquals(emptyList(), at(999))
        assertEquals(listOf("a"), at(1_000))
        assertEquals(listOf("a", "b"), at(2_000))
        assertEquals(listOf("b"), at(3_000))
        assertEquals(emptyList(), at(4_000))
        assertEquals(listOf("late"), at(5_999))
        assertEquals(emptyList(), at(6_000))
    }
}

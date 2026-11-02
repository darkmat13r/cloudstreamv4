package com.lagradost.cloudstream4.core.designsystem.icon

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import compose.icons.AllIcons
import compose.icons.TablerIcons
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whether the pack actually draws on the Compose this app runs, and whether [CloudStreamIcons] keeps the
 * rules in `.claude/rules/icons.md`.
 *
 * Drawn and not only built: a vector that links but paints nothing, or paints its whole viewport because a
 * "no fill" path came through as a fill, passes everything short of counting pixels.
 */
class CloudStreamIconsTest {

    private fun draw(size: Int, content: @Composable () -> Unit): PixelMap {
        val scene = ImageComposeScene(size, size, Density(1f)) { CloudStreamTheme { content() } }
        return try {
            scene.render().toComposeImageBitmap().toPixelMap()
        } finally {
            scene.close()
        }
    }

    /** The share of pixels with any ink at all. */
    private fun coverage(vector: ImageVector): Float {
        val pixels = draw(CANVAS) {
            Image(
                painter = rememberVectorPainter(vector),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color.White),
                modifier = Modifier.size(CANVAS.dp),
            )
        }
        var inked = 0
        for (x in 0 until CANVAS) for (y in 0 until CANVAS) if (pixels[x, y].alpha > 0f) inked++
        return inked.toFloat() / (CANVAS * CANVAS)
    }

    @Test
    fun `AllIcons is the whole pack, so testing it tests every icon`() {
        val jar = TablerIcons::class.java.protectionDomain.codeSource.location.toURI().let { java.io.File(it) }
        val classes = ZipFile(jar).use { zip ->
            zip.entries().asSequence().count { it.name.matches(Regex("compose/icons/tablericons/[^/]+Kt\\.class")) }
        }
        assertEquals(classes, TablerIcons.AllIcons.size)
    }

    @Test
    fun `every icon in the pack draws something, and not a filled square`() {
        val coverages = TablerIcons.AllIcons.associate { it.name to coverage(it) }
        val blank = coverages.filterValues { it == 0f }.keys
        val filled = coverages.filterValues { it > MAX_COVERAGE }
        println("pack: ${coverages.size} icons, coverage ${coverages.values.min()}..${coverages.values.max()}")
        assertEquals(emptySet(), blank, "drew nothing")
        assertEquals(emptyMap(), filled, "drew over $MAX_COVERAGE of the canvas")
    }

    private fun meanings(): Map<String, ImageVector> = CloudStreamIcons::class.java.declaredMethods
        .filter { it.returnType == ImageVector::class.java && it.parameterCount == 0 }
        .associate { it.name.removePrefix("get") to it.invoke(CloudStreamIcons) as ImageVector }

    @Test
    fun `every meaning draws`() {
        val meanings = meanings()
        assertTrue(meanings.size >= 2, "found by reflection: $meanings")
        meanings.forEach { (name, vector) -> assertTrue(coverage(vector) > 0f, name) }
    }

    @Test
    fun `one glyph per meaning`() {
        val shared = meanings().entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }.values
        assertEquals(emptyList(), shared.toList())
    }

    /** Every pixel of the icon takes the tint, whatever colour the pack's own paths are. */
    @Test
    fun `an icon takes its tint`() {
        val tint = Color(TINT)
        val pixels = draw(CANVAS) {
            CloudStreamIcon(CloudStreamIcons.Play, null, size = CloudStreamIconSize.Large, tint = tint)
        }
        val inked = (0 until CANVAS).flatMap { x -> (0 until CANVAS).map { y -> pixels[x, y] } }.filter { it.alpha == 1f }
        assertTrue(inked.isNotEmpty())
        assertEquals(setOf(tint.toArgb()), inked.map { it.toArgb() }.toSet())
    }

    @Test
    fun `an icon stays inside its size, which is on the 4dp step`() {
        CloudStreamIconSize.entries.forEach { size ->
            assertEquals(0, size.dp.value.toInt() % STEP, "$size")
            val pixels = draw(CANVAS) {
                Box(Modifier.size(CANVAS.dp), contentAlignment = Alignment.TopStart) {
                    CloudStreamIcon(CloudStreamIcons.Warning, null, size = size)
                }
            }
            val edge = size.dp.value.toInt()
            for (x in 0 until CANVAS) for (y in 0 until CANVAS) {
                if (x >= edge || y >= edge) assertEquals(0f, pixels[x, y].alpha, "$size at $x,$y")
            }
        }
    }

    private companion object {
        const val CANVAS = 48
        const val STEP = 4
        const val MAX_COVERAGE = 0.9f
        const val TINT = 0xFFFF0000
    }
}

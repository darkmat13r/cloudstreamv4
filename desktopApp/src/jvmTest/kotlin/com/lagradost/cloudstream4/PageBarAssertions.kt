package com.lagradost.cloudstream4

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopPageBarDefaults
import org.jetbrains.skia.Bitmap
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * `KMP-98`: the page starts with one page bar, [DesktopPageBarDefaults.Height] tall at the top of the page, its whole
 * width, with a hairline along its bottom edge and nothing along its top. Read from the pixels at [probeX], a column
 * the bar's own padding keeps empty: the top row is the band's fill, as its middle is, and the bottom row is not.
 *
 * @param atNanos the frame to read, at or after the last one the test rendered.
 * @param left where the page starts in the scene: 0 for a page drawn alone, the sidebar's width in the whole window.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
internal fun ImageComposeScene.assertPageBar(width: Int, atNanos: Long, left: Int = 0, probeX: Int = left + PROBE_INSET) {
    val bars = semanticsOwners.flatMap { it.rootSemanticsNode.all() }
        .filter { it.config.getOrNull(SemanticsProperties.TestTag) == DesktopPageBarDefaults.TestTag }
    assertEquals(1, bars.size, "one page bar")
    val bounds = bars.single().boundsInRoot
    val height = DesktopPageBarDefaults.Height.value
    assertEquals(0f, bounds.top, "at the top of the window, under the transparent title bar")
    assertEquals(height, bounds.height, "the band's height")
    assertEquals(left.toFloat(), bounds.left, "from the page's leading edge")
    assertEquals((width - left).toFloat(), bounds.width, "the page's whole width")

    val pixels = Bitmap.makeFromImage(render(atNanos))
    val top = pixels.getColor(probeX, 0)
    val middle = pixels.getColor(probeX, height.toInt() / 2)
    val bottom = pixels.getColor(probeX, height.toInt() - 1)
    assertEquals(middle, top, "no line along the top: a hairline under the bar, not a box round it")
    assertNotEquals(middle, bottom, "the hairline along the bottom edge")
}

private fun SemanticsNode.all(): List<SemanticsNode> = listOf(this) + children.flatMap { it.all() }

/** Inside the bar's leading padding, which is at least 12 on every page. */
private const val PROBE_INSET = 4

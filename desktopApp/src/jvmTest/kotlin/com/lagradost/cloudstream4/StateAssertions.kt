package com.lagradost.cloudstream4

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import org.jetbrains.skia.Bitmap
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Every semantics node in the scene, unmerged, so a button's own label is a node of its own. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
internal fun ImageComposeScene.allNodes(): List<SemanticsNode> = semanticsOwners.flatMap { it.rootSemanticsNode.everything() }

private fun SemanticsNode.everything(): List<SemanticsNode> = listOf(this) + children.flatMap { it.everything() }

internal fun ImageComposeScene.tagged(tag: String): List<SemanticsNode> =
    allNodes().filter { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

internal fun SemanticsNode.texts(): List<String> =
    config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

/** The node that draws exactly [text]. */
internal fun ImageComposeScene.textNode(text: String): SemanticsNode = allNodes().single { text in it.texts() }

/** The button whose own label is [label]: the nearest ancestor with the button role. */
internal fun ImageComposeScene.buttonLabelled(label: String): SemanticsNode {
    var node: SemanticsNode? = textNode(label)
    while (node != null && node.config.getOrNull(SemanticsProperties.Role) != Role.Button) node = node.parent
    return checkNotNull(node) { "no button around \"$label\"" }
}

/**
 * `KMP-101`: [bounds] is an outlined button, a hairline round it and no fill, at the frame [atNanos]. Read on the button's
 * middle row: its leading pixel is the hairline, and a pixel just inside it, in the padding before the glyph, is the
 * page under it.
 *
 * @param page a pixel of the surface the button sits on, well away from it.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
internal fun ImageComposeScene.assertOutlined(bounds: Rect, atNanos: Long, page: Pair<Int, Int>) {
    val pixels = Bitmap.makeFromImage(render(atNanos))
    val y = bounds.center.y.toInt()
    val edge = pixels.getColor(bounds.left.toInt(), y)
    val inside = pixels.getColor(bounds.left.toInt() + OUTLINE_PROBE, y)
    val surface = pixels.getColor(page.first, page.second)
    assertEquals(surface, inside, "no fill: the page shows through")
    assertNotEquals(surface, edge, "a hairline round it")
}

/** Inside the hairline and before the glyph: every desktop button's padding is at least 12. */
private const val OUTLINE_PROBE = 4

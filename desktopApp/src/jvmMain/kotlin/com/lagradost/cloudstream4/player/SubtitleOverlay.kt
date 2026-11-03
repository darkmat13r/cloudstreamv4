package com.lagradost.cloudstream4.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Shadow
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.media.model.CaptionEdge
import com.lagradost.cloudstream4.media.model.CaptionStyle
import com.lagradost.cloudstream4.media.model.CueAnchor
import com.lagradost.cloudstream4.media.model.CuePlacement
import com.lagradost.cloudstream4.media.model.CueTextAlign
import com.lagradost.cloudstream4.media.model.SubtitleCue
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The cues on screen now, where their file asked for them, over the picture.
 *
 * Their colours are the viewer's [CaptionStyle] and deliberately not the theme's: a caption has to read over
 * a frame that nobody designed, in the light or the dark, so its colours belong to it and not to the chrome.
 *
 * Plain text in [BasicText]: no markup, no links, nothing a cue can do but be read. Each line of a cue is
 * its own paragraph, so an unbalanced U+202E in one cannot reorder another (`SubtitleOverlayTest`).
 *
 * Laid out left to right whatever the app's language: a cue's numbers describe the picture, not the UI.
 *
 * @param pictureSize the frame's size, or null before one has arrived.
 * @param topInset,bottomInset what the HUD covers now. Cues keep clear of it, so they lift as it appears.
 */
@Composable
internal fun SubtitleOverlay(
    cues: List<SubtitleCue>,
    pictureSize: IntSize?,
    topInset: Dp,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
    style: CaptionStyle = CaptionStyle(),
) {
    if (cues.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val box = IntSize(constraints.maxWidth, constraints.maxHeight)
        val picture = pictureRect(box, pictureSize)
        val margin = with(density) { DesktopDimens.WindowMargin.roundToPx() }
        val region = IntRect(
            left = picture.left + margin,
            top = maxOf(picture.top + margin, with(density) { topInset.roundToPx() }),
            right = picture.right - margin,
            bottom = minOf(picture.bottom - margin, box.height - with(density) { bottomInset.roundToPx() }),
        )
        val text = captionTextStyle(style, with(density) { picture.height.toDp() })
        // A style-wide alignment stands in for the default, and never overrides what a cue asked for.
        val fallback = style.alignment?.let(CuePlacement::fromSsaAlignment) ?: CuePlacement.Default
        val groups = cues.groupBy { if (it.placement == CuePlacement.Default) fallback else it.placement }.toList()
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Layout(
                content = {
                    groups.forEach { (placement, group) -> CueBox(group, placement, text, style) }
                },
            ) { measurables, _ ->
                val placeables = measurables.mapIndexed { index, measurable ->
                    measurable.measure(constraintsFor(groups[index].first, region, picture))
                }
                layout(box.width, box.height) {
                    placeables.forEachIndexed { index, placeable ->
                        val at = cueOrigin(groups[index].first, IntSize(placeable.width, placeable.height), picture, region)
                        placeable.place(at.x, at.y)
                    }
                }
            }
        }
    }
}

/** Where a frame sits in [box] under `ContentScale.Fit`: the whole box until one has arrived. */
internal fun pictureRect(box: IntSize, picture: IntSize?): IntRect {
    if (picture == null || picture.width <= 0 || picture.height <= 0) return IntRect(0, 0, box.width, box.height)
    val scale = minOf(box.width.toFloat() / picture.width, box.height.toFloat() / picture.height)
    val width = (picture.width * scale).roundToInt()
    val height = (picture.height * scale).roundToInt()
    val left = (box.width - width) / 2
    val top = (box.height - height) / 2
    return IntRect(left, top, left + width, top + height)
}

private fun constraintsFor(placement: CuePlacement, region: IntRect, picture: IntRect): Constraints {
    val available = region.width.coerceAtLeast(0)
    val asked = placement.widthPercent?.let { (picture.width * it / PERCENT).roundToInt() } ?: available
    return Constraints(maxWidth = minOf(asked, available), maxHeight = region.height.coerceAtLeast(0))
}

/**
 * Top left of a cue's box: its anchor on the picture's point, then pushed back inside [region], so a cue
 * asking for an edge sits at the safe margin and one too big for what is left is still fully on screen.
 */
internal fun cueOrigin(placement: CuePlacement, size: IntSize, picture: IntRect, region: IntRect): IntOffset {
    fun along(start: Int, length: Int, percent: Float, anchor: CueAnchor, extent: Int, low: Int, high: Int): Int {
        val point = start + length * percent / PERCENT
        val shift = when (anchor) {
            CueAnchor.START -> 0f
            CueAnchor.MIDDLE -> extent / 2f
            CueAnchor.END -> extent.toFloat()
        }
        val at = (point - shift).roundToInt()
        return if (high - extent < low) low else at.coerceIn(low, high - extent)
    }
    return IntOffset(
        x = along(picture.left, picture.width, placement.horizontalPercent, placement.horizontalAnchor, size.width, region.left, region.right),
        y = along(picture.top, picture.height, placement.verticalPercent, placement.verticalAnchor, size.height, region.top, region.bottom),
    )
}

@Composable
private fun CueBox(cues: List<SubtitleCue>, placement: CuePlacement, text: TextStyle, style: CaptionStyle) {
    val background = Color(style.backgroundColor.value)
    Column(
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier.width(IntrinsicSize.Max),
    ) {
        val align = when (placement.textAlign) {
            CueTextAlign.START -> TextAlign.Start
            CueTextAlign.CENTER -> TextAlign.Center
            CueTextAlign.END -> TextAlign.End
            CueTextAlign.LEFT -> TextAlign.Left
            CueTextAlign.RIGHT -> TextAlign.Right
        }
        cues.forEach { cue ->
            cue.text.forEach { line ->
                Box(Modifier.fillMaxWidth().background(background)) {
                    CueLine(line, text.copy(textAlign = align), style)
                }
            }
        }
    }
}

/** The edge is the same text drawn first, so a fill over it stays exactly as heavy as without one. */
@Composable
private fun CueLine(line: String, text: TextStyle, style: CaptionStyle) {
    if (style.edge == CaptionEdge.OUTLINE) {
        val stroke = with(LocalDensity.current) { text.fontSize.toPx() } * OUTLINE_SHARE
        BasicText(
            text = line,
            style = text.copy(
                color = Color(style.edgeColor.value),
                drawStyle = Stroke(width = stroke, join = StrokeJoin.Round),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    BasicText(text = line, style = text, modifier = Modifier.fillMaxWidth())
}

/**
 * [CaptionStyle] as a text style, at a size that follows the picture.
 *
 * A fixed size would be a different share of the picture in every window, so the size comes from the picture
 * height unless the style names one, on the 4 dp step: 16 at the smallest window and 32 at the largest usual.
 * The font is the app's: `CaptionStyle.font` has only a default until settings exist.
 */
@Composable
internal fun captionTextStyle(style: CaptionStyle, pictureHeight: Dp): TextStyle {
    val size = style.textSizeSp ?: captionSizeFor(pictureHeight)
    return CloudStreamTheme.typography.body.copy(
        color = Color(style.foregroundColor.value),
        fontSize = size.sp,
        lineHeight = (ceil(size * PlayerScreenDefaults.CaptionLineRatio / STEP) * STEP).sp,
        fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Medium,
        fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
        textDirection = TextDirection.Content,
        shadow = when (style.edge) {
            CaptionEdge.NONE, CaptionEdge.OUTLINE -> null
            else -> Shadow(color = Color(style.edgeColor.value), blurRadius = size * SHADOW_SHARE)
        },
    )
}

internal fun captionSizeFor(pictureHeight: Dp): Float {
    val raw = pictureHeight.value * PlayerScreenDefaults.CaptionHeightShare
    return (raw / STEP).roundToInt().times(STEP).coerceIn(PlayerScreenDefaults.CaptionMinSize, PlayerScreenDefaults.CaptionMaxSize)
}

private const val PERCENT = 100f
private const val STEP = 4f
private const val OUTLINE_SHARE = 0.16f
private const val SHADOW_SHARE = 0.25f

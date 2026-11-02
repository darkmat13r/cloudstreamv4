package com.lagradost.cloudstream4.core.designsystem.desktop

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.CloudStreamTypography

/**
 * The spacing of the desktop form factor, on the repo's 4dp step. See `docs/plans/desktop-design.md`
 * for what each value came from.
 */
object DesktopDimens {
    /** The window's margin. Everything that is not full-bleed aligns to it. */
    val WindowMargin: Dp = 24.dp
    val SectionGap: Dp = 32.dp
    val GroupGap: Dp = 16.dp
    val RelatedGap: Dp = 8.dp
    val ToolbarFieldHeight: Dp = 40.dp
    val ButtonHeight: Dp = 36.dp
    val ButtonPadding: Dp = 16.dp
    val RowMinHeight: Dp = 48.dp
    /**
     * A row's inset. A list sits this much further out than the column it belongs to, so a row's text
     * lines up with its header and only the hover fill reaches past it.
     */
    val RowPadding: Dp = 12.dp
    /** The longest comfortable line of body text at 16sp, so a wide window does not make prose wider. */
    val ReadingWidth: Dp = 640.dp
    val SidePanelWidth: Dp = 360.dp
}

/**
 * Motion for anything a pointer touches: a critically damped spring, with no overshoot.
 *
 * Stiffness is `(2π / response)²` at Apple's 0.3 s response. A spring starts from the value on screen,
 * so hovering off a card halfway through its hover never jumps, which a fixed-duration tween would.
 */
object DesktopMotion {
    private const val RESPONSE_STIFFNESS = 438f
    const val HoverScale = 1.03f
    const val PressScale = 0.97f

    fun <T> spec(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = RESPONSE_STIFFNESS)
}

/**
 * Design System v2's desktop column of the type ramp, applied over the app's families.
 *
 * Sizes are that table's. Line boxes are the same table's TV ratio for the matching size, which keeps
 * them on the 4dp step: the ink has to fit the box, and that ratio is what was measured.
 */
fun CloudStreamTypography.forDesktop(): CloudStreamTypography = copy(
    display = display.copy(fontSize = 40.sp, lineHeight = 56.sp),
    h1 = h1.copy(fontSize = 32.sp, lineHeight = 44.sp),
    railTitle = railTitle.copy(fontSize = 24.sp, lineHeight = 32.sp),
    body = body.copy(fontSize = 16.sp, lineHeight = 24.sp),
    label = label.copy(fontSize = 16.sp, lineHeight = 24.sp),
    caption = caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
    labelMono = labelMono.copy(fontSize = 12.sp, lineHeight = 16.sp),
    captionMono = captionMono.copy(fontSize = 12.sp, lineHeight = 16.sp),
)

/**
 * Press and hover feedback: the moment the pointer goes down, not when it comes up.
 *
 * @param lift false for something in a list, where a growing row would overlap its neighbours.
 */
@Composable
fun Modifier.desktopFeedback(source: MutableInteractionSource, lift: Boolean = true): Modifier {
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> DesktopMotion.PressScale
            hovered && lift -> DesktopMotion.HoverScale
            else -> 1f
        },
        animationSpec = DesktopMotion.spec(),
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** A text button. Replaces the TV one here because that has no hover state, and a pointer needs one. */
@Composable
fun DesktopButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    val colors = CloudStreamTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.medium
    val fill = when {
        primary && hovered -> colors.ink2
        primary -> colors.ink
        hovered -> colors.line
        else -> colors.raised
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .desktopFeedback(source)
            .tvFocusRing(focused = focused, shape = shape)
            .background(fill, shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .heightIn(min = DesktopDimens.ButtonHeight)
            .padding(horizontal = DesktopDimens.ButtonPadding),
    ) {
        BasicText(
            text = label,
            style = CloudStreamTheme.typography.label.copy(color = if (primary) colors.bg else colors.ink),
            maxLines = 1,
        )
    }
}

/** One row of a list, with a hover fill, and the whole row is the target. */
@Composable
fun DesktopRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.medium
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .desktopFeedback(source, lift = false)
            .tvFocusRing(focused = focused, shape = shape)
            .background(if (hovered) colors.raised else Color.Transparent, shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .heightIn(min = DesktopDimens.RowMinHeight)
            .padding(horizontal = DesktopDimens.RowPadding, vertical = DesktopDimens.RelatedGap),
    ) {
        Column(verticalArrangement = Arrangement.Center) {
            BasicText(
                text = title,
                style = typography.label.copy(color = colors.ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                BasicText(
                    text = subtitle,
                    style = typography.caption.copy(color = colors.muted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

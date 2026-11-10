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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream4.core.designsystem.cloudStreamAppTypography
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.primitive.insetOutline
import com.lagradost.cloudstream4.core.designsystem.state.SkeletonBlock
import com.lagradost.cloudstream4.core.designsystem.state.SkeletonDefaults
import com.lagradost.cloudstream4.core.designsystem.state.rememberSkeletonAlpha
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
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
    /** A control inside a [ToolbarFieldHeight] field, leaving the same 8 above, below and after it. */
    val FieldActionSize: Dp = 24.dp
    val RowMinHeight: Dp = 48.dp
    /**
     * A control inside a row: a switch's cell, a pop-up button, a row's own button. A compact [ButtonHeight], so it
     * centres on the step in a [RowMinHeight] row and in a 64 row with a second line (`DS-24`, the PM's call 2026-11-08).
     */
    val RowControlHeight: Dp = 32.dp
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
 *
 * Weights are the boards' (`KMP-97`): the weight each role is drawn at in `Design System v2`'s line-box samples, and
 * the one most of the boards use at that role's size. They are here and not in the shared ramp until TV takes the
 * bundled fonts too, so that TV's pixels do not move before it does.
 */
fun CloudStreamTypography.forDesktop(): CloudStreamTypography = copy(
    display = display.copy(fontSize = 40.sp, lineHeight = 56.sp, fontWeight = FontWeight.ExtraBold),
    h1 = h1.copy(fontSize = 32.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold),
    railTitle = railTitle.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    body = body.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
    label = label.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    caption = caption.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold),
    labelMono = labelMono.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    captionMono = captionMono.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

/** What the Mac draws with: the bundled families ([cloudStreamAppTypography]) on the desktop column of the ramp. */
@Composable
fun desktopTypography(): CloudStreamTypography {
    val app = cloudStreamAppTypography()
    return remember(app) { app.forDesktop() }
}

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

/** How much a desktop button stands out. */
enum class DesktopButtonTone {
    /** The one thing a surface is for. */
    Primary,
    Secondary,
    /** No fill until the pointer arrives, for a control inside something that already has one. */
    Quiet,
    /**
     * A hairline and no fill, `raised` under the pointer: an action a state offers, which is not the one thing the
     * surface is for (`KMP-101`, boards D4, S3 and S6).
     */
    Outlined,
}

/**
 * A text button, with an optional glyph ahead of the words. Replaces the TV one here because that has no
 * hover state, and a pointer needs one.
 */
@Composable
fun DesktopButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    icon: ImageVector? = null,
    tone: DesktopButtonTone = if (primary) DesktopButtonTone.Primary else DesktopButtonTone.Secondary,
) {
    DesktopButtonSurface(
        tone = tone,
        onClick = onClick,
        layout = Modifier
            .heightIn(min = DesktopDimens.ButtonHeight)
            .padding(horizontal = DesktopDimens.ButtonPadding),
        modifier = modifier,
    ) { content ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        ) {
            // Decoration: the label beside it already says what it does.
            if (icon != null) CloudStreamIcon(icon, null, size = CloudStreamIconSize.Small, tint = content)
            BasicText(
                text = label,
                style = CloudStreamTheme.typography.label.copy(color = content),
                maxLines = 1,
            )
        }
    }
}

/**
 * A button that is only a glyph, which replaces `IconCell` here for the same reason [DesktopButton]
 * replaces the TV text button.
 *
 * @param contentDescription required, from `:core:strings`: the glyph is all this control has.
 * @param size the whole target, [DesktopDimens.ButtonHeight] so it lines up with [DesktopButton].
 */
@Composable
fun DesktopIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: DesktopButtonTone = DesktopButtonTone.Secondary,
    size: Dp = DesktopDimens.ButtonHeight,
    iconSize: CloudStreamIconSize = CloudStreamIconSize.Medium,
) {
    DesktopButtonSurface(
        tone = tone,
        onClick = onClick,
        layout = Modifier.size(size),
        modifier = modifier,
    ) { content ->
        CloudStreamIcon(icon, contentDescription, size = iconSize, tint = content)
    }
}

/** What every desktop button shares: the fill, press and hover feedback, the ring and the target. */
@Composable
private fun DesktopButtonSurface(
    tone: DesktopButtonTone,
    onClick: () -> Unit,
    layout: Modifier,
    modifier: Modifier = Modifier,
    content: @Composable (contentColor: Color) -> Unit,
) {
    val colors = CloudStreamTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.medium
    val fill = when (tone) {
        DesktopButtonTone.Primary -> if (hovered) colors.ink2 else colors.ink
        DesktopButtonTone.Secondary -> if (hovered) colors.line else colors.raised
        DesktopButtonTone.Quiet -> if (hovered) colors.line else Color.Transparent
        DesktopButtonTone.Outlined -> if (hovered) colors.raised else Color.Transparent
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .desktopFeedback(source)
            .tvFocusRing(focused = focused, shape = shape)
            .background(fill, shape)
            .then(if (tone == DesktopButtonTone.Outlined) Modifier.insetOutline(colors.line, shape) else Modifier)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .then(layout),
    ) {
        content(if (tone == DesktopButtonTone.Primary) colors.bg else colors.ink)
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

/**
 * A column of placeholders for [DesktopRow]s with a subtitle, sharing one pulse. Each sits where its row will,
 * so the words start in the same place once the first row lands.
 */
@Composable
fun DesktopRowsSkeleton(modifier: Modifier = Modifier, rowCount: Int = DESKTOP_SKELETON_ROWS) {
    val alpha = rememberSkeletonAlpha()
    val shape = CloudStreamTheme.shapes.small
    Column(modifier) {
        repeat(rowCount) {
            Column(
                verticalArrangement = Arrangement.spacedBy(CloudStreamDimens.StackGap, Alignment.CenterVertically),
                modifier = Modifier
                    .heightIn(min = DesktopDimens.RowMinHeight)
                    .padding(horizontal = DesktopDimens.RowPadding, vertical = DesktopDimens.RelatedGap),
            ) {
                SkeletonBlock(
                    alpha = alpha,
                    shape = shape,
                    modifier = Modifier.size(SkeletonDefaults.PrimaryBarWidth, CloudStreamDimens.StackTitleLineBox),
                )
                SkeletonBlock(
                    alpha = alpha,
                    shape = shape,
                    modifier = Modifier.size(SkeletonDefaults.SecondaryBarWidth, CloudStreamDimens.StackSubtitleLineBox),
                )
            }
        }
    }
}

private const val DESKTOP_SKELETON_ROWS = 8

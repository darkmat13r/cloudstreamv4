package com.lagradost.cloudstream4.core.designsystem.primitive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.designsystem.target.TargetLabel
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * A person's headshot in a circle, or their initials when the provider sent no image (`TV Detail Player` 7a).
 *
 * Deliberately not a `CardSize` rung: a person is not media, and a square on the media ladder would let a poster
 * be drawn square. Headshots arrive in every shape, so the circle is the honest crop.
 *
 * The initials' line box is the disc's own height, as the board specifies, so the text is not centred off the
 * step.
 *
 * Like the media cards it hoists [focused] and is not itself focusable. The ring goes on the disc, outside this
 * box, so no ancestor may clip it. The lift on the board moves the whole cast block, which is the caller's.
 *
 * @param initials already derived by the caller; deriving them is locale work.
 * @param focused whether the ring is drawn.
 * @param image the headshot, clipped to the circle, or null for [initials]. It receives the full size as a
 *   minimum constraint.
 */
@Composable
fun Avatar(
    initials: String,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    image: (@Composable () -> Unit)? = null,
) {
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.pill
    Box(modifier = modifier.size(AvatarDefaults.Size).tvFocusRing(focused = focused, shape = shape)) {
        if (image != null) {
            Box(Modifier.fillMaxSize().clip(shape), propagateMinConstraints = true) { image() }
        } else {
            val base = CloudStreamTheme.typography.railTitle
            val style = remember(base) { base.copy(lineHeight = AvatarDefaults.InitialsLineHeight) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.raised, shape)
                    .insetOutline(colors.line, shape),
                contentAlignment = Alignment.Center,
            ) {
                TargetLabel(text = initials, style = style, color = colors.ink2)
            }
        }
    }
}

/** Values [Avatar] needs that `:core:theme` does not name yet. */
@Immutable
object AvatarDefaults {

    /** The disc, 160px on the artboard. Mirrors no token: a person is not a rung of the media ladder. */
    val Size: Dp = 80.dp

    /** The disc's height in `sp`, so the line box fills it. See [Avatar]. */
    val InitialsLineHeight: TextUnit = 80.sp
}

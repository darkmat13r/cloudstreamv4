package com.lagradost.cloudstream4.core.designsystem.state

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

private const val PULSE_LABEL = "skeleton-pulse"
private const val PULSE_DURATION_MILLIS = 900
private const val PULSE_MIN_ALPHA = 0.4f
private const val PULSE_MAX_ALPHA = 1.0f

/**
 * The shared breathing value every skeleton block in one skeleton uses.
 *
 * Call it **once** at the root of a skeleton and thread the result down, rather than once per
 * block: a rail of six cards driven by six independent infinite transitions would drift out of
 * phase and shimmer like noise instead of like one surface.
 *
 * Public with [SkeletonBlock], so a screen whose loading state is not a rail of cards can build the
 * skeleton its own board draws. The generic [SkeletonRail] stays the default.
 */
@Composable
fun rememberSkeletonAlpha(): Float {
    val transition = rememberInfiniteTransition(label = PULSE_LABEL)
    val alpha by transition.animateFloat(
        initialValue = PULSE_MIN_ALPHA,
        targetValue = PULSE_MAX_ALPHA,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = PULSE_DURATION_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = PULSE_LABEL,
    )
    return alpha
}

/**
 * One placeholder block: a `raised` rectangle breathing between 40% and 100% opacity against the
 * `bg` ground.
 *
 * The alpha rides the fill colour rather than a `graphicsLayer`, so a block costs no layer and
 * nothing is allocated per frame — `Color.copy` on a value class stays on the stack.
 *
 * @param alpha from [rememberSkeletonAlpha], shared across the whole skeleton.
 * @param shape the placeholder's corner, taken from the shape the real content will have.
 * @param modifier must carry the block's size; this composable imposes none, because the size is
 *   the whole point — it is the real content's size.
 */
@Composable
fun SkeletonBlock(
    alpha: Float,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(
            color = CloudStreamTheme.colors.raised.copy(alpha = alpha),
            shape = shape,
        ),
    )
}

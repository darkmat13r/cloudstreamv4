package com.lagradost.cloudstream4.core.designsystem.primitive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.target.TargetLabel
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * How a disk divides, as fractions of its total. What is left over is free space.
 *
 * Coerced rather than rejected: these come from a filesystem that changes while it is read, and a meter that
 * throws because a figure went stale takes the screen down over a rounding error. [downloads] is limited to what
 * [other] leaves, so the two can never run past the bar.
 *
 * @param other used by everything except downloads.
 * @param downloads used by downloads.
 */
@Immutable
class StorageUsage(other: Float, downloads: Float) {
    val other: Float = other.coerceIn(EMPTY, FULL)
    val downloads: Float = downloads.coerceIn(EMPTY, FULL - this.other)

    private companion object {
        const val EMPTY = 0f
        const val FULL = 1f
    }
}

/**
 * The used-of-total disk meter from `TV Episodes Downloads` 6c: a legend over a three-part bar. Other use is
 * `muted`, downloads `ink` and free space `line`, so it spends no `live`.
 *
 * Width comes from the caller. Height does not change between states, so the content below never moves when the
 * figures arrive.
 *
 * @param usage the division, or null while loading: an undivided `line` bar with the legend suppressed (6e).
 * @param legend already formatted, such as `384 GB OF 512 GB USED`. Formatting bytes is locale work.
 */
@Composable
fun StorageMeter(
    usage: StorageUsage?,
    legend: String?,
    modifier: Modifier = Modifier,
) {
    val colors = CloudStreamTheme.colors
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(StorageMeterDefaults.LegendGap),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(CloudStreamDimens.StackTitleLineBox),
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (usage != null && legend != null) {
                TargetLabel(text = legend, style = CloudStreamTheme.typography.labelMono, color = colors.ink2)
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(StorageMeterDefaults.BarHeight)
                .clip(CloudStreamTheme.shapes.pill),
        ) {
            drawRect(color = colors.line)
            if (usage != null) {
                val otherWidth = size.width * usage.other
                drawRect(color = colors.muted, size = Size(otherWidth, size.height))
                drawRect(
                    color = colors.ink,
                    topLeft = Offset(otherWidth, ORIGIN),
                    size = Size(size.width * usage.downloads, size.height),
                )
            }
        }
    }
}

/** Values [StorageMeter] needs that `:core:theme` does not name yet. */
@Immutable
object StorageMeterDefaults {

    /** The bar, 8px on the artboard. A real dimension rather than a stroke, and on the step. */
    val BarHeight: Dp = 4.dp

    /** Legend to bar, 8px on the artboard. Mirrors `CloudStreamDimens.StackGap`. */
    val LegendGap: Dp = 4.dp
}

private const val ORIGIN = 0f

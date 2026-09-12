package com.lagradost.cloudstream4.core.designsystem.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * The artwork slot's stand-in: a flat `colors.surface` fill.
 *
 * Use it while a poster is loading, when a title has no artwork at all, and in previews. It is an
 * *inset* surface rather than a raised one on purpose — an empty card should read as a hole the
 * artwork has not filled yet, not as a button.
 */
@Composable
fun MediaCardArtPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(CloudStreamTheme.colors.surface))
}

/**
 * Preview scaffold: the tokens, the ground, and enough margin for the focus ring's 8dp bleed plus
 * the 4dp lift to be visible instead of clipped at the preview's edge.
 *
 * `CloudStreamDimens.FocusBandInset` is the right inset for exactly this reason — it is the
 * artboard margin that already pays for the bleed.
 */
@Composable
internal fun CardPreviewSurface(content: @Composable () -> Unit) {
    CloudStreamTheme {
        Box(
            Modifier
                .background(CloudStreamTheme.colors.bg)
                .padding(CloudStreamDimens.FocusBandInset),
        ) {
            content()
        }
    }
}

/**
 * A preview row laid out at the design's minimum focusable gap, which is also what makes a clipped
 * or size-changing focus ring obvious: if focusing a card moved its neighbour, this row would show
 * it.
 */
@Composable
internal fun CardPreviewRow(content: @Composable () -> Unit) {
    CardPreviewSurface {
        Row(horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.ChipRowSpacing)) {
            content()
        }
    }
}

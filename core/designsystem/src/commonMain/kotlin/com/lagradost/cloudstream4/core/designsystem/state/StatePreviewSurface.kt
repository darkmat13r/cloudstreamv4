package com.lagradost.cloudstream4.core.designsystem.state

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * The ground every `@Preview` in this package stands on: the design's tokens, the design's
 * background, and [CloudStreamDimens.FocusBandInset] (28dp) of margin.
 *
 * The inset is not decoration. A focus ring bleeds [CloudStreamDimens.FocusRingBleed] (8dp)
 * outside its target and **nothing may clip it**; a preview with no margin clips the ring at the
 * artboard edge and quietly makes a correct component look broken. 28dp is the same reserve the
 * real chassis gives it.
 */
@Composable
internal fun StatePreviewSurface(content: @Composable () -> Unit) {
    CloudStreamTheme {
        Box(
            modifier = Modifier
                .background(CloudStreamTheme.colors.bg)
                .padding(CloudStreamDimens.FocusBandInset),
            contentAlignment = Alignment.Center,
            content = { content() },
        )
    }
}

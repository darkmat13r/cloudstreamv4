package com.lagradost.cloudstream4.core.designsystem.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * The ground every `@Preview` in this module draws on.
 *
 * Previews of a TV component are misleading without two things: the token ground
 * (`CloudStreamTheme.colors.bg`, not the tooling's white), and room for the focus ring's
 * [CloudStreamDimens.FocusRingBleed] to render. A focused component previewed flush against the
 * artboard edge shows a clipped ring and looks like a bug in the ring.
 *
 * The padding is [CloudStreamDimens.FocusBandInset], which is what the real chassis gives every
 * focusable, so a preview shows the same clearance the screen will.
 */
@Composable
internal fun PreviewSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    CloudStreamTheme {
        Box(
            modifier
                .background(CloudStreamTheme.colors.bg)
                .padding(CloudStreamDimens.FocusBandInset),
        ) {
            content()
        }
    }
}

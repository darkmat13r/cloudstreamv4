package com.lagradost.cloudstream4.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.player_fit_original
import com.lagradost.cloudstream4.core.strings.generated.resources.player_fit_screen
import com.lagradost.cloudstream4.core.strings.generated.resources.player_fit_title
import com.lagradost.cloudstream4.core.strings.generated.resources.player_fit_width
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The three ways a picture can sit in the window, above the controls, closing as [SubtitlePicker] does. */
@Composable
internal fun PictureFitPicker(current: PictureFit, onSelect: (PictureFit) -> Unit, onDismiss: () -> Unit) {
    val colors = CloudStreamTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(onDismiss) {
                awaitEachGesture {
                    awaitFirstDown()
                    onDismiss()
                }
            },
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            contentAlignment = Alignment.BottomEnd,
            modifier = Modifier
                .padding(
                    start = DesktopDimens.WindowMargin,
                    end = DesktopDimens.WindowMargin,
                    bottom = PlayerScreenDefaults.HudBottomInset,
                )
                .widthIn(max = PlayerScreenDefaults.ControlsMaxWidth)
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .width(PlayerScreenDefaults.PickerWidth)
                    .background(colors.surface.copy(alpha = PlayerScreenDefaults.PickerAlpha), CloudStreamTheme.shapes.large)
                    // A click on the panel but between its rows is not a click outside it.
                    .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } }
                    .padding(DesktopDimens.RelatedGap),
            ) {
                BasicText(
                    text = stringResource(Res.string.player_fit_title),
                    style = CloudStreamTheme.typography.caption.copy(color = colors.muted),
                    modifier = Modifier.padding(horizontal = DesktopDimens.RowPadding, vertical = DesktopDimens.RelatedGap),
                )
                PictureFit.entries.forEach { fit ->
                    TrackRow(
                        title = stringResource(fit.words()),
                        detail = null,
                        selected = fit == current,
                        onClick = { onSelect(fit) },
                        modifier = if (fit == current) Modifier.focusRequester(focus) else Modifier,
                    )
                }
            }
        }
    }
}

internal fun PictureFit.words(): StringResource = when (this) {
    PictureFit.ORIGINAL -> Res.string.player_fit_original
    PictureFit.FILL_WIDTH -> Res.string.player_fit_width
    PictureFit.FILL_SCREEN -> Res.string.player_fit_screen
}

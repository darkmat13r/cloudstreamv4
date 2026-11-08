package com.lagradost.cloudstream4.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.player_fit_original
import com.lagradost.cloudstream4.core.strings.generated.resources.player_fit_screen
import com.lagradost.cloudstream4.core.strings.generated.resources.player_fit_title
import com.lagradost.cloudstream4.core.strings.generated.resources.player_fit_width
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** `DS-23` M7: the three ways a picture can sit in the window, in the same panel from the right as the other lists. */
@Composable
internal fun PictureFitPicker(current: PictureFit, onSelect: (PictureFit) -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    PlayerListPanel(stringResource(Res.string.player_fit_title), onDismiss) {
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

internal fun PictureFit.words(): StringResource = when (this) {
    PictureFit.ORIGINAL -> Res.string.player_fit_original
    PictureFit.FILL_WIDTH -> Res.string.player_fit_width
    PictureFit.FILL_SCREEN -> Res.string.player_fit_screen
}

package com.lagradost.cloudstream4.core.designsystem.target

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.designsystem.primitive.insetOutline
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * A button in a chrome row that says what it opens and how many: `TV Search` 4a's `.srcbtn`.
 *
 * 48dp tall so it matches the field beside it, with a raised ground and a draw-only hairline, an `ink`
 * label at the start and an `ink2` mono count at the end. The caller sets the width, because the board
 * fixes it for each screen. The ring is on the button itself, drawn, so no ancestor may clip it.
 *
 * @param label already-localised.
 * @param count already-localised and formatted, for the same reason as [Chip]'s.
 * @param onClick invoked on D-pad centre, Enter or a tap.
 * @param modifier applied to the button, which is the focusable.
 */
@Composable
fun ChromeButton(
    label: String,
    count: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val shape = CloudStreamTheme.shapes.medium
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Row(
        modifier = modifier
            .height(ChromeButtonDefaults.Height)
            .tvFocusRing(focused = focused, shape = shape)
            .background(color = colors.raised, shape = shape)
            .insetOutline(colors.line, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = ChromeButtonDefaults.HorizontalPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TargetLabel(text = label, style = typography.label, color = colors.ink)
        TargetLabel(text = count, style = typography.labelMono, color = colors.ink2)
    }
}

/** Values [ChromeButton] needs that `:core:theme` does not name yet. */
@Immutable
object ChromeButtonDefaults {
    /** `.srcbtn`'s 96px, halved. Mirrors `CloudStreamDimens.ChromeRowHeight`. */
    val Height: Dp = CloudStreamDimens.ChromeRowHeight

    /** `.srcbtn`'s `padding: 0 24px`, halved. */
    val HorizontalPadding: Dp = 12.dp
}

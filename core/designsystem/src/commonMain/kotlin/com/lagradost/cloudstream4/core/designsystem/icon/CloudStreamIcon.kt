package com.lagradost.cloudstream4.core.designsystem.icon

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/** The sizes an icon comes in. A type and not a `Dp`, so a call site cannot invent a fourth. */
enum class CloudStreamIconSize(val dp: Dp) {
    /** Beside a line of text, or on a chip. */
    Small(CloudStreamDimens.ChipGlyphSize),
    /** Alone in a control. */
    Medium(20.dp),
    /** Alone in a large control. */
    Large(24.dp),
}

/**
 * One glyph from [CloudStreamIcons].
 *
 * @param contentDescription what it does, from `:core:strings`, when the icon is all the control has. null
 *   when words beside it already say so, or when it is decoration.
 * @param tint a role from `CloudStreamTheme.colors`, never a literal.
 */
@Composable
fun CloudStreamIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: CloudStreamIconSize = CloudStreamIconSize.Medium,
    tint: Color = CloudStreamTheme.colors.ink,
) {
    Image(
        painter = rememberVectorPainter(icon),
        contentDescription = contentDescription,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(size.dp),
    )
}

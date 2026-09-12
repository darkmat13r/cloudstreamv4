package com.lagradost.cloudstream4.core.designsystem.target

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow

/**
 * The single text primitive the three target classes draw with.
 *
 * It is `BasicText`, not `androidx.compose.material3.Text`, on purpose. A Material `Text` reads
 * `LocalTextStyle` and `LocalContentColor`, which are Material's token system, not CloudStream's —
 * a target that inherited from them would render differently depending on which theme happened to
 * wrap it. Every target passes its style and colour explicitly, so what a target looks like is
 * decided by this module and nothing else.
 *
 * The colour goes through a [ColorProducer] rather than `style.copy(color = …)` because `copy`
 * allocates a whole [TextStyle] on every recomposition; the producer is remembered against the
 * colour and reads it at draw time.
 *
 * Targets are single-line by contract: a target class has a fixed height
 * (`CloudStreamDimens.TextButtonHeight`, `CloudStreamDimens.ChipVisualHeight`) and a second line
 * would either overflow it or force the height off the 4dp step. A label too long for its target
 * ellipsises; it never wraps.
 *
 * @param text the label. Already localised — this module never holds user-facing strings.
 * @param style a style from `CloudStreamTheme.typography`, never a hand-built [TextStyle].
 * @param color a role from `CloudStreamTheme.colors`.
 */
@Composable
internal fun TargetLabel(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val colorProducer = remember(color) { ColorProducer { color } }
    BasicText(
        text = text,
        modifier = modifier,
        style = style,
        overflow = TextOverflow.Ellipsis,
        softWrap = false,
        maxLines = SINGLE_LINE,
        color = colorProducer,
    )
}

private const val SINGLE_LINE = 1

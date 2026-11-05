package com.lagradost.cloudstream4.core.designsystem.panel

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color

/**
 * The open [SidePanel]'s scrim, for a view **outside** its [SidePanelHost].
 *
 * `TV Search` 4d dims the nav rail with the content behind the panel. On Android TV the rail is not
 * in the panel's composition: it is a separate `ComposeView`, a sibling of the page in the
 * activity's shell, so the host's own scrim cannot reach it and a composition local cannot cross to
 * it. This is the bridge: every [SidePanelHost] publishes its scrim's opacity here on each frame of
 * the slide, and [sidePanelOutsideDim] paints the same opacity over whatever it is applied to.
 * Both run on the main thread in one process, so snapshot state is enough, and the reader redraws
 * without recomposing.
 *
 * TV only by use, not by code: the TV shell is the one caller of [sidePanelOutsideDim]. On desktop
 * nothing reads the value.
 *
 * There is one panel open at a time. The last host to publish owns the value, and only the owner
 * clears it when it leaves, so a host that unmounts cannot wipe a newer one's dim.
 */
object SidePanelOutsideDim {

    private val current = mutableFloatStateOf(0f)
    private var owner: Any? = null

    /** The current scrim opacity, 0 with no panel open. Read it in a draw phase. */
    val alpha: Float get() = current.floatValue

    internal fun publish(from: Any, alpha: Float) {
        owner = from
        current.floatValue = alpha
    }

    internal fun clear(from: Any) {
        if (owner !== from) return
        owner = null
        current.floatValue = 0f
    }
}

/**
 * Paints [color] over this element at [SidePanelOutsideDim.alpha], after its content: the open side
 * panel's scrim, extended to a view outside the panel's host. Draw-only, and it reads the alpha in
 * the draw phase, so the slide redraws this element without recomposing it.
 *
 * @param color the scrim colour, `CloudStreamTheme.colors.bg`, as [SidePanelHost]'s own scrim.
 */
fun Modifier.sidePanelOutsideDim(color: Color): Modifier = drawWithContent {
    drawContent()
    val alpha = SidePanelOutsideDim.alpha
    if (alpha > 0f) drawRect(color = color, alpha = alpha)
}

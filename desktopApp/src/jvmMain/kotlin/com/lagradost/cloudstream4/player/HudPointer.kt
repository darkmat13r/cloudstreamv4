package com.lagradost.cloudstream4.player

import androidx.compose.ui.input.pointer.PointerIcon
import kotlinx.coroutines.delay
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage

/**
 * The pointer over the player (`DS-23` M2): the system's arrow while the HUD is up, and none while it is hidden, as a
 * Mac player does, so a still pointer does not sit on the picture. Any move shows both again.
 */
internal fun playerPointer(hudShown: Boolean): PointerIcon = if (hudShown) PointerIcon.Default else HiddenPointer

/** A one-pixel transparent cursor: AWT has no "none", and this is how a Swing app hides its pointer. */
internal val HiddenPointer: PointerIcon by lazy {
    val blank = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(blank, Point(0, 0), "hidden"))
}

/** The time under the pointer on the scrub bar (`DS-23` M3): [fraction] of [lengthMillis], as the clock writes it. */
internal fun hoverTime(fraction: Float, lengthMillis: Long): String = clock((fraction.coerceIn(0f, 1f) * lengthMillis).toLong())

/**
 * How long the HUD waits, still and playing, before it leaves (`DS-23` M2). Injected so a test can say when that time
 * has passed instead of sleeping through it.
 */
internal fun interface HudIdle {
    suspend fun await()

    companion object {
        val Real = HudIdle { delay(PlayerScreenDefaults.HudTimeoutMillis) }
    }
}

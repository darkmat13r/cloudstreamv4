package com.lagradost.cloudstream4.core.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * One rung of the media ladder: a card size that is known to be on the 4dp step in both axes and
 * to hold its aspect ratio exactly.
 *
 * Typed rather than a bare [DpSize] so a card component can only be handed a size the ladder
 * actually contains — `CardSize` has no public constructor.
 */
@Immutable
class CardSize internal constructor(
    val width: Dp,
    val height: Dp,
) {
    /** For `Modifier.size(rung.asDpSize)`. */
    val asDpSize: DpSize get() = DpSize(width, height)

    override fun toString(): String = "CardSize(${width.value}x${height.value}dp)"
}

/**
 * **The media ladder — these are the only valid card sizes.**
 *
 * Anything else is off the ladder, and almost certainly off the 4dp step in one axis. In
 * particular **320x180 is banned**: it is 160x90 dp and 90 is off the step.
 *
 * Artboard px are given beside each rung so the values stay traceable back to the design.
 */
@Immutable
object CloudStreamCardSizes {

    /** 16:9, 384x216 px. */
    val LandscapeLarge: CardSize = CardSize(192.dp, 108.dp)

    /** 16:9, 256x144 px. */
    val LandscapeMedium: CardSize = CardSize(128.dp, 72.dp)

    /** 16:9, 128x72 px. */
    val LandscapeSmall: CardSize = CardSize(64.dp, 36.dp)

    /** 2:3, 192x288 px. */
    val PortraitLarge: CardSize = CardSize(96.dp, 144.dp)

    /** 2:3, 160x240 px. */
    val PortraitMedium: CardSize = CardSize(80.dp, 120.dp)

    /** 2:3, 128x192 px. */
    val PortraitSmall: CardSize = CardSize(64.dp, 96.dp)

    /** Every rung, largest first within each aspect. Useful for previews and for validation. */
    val All: List<CardSize> = listOf(
        LandscapeLarge, LandscapeMedium, LandscapeSmall,
        PortraitLarge, PortraitMedium, PortraitSmall,
    )
}

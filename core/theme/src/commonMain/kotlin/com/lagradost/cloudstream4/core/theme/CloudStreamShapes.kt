package com.lagradost.cloudstream4.core.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Corner ladder for CloudStream 4.
 *
 * **Caveat for whoever reviews this:** the artboards fix colour, type, and every layout
 * dimension, but they do not state corner radii. These are therefore the smallest honest thing
 * this module can provide — the 4dp step applied to radii, which
 * `.claude/rules/dimension-scale.md` requires of anything that occupies space. They are *not*
 * transcribed from a mockup, and they deliberately avoid the repo's existing off-grid
 * `RoundedCornerShape(10.dp)`. If the design has real radii, replace these values; the roles
 * below are the ones components will reference either way.
 */
@Immutable
data class CloudStreamShapes(
    /** Square. Media cards on the artboards are square-cornered. */
    val none: Shape,
    /** Badges, selection markers. */
    val small: Shape,
    /** Buttons, list rows. */
    val medium: Shape,
    /** Cards, panels. */
    val large: Shape,
    /** Dialogs, the side panel. */
    val extraLarge: Shape,
    /** Chips and avatars. */
    val pill: Shape,
)

private object ShapeScale {
    val Small = 4.dp
    val Medium = 8.dp
    val Large = 12.dp
    val ExtraLarge = 16.dp
}

fun cloudStreamShapes(): CloudStreamShapes = CloudStreamShapes(
    none = RectangleShape,
    small = RoundedCornerShape(ShapeScale.Small),
    medium = RoundedCornerShape(ShapeScale.Medium),
    large = RoundedCornerShape(ShapeScale.Large),
    extraLarge = RoundedCornerShape(ShapeScale.ExtraLarge),
    pill = CircleShape,
)

/** Read through [CloudStreamTheme.shapes]. */
val LocalCloudStreamShapes = staticCompositionLocalOf { cloudStreamShapes() }

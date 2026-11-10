package com.lagradost.cloudstream4.core.designsystem.material

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * A ground a screen names by what it is, not by its colour (`KMP-111`). Each is one solid theme colour on every
 * platform: the owner chose a lighter tone over translucency, so there is no native backdrop behind any of them.
 *
 * Where each applies: the Mac window's sidebar and the Settings window's sidebar now. The TV rail and a tablet layout
 * take [Sidebar] when they gain a sidebar column of their own; neither has one today.
 */
enum class CloudStreamMaterial {
    /** The sidebar column, its title band included: [CloudStreamColors.sidebar][com.lagradost.cloudstream4.core.theme.CloudStreamColors.sidebar]. */
    Sidebar,
}

/** The colour [material] paints, from the theme in use. */
@Composable
fun CloudStreamMaterial.color(): Color = when (this) {
    CloudStreamMaterial.Sidebar -> CloudStreamTheme.colors.sidebar
}

/** Paints [material] behind this element. */
@Composable
fun Modifier.material(material: CloudStreamMaterial): Modifier = background(material.color())

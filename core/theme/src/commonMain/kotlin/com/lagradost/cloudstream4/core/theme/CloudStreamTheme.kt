package com.lagradost.cloudstream4.core.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember

/**
 * Provides the CloudStream 4 design tokens to a subtree.
 *
 * This is the *token* layer only. It deliberately does not install a `MaterialTheme`: the
 * existing `:shared` theme (`com.lagradost.cloudstream4.theme.CloudStreamTheme`) still owns that,
 * and this module does not touch it. Folding the two together is a later phase of the migration.
 *
 * Dimensions and card sizes are not provided here because they are not substitutable — read them
 * straight off [CloudStreamDimens] and [CloudStreamCardSizes], or through
 * [CloudStreamTheme.dimens] / [CloudStreamTheme.cardSizes] if you prefer one entry point.
 *
 * @param colors defaults to [cloudStreamDarkColors]; there is no light scheme yet, see
 *   [cloudStreamDarkColors] for why.
 * @param typography defaults to the ramp with platform-default families. An app that bundles
 *   Manrope and JetBrains Mono passes them via [cloudStreamTypography].
 */
@Composable
fun CloudStreamTheme(
    colors: CloudStreamColors = cloudStreamDarkColors(),
    typography: CloudStreamTypography = LocalCloudStreamTypography.current,
    shapes: CloudStreamShapes = LocalCloudStreamShapes.current,
    content: @Composable () -> Unit,
) {
    val rememberedColors = remember(colors) { colors }
    CompositionLocalProvider(
        LocalCloudStreamColors provides rememberedColors,
        LocalCloudStreamTypography provides typography,
        LocalCloudStreamShapes provides shapes,
        content = content,
    )
}

/**
 * The single read point for design tokens:
 * `CloudStreamTheme.colors.ink`, `CloudStreamTheme.dimens.FocusRingStroke`.
 */
object CloudStreamTheme {

    val colors: CloudStreamColors
        @Composable @ReadOnlyComposable get() = LocalCloudStreamColors.current

    val typography: CloudStreamTypography
        @Composable @ReadOnlyComposable get() = LocalCloudStreamTypography.current

    val shapes: CloudStreamShapes
        @Composable @ReadOnlyComposable get() = LocalCloudStreamShapes.current

    /** Not a `CompositionLocal`: the measurements of a fixed design, see [CloudStreamDimens]. */
    val dimens: CloudStreamDimens get() = CloudStreamDimens

    /** Not a `CompositionLocal`: see [CloudStreamCardSizes]. */
    val cardSizes: CloudStreamCardSizes get() = CloudStreamCardSizes
}

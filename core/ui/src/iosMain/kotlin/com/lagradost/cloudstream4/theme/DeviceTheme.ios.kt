package com.lagradost.cloudstream4.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * iOS has no dynamic colour to resolve, so this is the same answer `jvmMain` gives.
 *
 * Not a stub: it is the only possible implementation. Android's Material You exposes the user's
 * wallpaper palette through `android.R.color.system_*`, and macOS exposes a chosen accent through
 * `NSColor.controlAccentColor`, but **iOS publishes no system accent or palette to apps at all**.
 * There is nothing to read.
 *
 * This is also what `CloudStreamThemeMode.Dynamic` already documents — "falls back to [Dark] on
 * unsupported platforms" — so the behaviour here was specified before the source set existed; it
 * just had nowhere to live. Without it, `:core:ui` does not compile for Kotlin/Native and the
 * iOS canary is red for the whole Compose stack (`KMP-10`).
 */
internal actual object DeviceTheme {
    @Composable
    @ReadOnlyComposable
    actual fun resolveDynamicTheme(): CloudStreamColorScheme = darkScheme()

    @Composable
    @ReadOnlyComposable
    actual fun resolveDynamicPrimaryColor(): Color = CloudStreamPrimaryColor.NORMAL.color

    @Composable
    @ReadOnlyComposable
    actual fun resolveDynamicSecondaryColor(): Color = CloudStreamPrimaryColor.NORMAL.color
}

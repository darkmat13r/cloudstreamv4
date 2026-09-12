package com.lagradost.cloudstream4.core.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The complete CloudStream 4 colour set: **nine roles, no others.**
 *
 * The artboards were drawn with exactly these nine values. A tenth colour at a call site is a
 * design change, not an implementation detail — raise it rather than adding a `Color(0xFF…)`.
 *
 * @property bg the ground the whole app sits on.
 * @property surface an *inset* surface — a text field, a well.
 * @property raised a *raised* surface — a button, a chip, a card back.
 * @property line a hairline. Draw it with [CloudStreamDimens.Hairline] and a stroke, never with
 *   padding: see the "hairlines do not consume layout" contract on [CloudStreamDimens.Hairline].
 * @property muted de-emphasised text and inactive glyphs.
 * @property ink2 secondary text.
 * @property ink primary text, and the fill of the primary button.
 * @property focus the focus ring. Consumed by the design system's focus-ring modifier.
 * @property live **playback state only** — progress, LIVE badges, a failure marker. It is never a
 *   button and never a generic accent: the primary button is an [ink] fill with a [bg] label.
 */
@Immutable
data class CloudStreamColors(
    val bg: Color,
    val surface: Color,
    val raised: Color,
    val line: Color,
    val muted: Color,
    val ink2: Color,
    val ink: Color,
    val focus: Color,
    val live: Color,
)

/**
 * The raw artboard values. Nothing outside this file should name a hex literal.
 */
private object CloudStreamPalette {
    val Black = Color(0xFF000000)
    val InsetSurface = Color(0xFF0A1016)
    val RaisedSurface = Color(0xFF15191E)
    val Hairline = Color(0xFF242A31)
    val Muted = Color(0xFF686868)
    val SecondaryInk = Color(0xFFCCCCCC)
    val White = Color(0xFFFFFFFF)
    val Live = Color(0xFFCD3D4B)
}

/**
 * The dark scheme — the authoritative one. CloudStream 4 is a dark-first, TV-first design and
 * every artboard was drawn against [CloudStreamPalette.Black].
 *
 * There is deliberately **no light scheme here.** A "straight role inversion" of these nine values
 * is not honest: [CloudStreamColors.surface] (`#0A1016`) and [CloudStreamColors.raised]
 * (`#15191E`) are cool-tinted near-blacks, and a channel-wise inversion turns them into warm
 * creams (`#F5EFE9`, `#EAE6E1`) that nobody designed. Deriving a light scheme means deciding
 * whether hue is preserved and what `live` and `focus` become on white — designer calls, not
 * module calls. When those land, add `cloudStreamLightColors()` beside this function; the
 * [LocalCloudStreamColors] plumbing already supports two schemes.
 */
fun cloudStreamDarkColors(): CloudStreamColors = CloudStreamColors(
    bg = CloudStreamPalette.Black,
    surface = CloudStreamPalette.InsetSurface,
    raised = CloudStreamPalette.RaisedSurface,
    line = CloudStreamPalette.Hairline,
    muted = CloudStreamPalette.Muted,
    ink2 = CloudStreamPalette.SecondaryInk,
    ink = CloudStreamPalette.White,
    focus = CloudStreamPalette.White,
    live = CloudStreamPalette.Live,
)

/**
 * Read through [CloudStreamTheme.colors]. Static because a colour swap is a whole-tree event;
 * there is no reason to invalidate readers individually.
 */
val LocalCloudStreamColors = staticCompositionLocalOf { cloudStreamDarkColors() }

package com.lagradost.cloudstream4.core.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/**
 * The CloudStream 4 type ramp.
 *
 * Sizes are the 1920x1080 artboard's px halved (xhdpi: `artboard px / 2 = dp`, and the ramp is
 * expressed in `sp` so it still honours the user's font scale).
 *
 * **Line boxes are measured, not assumed.** Manrope's glyph box is ~1.3x the font size, so each
 * line box is `ceil(size * 1.3)` rounded up to the artboard's 8px step. This is not cosmetic: a
 * 64px headline in a 72px box clipped its descenders by 7px, and in an 80px box it still clipped
 * by 3. Nothing in this ramp has a line height of 1.
 *
 * The module owns the *metrics*, not the font files — no Manrope or JetBrains Mono binaries live
 * here. Pass the families in via [cloudStreamTypography]; unset means the platform default.
 */
@Immutable
data class CloudStreamTypography(
    val display: TextStyle,
    val h1: TextStyle,
    val railTitle: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    val labelMono: TextStyle,
    val captionMono: TextStyle,
)

/**
 * The ramp in artboard px alongside the sp it becomes. Named so the two stay traceable when
 * someone reopens the artboard.
 */
private object TypeScale {
    /** 88 / 120 px. */
    val DisplaySize = 44.sp
    val DisplayLineHeight = 60.sp

    /** 64 / 88 px. */
    val H1Size = 32.sp
    val H1LineHeight = 44.sp

    /** 40 / 56 px. */
    val RailTitleSize = 20.sp
    val RailTitleLineHeight = 28.sp

    /** 28 / 40 px. */
    val BodySize = 14.sp
    val BodyLineHeight = 20.sp

    /** 24 / 32 px. Shared by [CloudStreamTypography.label] and [CloudStreamTypography.labelMono]. */
    val LabelSize = 12.sp
    val LabelLineHeight = 16.sp

    /** 20 / 24 px. Shared by [CloudStreamTypography.caption] and [CloudStreamTypography.captionMono]. */
    val CaptionSize = 10.sp
    val CaptionLineHeight = 12.sp
}

/**
 * Builds the ramp. The families are parameters because the font binaries are not part of this
 * module; an app-level theme supplies Manrope (or its fallback) and JetBrains Mono.
 *
 * @param sans the proportional family for every style except the two mono ones.
 * @param mono the monospace family — JetBrains Mono on the artboards, which is tighter than the
 *   sans and therefore keeps the same 24/32 and 20/24 px boxes at the same sizes.
 */
fun cloudStreamTypography(
    sans: FontFamily? = null,
    mono: FontFamily? = null,
): CloudStreamTypography = CloudStreamTypography(
    display = TextStyle(
        fontFamily = sans,
        fontSize = TypeScale.DisplaySize,
        lineHeight = TypeScale.DisplayLineHeight,
    ),
    h1 = TextStyle(
        fontFamily = sans,
        fontSize = TypeScale.H1Size,
        lineHeight = TypeScale.H1LineHeight,
    ),
    railTitle = TextStyle(
        fontFamily = sans,
        fontSize = TypeScale.RailTitleSize,
        lineHeight = TypeScale.RailTitleLineHeight,
    ),
    body = TextStyle(
        fontFamily = sans,
        fontSize = TypeScale.BodySize,
        lineHeight = TypeScale.BodyLineHeight,
    ),
    label = TextStyle(
        fontFamily = sans,
        fontSize = TypeScale.LabelSize,
        lineHeight = TypeScale.LabelLineHeight,
    ),
    /**
     * **OPEN QUESTION — do not quietly resolve this.**
     *
     * caption at 20px is 10sp, which is below Android TV's 12sp minimum for legibility at three
     * metres.
     *
     * The ramp is encoded here at the artboard value (10sp) on purpose. The artboards use 20px in
     * many places, so rounding it up to 12sp reflows those layouts — that is the designer's call,
     * not this module's. Raise it; do not patch it at a call site either.
     */
    caption = TextStyle(
        fontFamily = sans,
        fontSize = TypeScale.CaptionSize,
        lineHeight = TypeScale.CaptionLineHeight,
    ),
    labelMono = TextStyle(
        fontFamily = mono,
        fontSize = TypeScale.LabelSize,
        lineHeight = TypeScale.LabelLineHeight,
    ),
    /** Carries the same 10sp-vs-12sp open question as [CloudStreamTypography.caption]. */
    captionMono = TextStyle(
        fontFamily = mono,
        fontSize = TypeScale.CaptionSize,
        lineHeight = TypeScale.CaptionLineHeight,
    ),
)

/** Read through [CloudStreamTheme.typography]. */
val LocalCloudStreamTypography = staticCompositionLocalOf { cloudStreamTypography() }

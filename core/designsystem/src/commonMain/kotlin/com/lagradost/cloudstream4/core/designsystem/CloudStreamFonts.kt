package com.lagradost.cloudstream4.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.lagradost.cloudstream4.core.designsystem.generated.resources.Res
import com.lagradost.cloudstream4.core.designsystem.generated.resources.jetbrainsmono_bold
import com.lagradost.cloudstream4.core.designsystem.generated.resources.jetbrainsmono_medium
import com.lagradost.cloudstream4.core.designsystem.generated.resources.jetbrainsmono_regular
import com.lagradost.cloudstream4.core.designsystem.generated.resources.manrope_bold
import com.lagradost.cloudstream4.core.designsystem.generated.resources.manrope_extrabold
import com.lagradost.cloudstream4.core.designsystem.generated.resources.manrope_medium
import com.lagradost.cloudstream4.core.designsystem.generated.resources.manrope_regular
import com.lagradost.cloudstream4.core.designsystem.generated.resources.manrope_semibold
import com.lagradost.cloudstream4.core.theme.CloudStreamTypography
import com.lagradost.cloudstream4.core.theme.cloudStreamTypography
import org.jetbrains.compose.resources.Font

/**
 * The boards' two families (`KMP-97`), bundled: Manrope for words, JetBrains Mono for numbers, sizes and counts.
 *
 * The faces are the ones `Design System v2` loads, and no others: Manrope 400 to 800 and JetBrains Mono 400, 500 and
 * 700. A weight in between draws the nearest of them. Static files rather than variable ones: Android applies a font's
 * variation only from API 26, and below that a variable Manrope draws its default instance, ExtraLight.
 *
 * The files are upstream's bytes, renamed only: Manrope 4.505 (`googlefonts/manrope`) and JetBrains Mono 2.304
 * (`JetBrains/JetBrainsMono`), both under the SIL Open Font License 1.1. Their licences travel with them, in
 * `files/licences/`, and `NOTICE` records where each came from and its SHA-256.
 */
object CloudStreamFonts {

    @Composable
    fun manrope(): FontFamily = FontFamily(
        Font(Res.font.manrope_regular, FontWeight.Normal),
        Font(Res.font.manrope_medium, FontWeight.Medium),
        Font(Res.font.manrope_semibold, FontWeight.SemiBold),
        Font(Res.font.manrope_bold, FontWeight.Bold),
        Font(Res.font.manrope_extrabold, FontWeight.ExtraBold),
    )

    @Composable
    fun jetBrainsMono(): FontFamily = FontFamily(
        Font(Res.font.jetbrainsmono_regular, FontWeight.Normal),
        Font(Res.font.jetbrainsmono_medium, FontWeight.Medium),
        Font(Res.font.jetbrainsmono_bold, FontWeight.Bold),
    )
}

/** The ramp with the bundled families. Sizes and line boxes are the ramp's own; the weights are each platform's. */
@Composable
fun cloudStreamAppTypography(): CloudStreamTypography {
    val sans = CloudStreamFonts.manrope()
    val mono = CloudStreamFonts.jetBrainsMono()
    return remember(sans, mono) { cloudStreamTypography(sans = sans, mono = mono) }
}

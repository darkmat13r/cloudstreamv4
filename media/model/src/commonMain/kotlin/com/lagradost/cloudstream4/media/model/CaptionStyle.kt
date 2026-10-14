package com.lagradost.cloudstream4.media.model

// kotlin.jvm.* is a default import only on JVM; Kotlin/Native needs it named.
import kotlin.jvm.JvmInline

/**
 * A colour as packed ARGB, 8 bits per channel.
 *
 * `:app`'s `SaveCaptionStyle` stores four colours as bare `Int`s whose packing is
 * `android.graphics.Color`'s. The packing is not Android's invention and is portable; what is not
 * portable is a bare `Int`, which says nothing about which of the many orderings it uses. This
 * value class costs nothing at runtime and makes the convention checkable.
 */
@JvmInline
value class ArgbColor(val value: Int) {
    val alpha: Int get() = (value ushr 24) and CHANNEL_MASK
    val red: Int get() = (value ushr 16) and CHANNEL_MASK
    val green: Int get() = (value ushr 8) and CHANNEL_MASK
    val blue: Int get() = value and CHANNEL_MASK

    companion object {
        private const val CHANNEL_MASK = 0xFF

        val Transparent = ArgbColor(0x00000000)
        val Black = ArgbColor(0xFF000000.toInt())
        val White = ArgbColor(0xFFFFFFFF.toInt())

        fun of(alpha: Int, red: Int, green: Int, blue: Int): ArgbColor = ArgbColor(
            ((alpha and CHANNEL_MASK) shl 24) or
                ((red and CHANNEL_MASK) shl 16) or
                ((green and CHANNEL_MASK) shl 8) or
                (blue and CHANNEL_MASK)
        )
    }
}

/**
 * How a caption's glyphs are separated from whatever is behind them.
 *
 * Today this is an `Int` in **two mutually incompatible namespaces**: Media3's
 * `CaptionStyleCompat.EDGE_TYPE_*` for the player (`SaveCaptionStyle.edgeType`) and Cast's
 * `TextTrackStyle.EDGE_TYPE_*` for Chromecast (`SaveChromeCaptionStyle.edgeType`). The same user
 * setting is stored twice, as two different integers, and nothing in the type system says so.
 *
 * Naming the cases makes the two mappings `:app`'s job and total in both directions.
 */
enum class CaptionEdge {
    NONE,
    OUTLINE,
    DROP_SHADOW,
    RAISED,
    DEPRESSED,
}

/**
 * Which typeface to render captions in.
 *
 * `SaveCaptionStyle` has three overlapping fields for this — `typeface: Int?` (an Android
 * `@FontRes` id), `typefaceFilePath: String?` (a file under the app's external files dir) — and
 * `SaveChromeCaptionStyle` has two more that do not match either (`fontFamily: String?`,
 * `fontGenericFamily: Int?`). Modelling that honestly means admitting there are three different
 * kinds of answer.
 *
 * **[PlatformDefault] is not "no choice".** An Android `@FontRes` id is a lookup into a resource
 * table that exists only in an Android build; there is no portable value that means the same thing.
 * A caller that wants a bundled font names it with [Bundled], and the platform decides what that
 * name resolves to.
 */
sealed interface CaptionFont {
    /** Whatever the platform renders captions in when nothing is chosen. */
    data object PlatformDefault : CaptionFont

    /** A font this build ships, named rather than numbered so the name survives a resource id. */
    data class Bundled(val name: String) : CaptionFont

    /** A font file the user supplied. [locator] is opaque, as [StreamSource.Local]'s is. */
    data class UserFile(val locator: String) : CaptionFont
}

/**
 * How captions are drawn.
 *
 * Restates the portable half of `:app`'s `SaveCaptionStyle` and `SaveChromeCaptionStyle` as one
 * type. What is **not** restated, and why:
 *
 * - `elevation` (dp), `edgeSize` (px) and `backgroundRadius` (px) — three different density units
 *   in one object, resolved only on Android through `TypedValue` and `.toPx`. A contract that
 *   carries a px value carries a screen with it. [textSizeSp] is kept because it is the one the
 *   user actually sets and because sp is a stated scale rather than a device measurement; the rest
 *   belong to the renderer.
 * - `removeCaptions`, `removeBloat`, `upperCase` — text *filtering*, not style. They describe what
 *   to do to the cue text before drawing it, which is `:media:subtitle`'s job, and folding them in
 *   here is what makes the existing type 17 fields wide.
 *
 * [alignment] is an SSA alignment code (1–9, numpad layout) and is kept as the subtitle format's
 * own number rather than renamed, because that is what the file supplies.
 */
data class CaptionStyle(
    val foregroundColor: ArgbColor = ArgbColor.White,
    val backgroundColor: ArgbColor = ArgbColor.Transparent,
    val windowColor: ArgbColor = ArgbColor.Transparent,
    val edge: CaptionEdge = CaptionEdge.OUTLINE,
    val edgeColor: ArgbColor = ArgbColor.Black,
    val font: CaptionFont = CaptionFont.PlatformDefault,
    /** Fixed text size in sp. `null` means the renderer scales with the surface, as today. */
    val textSizeSp: Float? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val alignment: Int? = null,
)

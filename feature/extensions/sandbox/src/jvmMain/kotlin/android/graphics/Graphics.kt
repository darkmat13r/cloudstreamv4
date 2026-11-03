package android.graphics

import android.graphics.drawable.Drawable

/** Pure arithmetic on ints. Plugins build colours for dialogs that never open here. */
object Color {
    @JvmField val TRANSPARENT: Int = 0

    @JvmStatic fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    @JvmStatic fun rgb(red: Int, green: Int, blue: Int): Int = argb(0xFF, red, green, blue)

    /** `#RRGGBB` or `#AARRGGBB`. Android throws on anything else, and so does this. */
    @JvmStatic fun parseColor(text: String): Int {
        require(text.startsWith("#") && (text.length == 7 || text.length == 9)) { "Unknown color" }
        val value = text.drop(1).toLong(16)
        return (if (text.length == 7) value or 0xFF000000L else value).toInt()
    }
}

open class Typeface {
    companion object {
        @JvmField val DEFAULT: Typeface = Typeface()
        @JvmField val DEFAULT_BOLD: Typeface = Typeface()
    }
}

open class Paint

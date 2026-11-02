package android.graphics.drawable

/** Types only: enough to link. Anything that would draw gets `NoSuchMethodError`, reported as that call failing. */
abstract class Drawable
open class ColorDrawable : Drawable()
open class ClipDrawable : Drawable()
open class LayerDrawable : Drawable()
open class GradientDrawable : Drawable() {
    enum class Orientation { TOP_BOTTOM, TR_BL, RIGHT_LEFT, BR_TL, BOTTOM_TOP, BL_TR, LEFT_RIGHT, TL_BR }
}

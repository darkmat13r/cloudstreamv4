package android.util

/** A fixed, plausible phone, so a plugin sizing a dialog does no arithmetic with zero. */
open class DisplayMetrics {
    @JvmField var density: Float = 1f
    @JvmField var widthPixels: Int = 1080
    @JvmField var heightPixels: Int = 1920
}

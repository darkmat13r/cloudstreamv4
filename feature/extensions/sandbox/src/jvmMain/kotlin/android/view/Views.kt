package android.view

import android.content.Context

/**
 * The view types, with their real hierarchy and no behaviour.
 *
 * A plugin's settings screen and donation dialog refer to these, so the JVM's verifier needs them
 * before `load()` can run, even though neither ever opens: `CommonActivity.activity` is null here and
 * `openSettings` is never called. So these are for linking. A plugin that does try to build UI gets
 * `NoSuchMethodError`, reported as that call failing, the same rule as [Context].
 */
open class View(@Suppress("UNUSED_PARAMETER") context: Context? = null) {
    interface OnClickListener
    interface OnFocusChangeListener
    interface OnKeyListener
    interface OnScrollChangeListener
    interface OnTouchListener
    interface OnLongClickListener
}

abstract class ViewGroup(context: Context? = null) : View(context) {
    open class LayoutParams
    open class MarginLayoutParams : LayoutParams()
}

interface ViewParent
interface Menu
interface MenuItem
open class ViewPropertyAnimator
open class ViewTreeObserver {
    interface OnGlobalLayoutListener
}
open class Window
open class LayoutInflater
open class KeyEvent {
    fun getAction(): Int = ACTION_DOWN

    companion object {
        const val ACTION_DOWN = 0
    }
}
open class MotionEvent
open class WindowManager {
    open class LayoutParams : ViewGroup.LayoutParams()
}

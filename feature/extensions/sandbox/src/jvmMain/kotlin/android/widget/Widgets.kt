package android.widget

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Types only, as in `android.view`: for linking a settings screen that never opens here. */
open class TextView(context: Context? = null) : View(context) {
    interface OnEditorActionListener
}
open class EditText(context: Context? = null) : TextView(context)
open class AutoCompleteTextView(context: Context? = null) : EditText(context)
open class Button(context: Context? = null) : TextView(context)
abstract class CompoundButton(context: Context? = null) : Button(context) {
    interface OnCheckedChangeListener
}
open class CheckBox(context: Context? = null) : CompoundButton(context)
open class Switch(context: Context? = null) : CompoundButton(context)
open class ImageView(context: Context? = null) : View(context)
open class ImageButton(context: Context? = null) : ImageView(context)
open class ProgressBar(context: Context? = null) : View(context)
open class LinearLayout(context: Context? = null) : ViewGroup(context) {
    open class LayoutParams : ViewGroup.MarginLayoutParams()
}
open class FrameLayout(context: Context? = null) : ViewGroup(context) {
    open class LayoutParams : ViewGroup.MarginLayoutParams()
}
open class ScrollView(context: Context? = null) : FrameLayout(context)
open class HorizontalScrollView(context: Context? = null) : FrameLayout(context)
interface Adapter
interface ListAdapter : Adapter
interface SpinnerAdapter : Adapter
open class BaseAdapter : ListAdapter, SpinnerAdapter
open class ArrayAdapter<T> : BaseAdapter()
abstract class AdapterView<T : Adapter>(context: Context? = null) : ViewGroup(context) {
    interface OnItemSelectedListener
}
open class Spinner(context: Context? = null) : AdapterView<SpinnerAdapter>(context)
open class Toast

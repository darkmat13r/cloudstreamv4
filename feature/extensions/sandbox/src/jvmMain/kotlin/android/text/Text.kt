package android.text

/** Types only, with the plain-text behaviour a plugin's helpers can observe. No spans are rendered. */
interface Editable : CharSequence
interface Spanned : CharSequence
interface TextWatcher
interface InputFilter {
    class LengthFilter(val max: Int) : InputFilter
}

open class SpannableStringBuilder(text: CharSequence = "") : CharSequence, Spanned {
    private val builder = StringBuilder(text)
    override val length: Int get() = builder.length
    override fun get(index: Int): Char = builder[index]
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = builder.subSequence(startIndex, endIndex)
    fun append(text: CharSequence?): SpannableStringBuilder = apply { builder.append(text) }
    fun setSpan(what: Any?, start: Int, end: Int, flags: Int) = Unit
    override fun toString(): String = builder.toString()
}

object Html {
    const val FROM_HTML_MODE_LEGACY = 0

    /** Tags removed, not rendered: text for a dialog that does not open here, and never parsed as markup. */
    @JvmStatic fun fromHtml(source: String?, flags: Int): Spanned =
        SpannableStringBuilder(source.orEmpty().replace(TAG, ""))

    private val TAG = Regex("<[^>]*>")
}

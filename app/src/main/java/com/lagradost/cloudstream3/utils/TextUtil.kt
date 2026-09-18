package com.lagradost.cloudstream3.utils

import android.content.Context
import android.util.Log
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppContextUtils.html

/**
 * Superseded by `com.lagradost.cloudstream4.core.strings.UiString` (migration plan §7 Phase 3 S-b, D4).
 *
 * `UiString` is the same idea without a `Context`: `Raw` for text that is already final, `Resource`/`Plural` for a
 * Compose Multiplatform `StringResource`, resolved with `resolve()` inside a composition or `load()` outside one.
 * It lives in `:core:strings`, so a ViewModel in `commonMain` can hold one, which `UiText` — `@StringRes Int` plus
 * `Context` — cannot.
 *
 * Nothing is removed: every existing call site keeps working, and deprecating rather than deleting is what lets
 * third-party extensions, which compile against `classes.jar`, see the warning before the type eventually goes.
 * There is no adapter between the two on purpose: an adapter written in `:app` would itself land in `classes.jar`
 * as new frozen API, and no consumer needs one yet.
 */
@Deprecated(
    "Use com.lagradost.cloudstream4.core.strings.UiString; see the KDoc above.",
    level = DeprecationLevel.WARNING,
)
sealed class UiText {
    companion object {
        const val TAG = "UiText"
    }

    data class DynamicString(val value: String) : UiText() {
        override fun toString(): String = value

        override fun equals(other: Any?): Boolean {
            if (other !is DynamicString) return false
            return this.value == other.value
        }

        override fun hashCode(): Int = value.hashCode()
    }

    class StringResource(
        @StringRes val resId: Int,
        val args: List<Any>
    ) : UiText() {
        override fun toString(): String =
            "resId = $resId\nargs = ${args.toList().map { "(${it::class} = $it)" }}"
        override fun equals(other: Any?): Boolean {
            if (other !is StringResource) return false
            return this.resId == other.resId && this.args == other.args
        }

        override fun hashCode(): Int {
            var result = resId
            result = 31 * result + args.hashCode()
            return result
        }
    }

    fun asStringNull(context: Context?): String? {
        try {
            return asString(context ?: return null)
        } catch (e: Exception) {
            Log.e(TAG, "Got invalid data from $this")
            logError(e)
            return null
        }
    }

    fun asString(context: Context): String {
        return when (this) {
            is DynamicString -> value
            is StringResource -> {
                val str = context.getString(resId)
                if (args.isEmpty()) {
                    str
                } else {
                    str.format(*args.map {
                        when (it) {
                            is UiText -> it.asString(context)
                            else -> it
                        }
                    }.toTypedArray())
                }
            }
        }
    }
}

@Deprecated(
    "Use com.lagradost.cloudstream4.core.strings.UiString.Raw(value).",
    level = DeprecationLevel.WARNING,
)
fun txt(value: String): UiText {
    return UiText.DynamicString(value)
}

@Deprecated(
    "Use com.lagradost.cloudstream4.core.strings.UiString.Raw, as value?.let(UiString::Raw).",
    level = DeprecationLevel.WARNING,
)
@JvmName("txtNull")
fun txt(value: String?): UiText? {
    return UiText.DynamicString(value ?: return null)
}

@Deprecated(
    "Use com.lagradost.cloudstream4.core.strings.UiString.Resource(res, args.toList()).",
    level = DeprecationLevel.WARNING,
)
fun txt(@StringRes resId: Int, vararg args: Any): UiText {
    return UiText.StringResource(resId, args.toList())
}

@Deprecated(
    "Use com.lagradost.cloudstream4.core.strings.UiString.Resource(res, args.toList()).",
    level = DeprecationLevel.WARNING,
)
@JvmName("txtNull")
fun txt(@StringRes resId: Int?, vararg args: Any?): UiText? {
    if (resId == null || args.any { it == null }) {
        return null
    }
    return UiText.StringResource(resId, args.filterNotNull().toList())
}

fun TextView?.setText(text: UiText?) {
    if (this == null) return
    if (text == null) {
        this.isVisible = false
    } else {
        val str = text.asStringNull(context)?.let {
            if (this.maxLines == 1) {
                it.replace("\n", " ")
            } else {
                it
            }
        }

        this.isGone = str.isNullOrBlank()
        this.text = str
    }
}

fun TextView?.setTextHtml(text: UiText?) {
    if (this == null) return
    if (text == null) {
        this.isVisible = false
    } else {
        val str = text.asStringNull(context)
        this.isGone = str.isNullOrBlank()
        this.text = str.html()
    }
}

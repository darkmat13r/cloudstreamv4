package com.lagradost.cloudstream3.utils

import android.content.Context

/** `:app`'s text that needs a `Context`. Only here to be named: a host never makes one. */
abstract class UiText {
    abstract fun asString(context: Context): String
}

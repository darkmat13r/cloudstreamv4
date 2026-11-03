package com.lagradost.cloudstream3.ui.settings

/** Reports a phone layout, the one a plugin's non-TV branches assume. It selects UI that never shows. */
object Globals {
    const val PHONE: Int = 1
    const val TV: Int = 2
    const val EMULATOR: Int = 4

    fun isLayout(flags: Int): Boolean = (PHONE and flags) != 0
}

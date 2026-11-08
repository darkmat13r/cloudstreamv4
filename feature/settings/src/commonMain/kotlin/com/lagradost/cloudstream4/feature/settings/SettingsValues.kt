package com.lagradost.cloudstream4.feature.settings

/**
 * Every setting the Settings window can change, with its default. Only rows that take effect are here (`KMP-90`):
 * a row lands with the thing that reads it, so no control in the window does nothing.
 *
 * Stored as text under Android's own key names, so the two platforms mean the same thing by a key. Anything missing,
 * malformed or out of range reads as the default: a hand-edited file cannot put the app in a state the window could not.
 */
data class SettingsValues(
    val autoplayNext: Boolean = DEFAULT_AUTOPLAY_NEXT,
    val downloadsAtOnce: Int = DEFAULT_DOWNLOADS_AT_ONCE,
    val showRandom: Boolean = DEFAULT_SHOW_RANDOM,
) {
    init {
        require(downloadsAtOnce in DOWNLOADS_AT_ONCE) { "downloadsAtOnce out of range" }
    }

    /** The stored form: text values under Android's keys. */
    fun toStored(): Map<String, String> = mapOf(
        KEY_AUTOPLAY_NEXT to autoplayNext.toString(),
        KEY_DOWNLOADS_AT_ONCE to downloadsAtOnce.toString(),
        KEY_SHOW_RANDOM to showRandom.toString(),
    )

    companion object {
        /** Android's `autoplay_next_key`. */
        const val KEY_AUTOPLAY_NEXT = "autoplay_next_key"

        /** Android's `download_parallel_key`. */
        const val KEY_DOWNLOADS_AT_ONCE = "download_parallel_key"

        /** Android's `random_button_key`. */
        const val KEY_SHOW_RANDOM = "random_button_key"

        const val DEFAULT_AUTOPLAY_NEXT = true

        /**
         * On, where Android's is off: the Mac's Home has always shown Random (the owner's call on `DS-24`, 2026-11-05),
         * and the switch only lets someone hide it (the owner, 2026-11-08). The key means the same on both.
         */
        const val DEFAULT_SHOW_RANDOM = true

        /** Android's default, and the Mac engine's until now (`DesktopDownloads.MAX_RUNNING`). */
        const val DEFAULT_DOWNLOADS_AT_ONCE = 3

        /** The choices the window offers. More than five at once only splits one connection's speed further. */
        val DOWNLOADS_AT_ONCE: IntRange = 1..5

        /** Reads a stored map. Unknown keys are ignored; a bad value reads as that setting's default. */
        fun fromStored(stored: Map<String, String>): SettingsValues = SettingsValues(
            autoplayNext = stored.flag(KEY_AUTOPLAY_NEXT, DEFAULT_AUTOPLAY_NEXT),
            downloadsAtOnce = stored[KEY_DOWNLOADS_AT_ONCE]?.trim()?.toIntOrNull()
                ?.takeIf { it in DOWNLOADS_AT_ONCE }
                ?: DEFAULT_DOWNLOADS_AT_ONCE,
            showRandom = stored.flag(KEY_SHOW_RANDOM, DEFAULT_SHOW_RANDOM),
        )

        /** `true` or `false` exactly, around spaces; anything else is [default]. */
        private fun Map<String, String>.flag(key: String, default: Boolean): Boolean = when (this[key]?.trim()) {
            "true" -> true
            "false" -> false
            else -> default
        }
    }
}

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
) {
    init {
        require(downloadsAtOnce in DOWNLOADS_AT_ONCE) { "downloadsAtOnce out of range" }
    }

    /** The stored form: text values under Android's keys. */
    fun toStored(): Map<String, String> = mapOf(
        KEY_AUTOPLAY_NEXT to autoplayNext.toString(),
        KEY_DOWNLOADS_AT_ONCE to downloadsAtOnce.toString(),
    )

    companion object {
        /** Android's `autoplay_next_key`. */
        const val KEY_AUTOPLAY_NEXT = "autoplay_next_key"

        /** Android's `download_parallel_key`. */
        const val KEY_DOWNLOADS_AT_ONCE = "download_parallel_key"

        const val DEFAULT_AUTOPLAY_NEXT = true

        /** Android's default, and the Mac engine's until now (`DesktopDownloads.MAX_RUNNING`). */
        const val DEFAULT_DOWNLOADS_AT_ONCE = 3

        /** The choices the window offers. More than five at once only splits one connection's speed further. */
        val DOWNLOADS_AT_ONCE: IntRange = 1..5

        /** Reads a stored map. Unknown keys are ignored; a bad value reads as that setting's default. */
        fun fromStored(stored: Map<String, String>): SettingsValues = SettingsValues(
            autoplayNext = when (stored[KEY_AUTOPLAY_NEXT]?.trim()) {
                "true" -> true
                "false" -> false
                else -> DEFAULT_AUTOPLAY_NEXT
            },
            downloadsAtOnce = stored[KEY_DOWNLOADS_AT_ONCE]?.trim()?.toIntOrNull()
                ?.takeIf { it in DOWNLOADS_AT_ONCE }
                ?: DEFAULT_DOWNLOADS_AT_ONCE,
        )
    }
}

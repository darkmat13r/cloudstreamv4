package com.lagradost.cloudstream4.feature.settings

import com.lagradost.cloudstream4.core.designsystem.desktop.PosterSize

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
    val posterSize: PosterSize = DEFAULT_POSTER_SIZE,
    val seekSeconds: Int = DEFAULT_SEEK_SECONDS,
) {
    init {
        require(downloadsAtOnce in DOWNLOADS_AT_ONCE) { "downloadsAtOnce out of range" }
        require(seekSeconds in SEEK_SECONDS) { "seekSeconds out of range" }
    }

    /** The stored form: text values under Android's keys. */
    fun toStored(): Map<String, String> = mapOf(
        KEY_AUTOPLAY_NEXT to autoplayNext.toString(),
        KEY_DOWNLOADS_AT_ONCE to downloadsAtOnce.toString(),
        KEY_SHOW_RANDOM to showRandom.toString(),
        KEY_POSTER_SIZE to posterSize.name.lowercase(),
        KEY_SEEK_SECONDS to seekSeconds.toString(),
    )

    companion object {
        /** Android's `autoplay_next_key`. */
        const val KEY_AUTOPLAY_NEXT = "autoplay_next_key"

        /** Android's `download_parallel_key`. */
        const val KEY_DOWNLOADS_AT_ONCE = "download_parallel_key"

        /** Android's `random_button_key`. */
        const val KEY_SHOW_RANDOM = "random_button_key"

        /**
         * The Mac's own key, not Android's `poster_size_key`: that is a 0 to 15 slider, 10% a step from 0, so it has
         * nothing smaller than its default, and the Mac's Small would mean nothing to it (`KMP-90e`).
         */
        const val KEY_POSTER_SIZE = "poster_size_desktop_key"

        /**
         * The Mac's own key, in whole seconds (`KMP-96`). Not one of Android's: its seek keys are the TV's, in another
         * range and with another meaning.
         */
        const val KEY_SEEK_SECONDS = "seek_amount_desktop_key"

        const val DEFAULT_AUTOPLAY_NEXT = true

        /**
         * On, where Android's is off: the Mac's Home has always shown Random (the owner's call on `DS-24`, 2026-11-05),
         * and the switch only lets someone hide it (the owner, 2026-11-08). The key means the same on both.
         */
        const val DEFAULT_SHOW_RANDOM = true

        /** Every screen's width before the setting. */
        val DEFAULT_POSTER_SIZE: PosterSize = PosterSize.Medium

        /** Android's default, and the Mac engine's until now (`DesktopDownloads.MAX_RUNNING`). */
        const val DEFAULT_DOWNLOADS_AT_ONCE = 3

        /** The choices the window offers. More than five at once only splits one connection's speed further. */
        val DOWNLOADS_AT_ONCE: IntRange = 1..5

        /** The Mac player's step until now, so a window that never opens Settings seeks as it did. */
        const val DEFAULT_SEEK_SECONDS = 10

        /** What the arrow keys and the skip buttons can move by, in seconds: the choices the window offers. */
        val SEEK_SECONDS: List<Int> = listOf(5, 10, 15, 30)

        /** Reads a stored map. Unknown keys are ignored; a bad value reads as that setting's default. */
        fun fromStored(stored: Map<String, String>): SettingsValues = SettingsValues(
            autoplayNext = stored.flag(KEY_AUTOPLAY_NEXT, DEFAULT_AUTOPLAY_NEXT),
            downloadsAtOnce = stored[KEY_DOWNLOADS_AT_ONCE]?.trim()?.toIntOrNull()
                ?.takeIf { it in DOWNLOADS_AT_ONCE }
                ?: DEFAULT_DOWNLOADS_AT_ONCE,
            showRandom = stored.flag(KEY_SHOW_RANDOM, DEFAULT_SHOW_RANDOM),
            posterSize = stored[KEY_POSTER_SIZE]?.trim()
                ?.let { value -> PosterSize.entries.firstOrNull { it.name.lowercase() == value } }
                ?: DEFAULT_POSTER_SIZE,
            seekSeconds = stored[KEY_SEEK_SECONDS]?.trim()?.toIntOrNull()
                ?.takeIf { it in SEEK_SECONDS }
                ?: DEFAULT_SEEK_SECONDS,
        )

        /** `true` or `false` exactly, around spaces; anything else is [default]. */
        private fun Map<String, String>.flag(key: String, default: Boolean): Boolean = when (this[key]?.trim()) {
            "true" -> true
            "false" -> false
            else -> default
        }
    }
}

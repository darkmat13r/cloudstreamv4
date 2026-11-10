package com.lagradost.cloudstream4.feature.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the player reads from Settings. Only reads: the Settings window is the one writer ([MutablePlaybackSettings]).
 *
 * A [StateFlow], because the window can change a value while an Up next card is on screen (`DS-23`).
 */
interface PlaybackSettings {
    /**
     * "Play the next episode": Android's `autoplay_next_key`, on unless turned off. On, Up next counts down and plays;
     * off, it shows with no countdown (`DS-23` M16).
     */
    val autoplayNext: StateFlow<Boolean>

    /** "Seek amount": how far the arrow keys and the skip buttons move, always one of [SettingsValues.SEEK_SECONDS]. */
    val seekSeconds: StateFlow<Int>

    /**
     * "Skip OP": Android's `enable_skip_op_from_database` (`KMP-86`). On, the player asks the skip services for the
     * episode's stamps; off, it asks nothing at all.
     */
    val skipStamps: StateFlow<Boolean>

    companion object {
        /** Every value at its default, never stored. What a test or a host without storage reads. */
        val Default: PlaybackSettings = object : PlaybackSettings {
            override val autoplayNext: StateFlow<Boolean> =
                MutableStateFlow(SettingsValues.DEFAULT_AUTOPLAY_NEXT).asStateFlow()
            override val seekSeconds: StateFlow<Int> =
                MutableStateFlow(SettingsValues.DEFAULT_SEEK_SECONDS).asStateFlow()
            override val skipStamps: StateFlow<Boolean> =
                MutableStateFlow(SettingsValues.DEFAULT_SKIP_STAMPS).asStateFlow()
        }
    }
}

/** [PlaybackSettings] with its writer, held only by the Settings window. */
interface MutablePlaybackSettings : PlaybackSettings {
    fun setAutoplayNext(on: Boolean)
}

/** What the downloads engine reads from Settings. */
interface DownloadSettings {
    /** "Downloads at once": Android's `download_parallel_key`, always within [SettingsValues.DOWNLOADS_AT_ONCE]. */
    val downloadsAtOnce: StateFlow<Int>
}

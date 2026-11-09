package com.lagradost.cloudstream4.feature.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What Home, Search and Library read from Settings' Appearance pane (`KMP-90g`). Only reads: the Settings window is the
 * one writer. A [StateFlow], so a screen on show changes as the switch does.
 */
interface AppearanceSettings {
    /** "Random Button": Android's `random_button_key`. Off, Home's top bar has no Random. */
    val showRandom: StateFlow<Boolean>

    companion object {
        /** Every value at its default, never stored. What a test or a host without storage reads. */
        val Default: AppearanceSettings = object : AppearanceSettings {
            override val showRandom: StateFlow<Boolean> =
                MutableStateFlow(SettingsValues.DEFAULT_SHOW_RANDOM).asStateFlow()
        }
    }
}

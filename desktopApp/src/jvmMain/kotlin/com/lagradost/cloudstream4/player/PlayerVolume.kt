package com.lagradost.cloudstream4.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * How loud the player is, shared by every stream in a run because each one starts a new host. Not kept between runs.
 *
 * Muting keeps [level], so unmuting returns to it. Choosing a level is choosing to hear it, so it unmutes.
 */
internal class PlayerVolume(level: Int = MAX) {
    var level by mutableStateOf(level.coerceIn(0, MAX))
        private set
    var muted by mutableStateOf(false)
        private set

    /** What the host should play at. */
    val audible: Int get() = if (muted) 0 else level

    fun set(level: Int) {
        this.level = level.coerceIn(0, MAX)
        muted = false
    }

    fun step(by: Int) = set(level + by)

    fun toggleMute() {
        muted = !muted
    }

    companion object {
        const val MAX = 100
        const val STEP = 5
    }
}

internal val desktopPlayerVolume: PlayerVolume by lazy { PlayerVolume() }

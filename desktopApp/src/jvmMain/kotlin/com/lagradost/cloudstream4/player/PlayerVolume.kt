package com.lagradost.cloudstream4.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * How loud the player is, shared by every stream in a run because each one starts a new host, and kept in [store]
 * between runs.
 *
 * Muting keeps [level], so unmuting returns to it. Choosing a level is choosing to hear it, so it unmutes.
 */
internal class PlayerVolume(
    level: Int = MAX,
    muted: Boolean = false,
    private val store: PlayerVolumeStore = PlayerVolumeStore.None,
) {
    constructor(store: PlayerVolumeStore) : this(store.read(), store)

    private constructor(saved: SavedVolume, store: PlayerVolumeStore) : this(saved.level, saved.muted, store)

    var level by mutableStateOf(level.coerceIn(0, MAX))
        private set
    var muted by mutableStateOf(muted)
        private set

    /** What the host should play at. */
    val audible: Int get() = if (muted) 0 else level

    fun set(level: Int) {
        val clamped = level.coerceIn(0, MAX)
        if (clamped == this.level && !muted) return
        this.level = clamped
        muted = false
        store.remember(SavedVolume(this.level, muted))
    }

    fun step(by: Int) = set(level + by)

    fun toggleMute() {
        muted = !muted
        store.remember(SavedVolume(level, muted))
    }

    companion object {
        const val MAX = 100
        const val STEP = 5
    }
}

internal val desktopPlayerVolume: PlayerVolume by lazy { PlayerVolume(FilePlayerVolumeStore()) }

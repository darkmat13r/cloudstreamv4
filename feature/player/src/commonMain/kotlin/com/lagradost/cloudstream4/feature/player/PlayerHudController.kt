package com.lagradost.cloudstream4.feature.player

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The remote's keys, as the HUD tells them apart. The host maps its platform's key codes onto these. */
enum class HudKey { Left, Right, Up, Down, Center, PlayPause, Other }

/**
 * 7d's `HIDDEN` readout: `+00:30 → 42:43`, the total moved in this burst of presses and where it lands.
 *
 * @property deltaMs the sum of the burst, negative for back.
 * @property targetMs where playback will be, kept inside the title.
 */
@Immutable
data class ScrubReadout(val deltaMs: Long, val targetMs: Long)

/**
 * Whether the HUD is up, and what the remote does about it. The rules are 7d's notes, and the timings are the old
 * player's, so nothing about how the player feels changes with the drawing:
 *
 * - **Shown, it hides itself** [hideAfterMs] after the last key, as `PlayerView.scheduleAutoHide` does (3000 ms), and
 *   only while playing: paused or buffering, it stays (7d's `BUFFERING`, and the old `onAutoHideUI`).
 * - **Hidden, Left and Right scrub** by [hiddenSeekMs] (the `android_tv_interface_off_seek_key` setting, 10 s by
 *   default) and raise [readout] without revealing the HUD. The readout clears [readoutMs] after the last press.
 * - **Hidden, Up and Down reveal.** Centre toggles and reveals, unless a skip is offered, when it takes the skip, as the
 *   old player does and as 7d's skip holding focus means.
 * - **Shown, every key re-arms the timer** and goes on to the focused control.
 * - **Play/pause** from a remote's media key works either way.
 *
 * @param onAction where the HUD's requests go; the host carries them to the player.
 */
class PlayerHudController(
    private val scope: CoroutineScope,
    private val hiddenSeekMs: Long,
    private val onAction: (PlayerHudAction) -> Unit,
    private val hideAfterMs: Long = HIDE_AFTER_MS,
    private val readoutMs: Long = READOUT_MS,
) {
    private val _shown = MutableStateFlow(false)
    val shown: StateFlow<Boolean> = _shown.asStateFlow()

    private val _readout = MutableStateFlow<ScrubReadout?>(null)
    val readout: StateFlow<ScrubReadout?> = _readout.asStateFlow()

    private var hideJob: Job? = null
    private var readoutJob: Job? = null
    private var holdOpen = false

    /** The latest facts, for where a scrub lands and whether a skip is offered. Set by the host on every change. */
    var state: PlayerHudState = PlayerHudState()
        set(value) {
            field = value
            val hold = !value.playing || value.buffering
            if (hold != holdOpen) {
                holdOpen = hold
                if (_shown.value) arm()
            }
        }

    fun reveal() {
        _shown.value = true
        arm()
    }

    fun hide() {
        hideJob?.cancel()
        _shown.value = false
    }

    /**
     * One key from the remote. Returns whether it was used up here; `false` means it goes on to the focused control.
     */
    fun onKey(key: HudKey): Boolean {
        if (key == HudKey.PlayPause) {
            onAction(PlayerHudAction.PlayPause)
            if (_shown.value) arm()
            return true
        }
        if (_shown.value) {
            arm()
            return false
        }
        when (key) {
            HudKey.Left -> scrub(-hiddenSeekMs)
            HudKey.Right -> scrub(hiddenSeekMs)
            HudKey.Up, HudKey.Down -> reveal()
            HudKey.Center -> if (state.skip != null) {
                onAction(PlayerHudAction.Skip)
            } else {
                onAction(PlayerHudAction.PlayPause)
                reveal()
            }
            HudKey.PlayPause, HudKey.Other -> return false
        }
        return true
    }

    private fun scrub(deltaMs: Long) {
        onAction(PlayerHudAction.SeekBy(deltaMs))
        val burst = _readout.value
        val from = burst?.let { it.targetMs - it.deltaMs } ?: state.positionMs
        val total = (burst?.deltaMs ?: 0) + deltaMs
        val end = if (state.durationMs > 0) state.durationMs else Long.MAX_VALUE
        _readout.value = ScrubReadout(deltaMs = total, targetMs = (from + total).coerceIn(0, end))
        readoutJob?.cancel()
        readoutJob = scope.launch {
            delay(readoutMs)
            _readout.value = null
        }
    }

    private fun arm() {
        hideJob?.cancel()
        if (holdOpen) return
        hideJob = scope.launch {
            delay(hideAfterMs)
            _shown.value = false
        }
    }

    companion object {
        /** `PlayerView.scheduleAutoHide`'s default. */
        const val HIDE_AFTER_MS = 3_000L

        /** 7d: the readout and its hairline clear 1500 ms after the last key. */
        const val READOUT_MS = 1_500L
    }
}

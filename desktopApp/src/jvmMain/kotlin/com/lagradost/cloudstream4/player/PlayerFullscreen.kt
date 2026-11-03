package com.lagradost.cloudstream4.player

import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState

/**
 * The window's full screen, as the player uses it. Going through [WindowState] keeps this in step with the
 * window's own green button, so a window made full screen that way reads as [on] too.
 *
 * Made once per playback, because where the window was when playback began is what [leave] goes back to.
 * A window that was already full screen before the player opened stays full screen after it.
 */
internal class PlayerFullscreen(private val window: WindowState) {
    private val startedIn = window.placement

    val on: Boolean get() = window.placement == WindowPlacement.Fullscreen

    fun toggle() {
        window.placement = if (on) windowed() else WindowPlacement.Fullscreen
    }

    /** When the player closes, by Back or by the stream ending. */
    fun leave() {
        if (on && startedIn != WindowPlacement.Fullscreen) window.placement = startedIn
    }

    private fun windowed() = if (startedIn == WindowPlacement.Fullscreen) WindowPlacement.Floating else startedIn
}

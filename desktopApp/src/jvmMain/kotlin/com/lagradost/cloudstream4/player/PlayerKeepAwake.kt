package com.lagradost.cloudstream4.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.io.File

/**
 * Keeps the display awake while a video is playing, and only then: a pause gives the Mac back its own idle timer.
 *
 * `caffeinate` is part of macOS, so this adds no native code. `-w` ties it to this process, so a crash cannot leave
 * the display held. Nothing here comes from a plugin or a stream. Off the Mac, or if it cannot start, the
 * video plays on and the display may sleep, which is no worse than before.
 */
internal class DisplayWakeHold(
    private val start: () -> Process? = ::startCaffeinate,
) {
    private var held: Process? = null

    fun set(wanted: Boolean) {
        if (wanted && held?.isAlive != true) held = start()
        if (!wanted) {
            held?.destroy()
            held = null
        }
    }
}

private fun startCaffeinate(): Process? = runCatching {
    if (!File(CAFFEINATE).canExecute()) return null
    ProcessBuilder(CAFFEINATE, "-d", "-w", ProcessHandle.current().pid().toString())
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
}.getOrNull()

private const val CAFFEINATE = "/usr/bin/caffeinate"

/** Holds for as long as [playing] is true and this is on screen. */
@Composable
internal fun KeepDisplayAwake(playing: Boolean) {
    DisposableEffect(playing) {
        val hold = DisplayWakeHold()
        hold.set(playing)
        onDispose { hold.set(false) }
    }
}

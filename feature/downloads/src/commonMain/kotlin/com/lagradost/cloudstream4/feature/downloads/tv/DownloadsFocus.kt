package com.lagradost.cloudstream4.feature.downloads.tv

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * Brings focus back to the stop that left the screen when it comes back from the rail, because the rail's RIGHT
 * asks Compose for the nearest stop, which on a scrolling list is not the one that left. The same answer as
 * `:feature:library`, measured there on `Television_720p`.
 */
@Stable
internal class DownloadsFocusMemory {
    /** Whether focus was on this screen, as of the last frame. */
    var inside = false
        private set

    var last: FocusRequester? = null
        private set

    /** Where focus still has to go. */
    var pending by mutableStateOf<FocusRequester?>(null)

    private var arrivedAt: FocusRequester? = null

    fun landed(stop: FocusRequester) {
        arrivedAt = stop
        val back = last
        if (!inside && back != null && back !== stop) {
            inside = true
            pending = back
            return
        }
        inside = true
        last = stop
    }

    /** [pending] has gone, so where focus arrived stands. */
    fun settle() {
        last = arrivedAt
    }

    fun left() {
        inside = false
    }
}

/** A stop on this screen: reachable through [stop] and remembered by [memory]. Same chain on every recomposition. */
internal fun Modifier.downloadsStop(stop: FocusRequester, memory: DownloadsFocusMemory): Modifier =
    focusRequester(stop).onFocusChanged { if (it.hasFocus) memory.landed(stop) }

/**
 * Whether focus went there. A requester with no node attached says no, or on some versions throws, and "nothing to focus
 * yet" is not an error here: a caller with somewhere else to try needs the answer (measured: `false`, on `Television_720p`).
 */
internal fun FocusRequester.requestFocusIfAttached(): Boolean =
    try {
        requestFocus()
    } catch (_: IllegalStateException) {
        false
    }

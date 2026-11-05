package com.lagradost.cloudstream4.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.feature.detail.StreamQueue

/**
 * What the player screen decides when a stream fails or someone picks another: which link, and what the list and the
 * notice say about it. Separate from the screen so a test can drive it with no media host; [P] is a started player.
 *
 * @param first the link the screen opened on, which counts as tried.
 */
internal class SourceSwitcher(first: StreamLink) {
    private val queue = StreamQueue(first)

    /** Failed since the screen opened. Still choosable: the list offers each one again. */
    val failed = mutableStateListOf<StreamLink>()

    /** The link the screen is on now, for a check to read. */
    var current by mutableStateOf(first)
        private set

    /** The last time the screen moved on by itself, for the notice. */
    var fellThrough by mutableStateOf<FallThrough?>(null)
        private set

    fun onFailed(link: StreamLink) {
        if (failed.none { it.isSameStream(link) }) failed += link
    }

    /**
     * Someone chose [link]. @return its new player, or null when one could not be started, and then it is failed.
     */
    fun <P : Any> choose(link: StreamLink, open: () -> P?): P? {
        queue.chosen(link)
        val player = open()
        if (player == null) {
            onFailed(link)
        } else {
            failed.removeAll { it.isSameStream(link) }
            current = link
        }
        return player
    }

    /**
     * [from] has failed: the best of [found] not yet tried, started, skipping any whose player would not start.
     * @return null once every link found so far has been tried, which is where the screen shows the failure.
     */
    fun <P : Any> fallThrough(from: StreamLink, found: List<StreamLink>, open: () -> P?): Pair<StreamLink, P>? {
        while (true) {
            val next = queue.next(found) ?: return null
            val player = choose(next, open) ?: continue
            fellThrough = FallThrough(from, (fellThrough?.serial ?: 0) + 1)
            return next to player
        }
    }
}

/**
 * Whether a stream has failed, so the screen moves on: its host would not take it ([started] false), libVLC gave up
 * on it, or it ended without ever showing a frame. One that ends after showing frames has finished, and the screen
 * closes, as it always has.
 */
internal fun streamFailed(started: Boolean, status: PlayerStatus, framed: Boolean): Boolean =
    !started || status.failed || (status.finished && !framed)

/** The screen moved on from [from] by itself. [serial] tells two in a row apart, so each one is said. */
internal data class FallThrough(val from: StreamLink, val serial: Int)

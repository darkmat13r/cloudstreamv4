package com.lagradost.cloudstream4.player

import kotlin.math.abs

/**
 * The stream's position right now, for what has to be right to the frame: which cue is on screen.
 *
 * libVLC reports time about four times a second (measured, `KMP-25`), so between two reports the position
 * read from [PlayerStatus] is up to 250 ms old, and a cue would appear that late. This runs the position
 * forward from the last report at wall-clock speed, and the next report replaces the estimate outright.
 *
 * - **Pause** freezes the estimate where it was, rather than falling back to the older report.
 * - **A stall** cannot run it away: it extrapolates at most [MAX_EXTRAPOLATION_MILLIS] past a report.
 * - **A seek** the person made moves it at once, and it then ignores reports that are still about the old
 *   position, which a pipe can have in flight. It takes the first one that is not, because libVLC can land
 *   on a keyframe seconds away from what was asked for, or after [SEEK_PATIENCE_MILLIS] whatever it says.
 *
 * Read from the composition and written from a collector, hence the lock.
 *
 * @param nowNanos replaceable, so a test does not have to wait.
 */
internal class PlaybackClock(private val nowNanos: () -> Long = System::nanoTime) {
    private val lock = Any()
    private var anchorPosition = 0L
    private var anchorNanos = nowNanos()
    private var running = false
    private var lastReport = -1L
    private var length = 0L
    private var seekFrom: Long? = null
    private var seekNanos = 0L

    fun onStatus(status: PlayerStatus) = synchronized(lock) {
        val now = nowNanos()
        val estimate = estimateAt(now)
        val shouldRun = status.playing && !status.buffering
        length = status.lengthMillis

        val from = seekFrom
        val stale = from != null && abs(status.positionMillis - from) < STALE_TOLERANCE_MILLIS &&
            (now - seekNanos) / NANOS_PER_MILLI < SEEK_PATIENCE_MILLIS
        when {
            stale -> Unit
            from != null || status.positionMillis != lastReport -> {
                seekFrom = null
                lastReport = status.positionMillis
                anchor(status.positionMillis, now)
            }
            shouldRun != running -> anchor(estimate, now)
        }
        running = shouldRun
    }

    /** A seek the person just asked for, to [targetMillis]. */
    fun onSeek(targetMillis: Long) = synchronized(lock) {
        val now = nowNanos()
        seekFrom = estimateAt(now)
        seekNanos = now
        anchor(targetMillis.coerceIn(0, if (length > 0) length else Long.MAX_VALUE), now)
    }

    fun onSeekBy(deltaMillis: Long) = onSeek(positionMillis() + deltaMillis)

    fun onSeekTo(fraction: Float) {
        val known = synchronized(lock) { length }
        if (known > 0) onSeek((known * fraction.coerceIn(0f, 1f)).toLong())
    }

    fun positionMillis(): Long = synchronized(lock) { estimateAt(nowNanos()) }

    private fun anchor(position: Long, now: Long) {
        anchorPosition = position
        anchorNanos = now
    }

    private fun estimateAt(now: Long): Long {
        if (!running || seekFrom != null) return anchorPosition
        val elapsed = ((now - anchorNanos) / NANOS_PER_MILLI).coerceIn(0, MAX_EXTRAPOLATION_MILLIS)
        val estimate = anchorPosition + elapsed
        return if (length > 0) estimate.coerceAtMost(length) else estimate
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val MAX_EXTRAPOLATION_MILLIS = 1_000L
        const val STALE_TOLERANCE_MILLIS = 1_000L
        const val SEEK_PATIENCE_MILLIS = 3_000L
    }
}

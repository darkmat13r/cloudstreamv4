package com.lagradost.cloudstream4.player

/**
 * Starts a stream somewhere other than its beginning, and keeps what it reports on the way there out of the record.
 *
 * The seek waits for a reported length, because [VlcPlayer.seekTo] takes a fraction, and it happens once, because a
 * second one would fight the person's own seeking. Until the stream has reached about [startAtMillis] its position is
 * not where this person is: saving it would lose their place, and a further switch of link has to start from
 * [startAtMillis] rather than from a stream that never got going.
 */
internal class ResumeSeek(private val startAtMillis: Long) {
    private var sought = startAtMillis <= 0L
    private var arrived = startAtMillis <= 0L

    /** @return whether [status] says where the person is, and so is worth reporting. */
    fun onStatus(status: PlayerStatus, seekTo: (Float) -> Unit): Boolean {
        if (!sought && status.lengthMillis > 0L) {
            sought = true
            seekTo((startAtMillis.toFloat() / status.lengthMillis).coerceIn(0f, 1f))
        }
        if (sought && status.positionMillis >= startAtMillis - TOLERANCE_MILLIS) arrived = true
        return arrived
    }

    /** Where the next link should start, if the person changes link now. */
    fun resumePoint(positionMillis: Long): Long = if (arrived) positionMillis else startAtMillis

    private companion object {
        /** A seek lands on a keyframe, which can be well short of what was asked for. */
        const val TOLERANCE_MILLIS = 30_000L
    }
}

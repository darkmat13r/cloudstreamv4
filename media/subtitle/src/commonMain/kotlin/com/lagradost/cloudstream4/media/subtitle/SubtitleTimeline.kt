package com.lagradost.cloudstream4.media.subtitle

import com.lagradost.cloudstream4.media.model.SubtitleCue

/**
 * The user's subtitle delay. **Positive means the subtitles appear later**, which is what the
 * delay dialog shows.
 *
 * `:app` holds this under the opposite sign: `FullScreenPlayer` calls `setSubtitleOffset(-value)`
 * and `CustomDecoder` then subtracts it from each start time. So `CustomDecoder.subtitleOffset` is
 * `-delayMs`. Only [delayMs]'s sign exists here.
 *
 * All arithmetic saturates. A delay comes from a text field through `toLongOrNull`, and a position
 * can be Media3's `C.TIME_UNSET` (close to `Long.MIN_VALUE`), so wrapping is reachable: it would
 * turn "unknown position" into a far-future time.
 */
object SubtitleDelay {

    /** A delay past this cannot line up with anything [SubtitleParser] accepts. */
    const val MAX_DELAY_MS: Long = SubtitleParser.MAX_TIMESTAMP_MS

    /** The time in the cue file that is on screen at [positionMs]. */
    fun cueTimeAt(positionMs: Long, delayMs: Long): Long =
        saturatingAdd(positionMs, -clamp(delayMs))

    /** The delay that puts [cue] on screen at [positionMs]: the "tap the line being spoken" action. */
    fun delayToSync(cue: SubtitleCue, positionMs: Long): Long =
        // maxOf: `-Long.MIN_VALUE` is still `Long.MIN_VALUE`, the one value with no negation.
        clamp(saturatingAdd(positionMs, -maxOf(cue.startTimeMs, -Long.MAX_VALUE)))

    /**
     * [cue] as it appears under [delayMs], for an engine that takes shifted times. The start can go
     * negative and is kept, so applying the opposite delay restores it exactly.
     *
     * Keep the undelayed cues too. `CustomDecoder` does, because a sync list has to show file times.
     */
    fun delayed(cue: SubtitleCue, delayMs: Long): SubtitleCue {
        val start = saturatingAdd(cue.startTimeMs, clamp(delayMs))
        return cue.copy(startTimeMs = minOf(start, Long.MAX_VALUE - cue.durationMs))
    }

    private fun clamp(delayMs: Long): Long = delayMs.coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS)

    private fun saturatingAdd(a: Long, b: Long): Long {
        val sum = a + b
        // Overflow only if both operands have the same sign and the sum's differs.
        val overflowed = ((a xor sum) and (b xor sum)) < 0
        return when {
            !overflowed -> sum
            a < 0 -> Long.MIN_VALUE
            else -> Long.MAX_VALUE
        }
    }
}

/**
 * Cues sorted by start time, for asking "what is on screen now" several times a second.
 *
 * Holds file times and takes the delay at lookup, so changing the delay costs nothing and a sync
 * list keeps showing file times. Cue files can be out of order and can overlap, so neither is assumed.
 */
class SubtitleTimeline(cues: List<SubtitleCue>) {

    /** Sorted by start; equal starts keep file order. */
    val cues: List<SubtitleCue> = cues.sortedBy { it.startTimeMs }

    /** `latestEnd[i]` is the latest end among cues 0..i, so a backward walk knows when to stop. */
    private val latestEnd = LongArray(this.cues.size).also { ends ->
        var latest = Long.MIN_VALUE
        this.cues.forEachIndexed { index, cue ->
            latest = maxOf(latest, cue.endTimeMs)
            ends[index] = latest
        }
    }

    /**
     * The cues on screen at [positionMs], earliest start first, at most [maxResults] of them,
     * preferring the latest started. A cue covers `[start, end)`, as `SubtitleOffsetItemAdapter`
     * draws it, so a zero-length cue is never active.
     *
     * [maxResults] is there because a hostile file can make every cue overlap, and no screen can
     * show thousands of lines.
     */
    fun activeAt(positionMs: Long, delayMs: Long = 0, maxResults: Int = DEFAULT_MAX_ACTIVE): List<SubtitleCue> {
        val time = SubtitleDelay.cueTimeAt(positionMs, delayMs)
        val active = ArrayList<SubtitleCue>(2)
        var i = lastStartedIndex(time)
        while (i >= 0 && latestEnd[i] > time && active.size < maxResults) {
            if (cues[i].endTimeMs > time) active += cues[i]
            i--
        }
        active.reverse()
        return active
    }

    /**
     * The index in [cues] of the last cue started by [positionMs], whether or not it has ended, or
     * `null` if none has. This is where a sync list scrolls to (`getLatestActiveItem`).
     */
    fun latestStartedIndex(positionMs: Long, delayMs: Long = 0): Int? =
        lastStartedIndex(SubtitleDelay.cueTimeAt(positionMs, delayMs)).takeIf { it >= 0 }

    private fun lastStartedIndex(time: Long): Int {
        var low = 0
        var high = cues.size - 1
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (cues[middle].startTimeMs <= time) {
                found = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return found
    }

    private companion object {
        const val DEFAULT_MAX_ACTIVE = 8
    }
}

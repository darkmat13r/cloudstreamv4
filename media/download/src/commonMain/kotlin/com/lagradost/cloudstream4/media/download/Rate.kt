package com.lagradost.cloudstream4.media.download

/**
 * Bytes per second over a sliding window, fed with the running total at each progress tick.
 * Reading the rate from totals that come from the part files means a stale counter cannot inflate it.
 * Not thread-safe: one ticker owns it.
 */
internal class RateMeter(private val windowMillis: Long = 5_000L) {
    private val times = ArrayDeque<Long>()
    private val totals = ArrayDeque<Long>()

    fun sample(nowMillis: Long, totalBytes: Long) {
        times.addLast(nowMillis)
        totals.addLast(totalBytes)
        while (times.size > 2 && times[1] <= nowMillis - windowMillis) {
            times.removeFirst()
            totals.removeFirst()
        }
    }

    fun bytesPerSecond(): Long {
        if (times.size < 2) return 0L
        val elapsed = times.last() - times.first()
        val gained = totals.last() - totals.first()
        if (elapsed <= 0L || gained <= 0L) return 0L
        return gained * 1000L / elapsed
    }
}

/** Whole seconds to go at [bytesPerSecond], or `null` when the total or the rate is unknown. */
internal fun etaSeconds(remaining: Long?, bytesPerSecond: Long): Long? {
    if (remaining == null || bytesPerSecond <= 0L) return null
    if (remaining <= 0L) return 0L
    return (remaining + bytesPerSecond - 1) / bytesPerSecond
}

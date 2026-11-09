package com.lagradost.cloudstream4.feature.account

/**
 * `BUG-55`'s limit on wrong PINs, for a host that is not Android (`KMP-95c`, the Mac). The numbers and the rule are
 * `PinAttempts`' in `:app`, which this does not replace:
 *
 * - The first [FREE_WRONG] - 1 wrong PINs cost nothing. The [FREE_WRONG]th starts a [FIRST_WAIT_MS] wait, and each wrong
 *   PIN after it doubles the wait, up to [MAX_WAIT_MS], reached on the 12th.
 * - A right PIN clears the profile's count.
 * - While a wait runs, no PIN is compared: the prompt's cells are off (A4, A4b).
 * - **The clock counts from boot** and ignores the wall clock, so setting the time forward does not end a wait. With
 *   each wait the clock reading it started at is kept. A wait ends at that reading plus the wait, or, when the clock now
 *   reads below it (the machine rebooted), at the full wait counted from boot. Either way at least the wait has truly
 *   passed: a reboot never shortens one.
 * - Only counts and waits are kept, never a PIN or anything taken from one.
 *
 * Not thread-safe: the window calls it from its one UI thread. This process's copy is read first, so a store that
 * cannot write never lifts the limit.
 *
 * @param clock milliseconds since boot. The Mac's is `System.nanoTime()`, measured to count from boot there.
 */
class PinLimit(private val store: Store, private val clock: () -> Long) {

    /** One profile's record: [wrong] PINs so far, and a wait of [waitMs] that started at clock reading [startedAt]. */
    data class Record(val wrong: Int, val startedAt: Long, val waitMs: Long)

    /** Where records live across restarts. */
    interface Store {
        fun read(key: Int): Record?

        /** Writes [record], or removes [key]'s when null. */
        fun write(key: Int, record: Record?)
    }

    private val held = HashMap<Int, Record>()

    private fun record(key: Int): Record? = held[key] ?: store.read(key)?.also { held[key] = it }

    /** How long [key]'s prompt must still wait before a PIN is compared, in ms; 0 when it may compare now. */
    fun waitMs(key: Int): Long {
        val record = record(key)?.takeIf { it.waitMs > 0L } ?: return 0L
        val now = clock()
        // The clock below the start: the machine rebooted, and the whole wait runs again from boot.
        val end = if (now < record.startedAt) record.waitMs else record.startedAt + record.waitMs
        return (end - now).coerceAtLeast(0L)
    }

    /** Records a wrong PIN for [key] and returns the wait it starts, in ms; 0 while it is still free. */
    fun wrong(key: Int): Long {
        val count = (record(key)?.wrong ?: 0) + 1
        val wait = if (count < FREE_WRONG) 0L
        else (FIRST_WAIT_MS shl (count - FREE_WRONG).coerceAtMost(MAX_DOUBLINGS)).coerceAtMost(MAX_WAIT_MS)
        val next = Record(count, clock(), wait)
        held[key] = next
        runCatching { store.write(key, next) }
        return wait
    }

    /** A right PIN: [key] starts again from nothing. */
    fun right(key: Int) {
        held.remove(key)
        runCatching { store.write(key, null) }
    }

    /** [key]'s profile was deleted: its count goes with it. */
    fun forget(key: Int) = right(key)

    companion object {
        const val FREE_WRONG = 5
        const val FIRST_WAIT_MS = 30_000L

        /** The longest wait: one hour, reached on the 12th wrong PIN (30 s doubled seven times would be 64 minutes). */
        const val MAX_WAIT_MS = 3_600_000L

        /** Keeps the shift from overflowing before the cap applies. */
        private const val MAX_DOUBLINGS = 20
    }
}

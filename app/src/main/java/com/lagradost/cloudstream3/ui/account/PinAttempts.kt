package com.lagradost.cloudstream3.ui.account

import android.content.Context
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context

/**
 * `BUG-55`: how many wrong PINs each profile has had, and how long its prompt must wait. A 4-digit PIN has 10,000
 * values, so without a limit any lock can be guessed.
 *
 * - The first [FREE_WRONG] - 1 wrong PINs cost nothing. The [FREE_WRONG]th starts a [FIRST_WAIT_MS] wait, and each
 *   wrong PIN after it doubles the wait, up to [MAX_WAIT_MS]. The cap keeps someone at the remote from locking the
 *   owner out for good; at one guess an hour, the 10,000 PINs still take over a year, about 200 days on average.
 * - A right PIN clears the profile's count.
 * - **Kept across restarts** in [FILE], so restarting the app after each few guesses doesn't reset the limit. Only the
 *   count and the wait are stored, never a PIN or anything taken from one. [FILE] is excluded from Android's backup and
 *   device transfer, like `CredentialStore`'s file, so a wait never follows a profile to another device.
 * - **Clock:** [SystemClock.elapsedRealtime], which counts from boot and ignores the wall clock, so setting the
 *   device's time forward does not end a wait. With each wait the uptime it started at is stored. A wait ends at that
 *   uptime plus the wait, or, when the uptime is now below it (the device rebooted), at the full wait counted from boot.
 *   Either way, at least the wait has truly passed: a reboot never shortens a wait.
 *
 * Keyed by profile index. A prompt opened without one (the published `showPinInputDialog`, which an extension could
 * call) counts under [UNKEYED], so it is limited too.
 */
internal object PinAttempts {
    const val FREE_WRONG = 5
    const val FIRST_WAIT_MS = 30_000L

    /** The longest wait: one hour, reached on the 12th wrong PIN (30 s doubled seven times would be 64 minutes). */
    const val MAX_WAIT_MS = 3_600_000L

    /** The key for a prompt that names no profile. No profile index is negative. */
    const val UNKEYED = Int.MIN_VALUE

    /** The preferences file; the backup rules exclude `$FILE.xml`. */
    const val FILE = "cs_pin_attempts"

    /** Keeps the shift from overflowing before the cap applies. */
    private const val MAX_DOUBLINGS = 20

    /** One profile's record: [wrong] PINs so far, and a wait of [waitMs] that started at uptime [startedAt]. */
    data class Record(val wrong: Int, val startedAt: Long, val waitMs: Long)

    /** Where records live. The app's is [FILE]; a test hands in its own. */
    interface Store {
        fun read(key: Int): Record?

        /** Writes [record], or removes [key]'s when null. */
        fun write(key: Int, record: Record?)
    }

    private object PrefsStore : Store {
        private fun prefs() = context?.getSharedPreferences(FILE, Context.MODE_PRIVATE)

        override fun read(key: Int): Record? {
            val prefs = prefs() ?: return null
            if (!prefs.contains("$key/wrong")) return null
            return Record(prefs.getInt("$key/wrong", 0), prefs.getLong("$key/at", 0L), prefs.getLong("$key/wait", 0L))
        }

        override fun write(key: Int, record: Record?) {
            prefs()?.edit(commit = true) {
                if (record == null) {
                    remove("$key/wrong"); remove("$key/at"); remove("$key/wait")
                } else {
                    putInt("$key/wrong", record.wrong); putLong("$key/at", record.startedAt); putLong("$key/wait", record.waitMs)
                }
            }
        }
    }

    @VisibleForTesting
    var store: Store = PrefsStore

    @VisibleForTesting
    var clock: () -> Long = { SystemClock.elapsedRealtime() }

    /**
     * This process's copy, read first. It also keeps the limit if [store] has nowhere to write (no context, or a failed
     * commit), so a write failure never lifts it.
     */
    private val held = HashMap<Int, Record>()

    private fun record(key: Int): Record? = held[key] ?: store.read(key)?.also { held[key] = it }

    /** How long [key]'s prompt must still wait before a PIN is compared, in ms; 0 when it may compare now. */
    @Synchronized
    fun waitMs(key: Int): Long {
        val record = record(key)?.takeIf { it.waitMs > 0L } ?: return 0L
        val now = clock()
        // Uptime below the start: the device rebooted, and the whole wait runs again from boot.
        val end = if (now < record.startedAt) record.waitMs else record.startedAt + record.waitMs
        return (end - now).coerceAtLeast(0L)
    }

    /** Records a wrong PIN for [key] and returns the wait it starts, in ms; 0 while it is still free. */
    @Synchronized
    fun wrong(key: Int): Long {
        val count = (record(key)?.wrong ?: 0) + 1
        val wait = if (count < FREE_WRONG) 0L
        else (FIRST_WAIT_MS shl (count - FREE_WRONG).coerceAtMost(MAX_DOUBLINGS)).coerceAtMost(MAX_WAIT_MS)
        val next = Record(count, clock(), wait)
        held[key] = next
        store.write(key, next)
        return wait
    }

    /** A right PIN: [key] starts again from nothing. */
    @Synchronized
    fun right(key: Int) {
        held.remove(key)
        store.write(key, null)
    }

    /** Drops this process's copy, as a restart would; [store] keeps its records. */
    @VisibleForTesting
    @Synchronized
    fun forgetHeld() = held.clear()
}

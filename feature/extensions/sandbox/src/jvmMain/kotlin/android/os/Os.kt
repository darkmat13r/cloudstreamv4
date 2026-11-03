package android.os

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Enough of `android.os` that a plugin's `load()` runs. Nothing here reaches the OS: no real device
 * identifiers, and nothing to read. The fields a plugin could fingerprint with are not provided.
 */
object Build {
    object VERSION {
        /** Android 14. A plugin that branches on the SDK takes its modern path, which is the tested one. */
        @JvmField val SDK_INT: Int = 34
    }
}

/**
 * One daemon thread stands in for the main thread, so tasks run in order and never concurrently, as a
 * plugin posting to `Looper.getMainLooper()` assumes. It holds no UI: there is no event loop to
 * attach to, and a plugin that touches a view from here gets `NoSuchMethodError`.
 */
class Looper private constructor() {
    companion object {
        private val MAIN = Looper()

        @JvmStatic fun getMainLooper(): Looper = MAIN

        /** Null off the main thread, so a plugin's "am I on main?" check is answered truthfully. */
        @JvmStatic fun myLooper(): Looper? = if (Thread.currentThread() === mainThread) MAIN else null

        @Volatile private var mainThread: Thread? = null
        internal val executor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "plugin-main").apply {
                isDaemon = true
                mainThread = this
            }
        }
    }
}

/** Runs on the stand-in main thread. Delays are honoured; `removeCallbacks` cancels what has not run. */
open class Handler(private val looper: Looper) {
    private val pending = ConcurrentHashMap<Runnable, Future<*>>()

    constructor() : this(Looper.getMainLooper())

    fun getLooper(): Looper = looper

    fun post(task: Runnable): Boolean = postDelayed(task, 0)

    fun postDelayed(task: Runnable, delayMillis: Long): Boolean {
        pending[task] = Looper.executor.schedule({
            pending.remove(task)
            // A plugin's exception must not end the one thread every Handler shares.
            runCatching { task.run() }.onFailure { System.err.println("WARN Handler: $it") }
        }, delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        return true
    }

    fun removeCallbacks(task: Runnable) {
        pending.remove(task)?.cancel(false)
    }

    fun removeCallbacksAndMessages(@Suppress("UNUSED_PARAMETER") token: Any?) {
        pending.keys.toList().forEach(::removeCallbacks)
    }
}

object SystemClock {
    @JvmStatic fun uptimeMillis(): Long = System.nanoTime() / NANOS_PER_MILLI
    @JvmStatic fun elapsedRealtime(): Long = uptimeMillis()
    private const val NANOS_PER_MILLI = 1_000_000L
}

/** Key-value holder. A plugin stores settings dialog arguments in it; it goes nowhere. */
open class Bundle {
    private val values = HashMap<String?, Any?>()

    fun putString(key: String?, value: String?) { values[key] = value }
    fun putInt(key: String?, value: Int) { values[key] = value }
    fun putDouble(key: String?, value: Double) { values[key] = value }
    fun putBoolean(key: String?, value: Boolean) { values[key] = value }
    fun getString(key: String?): String? = values[key] as? String
    fun getString(key: String?, defaultValue: String?): String? = values[key] as? String ?: defaultValue
    fun getInt(key: String?, defaultValue: Int): Int = values[key] as? Int ?: defaultValue
    fun getDouble(key: String?, defaultValue: Double): Double = values[key] as? Double ?: defaultValue
    fun getBoolean(key: String?, defaultValue: Boolean): Boolean = values[key] as? Boolean ?: defaultValue
    fun containsKey(key: String?): Boolean = values.containsKey(key)
}

package android.util

/** `android.util.Log`, to stderr, which the app reads as this host's diagnostics. */
object Log {
    @JvmStatic fun v(tag: String?, msg: String?): Int = write("VERBOSE", tag, msg, null)
    @JvmStatic fun d(tag: String?, msg: String?): Int = write("DEBUG", tag, msg, null)
    @JvmStatic fun i(tag: String?, msg: String?): Int = write("INFO", tag, msg, null)
    @JvmStatic fun w(tag: String?, msg: String?): Int = write("WARN", tag, msg, null)
    @JvmStatic fun w(tag: String?, msg: String?, tr: Throwable?): Int = write("WARN", tag, msg, tr)
    @JvmStatic fun e(tag: String?, msg: String?): Int = write("ERROR", tag, msg, null)
    @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?): Int = write("ERROR", tag, msg, tr)
    @JvmStatic fun getStackTraceString(tr: Throwable?): String = tr?.stackTraceToString().orEmpty()

    private fun write(level: String, tag: String?, msg: String?, tr: Throwable?): Int {
        System.err.println("$level $tag: $msg${tr?.let { " ($it)" }.orEmpty()}")
        return 0
    }
}

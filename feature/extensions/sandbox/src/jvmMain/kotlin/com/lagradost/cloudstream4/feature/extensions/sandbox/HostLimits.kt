package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.io.File
import java.nio.file.Files
import java.nio.file.FileVisitOption
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * What a plugin host may use before it is stopped.
 *
 * Seatbelt says what a host may touch and nothing about how much, and macOS has no working per-process memory limit
 * to set (`RLIMIT_AS` is not enforced). `-Xmx` caps the Java heap and not threads or native memory, so what is
 * left is to watch from outside and stop a host that is past these. A stopped host fails its calls and the app goes on.
 *
 * @param maxResidentBytes all of its memory. Well above the 512 MB heap, for the JVM's own and for thread stacks.
 * @param maxDiskBytes everything in its directory. `ulimit -f` caps one file and not how many there are.
 * @param busyShare,busyMillis this share of one core, for this long, with no call waiting on the answer. A plugin
 *   working for a call may use what it needs.
 */
class HostLimits(
    val maxResidentBytes: Long = 1536L * MEGABYTE,
    val maxDiskBytes: Long = 2048L * MEGABYTE,
    val busyShare: Double = 0.9,
    val busyMillis: Long = 60_000L,
    val periodMillis: Long = 5_000L,
) {
    private companion object {
        const val MEGABYTE = 1024L * 1024
    }
}

/** One look at a host, and what has been true since the last. Pure, so the arithmetic can be tested without one. */
internal class HostWatch(private val limits: HostLimits) {
    private var lastCpuMillis: Long? = null
    private var busySinceMillis = 0L

    /** @return why it should be stopped, or null. */
    fun look(nowMillis: Long, cpuMillis: Long?, residentBytes: Long?, diskBytes: Long?, callWaiting: Boolean): String? {
        if (residentBytes != null && residentBytes > limits.maxResidentBytes) return "used too much memory"
        if (diskBytes != null && diskBytes > limits.maxDiskBytes) return "wrote too much to disk"
        val previous = lastCpuMillis
        lastCpuMillis = cpuMillis
        val busy = cpuMillis != null && previous != null &&
            (cpuMillis - previous).toDouble() / limits.periodMillis >= limits.busyShare && !callWaiting
        busySinceMillis = if (busy) busySinceMillis.takeIf { it != 0L } ?: nowMillis else 0L
        return if (busy && nowMillis - busySinceMillis >= limits.busyMillis) "kept working with nothing asked of it" else null
    }
}

/** Watches [process] until it ends. Runs outside the sandbox, on a daemon thread of its own. */
internal fun watchHost(process: Process, data: File, limits: HostLimits, callWaiting: () -> Boolean, stop: (String) -> Unit) {
    val watch = HostWatch(limits)
    thread(isDaemon = true, name = "host-watch") {
        while (process.isAlive) {
            Thread.sleep(limits.periodMillis)
            if (!process.isAlive) break
            val used = usage(process)
            val reason = watch.look(System.currentTimeMillis(), used?.second, used?.first, diskBytes(data), callWaiting())
            if (reason != null) {
                stop(reason)
                break
            }
        }
    }
}

/**
 * From `ps`, for memory and for CPU: with `ProcessHandle.info().totalCpuDuration` a host spinning 8 threads never passed
 * the limit on this Apple silicon Mac, and with this it did, in 4.9 s against a 4 s window. Why was not established.
 */
private fun usage(process: Process): Pair<Long, Long>? = runCatching {
    val ps = ProcessBuilder("/bin/ps", "-o", "rss=,time=", "-p", process.pid().toString()).redirectErrorStream(true).start()
    val fields = ps.inputStream.bufferedReader().readText().trim().split(Regex("\\s+"))
    ps.waitFor(PS_WAIT_SECONDS, TimeUnit.SECONDS)
    fields[0].toLong() * KILOBYTE to parseCpuTime(fields[1])
}.getOrNull()

/** `ps`'s `[HH:]MM:SS.ss` or `DD-HH:MM:SS`, in milliseconds. */
internal fun parseCpuTime(text: String): Long {
    val days = text.substringBefore('-', "0").toLong()
    val seconds = text.substringAfter('-').split(':').fold(0.0) { total, part -> total * SECONDS_PER_MINUTE + part.toDouble() }
    return days * MILLIS_PER_DAY + (seconds * MILLIS_PER_SECOND).toLong()
}

/** Does not follow a link, which a plugin can plant, and stops counting once it has seen a great many files. */
private fun diskBytes(data: File): Long? = runCatching {
    Files.walk(data.toPath()).use { files ->
        files.limit(MAX_FILES_COUNTED).filter { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }
            .mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }.sum()
    }
}.getOrNull()

private const val PS_WAIT_SECONDS = 5L
private const val KILOBYTE = 1024L
private const val SECONDS_PER_MINUTE = 60.0
private const val MILLIS_PER_SECOND = 1000.0
private const val MILLIS_PER_DAY = 86_400_000L
private const val MAX_FILES_COUNTED = 200_000L

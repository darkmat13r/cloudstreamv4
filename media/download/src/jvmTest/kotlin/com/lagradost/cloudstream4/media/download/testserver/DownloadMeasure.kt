package com.lagradost.cloudstream4.media.download.testserver

import com.lagradost.cloudstream4.core.http.HttpClientFactory
import com.lagradost.cloudstream4.media.download.DownloadOptions
import com.lagradost.cloudstream4.media.download.DownloadRequest
import com.lagradost.cloudstream4.media.download.SegmentedDownloader
import com.lagradost.cloudstream4.media.model.DownloadState
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.concurrent.thread

/**
 * Not a test. Measures the engine against two JVM-only prototypes on a local server, then prints medians.
 *
 * Arguments: `[sizeMiB=256] [runs=3] [throttleMiBps=4]`. Servers: one that caps every connection at
 * `throttleMiBps`, one that does not. For each, parts = 1, 4, 8, 16:
 *  - engine: `SegmentedDownloader` (part files, then a join)
 *  - A: one preallocated `RandomAccessFile`, every part written at its offset, no join
 *  - B: part files, then a sequential `transferTo` join (the engine's design, without the engine)
 *
 * Caveats printed with the results: the server runs in this JVM and shares the CPU; "peak heap" is the
 * highest `MemoryMXBean` heap use sampled every 10 ms above a post-GC baseline, so it includes garbage
 * not yet collected; the engine's "join+verify share" is the time between the first snapshot showing
 * every byte and the COMPLETED snapshot, so it includes the final size check and the move and is
 * quantised by the snapshot interval (50 ms here).
 */
fun main(args: Array<String>) {
    val sizeMiB = args.getOrNull(0)?.toLong() ?: 256L
    val runs = args.getOrNull(1)?.toInt() ?: 3
    val throttleMiBps = args.getOrNull(2)?.toLong() ?: 4L
    val size = sizeMiB * MIB
    SIZE_MIB_FOR_RATE = sizeMiB.toDouble()
    val root = Files.createTempDirectory("csdl-measure").toFile()
    val server = TestServer()
    server.add("/big", Content(seed = 42, size = size))
    val okHttp = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).build()
    val client = HttpClientFactory(okHttp).create()
    val heap = ManagementFactory.getMemoryMXBean()
    println("size=${sizeMiB}MiB runs=$runs java=${System.getProperty("java.version")} maxHeap=${Runtime.getRuntime().maxMemory() / MIB}MiB")
    println("tmp=${root.path} (the disk under this path is what is measured)")

    for (bps in listOf(throttleMiBps * MIB, 0L)) {
        server.throttle = { bps }
        val label = if (bps == 0L) "unthrottled" else "${bps / MIB} MiB/s per connection"
        println("\n== server: $label ==")
        println("%-10s %5s | %9s %9s %9s %7s %6s".format("approach", "parts", "wall s", "MiB/s", "heap MiB", "join s", "join%"))
        for (parts in listOf(1, 4, 8, 16)) {
            report("engine", parts, (1..runs).map { engineRun(client, server, root, parts, size, heap) })
            report("A:rafile", parts, (1..runs).map { rafRun(okHttp, server, root, parts, size, heap) })
            if (parts > 1) report("B:join", parts, (1..runs).map { joinRun(okHttp, server, root, parts, size, heap) })
        }
    }
    client.close()
    server.close()
    root.deleteRecursively()
}

private class Sample(val wallSeconds: Double, val peakHeapBytes: Long, val joinSeconds: Double?, val maxParts: Int = 0)

private fun report(name: String, parts: Int, samples: List<Sample>) {
    fun median(values: List<Double>): Double = values.sorted()[values.size / 2]
    val wall = median(samples.map { it.wallSeconds })
    val heapMiB = median(samples.map { it.peakHeapBytes / MIB.toDouble() })
    val joins = samples.mapNotNull { it.joinSeconds }
    val join = if (joins.isEmpty()) null else median(joins)
    val share = if (join == null) "-" else "%.1f".format(100.0 * join / wall)
    val extra = samples.maxOf { it.maxParts }.takeIf { it > 0 }?.let { "  (max part files seen: $it)" } ?: ""
    println(
        "%-10s %5d | %9.2f %9.1f %9.1f %7s %6s%s".format(
            name, parts, wall, SIZE_MIB_FOR_RATE / wall, heapMiB, join?.let { "%.2f".format(it) } ?: "-", share, extra,
        ),
    )
}

private var SIZE_MIB_FOR_RATE = 0.0

private inline fun <T> withHeapSampling(heap: java.lang.management.MemoryMXBean, block: () -> T): Pair<T, Long> {
    System.gc()
    Thread.sleep(100)
    System.gc()
    val baseline = heap.heapMemoryUsage.used
    val peak = java.util.concurrent.atomic.AtomicLong(baseline)
    val running = java.util.concurrent.atomic.AtomicBoolean(true)
    val sampler = thread(isDaemon = true) {
        while (running.get()) {
            val used = heap.heapMemoryUsage.used
            if (used > peak.get()) peak.set(used)
            Thread.sleep(10)
        }
    }
    val result = block()
    running.set(false)
    sampler.join()
    return result to (peak.get() - baseline).coerceAtLeast(0L)
}

private fun engineRun(
    client: io.ktor.client.HttpClient,
    server: TestServer,
    root: File,
    parts: Int,
    size: Long,
    heap: java.lang.management.MemoryMXBean,
): Sample {
    val dir = File(root, "engine-$parts").also { it.deleteRecursively(); it.mkdirs() }
    val request = DownloadRequest(
        url = server.url("/big"),
        folder = PlatformFile(dir),
        fileName = "big.bin",
        options = DownloadOptions(parts = parts, allowPrivateAddresses = true, progressIntervalMillis = 50, stallTimeoutMillis = 120_000),
    )
    var allBytesAt = 0L
    var completedAt = 0L
    var maxParts = 0
    val started = System.nanoTime()
    val (_, peak) = withHeapSampling(heap) {
        runBlocking {
            SegmentedDownloader(client).download(request).collect { s ->
                val now = System.nanoTime()
                maxParts = maxOf(maxParts, s.parts.size)
                if (allBytesAt == 0L && s.bytesDone >= size) allBytesAt = now
                if (s.state == DownloadState.COMPLETED) completedAt = now
                check(s.state != DownloadState.FAILED) { "engine failed: ${s.failure}" }
            }
        }
    }
    val finished = System.nanoTime()
    check(File(dir, "big.bin").length() == size) { "engine produced a file of the wrong size" }
    dir.deleteRecursively()
    val wall = (finished - started) / 1e9
    val join = if (allBytesAt != 0L && completedAt != 0L) (completedAt - allBytesAt) / 1e9 else null
    return Sample(wall, peak, join, maxParts)
}

private fun partRanges(size: Long, parts: Int): List<LongRange> {
    val each = size / parts
    return (0 until parts).map { i ->
        val start = i * each
        val end = if (i == parts - 1) size - 1 else start + each - 1
        start..end
    }
}

private fun fetchRange(okHttp: OkHttpClient, url: String, range: LongRange, sink: (ByteArray, Int, Long) -> Unit) {
    val request = Request.Builder().url(url).header("Range", "bytes=${range.first}-${range.last}").header("Accept-Encoding", "identity").build()
    okHttp.newCall(request).execute().use { response ->
        check(response.code == 206) { "expected 206, got ${response.code}" }
        val body = response.body.source()
        val buffer = ByteArray(64 * 1024)
        var offset = range.first
        while (true) {
            val n = body.read(buffer)
            if (n < 0) break
            sink(buffer, n, offset)
            offset += n
        }
        check(offset == range.last + 1) { "short body: ended at $offset, wanted ${range.last + 1}" }
    }
}

/** Prototype A: one preallocated file, each part writes at its own offset. */
private fun rafRun(
    okHttp: OkHttpClient,
    server: TestServer,
    root: File,
    parts: Int,
    size: Long,
    heap: java.lang.management.MemoryMXBean,
): Sample {
    val dir = File(root, "raf-$parts").also { it.deleteRecursively(); it.mkdirs() }
    val file = File(dir, "big.bin")
    val started = System.nanoTime()
    val (_, peak) = withHeapSampling(heap) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(size)
            val channel = raf.channel
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            partRanges(size, parts).map { range ->
                thread {
                    try {
                        fetchRange(okHttp, server.url("/big"), range) { buf, n, offset ->
                            var written = 0
                            val bb = ByteBuffer.wrap(buf, 0, n)
                            while (written < n) written += channel.write(bb, offset + written)
                        }
                    } catch (t: Throwable) {
                        failure.set(t)
                    }
                }
            }.forEach { it.join() }
            failure.get()?.let { throw it }
        }
    }
    val wall = (System.nanoTime() - started) / 1e9
    check(file.length() == size)
    dir.deleteRecursively()
    return Sample(wall, peak, null)
}

/** Prototype B: part files, then one sequential `transferTo` join into the final file. */
private fun joinRun(
    okHttp: OkHttpClient,
    server: TestServer,
    root: File,
    parts: Int,
    size: Long,
    heap: java.lang.management.MemoryMXBean,
): Sample {
    val dir = File(root, "join-$parts").also { it.deleteRecursively(); it.mkdirs() }
    val ranges = partRanges(size, parts)
    val files = ranges.indices.map { File(dir, "part-$it") }
    var joinStart = 0L
    val started = System.nanoTime()
    val (_, peak) = withHeapSampling(heap) {
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        ranges.mapIndexed { i, range ->
            thread {
                try {
                    files[i].outputStream().buffered(64 * 1024).use { out ->
                        fetchRange(okHttp, server.url("/big"), range) { buf, n, _ -> out.write(buf, 0, n) }
                    }
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }
        }.forEach { it.join() }
        failure.get()?.let { throw it }
        joinStart = System.nanoTime()
        val joined = File(dir, "big.bin")
        FileChannel.open(joined.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { out ->
            for (part in files) {
                FileChannel.open(part.toPath(), StandardOpenOption.READ).use { input ->
                    var pos = 0L
                    val length = input.size()
                    while (pos < length) pos += input.transferTo(pos, length - pos, out)
                }
                part.delete()
            }
        }
    }
    val finished = System.nanoTime()
    check(File(dir, "big.bin").length() == size)
    dir.deleteRecursively()
    return Sample((finished - started) / 1e9, peak, (finished - joinStart) / 1e9)
}

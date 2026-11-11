package com.lagradost.cloudstream4.media.download.testserver

import com.lagradost.cloudstream4.core.http.HttpClientFactory
import com.lagradost.cloudstream4.media.download.DownloadOptions
import com.lagradost.cloudstream4.media.download.DownloadRequest
import com.lagradost.cloudstream4.media.download.DownloadSnapshot
import com.lagradost.cloudstream4.media.download.DownloadStorage
import com.lagradost.cloudstream4.media.download.ExpectedHash
import com.lagradost.cloudstream4.media.download.FileKitStorage
import com.lagradost.cloudstream4.media.download.HostResolver
import com.lagradost.cloudstream4.media.download.SegmentedDownloader
import com.lagradost.cloudstream4.media.download.SystemHostResolver
import com.lagradost.cloudstream4.media.model.DownloadState
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

const val KIB: Long = 1024L
const val MIB: Long = 1024L * 1024L

/** Options that make a test fast: small parts, millisecond backoff, a short stall timeout. */
fun suiteOptions(
    parts: Int = 4,
    minPartBytes: Long = 64 * KIB,
    maxPartFiles: Int = 64,
    maxAttemptsPerPart: Int = 5,
    stallTimeoutMillis: Long = 10_000,
    allowPrivateAddresses: Boolean = true,
    rejectHtml: Boolean = true,
    maxRedirects: Int = 5,
): DownloadOptions = DownloadOptions(
    parts = parts,
    minPartBytes = minPartBytes,
    maxPartFiles = maxPartFiles,
    maxAttemptsPerPart = maxAttemptsPerPart,
    backoffBaseMillis = 5,
    backoffMaxMillis = 40,
    stallTimeoutMillis = stallTimeoutMillis,
    maxRedirects = maxRedirects,
    allowPrivateAddresses = allowPrivateAddresses,
    rejectHtml = rejectHtml,
    progressIntervalMillis = 20,
)

/** A fake public address, for a [HostResolver] that must say "this name is public". */
val PUBLIC_V4: ByteArray = byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)
val LOOPBACK_V4: ByteArray = byteArrayOf(127, 0, 0, 1)

class MapResolver(private val map: Map<String, List<ByteArray>>) : HostResolver {
    override suspend fun resolve(host: String): List<ByteArray> = map[host] ?: SystemHostResolver.resolve(host)
}

class PartOnDisk(val id: Int, val start: Long, val endExclusive: Long, val onDisk: Long) {
    val complete: Boolean get() = onDisk == endExclusive - start
}

/** One engine run: every snapshot the flow emitted, and every size the final file was ever seen at. */
class Run(
    val snapshots: List<DownloadSnapshot>,
    /** file name -> sizes observed while polling the destination folder. */
    val observedSizes: Map<String, Set<Long>>,
) {
    val last: DownloadSnapshot get() = snapshots.last()
    val maxParts: Int get() = snapshots.maxOf { it.parts.size }
    override fun toString(): String = "Run(last=${last.state}/${last.failure}, snapshots=${snapshots.size})"
}

/**
 * One temp directory, one [TestServer] and one client. The destination folder is `<outer>/dest`, so a
 * test can see whether anything was written beside it.
 */
class Harness(dns: Map<String, String> = emptyMap()) : AutoCloseable {
    val outer: File = Files.createTempDirectory("csdl-suite").toFile()
    val folderFile: File = File(outer, "dest").also { it.mkdirs() }
    val folder: PlatformFile = PlatformFile(folderFile)
    val server = TestServer()

    private val inFlight = AtomicInteger()
    private val peakCalls = AtomicInteger()

    /**
     * The most calls the engine had open at once, counted in its own client (`BUG-102`): a call starts when it is made
     * and ends when its body is closed or it fails. The server's [TestServer.peakConcurrency] lags this by design, since
     * a handler frees its slot only after its last write, which the client may already have read.
     */
    val peakConcurrentCalls: Int get() = peakCalls.get()

    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .eventListener(object : EventListener() {
            override fun callStart(call: Call) {
                peakCalls.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            }

            override fun callEnd(call: Call) {
                inFlight.decrementAndGet()
            }

            override fun callFailed(call: Call, ioe: IOException) {
                inFlight.decrementAndGet()
            }
        })
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                dns[hostname]?.let { listOf(InetAddress.getByName(it)) } ?: Dns.SYSTEM.lookup(hostname)
        })
        .build()

    val client: HttpClient = HttpClientFactory(okHttp).create()

    fun downloader(
        storage: DownloadStorage = FileKitStorage(),
        resolver: HostResolver = SystemHostResolver,
    ): SegmentedDownloader = SegmentedDownloader(client, storage, resolver)

    /** A file at [path] with [size] bytes of `seed`'s stream, ETag `"v<seed>"`. */
    fun resource(path: String = "/f", size: Long, seed: Long = 7L, etag: String? = "\"v$seed\""): Resource =
        server.add(path, Content(seed, size, etag))

    fun request(
        path: String = "/f",
        name: String = "out.bin",
        options: DownloadOptions = suiteOptions(),
        hash: ExpectedHash? = null,
        overwrite: Boolean = false,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        url: String = server.url(path),
    ) = DownloadRequest(url, folder, name, headers, referer, hash, overwrite, options)

    fun tempDir(name: String = "out.bin"): File = File(folderFile, ".$name.csdl")
    fun finalFile(name: String = "out.bin"): File = File(folderFile, name)

    /**
     * Collects `download(request)` to its end, or until [stopWhen] accepts a snapshot (which cancels the
     * collector: a pause). Meanwhile a thread polls the folder for any visible file that is not a dot
     * file, so a final file that appears half written is caught.
     */
    fun run(
        request: DownloadRequest,
        downloader: SegmentedDownloader = downloader(),
        timeoutMillis: Long = 45_000,
        stopWhen: ((DownloadSnapshot) -> Boolean)? = null,
    ): Run {
        val seen = ConcurrentHashMap<String, CopyOnWriteArraySet<Long>>()
        val stopWatching = java.util.concurrent.atomic.AtomicBoolean(false)
        val watcher = Thread {
            while (!stopWatching.get()) {
                folderFile.listFiles()?.forEach { f ->
                    if (!f.name.startsWith(".") && f.isFile) {
                        seen.computeIfAbsent(f.name) { CopyOnWriteArraySet() }.add(f.length())
                    }
                }
                Thread.sleep(1)
            }
        }.apply { isDaemon = true; start() }
        val snapshots = ArrayList<DownloadSnapshot>()
        try {
            runBlocking(Dispatchers.Default) {
                withTimeout(timeoutMillis) {
                    downloader.download(request)
                        .transformWhile { snapshot ->
                            emit(snapshot)
                            stopWhen?.invoke(snapshot) != true
                        }
                        .collect { snapshots += it }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out after ${timeoutMillis}ms. ${diagnostics(snapshots)}", e)
        } finally {
            stopWatching.set(true)
            watcher.join()
        }
        assertTrue(snapshots.isNotEmpty(), "the flow emitted nothing")
        return Run(snapshots, seen.mapValues { it.value.toSet() })
    }

    fun diagnostics(snapshots: List<DownloadSnapshot>): String {
        val last = snapshots.lastOrNull()
        val temp = tempDir().listFiles().orEmpty().joinToString { "${it.name}=${it.length()}" }
        val state = runCatching { File(tempDir(), "state").readText() }.getOrNull()
        return "snapshots=${snapshots.size} last=${last?.state}/${last?.failure} bytesDone=${last?.bytesDone} " +
            "parts=${last?.parts}\ntemp=[$temp]\nstate=${state}\nserverTail=${server.log.takeLast(10)}"
    }

    /** The parts a paused download left behind: ranges from its state file, sizes from the part files. */
    fun partsOnDisk(name: String = "out.bin"): List<PartOnDisk> {
        val state = File(tempDir(name), "state")
        assertTrue(state.isFile, "no state file in ${tempDir(name)}")
        return state.readLines().filter { it.startsWith("part ") }.map { line ->
            val f = line.split(' ')
            val id = f[1].toInt()
            PartOnDisk(id, f[2].toLong(), f[3].toLong(), File(tempDir(name), "part-$id").takeIf { it.isFile }?.length() ?: 0L)
        }
    }

    fun diskBytes(name: String = "out.bin"): Long =
        tempDir(name).listFiles { f -> f.name.startsWith("part-") }.orEmpty().sumOf { it.length() }

    /**
     * No request after [mark] may start below what its part already holds on disk. A split's new part
     * starts above its parent's cursor, so it passes; a refetch of held bytes does not.
     */
    fun assertNoRefetch(before: List<PartOnDisk>, mark: Int) {
        for (entry in server.since(mark)) {
            if (entry.info.isProbe) continue
            val start = entry.servedStart ?: continue
            val part = before.firstOrNull { start >= it.start && start < it.endExclusive } ?: continue
            assertTrue(start >= part.start + part.onDisk, "request ${entry.info} starts at $start but part ${part.id} [${part.start},${part.endExclusive}) already held ${part.onDisk} bytes")
        }
    }

    /** Body bytes the server was asked for since [mark], probes excluded. */
    fun requestedSince(mark: Int): Long = server.since(mark).filter { !it.info.isProbe && it.status in listOf(200, 206) }.sumOf { it.declaredBytes }

    override fun close() {
        runCatching { client.close() }
        server.close()
        outer.deleteRecursively()
    }

    // region assertions

    fun assertCompleted(run: Run, source: SeededSource, name: String = "out.bin", extraAllowedSizes: Set<Long> = emptySet()) {
        val last = run.last
        assertEquals(DownloadState.COMPLETED, last.state, "state; failure=${last.failure}. ${diagnostics(run.snapshots)}")
        assertNull(last.failure, "a COMPLETED snapshot carries no failure")
        val file = assertNotNull(last.file, "a COMPLETED snapshot carries the file")
        val onDisk = File(file.path)
        assertEquals(folderFile.canonicalFile, onDisk.canonicalFile.parentFile, "final file lives in the destination folder")
        assertEquals(name, onDisk.name)
        assertNull(source.describeMismatch(onDisk), "final file differs from the source")
        run.observedSizes[name]?.let { sizes ->
            assertEquals(emptySet(), sizes - source.size - extraAllowedSizes, "the final file was visible at a size other than the full one")
        }
        val leftovers = tempDir(name).listFiles().orEmpty().filter { it.name.startsWith("part-") || it.name == "joined" }
        assertEquals(emptyList(), leftovers.map { it.name }, "temporary part files left behind after COMPLETED")
        assertEquals(source.size, last.bytesDone, "bytesDone at completion")
    }

    fun assertFailed(run: Run, name: String = "out.bin"): DownloadSnapshot {
        val last = run.last
        assertEquals(DownloadState.FAILED, last.state, "state; snapshots=${run.snapshots.size}")
        assertNotNull(last.failure, "a FAILED snapshot says why")
        assertNull(last.file, "a FAILED snapshot has no file")
        assertTrue(run.observedSizes[name].isNullOrEmpty(), "a final file was visible during a download that failed: ${run.observedSizes}")
        assertTrue(!finalFile(name).exists(), "a final file exists after a failed download")
        return last
    }

    // endregion
}

/** Base for the suites: a fresh [Harness] per test. */
abstract class SuiteBase {
    protected lateinit var h: Harness

    @kotlin.test.BeforeTest
    fun openHarness() {
        h = Harness(dnsMap())
    }

    @kotlin.test.AfterTest
    fun closeHarness() {
        h.close()
    }

    protected open fun dnsMap(): Map<String, String> = emptyMap()
}

package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * How to start a sandboxed child of this JVM, in the app bundle and out of it.
 *
 * A jpackage bundle strips `bin/java` from its runtime (measured: no `bin` directory under
 * `Contents/runtime/Contents/Home`), so packaged, the launcher re-runs itself with
 * [PluginHost.ARGUMENT]. In development it is a plain `java -cp`.
 */
class HostLauncher internal constructor(
    private val executable: File,
    private val prefixArguments: List<String>,
    private val readable: List<File>,
) {
    internal fun start(
        arguments: List<String>,
        readOnlyInputs: List<File>,
        writable: File,
        network: Boolean,
    ): Process {
        val profile = SeatbeltProfile.render(executable, readable, readOnlyInputs, writable, network)
        val builder = ProcessBuilder(
            listOf(SeatbeltProfile.SANDBOX_EXEC, "-p", profile, executable.path) +
                prefixArguments + arguments
        )
        // A plugin inherits nothing from the user's shell: no tokens, no paths, no API keys.
        builder.environment().clear()
        builder.environment()["HOME"] = writable.path
        builder.environment()["TMPDIR"] = writable.path
        builder.environment()["JAVA_TOOL_OPTIONS"] = JVM_OPTIONS
        builder.directory(writable)
        return builder.start()
    }

    companion object {
        private const val JVM_OPTIONS =
            "-Xmx512m -XX:+UseSerialGC -XX:-UsePerfData -XX:ActiveProcessorCount=4 -Xshare:auto"
        private const val DEV_MAIN = "com.lagradost.cloudstream4.feature.extensions.sandbox.PluginHostKt"

        /** `null` when this OS has no sandbox. There is no unsandboxed fallback, on purpose. */
        fun forThisProcess(): HostLauncher? {
            if (!File(SeatbeltProfile.SANDBOX_EXEC).canExecute()) return null
            val classpath = System.getProperty("java.class.path").orEmpty()
                .split(File.pathSeparator).filter { it.isNotEmpty() }.map(::File)
            val javaHome = File(System.getProperty("java.home"))
            val packaged = System.getProperty("jpackage.app-path")?.let(::File)
            return if (packaged != null) {
                val bundle = packaged.canonicalFile.parentFile.parentFile // <App>.app/Contents
                HostLauncher(packaged.canonicalFile, listOf(PluginHost.ARGUMENT), listOf(bundle))
            } else {
                val java = File(javaHome, "bin/java").canonicalFile
                HostLauncher(
                    executable = java,
                    prefixArguments = listOf("-cp", classpath.joinToString(File.pathSeparator), DEV_MAIN),
                    readable = classpath + javaHome.canonicalFile,
                )
            }
        }
    }
}

/**
 * The app's handle on one running plugin host.
 *
 * Everything received is untrusted. A frame that fails to parse ends the host rather than being
 * skipped: a peer that has stopped speaking the protocol cannot be resynchronised, and has either
 * crashed or is no longer the code we started.
 */
class PluginHostClient private constructor(
    private val process: Process,
    private val label: String,
) : Closeable {

    private class Pending(val decode: (DataInputStream) -> Any) {
        val reply = CompletableDeferred<Result<Any>>()
    }

    private val pending = ConcurrentHashMap<Int, Pending>()
    private val nextId = AtomicInteger(HostProtocol.READY_ID)
    private val out: OutputStream = process.outputStream
    private val dead = AtomicBoolean(false)
    private val diagnostics = StringBuffer()
    private val ready = Pending { it.readProviders() }

    /** Up to [MAX_DIAGNOSTIC_CHARS] of what the host wrote to stderr, for a failure report. */
    val hostOutput: String get() = diagnostics.toString()

    init {
        pending[HostProtocol.READY_ID] = ready
        thread(isDaemon = true, name = "plugin-host-$label-out") { readLoop() }
        thread(isDaemon = true, name = "plugin-host-$label-err") { drainStderr() }
    }

    private fun readLoop() {
        val reason = try {
            val input = process.inputStream.buffered()
            while (true) {
                val frame = HostProtocol.readFrame(input) ?: break
                HostProtocol.decode(frame) {
                    val id = readInt()
                    val waiter = pending.remove(id) ?: throw IOException("reply to unknown request $id")
                    if (readBoolean()) {
                        waiter.reply.complete(Result.success(waiter.decode(this)))
                    } else {
                        waiter.reply.complete(Result.failure(IOException(readText().sanitized(MAX_ERROR_CHARS))))
                    }
                }
            }
            "the plugin host exited"
        } catch (violation: Exception) {
            // IOException, IndexOutOfBounds, IllegalArgument: all the same fact.
            "protocol violation: ${violation.message.orEmpty().sanitized(MAX_ERROR_CHARS)}"
        }
        abort(reason)
    }

    /** Keeps only the start of it. A plugin writing forever must not be able to fill this process. */
    private fun drainStderr() {
        runCatching {
            val chunk = ByteArray(DIAGNOSTIC_CHUNK)
            process.errorStream.use { stream ->
                while (true) {
                    val count = stream.read(chunk)
                    if (count < 0) break
                    if (diagnostics.length < MAX_DIAGNOSTIC_CHARS) {
                        diagnostics.append(String(chunk, 0, count, Charsets.UTF_8).sanitizedLog())
                    }
                }
            }
        }
    }

    private fun abort(reason: String) {
        dead.set(true)
        process.destroyForcibly()
        val failure = IOException("$label: $reason")
        pending.values.forEach { it.reply.complete(Result.failure(failure)) }
        pending.clear()
    }

    private suspend fun providers(timeoutMillis: Long): Result<List<RemoteProvider>> = try {
        @Suppress("UNCHECKED_CAST")
        withTimeout(timeoutMillis) { ready.reply.await() } as Result<List<RemoteProvider>>
    } catch (slow: TimeoutCancellationException) {
        abort("did not start within ${timeoutMillis}ms")
        Result.failure(IOException("$label did not start within ${timeoutMillis}ms"))
    }

    suspend fun search(api: String, query: String, page: Int): RemoteSearchPage =
        call(HostProtocol.OP_SEARCH, { it.readSearchPage().cleaned() }) {
            writeText(api)
            writeText(query)
            writeInt(page)
        }

    suspend fun quickSearch(api: String, query: String): RemoteSearchPage =
        call(HostProtocol.OP_QUICK_SEARCH, { it.readSearchPage().cleaned() }) {
            writeText(api)
            writeText(query)
        }

    suspend fun load(api: String, url: String): RemoteDetails =
        call(HostProtocol.OP_LOAD, { it.readDetails().cleaned() }) {
            writeText(api)
            writeText(url)
        }

    suspend fun loadLinks(api: String, data: String): RemoteLinks =
        call(HostProtocol.OP_LOAD_LINKS, { it.readLinks().cleaned() }) {
            writeText(api)
            writeText(data)
        }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> call(
        op: Int,
        decode: (DataInputStream) -> T,
        args: java.io.DataOutputStream.() -> Unit,
    ): T {
        if (dead.get()) throw IOException("$label: the plugin host is not running")
        val id = nextId.incrementAndGet()
        val waiter = Pending(decode)
        pending[id] = waiter
        val frame = HostProtocol.encode {
            writeInt(id)
            writeInt(op)
            args()
        }
        return try {
            withContext(Dispatchers.IO) {
                synchronized(out) {
                    out.write(frame)
                    out.flush()
                }
            }
            withTimeout(CALL_TIMEOUT_MILLIS) { waiter.reply.await() }.getOrThrow() as T
        } catch (slow: TimeoutCancellationException) {
            pending.remove(id)
            throw IOException("$label did not answer within ${CALL_TIMEOUT_MILLIS}ms")
        }
    }

    /** Stops the host. Closing stdin would do; a plugin is not trusted to let it. */
    override fun close() {
        dead.set(true)
        runCatching { out.close() }
        process.destroy()
        if (!process.waitFor(STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
    }

    companion object {
        private const val CALL_TIMEOUT_MILLIS = 60_000L
        private const val START_TIMEOUT_MILLIS = 60_000L
        private const val STOP_GRACE_MILLIS = 500L
        private const val MAX_ERROR_CHARS = 400
        private const val MAX_DIAGNOSTIC_CHARS = 16 * 1024
        private const val DIAGNOSTIC_CHUNK = 4096

        /**
         * Starts a host for [jar] and waits for it to report what it registered.
         *
         * @param data this extension's private directory, the only place it may write.
         */
        suspend fun start(
            launcher: HostLauncher,
            jar: File,
            data: File,
            label: String,
        ): Pair<PluginHostClient, Result<List<RemoteProvider>>> {
            check(data.mkdirs() || data.isDirectory) { "cannot create the sandbox directory" }
            val process = withContext(Dispatchers.IO) {
                launcher.start(
                    arguments = listOf(
                        PluginHost.MODE_SERVE,
                        jar.parentFile.canonicalPath,
                        jar.name,
                        data.canonicalPath,
                    ),
                    readOnlyInputs = listOf(jar),
                    writable = data,
                    network = true,
                )
            }
            val client = PluginHostClient(process, label)
            val providers = client.providers(START_TIMEOUT_MILLIS).map { list -> list.map { it.cleaned() } }
            return client to providers
        }

        /**
         * Converts a `.cs3` in a no-network sandbox that can write only [outputDir].
         *
         * @return the host's stderr when it failed, or `null` on success.
         */
        suspend fun convert(launcher: HostLauncher, cs3: File, output: File, outputDir: File): String? =
            withContext(Dispatchers.IO) {
                check(outputDir.mkdirs() || outputDir.isDirectory) { "cannot create the output directory" }
                val process = launcher.start(
                    arguments = listOf(PluginHost.MODE_CONVERT, cs3.canonicalPath, output.canonicalPath),
                    readOnlyInputs = listOf(cs3),
                    writable = outputDir,
                    network = false,
                )
                process.outputStream.close()
                val errors = StringBuffer()
                val drain = thread(isDaemon = true) {
                    runCatching {
                        val text = process.errorStream.readNBytes(MAX_DIAGNOSTIC_CHARS)
                        errors.append(String(text, Charsets.UTF_8).sanitizedLog())
                    }
                }
                val finished = process.waitFor(CONVERT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                if (!finished) process.destroyForcibly()
                drain.join(STOP_GRACE_MILLIS)
                when {
                    !finished -> "conversion did not finish within ${CONVERT_TIMEOUT_MILLIS}ms"
                    process.exitValue() != 0 -> errors.toString().ifBlank { "exit ${process.exitValue()}" }
                    else -> null
                }
            }

        private const val CONVERT_TIMEOUT_MILLIS = 120_000L
    }
}

private const val MAX_FIELD_CHARS = 2048
private const val MAX_ITEMS = 500
private const val MAX_PROVIDER_FIELD_CHARS = 256

/** Terminal escapes and forged lines stop here, because this text reaches a screen and a log. */
private fun String.sanitized(limit: Int = MAX_FIELD_CHARS): String =
    take(limit).filterNot { it.isISOControl() }

private fun String.sanitizedLog(): String =
    filter { it == '\n' || !it.isISOControl() }

/**
 * A poster URL the app may fetch, or null.
 *
 * The image loader runs in this process, outside the sandbox, so a plugin must not be able to aim it
 * at `file:`, at this machine, or at the local network. Hosts are checked as written: a public name
 * that resolves to a private address gets past this, and that gap is recorded in `KMP-14`.
 */
internal fun String?.httpUrlOrNull(): String? {
    val url = this?.sanitized() ?: return null
    val parsed = runCatching { URI(url) }.getOrNull() ?: return null
    if (parsed.scheme?.lowercase() !in HTTP_SCHEMES) return null
    val host = parsed.host?.lowercase()?.trim('[', ']') ?: return null
    return url.takeUnless { host == "localhost" || host.endsWith(".local") || PRIVATE_ADDRESS.matches(host) }
}

private val HTTP_SCHEMES = setOf("http", "https")

/** Loopback, RFC 1918, link-local, the unspecified address, and their IPv6 forms. */
private val PRIVATE_ADDRESS = Regex(
    "(127\\.|10\\.|192\\.168\\.|169\\.254\\.|0\\.|172\\.(1[6-9]|2[0-9]|3[01])\\.).*|::1?|f[cd].*:.*|fe80:.*"
)

private fun RemoteProvider.cleaned() = copy(
    name = name.sanitized(MAX_PROVIDER_FIELD_CHARS),
    mainUrl = mainUrl.sanitized(MAX_PROVIDER_FIELD_CHARS),
    lang = lang.sanitized(MAX_PROVIDER_FIELD_CHARS),
    supportedTypes = supportedTypes.take(MAX_ITEMS).map { it.sanitized(MAX_PROVIDER_FIELD_CHARS) },
)

private fun RemoteSearchPage.cleaned() = copy(
    items = items.take(MAX_ITEMS).map { item ->
        item.copy(
            name = item.name.sanitized(),
            url = item.url.sanitized(),
            type = item.type?.sanitized(MAX_PROVIDER_FIELD_CHARS),
            posterUrl = item.posterUrl.httpUrlOrNull(),
        )
    },
)

private fun RemoteDetails.cleaned() = copy(
    name = name.sanitized(),
    url = url.sanitized(),
    type = type.sanitized(MAX_PROVIDER_FIELD_CHARS),
    plot = plot?.sanitized(),
    posterUrl = posterUrl.httpUrlOrNull(),
    dataUrl = dataUrl?.sanitized(),
    episodes = episodes.take(MAX_ITEMS).map { it.copy(name = it.name?.sanitized(), data = it.data.sanitized()) },
)

private fun RemoteLinks.cleaned() = copy(
    links = links.take(MAX_ITEMS).map { link ->
        link.copy(
            source = link.source.sanitized(MAX_PROVIDER_FIELD_CHARS),
            name = link.name.sanitized(MAX_PROVIDER_FIELD_CHARS),
            url = link.url.sanitized(),
            type = link.type.sanitized(MAX_PROVIDER_FIELD_CHARS),
        )
    },
)

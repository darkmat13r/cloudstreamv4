package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

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
        extraRules: List<String> = emptyList(),
        jvmOptions: String = JVM_OPTIONS,
        gate: OutboundGate? = null,
        lowered: Boolean = false,
        input: ProcessBuilder.Redirect? = null,
    ): Process {
        val profile = SeatbeltProfile.render(executable, readable, readOnlyInputs, writable, network, extraRules, gate?.port)
        val sandboxed = listOf(SeatbeltProfile.SANDBOX_EXEC, "-p", profile, executable.path) + prefixArguments + arguments
        // The same process all the way down, through `exec`, so what this returns is the JVM. A shell because a
        // limit needs one, and outside the sandbox, because the sandbox would not let it run.
        val builder = ProcessBuilder(if (lowered) LOWERED + sandboxed else sandboxed)
        // A plugin inherits nothing from the user's shell: no tokens, no paths, no API keys.
        builder.environment().clear()
        builder.environment()["HOME"] = writable.path
        builder.environment()["TMPDIR"] = writable.path
        val proxy = gate?.let { " -DsocksProxyHost=$LOOPBACK -DsocksProxyPort=${it.port} -DsocksNonProxyHosts=" }.orEmpty()
        builder.environment()["JAVA_TOOL_OPTIONS"] = "$jvmOptions -Duser.home=${writable.path}$proxy"
        builder.directory(writable)
        input?.let(builder::redirectInput)
        return builder.start()
    }

    /**
     * Starts the libVLC host under the plugin hosts' policy plus [SeatbeltProfile.mediaHostRules].
     *
     * @param vlcApp the VLC bundle. It is read, never written.
     * @param writable the host's own directory, empty and not shared with anything.
     * @param silent no audio output: libVLC is told so, and the sandbox refuses it the audio devices anyway.
     * @param input the host's stdin in place of a pipe: one end of a socket pair, which a downloaded file's descriptor
     *   arrives over (`KMP-74`). The host is told only that one is coming, never where from, and its policy is the same.
     */
    fun startMediaHost(
        vlcApp: File,
        writable: File,
        silent: Boolean = false,
        input: ProcessBuilder.Redirect? = null,
    ): Process {
        val app = vlcApp.canonicalFile
        return start(
            arguments = emptyList(),
            readOnlyInputs = emptyList(),
            writable = writable,
            network = true,
            extraRules = SeatbeltProfile.mediaHostRules(app, writable, audio = !silent),
            jvmOptions = "$JVM_OPTIONS -Djava.awt.headless=true -Djava.io.tmpdir=${writable.path} " +
                "-Djna.tmpdir=${writable.path}" + (if (silent) " -D$SILENT_PROPERTY=true" else "") +
                if (input != null) " -D$FILE_PROPERTY=true" else "",
            input = input,
        )
    }

    companion object {
        /** How [startMediaHost] tells the host, whose command line is the same either way. */
        const val SILENT_PROPERTY = "cloudstream.mediahost.silent"

        /** How [startMediaHost] tells the host a file's descriptor is the first thing on its stdin. Never a path. */
        const val FILE_PROPERTY = "cloudstream.mediahost.file"
        /** Stands in for the person's account name, which a host has no use for. */
        private const val HOST_USER = "cloudstream"
        private const val JVM_OPTIONS =
            "-Xmx512m -XX:+UseSerialGC -XX:-UsePerfData -XX:ActiveProcessorCount=4 -Xshare:auto -Duser.name=$HOST_USER"

        /** On top of [JVM_OPTIONS] for code from a plugin: what the heap's cap does not cover. */
        internal const val PLUGIN_JVM_OPTIONS = "$JVM_OPTIONS -XX:MaxDirectMemorySize=128m -XX:MaxMetaspaceSize=384m"

        /**
         * Below the app and the player for CPU, no core files, and no single file past 256 MB, counted in kilobytes on
         * this `/bin/sh` (measured). Not for the media host, which has frames to deliver on time.
         */
        private val LOWERED = listOf(
            "/bin/sh", "-c", "ulimit -c 0; ulimit -f 262144; exec /usr/bin/env -u PWD -u OLDPWD -u SHLVL -u _ /usr/bin/nice -n 10 \"\$@\"", "plugin-host",
        )
        private const val LOOPBACK = "127.0.0.1"
        private const val DEV_MAIN = "com.lagradost.cloudstream4.feature.extensions.sandbox.PluginHostKt"

        /** `null` when this OS has no sandbox. There is no unsandboxed fallback, on purpose. */
        fun forThisProcess(): HostLauncher? = forHost(PluginHost.ARGUMENT, DEV_MAIN)

        /** The same launcher, for the libVLC host: re-running [argument], or [devMain] out of a bundle. */
        fun forMediaHost(argument: String, devMain: String): HostLauncher? = forHost(argument, devMain)

        private fun forHost(argument: String, devMain: String): HostLauncher? {
            if (!File(SeatbeltProfile.SANDBOX_EXEC).canExecute()) return null
            val classpath = System.getProperty("java.class.path").orEmpty()
                .split(File.pathSeparator).filter { it.isNotEmpty() }.map(::File)
            val javaHome = File(System.getProperty("java.home"))
            val packaged = System.getProperty("jpackage.app-path")?.let(::File)
            return if (packaged != null) {
                val bundle = packaged.canonicalFile.parentFile.parentFile // <App>.app/Contents
                HostLauncher(packaged.canonicalFile, listOf(argument), listOf(bundle))
            } else {
                val java = File(javaHome, "bin/java").canonicalFile
                HostLauncher(
                    executable = java,
                    prefixArguments = listOf("-cp", classpath.joinToString(File.pathSeparator), devMain),
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
class PluginHostClient internal constructor(
    private val process: Process,
    private val label: String,
    private val gate: OutboundGate? = null,
) : Closeable {

    /** The names and addresses this host has asked to connect to. Never a path: nothing here sees one. */
    val contactedHosts: Set<String> get() = gate?.contacted.orEmpty()

    /** @param arrivals where partial replies go. Null for a call that may not send any. */
    private class Pending(val arrivals: LinkArrivals? = null, val decode: (DataInputStream) -> Any) {
        val reply = CompletableDeferred<Result<Any>>()
    }

    /** The host is gone, which is not the same as a plugin reporting that one call failed. */
    private class HostEnded(message: String) : IOException(message)

    private val pending = ConcurrentHashMap<Int, Pending>()

    /**
     * Calls that gave up waiting and may still be answered, once each. Their [Pending] is kept so a late
     * reply is decoded by the same checks as a timely one; nothing awaits it, so the result goes nowhere.
     * This lock also guards a move out of [pending], so a live id is never in neither map.
     */
    private val timedOut = HashMap<Int, Pending>()
    private val nextId = AtomicInteger(HostProtocol.READY_ID)
    private val out: OutputStream = process.outputStream
    private val dead = AtomicBoolean(false)
    private val stderr = HostStderr()
    private val stderrDrain: Thread
    private val ready = Pending { it.readProviders() }

    /**
     * What the host wrote to stderr, fixed in shape and holding none of the plugin's own text (`BUG-83`): its line and
     * byte counts, and the class of the failure the host reported. For a failure report.
     */
    val hostSummary: String get() = stderr.summary

    /** The plugin's own stderr lines, redacted ([redactedPluginText]). For `--check-extensions --verbose` and tests. */
    val redactedLog: String get() = stderr.redactedLog

    /** Why this side stopped the host, when it did. */
    @Volatile
    var endedBecause: String? = null
        private set

    internal val pid: Long get() = process.pid()

    init {
        pending[HostProtocol.READY_ID] = ready
        thread(isDaemon = true, name = "plugin-host-$label-out") { readLoop() }
        stderrDrain = thread(isDaemon = true, name = "plugin-host-$label-err") { drainStderr() }
    }

    private fun readLoop() {
        val reason = try {
            val input = process.inputStream.buffered()
            while (true) {
                val frame = HostProtocol.readFrame(input) ?: break
                HostProtocol.decode(frame) {
                    val id = readInt()
                    when (val kind = readByte().toInt()) {
                        HostProtocol.REPLY_PARTIAL -> {
                            val waiter = find(id) ?: throw IOException("partial reply to unknown request $id")
                            val arrivals = waiter.arrivals ?: throw IOException("partial reply to request $id")
                            arrivals.receive(frame.size) {
                                readLinks().also { part ->
                                    if (part.links.isEmpty() && part.subtitles.isEmpty()) throw IOException("an empty partial reply")
                                }
                            }
                        }
                        HostProtocol.REPLY_DONE -> {
                            val waiter = claim(id) ?: throw IOException("reply to unknown request $id")
                            // Its id is gone by now, so abort cannot reach it, and nothing else would until its timeout.
                            val answer = try {
                                waiter.decode(this)
                            } catch (violation: Exception) {
                                waiter.reply.complete(Result.failure(HostEnded("$label: a reply that could not be read")))
                                throw violation
                            }
                            waiter.reply.complete(Result.success(answer))
                        }
                        HostProtocol.REPLY_FAILED -> {
                            val waiter = claim(id) ?: throw IOException("reply to unknown request $id")
                            waiter.reply.complete(Result.failure(PluginFailed(readText())))
                        }
                        else -> throw IOException("reply of unknown kind $kind")
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

    private fun claim(id: Int): Pending? = synchronized(timedOut) { pending.remove(id) ?: timedOut.remove(id) }

    /** As [claim], for a reply that is not the last: the id stays, so a second final reply is still caught. */
    private fun find(id: Int): Pending? = synchronized(timedOut) { pending[id] ?: timedOut[id] }

    /**
     * A plugin can leave every call unanswered, so what is remembered for them is bounded, and past
     * [MAX_TIMED_OUT_CALLS] the host ends. Forgetting an old id instead would let the plugin choose when
     * its late reply kills the host, and a host this far behind is not answering anyway.
     *
     * @return false when the call had already been answered, so there is nothing to give up on.
     */
    private fun expire(id: Int): Boolean {
        val full = synchronized(timedOut) {
            val waiter = pending.remove(id) ?: return false
            (timedOut.size >= MAX_TIMED_OUT_CALLS).also { full -> if (!full) timedOut[id] = waiter }
        }
        if (full) abort("left more than $MAX_TIMED_OUT_CALLS calls unanswered past their timeout")
        return true
    }

    /** Reads it all and keeps a bounded amount ([HostStderr]): a plugin writing forever must not fill this process. */
    private fun drainStderr() {
        process.errorStream.drainInto(stderr)
    }

    private fun abort(reason: String) {
        if (endedBecause == null) endedBecause = reason
        dead.set(true)
        val failure = HostEnded("$label: $reason")
        pending.values.forEach { it.reply.complete(Result.failure(failure)) }
        pending.clear()
        synchronized(timedOut) { timedOut.clear() }
        // Last: its death ends readLoop, whose own reason must not reach a waiter ahead of this one.
        process.destroyForcibly()
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
        call(HostProtocol.OP_LOAD, { it.readDetails(::factDropped).cleaned(::factDropped) }) {
            writeText(api)
            writeText(url)
        }

    /** Which fact and why, in this code's own words: the value is the plugin's, and can carry an address or a token. */
    private fun factDropped(what: String) = System.err.println("$label: a title's $what was left out")

    /** Everything [linksAsTheyArrive] got, once it is over. */
    suspend fun loadLinks(api: String, data: String): RemoteLinks = linksAsTheyArrive(api, data).last()

    /**
     * What a plugin has offered so far, each time it offers more. Each value is the whole answer so far, so a
     * slow reader gets the latest and loses nothing, and however fast a host sends, the reader sets the pace.
     *
     * Ends in one of three ways, and a partial reply changes none of them:
     * - The plugin finishes. If it failed having offered links, those are the answer.
     * - [LINKS_CEILING_MILLIS] passes, counted from the start and never extended. With links, those are the
     *   answer, with [RemoteLinks.stoppedEarly] set. With none, it fails as any other unanswered call does.
     * - The collector goes away.
     *
     * The last two tell the host to stop the plugin, and leave the id in [timedOut] for its reply. A plugin
     * only stops if it cooperates, but the host answers at once and sends nothing more for this call either way.
     *
     * A host that ends takes the call with it, however many links had arrived. Those already delivered stay
     * with whoever has them: they passed the same checks as every other link.
     */
    fun linksAsTheyArrive(api: String, data: String): Flow<RemoteLinks> = channelFlow {
        if (dead.get()) throw IOException("$label: the plugin host is not running")
        val id = nextId.incrementAndGet()
        val arrivals = LinkArrivals()
        val waiter = Pending(arrivals) { input -> arrivals.receive(input.available()) { input.readLinks() } }
        pending[id] = waiter
        val frame = HostProtocol.encode {
            writeInt(id)
            writeInt(HostProtocol.OP_LOAD_LINKS)
            writeText(api)
            writeText(data)
        }
        try {
            withContext(Dispatchers.IO) {
                synchronized(out) {
                    out.write(frame)
                    out.flush()
                }
            }
            val forwarding = launch { arrivals.latest.collect { if (it.links.isNotEmpty()) send(it) } }
            val reply = withTimeoutOrNull(LINKS_CEILING_MILLIS) { waiter.reply.await() }
            forwarding.cancelAndJoin()
            val failure = reply?.exceptionOrNull()
            val answer = arrivals.close()
            when {
                reply == null -> {
                    val reason = "$label did not answer within ${LINKS_CEILING_MILLIS}ms"
                    if (answer.links.isEmpty()) throw IOException(reason)
                    send(answer.copy(stoppedEarly = "$label did not finish within ${LINKS_CEILING_MILLIS}ms"))
                }
                failure == null -> send(answer)
                failure is HostEnded || answer.links.isEmpty() -> throw failure
                else -> send(answer.copy(stoppedEarly = failure.message.orEmpty()))
            }
        } finally {
            arrivals.close()
            if (expire(id)) stop(id)
        }
    }.buffer(Channel.CONFLATED)

    /**
     * On its own thread, because this runs on the way out of a cancelled call, and a plugin that has stopped
     * reading stdin must not hold that up. Each one holds a place in [timedOut], so there is a bound on these.
     */
    private fun stop(id: Int) {
        if (dead.get()) return
        val frame = HostProtocol.encode {
            writeInt(id)
            writeInt(HostProtocol.OP_CANCEL)
        }
        thread(isDaemon = true, name = "plugin-host-$label-stop") {
            runCatching {
                synchronized(out) {
                    out.write(frame)
                    out.flush()
                }
            }
        }
    }

    /** @param section an index into [RemoteProvider.mainPage], which the host looks up on its own side. */
    suspend fun mainPage(api: String, section: Int, page: Int): RemoteHomePage =
        call(HostProtocol.OP_MAIN_PAGE, { it.readHomePage().cleaned() }) {
            writeText(api)
            writeInt(section)
            writeInt(page)
        }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> call(
        op: Int,
        decode: (DataInputStream) -> T,
        args: java.io.DataOutputStream.() -> Unit,
    ): T {
        if (dead.get()) throw IOException("$label: the plugin host is not running")
        val id = nextId.incrementAndGet()
        val waiter = Pending(decode = decode)
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
            expire(id)
            throw IOException("$label did not answer within ${CALL_TIMEOUT_MILLIS}ms")
        }
    }

    /** Stops the host. Closing stdin would do; a plugin is not trusted to let it. */
    override fun close() {
        dead.set(true)
        runCatching { out.close() }
        process.destroy()
        if (!process.waitFor(STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        // So a failure report made after this reads all the host wrote, not part of it.
        stderrDrain.join(STOP_GRACE_MILLIS)
        gate?.close()
    }

    companion object {
        internal const val MAX_TIMED_OUT_CALLS = 256
        private const val CALL_TIMEOUT_MILLIS = 60_000L

        /**
         * As long as a links call may run, whatever it sends. All five films measured in `BUG-19` finished between
         * 65 and 87 s and had nearly every link by 30 s, so 60 would have called every one of them cut short.
         * Nobody is held up by this: they can pick a link as soon as there is one, which stops the call.
         */
        internal const val LINKS_CEILING_MILLIS = 90_000L
        private const val START_TIMEOUT_MILLIS = 60_000L
        private const val STOP_GRACE_MILLIS = 500L
        private const val MAX_ERROR_CHARS = 400
        private const val DIAGNOSTIC_CHUNK = 4096

        private fun InputStream.drainInto(stderr: HostStderr) {
            runCatching {
                use { stream ->
                    val chunk = ByteArray(DIAGNOSTIC_CHUNK)
                    while (true) {
                        val count = stream.read(chunk)
                        if (count < 0) break
                        stderr.feed(chunk, count)
                    }
                }
            }
            stderr.finish()
        }

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
            limits: HostLimits = HostLimits(),
        ): Pair<PluginHostClient, Result<List<RemoteProvider>>> {
            check(data.mkdirs() || data.isDirectory) { "cannot create the sandbox directory" }
            val gate = OutboundGate()
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
                    gate = gate,
                    lowered = true,
                    jvmOptions = HostLauncher.PLUGIN_JVM_OPTIONS,
                )
            }
            val client = PluginHostClient(process, label, gate)
            watchHost(process, data, limits, { client.pending.isNotEmpty() }, client::abort)
            val providers = client.providers(START_TIMEOUT_MILLIS).map { list -> list.map { it.cleaned() } }
            return client to providers
        }

        /**
         * Converts a `.cs3` in a no-network sandbox that can write only [outputDir].
         *
         * @return why it failed, fixed in shape (exit or timeout, and [HostStderr.summary]), or `null` on success.
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
                val errors = HostStderr()
                val drain = thread(isDaemon = true) { process.errorStream.drainInto(errors) }
                val finished = process.waitFor(CONVERT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                if (!finished) process.destroyForcibly()
                drain.join(STOP_GRACE_MILLIS)
                when {
                    !finished -> "conversion did not finish within ${CONVERT_TIMEOUT_MILLIS}ms"
                    process.exitValue() != 0 -> "exit ${process.exitValue()} · ${errors.summary}"
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

/**
 * A poster URL the app may fetch, or null.
 *
 * The image loader runs in this process, outside the sandbox, so a plugin must not be able to aim it
 * at `file:`, at this machine, or at the local network. This is the cheap first filter, on the host
 * as written. The host's poster client re-checks the address it really connects to, on every redirect,
 * because a public name can resolve to a private address.
 */
internal fun String?.httpUrlOrNull(): String? {
    val url = this?.sanitized() ?: return null
    val parsed = runCatching { URI(url) }.getOrNull() ?: return null
    if (parsed.scheme?.lowercase() !in HTTP_SCHEMES) return null
    val host = parsed.host?.lowercase()?.trim('[', ']')?.trimEnd('.') ?: return null
    return url.takeUnless { isPrivateHostName(host) }
}

internal val HTTP_SCHEMES = setOf("http", "https")

/**
 * A poster or backdrop URL the app may fetch (`KMP-100`, `KMP-102`), or null: [httpUrlOrNull]'s checks, https only, and
 * no user info. Their image client also refuses any hop that is not https, so a redirect cannot step one down.
 *
 * User info (`https://user:pass@host/`) is refused: an image needs no credentials, and a plugin could use them to have
 * the app send a name and password of the plugin's choosing, or to dress one host up as another
 * (`https://trusted.example@other.example/`).
 */
internal fun String?.httpsUrlOrNull(): String? = httpUrlOrNull()?.takeIf { url ->
    val parsed = runCatching { URI(url) }.getOrNull() ?: return@takeIf false
    parsed.scheme.equals("https", ignoreCase = true) && parsed.rawUserInfo == null
}

/** The host as written: a lowercased name or IP literal with no brackets or trailing dot. */
internal fun isPrivateHostName(host: String): Boolean =
    host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || PRIVATE_ADDRESS.matches(host)

/** Loopback, RFC 1918, link-local, the unspecified address, and their IPv6 forms. */
private val PRIVATE_ADDRESS = Regex(
    "(127\\.|10\\.|192\\.168\\.|169\\.254\\.|0\\.|172\\.(1[6-9]|2[0-9]|3[01])\\.).*|::1?|f[cd].*:.*|fe80:.*"
)

internal fun RemoteProvider.cleaned() = copy(
    name = name.sanitized(MAX_PROVIDER_FIELD_CHARS),
    mainUrl = mainUrl.sanitized(MAX_PROVIDER_FIELD_CHARS),
    lang = lang.sanitized(MAX_PROVIDER_FIELD_CHARS),
    supportedTypes = supportedTypes.take(MAX_ITEMS).map { it.sanitized(MAX_PROVIDER_FIELD_CHARS) },
    mainPage = mainPage.take(HostProtocol.MAX_MAIN_PAGE_SECTIONS)
        .map { it.copy(name = it.name.sanitized(MAX_PROVIDER_FIELD_CHARS)) },
    sequentialDelayMillis = sequentialDelayMillis?.coerceIn(0, HostProtocol.MAX_SEQUENTIAL_DELAY_MILLIS),
)

internal fun RemoteHomePage.cleaned() = copy(
    rows = rows.take(HostProtocol.MAX_HOME_ROWS).map { row ->
        row.copy(
            name = row.name.sanitized(MAX_PROVIDER_FIELD_CHARS),
            items = row.items.take(HostProtocol.MAX_HOME_ROW_ITEMS).map { item ->
                item.copy(
                    name = item.name.sanitized(),
                    url = item.url.sanitized(),
                    type = item.type?.sanitized(MAX_PROVIDER_FIELD_CHARS),
                    posterUrl = item.posterUrl.httpsUrlOrNull(),
                )
            },
        )
    },
)

internal fun RemoteSearchPage.cleaned() = copy(
    items = items.take(MAX_ITEMS).map { item ->
        item.copy(
            name = item.name.sanitized(),
            url = item.url.sanitized(),
            type = item.type?.sanitized(MAX_PROVIDER_FIELD_CHARS),
            posterUrl = item.posterUrl.httpsUrlOrNull(),
        )
    },
)

internal fun RemoteDetails.cleaned(dropped: (String) -> Unit = {}) = copy(
    name = name.sanitized(),
    url = url.sanitized(),
    type = type.sanitized(MAX_PROVIDER_FIELD_CHARS),
    plot = plot?.sanitized(),
    posterUrl = posterUrl.httpsUrlOrNull(),
    dataUrl = dataUrl?.sanitized(),
    episodes = episodes.take(MAX_ITEMS).map { it.copy(name = it.name?.sanitized(), data = it.data.sanitized()) },
    facts = facts.cleaned(dropped),
    backdropUrl = backdropUrl.httpsUrlOrNull(),
)

/**
 * The facts the page may show, each checked for what it means once the reader has checked its shape (`KMP-76`).
 *
 * A fact that fails is left out and the others kept. Words lose controls and format characters, as a subtitle's label
 * does, because they are drawn beside the title: U+202E would reverse the line they sit on. A certification is refused
 * rather than repaired, because one that needs repair is not one.
 */
internal fun RemoteFacts.cleaned(dropped: (String) -> Unit = {}): RemoteFacts {
    val score = scoreTenths?.takeIf { it in 0..HostProtocol.MAX_SCORE_TENTHS }
    if (scoreTenths != null && score == null) dropped("score: out of range")
    val words = tags.map { tag -> tag.filterNot(::isInvisibleInLabel).trim() }
    val kept = words.filter { it.isNotEmpty() && it.length <= HostProtocol.MAX_TAG_CHARS }
        .distinct()
        .take(HostProtocol.MAX_TAGS)
    if (kept.size < tags.size) dropped("tags: ${tags.size - kept.size} blank, too long, repeated or past the count")
    val rating = contentRating?.trim()?.takeIf { it.isCertification() }
    if (contentRating != null && rating == null) dropped("contentRating: not a certification")
    val runtime = runtimeMinutes?.takeIf { it in 1..HostProtocol.MAX_RUNTIME_MINUTES }
    if (runtimeMinutes != null && runtime == null) dropped("runtime: out of range")
    return RemoteFacts(score, kept, rating, status, runtime)
}

private fun String.isCertification() =
    length in 1..HostProtocol.MAX_CONTENT_RATING_CHARS && all { it.isLetterOrDigit() || it in CERTIFICATION_MARKS }

private const val CERTIFICATION_MARKS = " -+/"

/**
 * What one links call has received, and what keeps it from being a flood.
 *
 * Every frame costs its bytes, and a partial with nothing in it is refused, so one call can send at most
 * [HostProtocol.MAX_LINKS] + [HostProtocol.MAX_SUBTITLES] partial frames and [HostProtocol.MAX_LINK_BYTES] in
 * all. The honest host stops short of each bound, so passing one means the peer is not the code we started,
 * and the throw ends it. That stays true after the call has given up: the counting goes on, only the keeping
 * stops, so a call past its ceiling is no cheaper a way to flood.
 */
internal class LinkArrivals {
    private val links = ArrayList<RemoteLink>()
    private val subtitles = LinkedHashMap<Pair<String, String>, RemoteSubtitle>()
    private var linksSeen = 0
    private var subtitlesSeen = 0
    private var bytes = 0L
    private var closed = false

    /** The answer so far. A state and not a queue, so a host sending fast cannot make anything here wait or pile up. */
    val latest = MutableStateFlow(RemoteLinks(emptyList(), emptyList()))

    /** @param read parses the frame. It runs only if the frame's size is within the bound. */
    @Synchronized
    fun receive(frameBytes: Int, read: () -> RemoteLinks) {
        bytes += frameBytes
        if (bytes > HostProtocol.MAX_LINK_BYTES) throw IOException("sent more than ${HostProtocol.MAX_LINK_BYTES} bytes of links")
        val part = read()
        linksSeen += part.links.size
        subtitlesSeen += part.subtitles.size
        if (linksSeen > HostProtocol.MAX_LINKS) throw IOException("sent more than ${HostProtocol.MAX_LINKS} links")
        if (subtitlesSeen > HostProtocol.MAX_SUBTITLES) throw IOException("sent more than ${HostProtocol.MAX_SUBTITLES} subtitles")
        if (closed) return
        val cleaned = part.cleaned()
        links += cleaned.links
        cleaned.subtitles.forEach { subtitles.putIfAbsent(it.url to it.language, it) }
        latest.value = RemoteLinks(links.toList(), subtitles.values.toList())
    }

    /** Stops keeping what arrives. @return the answer as it stands. */
    @Synchronized
    fun close(): RemoteLinks {
        closed = true
        return latest.value
    }
}

private fun RemoteLinks.cleaned() = copy(
    subtitles = subtitles.asSequence()
        .mapNotNull { it.cleaned() }
        .distinctBy { it.url to it.language }
        .take(HostProtocol.MAX_SUBTITLES)
        .toList(),
    links = links.take(HostProtocol.MAX_LINKS).map { link ->
        link.copy(
            source = link.source.sanitized(MAX_PROVIDER_FIELD_CHARS),
            name = link.name.sanitized(MAX_PROVIDER_FIELD_CHARS),
            url = link.url.sanitized(SandboxPolicy.MAX_URL_CHARS + 1),
            type = link.type.sanitized(MAX_PROVIDER_FIELD_CHARS),
            referer = link.referer?.sanitized(),
            userAgent = link.userAgent?.sanitized(MAX_PROVIDER_FIELD_CHARS),
            audioUrl = link.audioUrl?.sanitized(SandboxPolicy.MAX_URL_CHARS + 1),
        )
    },
)

/**
 * A track the app may offer, or null.
 *
 * A label is the one plugin string on the player's screen, so it loses format characters as well as
 * controls: U+202E would otherwise reverse the row it sits on, and the rows around nothing else tell the
 * user what they are picking.
 *
 * The URL gets a check of its shape only. The address is checked when someone chooses the track
 * (`DesktopSubtitleLoader`), so offering a list costs no lookup of names nobody asked for.
 */
internal fun RemoteSubtitle.cleaned(): RemoteSubtitle? {
    val tag = languageTag?.takeIf { it.length <= MAX_LANGUAGE_TAG_CHARS && it.all(::isLanguageTagChar) }
    val label = language.take(MAX_SUBTITLE_LABEL_CHARS).filterNot(::isInvisibleInLabel).withoutDefaultIgnorable().trim()
        .ifEmpty { tag }
        ?: return null
    if (!SandboxPolicy.isHttpUrlShape(url)) return null
    return copy(
        language = label,
        languageTag = tag,
        referer = referer?.sanitized(),
        userAgent = userAgent?.sanitized(MAX_PROVIDER_FIELD_CHARS),
    )
}

private fun isLanguageTagChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-'

private fun isInvisibleInLabel(c: Char) =
    c.isISOControl() || c.category == CharCategory.FORMAT ||
        c.category == CharCategory.LINE_SEPARATOR || c.category == CharCategory.PARAGRAPH_SEPARATOR

private const val MAX_SUBTITLE_LABEL_CHARS = 128
private const val MAX_LANGUAGE_TAG_CHARS = 35

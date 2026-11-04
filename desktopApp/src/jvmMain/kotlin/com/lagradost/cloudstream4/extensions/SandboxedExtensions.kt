package com.lagradost.cloudstream4.extensions

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream4.feature.extensions.ExtensionHash
import com.lagradost.cloudstream4.feature.extensions.ExtensionVerification
import com.lagradost.cloudstream4.feature.extensions.sandbox.ExtensionScanner
import com.lagradost.cloudstream4.feature.extensions.sandbox.HostLauncher
import com.lagradost.cloudstream4.feature.extensions.sandbox.PluginHostClient
import com.lagradost.cloudstream4.feature.extensions.sandbox.SandboxPolicy
import com.lagradost.cloudstream4.feature.extensions.sandbox.SandboxedProvider
import com.lagradost.cloudstream4.feature.extensions.sandbox.ScanReport
import com.lagradost.cloudstream4.feature.extensions.sandbox.ScanSeverity
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Takes what is in the extensions directory and gets it running, one sandbox per extension.
 *
 * The pipeline, for each `*.jar` or `*.cs3`, every launch:
 *
 * 1. **Hash.** Against a `<file>.sha256` sidecar or a published pin. A mismatch refuses the file.
 * 2. **Store.** Copied, or for a `.cs3` converted in a no-network sandbox, to
 *    `.store/<sha256>.jar`. The name is the input's hash, so a conversion happens once. No host can
 *    write this directory, converting ones included, so what was scanned is what runs. See
 *    [convertIntoStore].
 * 3. **Scan.** [ExtensionScanner] in this process, so a plugin cannot forge its own verdict. A
 *    blocking finding refuses the extension.
 * 4. **Hold.** An extension with warnings stops here until the person has allowed that exact file
 *    ([ExtensionDecisions]). Everything so far is safe to do first, because none of it runs the plugin:
 *    the scan reads its bytes, and conversion translates a dex without loading a class from it.
 * 5. **Run.** A host per extension under Seatbelt, with its own data directory. One extension cannot
 *    read another's cookies, and a crash or a hang takes down one host.
 *
 * Plugin code never runs in this JVM. Where there is no sandbox (anything but macOS today), nothing
 * is loaded at all: [JvmExtensionRuntime][com.lagradost.cloudstream4.feature.extensions.JvmExtensionRuntime]
 * in-process would work and would hand a third-party jar the user's whole account.
 */
class SandboxedExtensions(
    directory: File = defaultDirectory(),
    private val launcher: HostLauncher? = HostLauncher.forThisProcess(),
    private val decisions: ExtensionDecisions = ExtensionDecisions(ExtensionDecisions.fileFor(directory)),
) : ExtensionControl {
    private val root = directory.canonicalFile
    private val store = File(root, STORE_DIRECTORY)
    private val sandboxes = File(root, SANDBOX_DIRECTORY)
    private val hosts = mutableListOf<PluginHostClient>()

    /** Who is running, by file name, so a change of mind can stop it. Guarded by [hosts]. */
    private val running = mutableMapOf<String, PluginHostClient>()

    /** Each conversion is a JVM. Everything arriving at once on a first launch must not be 80 of them. */
    private val conversions = Semaphore(MAX_PARALLEL_CONVERSIONS)

    /** Every provider registered by [loadInstalled], for a caller that wants more than search. */
    val providers: List<SandboxedProvider>
        get() = APIHolder.apis.withLock { APIHolder.apis.filterIsInstance<SandboxedProvider>() }

    /** Hash, convert and scan, and stop there: no plugin code runs, so this is safe on anything. */
    suspend fun scanInstalled(): SandboxReport = process(start = false)

    suspend fun loadInstalled(): SandboxReport = process(start = true)

    private suspend fun process(start: Boolean): SandboxReport = coroutineScope {
        val sources = candidates()
        if (launcher == null) {
            return@coroutineScope SandboxReport(root, sources.map { source ->
                SandboxedLoad(source.name, null, null, 0, "not loaded: no sandbox on this platform")
            })
        }
        SandboxReport(
            root,
            sources.map { source -> async { loadOne(source, launcher, start) } }.awaitAll(),
            decisionsUnreadable = decisions.read().unreadable,
        )
    }

    override suspend fun allow(fileName: String, sha256: String): SandboxedLoad? {
        val source = candidates().firstOrNull { it.name == fileName } ?: return null
        val launcher = launcher ?: return null
        withContext(Dispatchers.IO) { decisions.record(fileName, sha256, ExtensionChoice.Allow) }
        // Goes through everything again: if the file changed after the person read about it, the choice
        // above was for the old bytes, and this holds the new ones. Nothing here trusts the caller's hash.
        synchronized(hosts) { running[fileName] }?.let { stop(source) }
        return loadOne(source, launcher, start = true)
    }

    override suspend fun keepOff(fileName: String, sha256: String): SandboxedLoad? {
        val source = candidates().firstOrNull { it.name == fileName } ?: return null
        val launcher = launcher ?: return null
        // First, and whether or not it can be remembered: the person wants it stopped now.
        stop(source)
        withContext(Dispatchers.IO) { decisions.record(fileName, sha256, ExtensionChoice.KeepOff) }
        return loadOne(source, launcher, start = false)
    }

    /** Stops one host and takes its providers away, so no screen can reach a host that has gone. */
    private fun stop(source: File) {
        val host = synchronized(hosts) { running.remove(source.name)?.also { hosts -= it } } ?: return
        providers.filter { it.sourcePlugin == source.path }.forEach { provider ->
            APIHolder.allProviders.remove(provider)
            APIHolder.removePluginMapping(provider)
        }
        host.close()
    }

    /** One per extension name. A published jar wins over conversion, since its author built it. */
    private fun candidates(): List<File> {
        val files = root.listFiles { file ->
            file.isFile && (file.name.endsWith(JAR_SUFFIX) || file.name.endsWith(CS3_SUFFIX))
        }.orEmpty()
        return files.groupBy { it.nameWithoutExtension }.values
            .map { sameName -> sameName.firstOrNull { it.name.endsWith(JAR_SUFFIX) } ?: sameName.first() }
            .sortedBy { it.name }
    }

    private suspend fun loadOne(source: File, launcher: HostLauncher, start: Boolean): SandboxedLoad {
        val verification = withContext(Dispatchers.IO) { ExtensionHash.verify(source, expectedHash(source)) }
        fun refused(why: String, scan: ScanReport? = null, sha256: String? = null, state: LoadState = LoadState.Failed) =
            SandboxedLoad(source.name, verification, scan, 0, "not loaded: $why", sha256, state)

        if (verification is ExtensionVerification.HashMismatch) {
            return refused(
                "published hash ${verification.expected}, file is ${verification.actual}",
                state = LoadState.HashMismatch,
            )
        }
        // First, before anything is written: this directory is what the plugin can change.
        val data = SandboxPolicy.dataDirectory(sandboxes, source.nameWithoutExtension)
            ?: return refused("unsafe file name")
        val sha256 = try {
            withContext(Dispatchers.IO) { sha256(source) }
        } catch (failure: IOException) {
            return refused(failure.message.orEmpty())
        }
        val jar = try {
            toStore(source, sha256, launcher)
        } catch (failure: IllegalStateException) {
            return refused(failure.message.orEmpty(), sha256 = sha256)
        }
        val scan = withContext(Dispatchers.IO) { ExtensionScanner.scan(jar) }
        if (scan.blocked) return refused("blocked by the security scan", scan, sha256, LoadState.Blocked)
        // Off unless this exact file was allowed. No other flag, argument or setting reaches past this.
        // A new version waits too, with warnings or without: a scan that finds nothing is not a review of different code.
        val recorded = withContext(Dispatchers.IO) { decisions.read() }
        val newVersion = recorded.isNewVersion(source.name, sha256)
        val choice = recorded.choiceFor(source.name, sha256)
        when {
            choice == ExtensionChoice.Allow -> Unit
            choice == ExtensionChoice.KeepOff ->
                return SandboxedLoad(source.name, verification, scan, 0, "kept off, as chosen", sha256, LoadState.KeptOff)
            newVersion ->
                return SandboxedLoad(
                    source.name, verification, scan, 0, "HELD: a new version, waiting for a decision", sha256, LoadState.Held,
                    newVersion = true,
                )
            scan.warnings > 0 ->
                return SandboxedLoad(source.name, verification, scan, 0, "HELD: warnings, waiting for a decision", sha256, LoadState.Held)
            // Scan-only writes nothing. A failed write costs noticing the next version, and is not worth refusing this one.
            choice == null && start ->
                withContext(Dispatchers.IO) { runCatching { decisions.record(source.name, sha256, ExtensionChoice.Seen) } }
        }
        if (!start) return SandboxedLoad(source.name, verification, scan, 0, "scanned only", sha256, LoadState.Scanned)

        val (host, started) = PluginHostClient.start(launcher, jar, data, source.nameWithoutExtension)
        val remotes = started.getOrElse { failure ->
            host.close()
            return refused(
                listOf(failure.message.orEmpty(), host.hostOutput.lastLines()).filter { it.isNotBlank() }
                    .joinToString(" | "),
                scan,
                sha256,
            )
        }
        val registered = synchronized(hosts) {
            hosts += host
            running[source.name] = host
            // A result is routed back to its provider by name, so a second plugin claiming a name would
            // receive the first one's URLs and answer for its titles. Whoever registered first keeps it.
            remotes.filter { remote ->
                providers.none { it.name == remote.name }.also { free ->
                    if (free) {
                        val provider = SandboxedProvider(remote, host)
                        provider.sourcePlugin = source.path
                        APIHolder.allProviders.add(provider)
                        APIHolder.addPluginMapping(provider)
                    }
                }
            }
        }
        val how = if (source.name.endsWith(CS3_SUFFIX)) "converted, " else ""
        val refused = remotes.size - registered.size
        val duplicates = if (refused > 0) ", $refused refused: name already taken" else ""
        return SandboxedLoad(
            source.name,
            verification,
            scan,
            registered.size,
            "$how" + "sandboxed$duplicates",
            sha256,
            LoadState.Running,
            chosen = choice == ExtensionChoice.Allow,
        )
    }

    /**
     * @param sha256 what the decision will be bound to, so what lands in the store under that name has to be
     *   those bytes: each path checks its private copy before using it, and a file swapped after being hashed
     *   is refused instead of being stored under the hash of the one before it.
     */
    private suspend fun toStore(source: File, sha256: String, launcher: HostLauncher): File = withContext(Dispatchers.IO) {
        // This order: extensions load in parallel, and mkdirs() is false for whoever comes second.
        check(store.mkdirs() || store.isDirectory) { "cannot create the extension store" }
        // A converted jar is named for its input *and* the converter, or a better converter would never
        // reach a file converted by a previous one: the store is kept across launches.
        val converter = if (source.name.endsWith(CS3_SUFFIX)) CONVERTER_REVISION else ""
        val target = File(store, "$sha256$converter$JAR_SUFFIX")
        if (target.isFile) return@withContext target
        if (source.name.endsWith(CS3_SUFFIX)) {
            val work = File(root, CONVERTING_DIRECTORY)
            check(work.mkdirs() || work.isDirectory) { "cannot create the conversion directory" }
            val input = Files.createTempFile(work.toPath(), "input", CS3_SUFFIX).toFile().canonicalFile
            try {
                copyExpecting(source, input, sha256)
                convertIntoStore(target, work) { output, directory ->
                    conversions.withPermit { PluginHostClient.convert(launcher, input, output, directory) }?.lastLines()
                }
            } finally {
                input.delete()
            }
        } else {
            val staging = File(store, "${target.name}$STAGING_SUFFIX").apply { delete() }
            try {
                copyExpecting(source, staging, sha256)
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                staging.delete()
            }
        }
        target
    }

    private fun expectedHash(file: File): String? = sidecarHash(file)
        ?: if (file.name.endsWith(CS3_SUFFIX)) {
            DesktopExtensionPins.forCs3(file.name)?.cs3Hash
        } else {
            DesktopExtensionPins.forJar(file.name)?.hash
        }

    /** Bounded before reading: this is a file of unknown provenance in a directory the app scans. */
    private fun sidecarHash(file: File): String? {
        val sidecar = File(file.parentFile, file.name + SIDECAR_SUFFIX)
        if (!sidecar.isFile || sidecar.length() > MAX_SIDECAR_BYTES) return null
        return runCatching { sidecar.readText() }.getOrNull()
            ?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Stops every host. Each also halts by itself when this process's end of its pipe closes. */
    fun close() {
        synchronized(hosts) {
            hosts.forEach { it.close() }
            hosts.clear()
        }
    }

    private fun String.lastLines(): String =
        lineSequence().filter { it.isNotBlank() && !it.startsWith(PICKED_UP) }.toList()
            .takeLast(MAX_REPORTED_LINES).joinToString(" / ")

    companion object {
        private const val MAX_PARALLEL_CONVERSIONS = 4
        private const val STORE_DIRECTORY = ".store"
        private const val CONVERTING_DIRECTORY = ".converting"
        private const val SANDBOX_DIRECTORY = ".sandbox"
        private const val JAR_SUFFIX = ".jar"
        private const val CS3_SUFFIX = ".cs3"

        /**
         * Bump when `DexConverter`'s output changes. `-c2`: real names and stack maps (`KMP-18`). `-c3`: stack maps
         * that name real classes (`BUG-18`).
         */
        private const val CONVERTER_REVISION = "-c3"
        private const val SIDECAR_SUFFIX = ".sha256"
        private const val STAGING_SUFFIX = ".part"
        private const val MAX_SIDECAR_BYTES = 1024L
        private const val MAX_REPORTED_LINES = 3
        private const val PICKED_UP = "Picked up JAVA_TOOL_OPTIONS"
        private const val DIRECTORY_ENV = "CLOUDSTREAM4_EXTENSIONS_DIR"

        /** An override must be absolute, so it can never mean "wherever the app was started from". */
        fun defaultDirectory(): File {
            val override = System.getenv(DIRECTORY_ENV)?.trim().orEmpty()
            val directory = if (File(override).isAbsolute) {
                File(override)
            } else {
                File(System.getProperty("user.home") ?: ".", ".cloudstream4/extensions")
            }
            directory.mkdirs()
            return directory
        }
    }
}

/** Copies [source], and fails unless what was copied has the hash it was promised to have. */
internal fun copyExpecting(source: File, copy: File, sha256: String) {
    source.copyTo(copy, overwrite = true)
    check(sha256(copy) == sha256) { "the file changed while it was being read" }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private const val BUFFER_BYTES = 64 * 1024

/** Where an extension ended up. Words are the screen's job, and [SandboxedLoad.outcome] is the log's. */
enum class LoadState {
    /** Its host started. */
    Running,

    /** Passed everything and was not asked to start, because this was a scan. */
    Scanned,

    /** Has warnings and no decision for this exact file. Nothing of it has run. */
    Held,

    /** Has warnings and the person said no. Nothing of it has run. */
    KeptOff,

    /** The scan refused it. No choice changes that. */
    Blocked,

    /** Not the file its published hash describes. No choice changes that. */
    HashMismatch,

    /** It could not be prepared or started. */
    Failed,
}

/** One extension's outcome. A null scan or verification means it never got that far. */
data class SandboxedLoad(
    val fileName: String,
    val verification: ExtensionVerification?,
    val scan: ScanReport?,
    val providers: Int,
    val outcome: String,
    /** What a decision about this file is bound to. Null when it was refused before being read. */
    val sha256: String? = null,
    val state: LoadState = LoadState.Failed,
    /** Held because an earlier file of this name was recorded, and this is not it. */
    val newVersion: Boolean = false,
    /** Someone allowed this exact file, so it can be changed on the review screen even if the scan found nothing. */
    val chosen: Boolean = false,
)

/** What the startup pass did, as data, so the caller decides where the words go. */
data class SandboxReport(
    val directory: File,
    val loads: List<SandboxedLoad>,
    /** The saved decisions failed their check, so everything with warnings was treated as undecided. */
    val decisionsUnreadable: Boolean = false,
) {

    val providers: Int get() = loads.sumOf { it.providers }

    fun lines(): List<String> = buildList {
        add("extensions: ${directory.path}")
        if (decisionsUnreadable) add("extensions: saved decisions could not be read, and were ignored")
        if (loads.isEmpty()) {
            add("extensions: nothing installed. Put a .jar or .cs3 here; each runs in its own sandbox.")
            for (pin in DesktopExtensionPins.all()) add("extensions:   curl -sSLO ${pin.url}")
        }
        for (load in loads) {
            val hash = when (val verification = load.verification) {
                ExtensionVerification.Verified -> "hash verified"
                is ExtensionVerification.HashMismatch -> "HASH MISMATCH"
                is ExtensionVerification.Skipped -> "hash not checked (${verification.reason})"
                null -> "hash not checked"
            }
            val scan = load.scan?.let { report ->
                val blocks = report.findings.count { it.severity == ScanSeverity.Block }
                "scan: ${report.classCount} classes, $blocks blocking, ${report.warnings} warnings"
            } ?: "not scanned"
            add("extensions: ${load.fileName.untrustedText()} — $hash · $scan · ${load.outcome.untrustedText(MAX_OUTCOME_CHARS)} · ${load.providers} provider(s)")
            load.scan?.findings.orEmpty().take(MAX_FINDINGS_PER_FILE).forEach { finding ->
                add("extensions:   ${finding.severity.name.uppercase()} ${finding.rule}: ${finding.detail.untrustedText()}")
            }
            val hidden = (load.scan?.findings?.size ?: 0) - MAX_FINDINGS_PER_FILE
            if (hidden > 0) add("extensions:   and $hidden more")
        }
        val held = loads.count { it.state == LoadState.Held }
        add("extensions: ${loads.count { it.providers > 0 }} of ${loads.size} running, $providers providers, $held held")
    }

    private companion object {
        const val MAX_FINDINGS_PER_FILE = 12
        const val MAX_OUTCOME_CHARS = 2000
    }
}

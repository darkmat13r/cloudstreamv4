package com.lagradost.cloudstream4.extensions

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream4.feature.extensions.ExtensionHash
import com.lagradost.cloudstream4.feature.extensions.ExtensionVerification
import com.lagradost.cloudstream4.feature.extensions.sandbox.ExtensionScanner
import com.lagradost.cloudstream4.feature.extensions.sandbox.HostLauncher
import com.lagradost.cloudstream4.feature.extensions.sandbox.PluginHostClient
import com.lagradost.cloudstream4.feature.extensions.sandbox.SandboxedProvider
import com.lagradost.cloudstream4.feature.extensions.sandbox.ScanReport
import com.lagradost.cloudstream4.feature.extensions.sandbox.ScanSeverity
import java.io.File
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
 *    `.store/<sha256>.jar`. The name is the input's hash, so a conversion happens once. A host can
 *    read this file and cannot write it, so what was scanned is what runs.
 * 3. **Scan.** [ExtensionScanner] in this process, so a plugin cannot forge its own verdict. A
 *    blocking finding refuses the extension.
 * 4. **Run.** A host per extension under Seatbelt, with its own data directory. One extension cannot
 *    read another's cookies, and a crash or a hang takes down one host.
 *
 * Plugin code never runs in this JVM. Where there is no sandbox (anything but macOS today), nothing
 * is loaded at all: [JvmExtensionRuntime][com.lagradost.cloudstream4.feature.extensions.JvmExtensionRuntime]
 * in-process would work and would hand a third-party jar the user's whole account.
 */
class SandboxedExtensions(
    directory: File = defaultDirectory(),
    private val launcher: HostLauncher? = HostLauncher.forThisProcess(),
) {
    private val root = directory.canonicalFile
    private val store = File(root, STORE_DIRECTORY)
    private val sandboxes = File(root, SANDBOX_DIRECTORY)
    private val hosts = mutableListOf<PluginHostClient>()

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
        SandboxReport(root, sources.map { source -> async { loadOne(source, launcher, start) } }.awaitAll())
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
        fun refused(why: String, scan: ScanReport? = null) =
            SandboxedLoad(source.name, verification, scan, 0, "not loaded: $why")

        if (verification is ExtensionVerification.HashMismatch) {
            return refused("published hash ${verification.expected}, file is ${verification.actual}")
        }
        val jar = try {
            toStore(source, launcher)
        } catch (failure: IllegalStateException) {
            return refused(failure.message.orEmpty())
        }
        val scan = withContext(Dispatchers.IO) { ExtensionScanner.scan(jar) }
        if (scan.blocked) return refused("blocked by the security scan", scan)
        if (!start) return SandboxedLoad(source.name, verification, scan, 0, "scanned only")

        val data = File(sandboxes, source.nameWithoutExtension)
        val (host, started) = PluginHostClient.start(launcher, jar, data, source.nameWithoutExtension)
        val remotes = started.getOrElse { failure ->
            host.close()
            return refused(
                listOf(failure.message.orEmpty(), host.hostOutput.lastLines()).filter { it.isNotBlank() }
                    .joinToString(" | "),
                scan,
            )
        }
        synchronized(hosts) { hosts += host }
        remotes.forEach { remote ->
            val provider = SandboxedProvider(remote, host)
            provider.sourcePlugin = source.path
            APIHolder.allProviders.add(provider)
            APIHolder.addPluginMapping(provider)
        }
        val how = if (source.name.endsWith(CS3_SUFFIX)) "converted, " else ""
        return SandboxedLoad(source.name, verification, scan, remotes.size, "${how}sandboxed")
    }

    private suspend fun toStore(source: File, launcher: HostLauncher): File = withContext(Dispatchers.IO) {
        // This order: extensions load in parallel, and mkdirs() is false for whoever comes second.
        check(store.mkdirs() || store.isDirectory) { "cannot create the extension store" }
        val target = File(store, "${sha256(source)}$JAR_SUFFIX")
        if (target.isFile) return@withContext target
        val staging = File(store, "${target.name}$STAGING_SUFFIX").apply { delete() }
        if (source.name.endsWith(CS3_SUFFIX)) {
            conversions.withPermit { PluginHostClient.convert(launcher, source, staging, store) }?.let { failure ->
                staging.delete()
                error("conversion failed: ${failure.lastLines()}")
            }
        } else {
            source.copyTo(staging, overwrite = true)
        }
        Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
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
        private const val SANDBOX_DIRECTORY = ".sandbox"
        private const val JAR_SUFFIX = ".jar"
        private const val CS3_SUFFIX = ".cs3"
        private const val SIDECAR_SUFFIX = ".sha256"
        private const val STAGING_SUFFIX = ".part"
        private const val MAX_SIDECAR_BYTES = 1024L
        private const val BUFFER_BYTES = 64 * 1024
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

/** One extension's outcome. A null scan or verification means it never got that far. */
data class SandboxedLoad(
    val fileName: String,
    val verification: ExtensionVerification?,
    val scan: ScanReport?,
    val providers: Int,
    val outcome: String,
)

/** What the startup pass did, as data, so the caller decides where the words go. */
data class SandboxReport(val directory: File, val loads: List<SandboxedLoad>) {

    val providers: Int get() = loads.sumOf { it.providers }

    fun lines(): List<String> = buildList {
        add("extensions: ${directory.path}")
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
            add("extensions: ${load.fileName} — $hash · $scan · ${load.outcome} · ${load.providers} provider(s)")
            load.scan?.findings.orEmpty().take(MAX_FINDINGS_PER_FILE).forEach { finding ->
                add("extensions:   ${finding.severity.name.uppercase()} ${finding.rule}: ${finding.detail}")
            }
            val hidden = (load.scan?.findings?.size ?: 0) - MAX_FINDINGS_PER_FILE
            if (hidden > 0) add("extensions:   and $hidden more")
        }
        add("extensions: ${loads.count { it.providers > 0 }} of ${loads.size} running, $providers providers")
    }

    private companion object {
        const val MAX_FINDINGS_PER_FILE = 12
    }
}

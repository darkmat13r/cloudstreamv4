package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.io.File

/**
 * The macOS Seatbelt policy a plugin host runs under, and the actual security boundary here.
 *
 * Deny by default, then the least a JVM needs: read its own runtime and classpath, read the one jar
 * it was given, write only inside its own directory. Nothing under `$HOME` is readable except that.
 * It cannot start a process, and cannot listen. A process started under this policy cannot leave it,
 * and everything it spawns would inherit it.
 *
 * `sandbox-exec` is deprecated by Apple but still what the OS's own daemons use, and there is no
 * replacement available to an unsigned app. Enforcement is by the kernel, so unlike the JDK's removed
 * `SecurityManager` it holds against reflection, `Unsafe` and native code alike.
 */
internal object SeatbeltProfile {

    const val SANDBOX_EXEC = "/usr/bin/sandbox-exec"

    /**
     * @param executable the one binary allowed to run: the JVM or the packaged launcher.
     * @param readable directories and jars the JVM needs: its runtime and its classpath.
     * @param readOnlyInputs the extension's own file.
     * @param writable the only directory the process may change.
     * @param network whether outbound traffic is allowed. Conversion has no use for it.
     * @param extraRules whole SBPL rules for a host that needs more, from [mediaHostRules].
     */
    fun render(
        executable: File,
        readable: List<File>,
        readOnlyInputs: List<File>,
        writable: File,
        network: Boolean,
        extraRules: List<String> = emptyList(),
    ): String = buildString {
        appendLine("(version 1)")
        appendLine("(deny default)")
        // Apple's baseline for any process: dyld's shared cache, and little else. It grants nothing
        // under the user's home (measured: `cat ~/.zshrc` under it is "Operation not permitted").
        // Without it dyld aborts in CacheFinder before main, as exit 134 with no output at all.
        appendLine("(import \"system.sb\")")
        appendLine("(allow process-exec (literal ${executable.quoted()}))")
        appendLine("(allow signal (target self))")
        appendLine("(allow process-info* (target self))")
        appendLine("(allow sysctl-read)")
        // stat() only, on any path. Java canonicalises paths and needs it; contents stay unreadable.
        appendLine("(allow file-read-metadata)")
        appendLine("(allow ipc-posix-shm-read* (ipc-posix-name \"apple.shm.notification_center\"))")
        appendLine("(allow mach-lookup")
        MACH_SERVICES.forEach { appendLine("  (global-name \"$it\")") }
        appendLine(")")
        appendLine("(allow file-read*")
        SYSTEM_READS.forEach { appendLine("  (subpath \"$it\")") }
        DEVICE_READS.forEach { appendLine("  (literal \"$it\")") }
        readable.forEach { entry ->
            val kind = if (entry.isDirectory) "subpath" else "literal"
            appendLine("  ($kind ${entry.quoted()})")
        }
        readOnlyInputs.forEach { appendLine("  (literal ${it.quoted()})") }
        appendLine(")")
        appendLine("(allow file-read* file-write* (subpath ${writable.quoted()}))")
        appendLine("(allow file-write* (literal \"/dev/null\"))")
        if (network) {
            appendLine("(allow system-socket)")
            appendLine("(allow network-outbound (remote ip \"*:*\"))")
            appendLine("(allow network-outbound (literal \"/private/var/run/mDNSResponder\"))")
            // Keeps a plugin away from services on this machine, the next-easiest thing to attack.
            appendLine("(deny network-outbound (remote ip \"localhost:*\"))")
        }
        extraRules.forEach(::appendLine)
    }

    /**
     * What the libVLC host needs beyond a plugin host, and nothing that opens the home directory.
     *
     * Each entry was added because a denial stopped playback, read from the system log. Reads VLC's own
     * bundle, and maps its libraries as code. Mapping from [writable] is for the one library JNA unpacks
     * there; a host that can already run code gains nothing from it.
     *
     * @param audio false for a check that must make no sound: the kernel then refuses every route to an
     *   audio device, whatever libVLC is told.
     */
    fun mediaHostRules(vlcApp: File, writable: File, audio: Boolean = true): List<String> = buildList {
        add("(allow file-read* (subpath ${vlcApp.quoted()}))")
        add("(allow file-map-executable (subpath ${vlcApp.quoted()}) (subpath ${writable.quoted()}))")
        // Certificate checks are done by trustd, out of process: without it every https stream fails
        // with -9808. Audio devices are CoreAudio's: without it, "No audio output devices found".
        add("(allow mach-lookup")
        MEDIA_MACH_SERVICES.forEach { add("  (global-name \"$it\")") }
        if (audio) MEDIA_AUDIO_MACH_SERVICES.forEach { add("  (global-name \"$it\")") }
        add(")")
        if (audio) add("(allow ipc-posix-shm-read* ipc-posix-shm-write-data (ipc-posix-name-prefix \"AudioIO\"))")
        add("(allow file-read*")
        MEDIA_READS.forEach { add("  (subpath \"$it\")") }
        if (audio) add("  (subpath \"$MEDIA_AUDIO_READ\")")
        add(")")
    }

    private val MEDIA_MACH_SERVICES = listOf(
        "com.apple.trustd",
        "com.apple.trustd.agent",
        "com.apple.SecurityServer",
        "com.apple.securityd.xpc",
        "com.apple.ocspd",
    )

    private val MEDIA_AUDIO_MACH_SERVICES = listOf(
        "com.apple.audio.coreaudiod",
        "com.apple.audio.audiohald",
        "com.apple.audio.AudioComponentRegistrar",
    )

    /** System trust roots and audio plug-ins. None of it is under the user's home. */
    private val MEDIA_READS = listOf(
        "/Library/Keychains",
        "/Library/Security",
        "/private/var/db/mds",
    )

    private const val MEDIA_AUDIO_READ = "/Library/Audio/Plug-Ins"

    /**
     * [File] as an SBPL string literal, after resolving symlinks.
     *
     * Seatbelt matches resolved paths (`/tmp` never matches, `/private/tmp` does), and these paths
     * can hold whatever a user named a folder, so a quote must not be able to end the literal and
     * start a rule of its own.
     */
    private fun File.quoted(): String {
        val resolved = runCatching { canonicalPath }.getOrDefault(absolutePath)
        require(resolved.none { it.isISOControl() }) { "control character in sandbox path" }
        return "\"" + resolved.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    private val SYSTEM_READS = listOf(
        "/usr/lib",
        "/usr/share",
        "/System/Library",
        "/Library/Preferences/Logging",
        "/private/var/db/timezone",
        "/private/etc",
        "/private/var/db/dyld",
        "/System/Cryptexes",
        "/System/Volumes/Preboot/Cryptexes",
    )

    private val DEVICE_READS = listOf("/dev/random", "/dev/urandom", "/dev/null", "/dev/dtracehelper")

    private val MACH_SERVICES = listOf(
        "com.apple.system.notification_center",
        "com.apple.system.opendirectoryd.libinfo",
        "com.apple.logd",
        "com.apple.diagnosticd",
        "com.apple.SystemConfiguration.configd",
        "com.apple.SystemConfiguration.DNSConfiguration",
    )
}

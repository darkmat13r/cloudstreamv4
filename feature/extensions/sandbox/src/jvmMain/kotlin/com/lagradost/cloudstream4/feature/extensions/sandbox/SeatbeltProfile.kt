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
     * @param gatewayPort with [network], the only port it may connect to, on loopback: an [OutboundGate], or the media
     *   host's gateway (`KMP-113`). Null leaves every address but loopback open.
     * @param resolver whether the host may ask the system resolver. A media host behind its gateway is given only
     *   `127.0.0.1` urls and has no use for it (measured, `docs/plans/media-gateway.md` §9). With [network], it also
     *   brings the system configuration services; without both, no host is given them (`BUG-96`, audit A-10).
     *
     * The certificate services are denied by name for every host (audit A-1; `BUG-96` for the plugin host). `system.sb`,
     * imported above, allows `com.apple.trustd` unconditionally, so leaving them out of this profile's own rules does not
     * deny them. They fetch OCSP responses, CRLs and issuers on a caller's behalf, which would let native code in the
     * host aim a request at an address the kernel refuses the host itself. No host needs them: the JVM's own TLS checks
     * certificates against its own trust store, and the media host behind its gateway speaks plain http.
     */
    fun render(
        executable: File,
        readable: List<File>,
        readOnlyInputs: List<File>,
        writable: File,
        network: Boolean,
        extraRules: List<String> = emptyList(),
        gatewayPort: Int? = null,
        resolver: Boolean = true,
    ): String = buildString {
        appendLine("(version 1)")
        appendLine("(deny default)")
        // Apple's baseline for any process: dyld's shared cache, and little else. It grants nothing
        // under the user's home (measured: `cat ~/.zshrc` under it is "Operation not permitted").
        // Without it dyld aborts in CacheFinder before main, as exit 134 with no output at all.
        appendLine("(import \"system.sb\")")
        appendLine("(allow process-exec (literal ${executable.quoted()}))")
        appendLine("(allow signal (target self))")
        // A deny of everything first, because `system.sb` allows some of this and `(target others)` does not stop it.
        appendLine("(deny process-info*)")
        appendLine("(allow process-info* (target self))")
        // Deny, then allow by name. `system.sb` allows every sysctl, and that is the machine's name, its network cards,
        // and every other process with its command line. Naming those is not enough: reading by number skips a name
        // filter, so a deny of `kern.proc` left it readable. Measured, see `SandboxEscapeAttemptTest`.
        appendLine("(deny sysctl-read)")
        appendLine("(allow sysctl-read")
        READABLE_SYSCTLS.forEach { appendLine("  (sysctl-name \"$it\")") }
        READABLE_SYSCTL_PREFIXES.forEach { appendLine("  (sysctl-name-prefix \"$it\")") }
        appendLine(")")
        // stat() only, on any path. Java canonicalises paths and needs it; contents stay unreadable.
        appendLine("(allow file-read-metadata)")
        appendLine("(allow ipc-posix-shm-read* (ipc-posix-name \"apple.shm.notification_center\"))")
        appendLine("(allow mach-lookup")
        MACH_SERVICES.forEach { appendLine("  (global-name \"$it\")") }
        // The computer's name and its interface addresses are in configd's store, which the sysctl rules above hide
        // otherwise. Only a host that resolves names itself has a use for these (`BUG-96`, audit A-10).
        if (network && resolver) RESOLVER_MACH_SERVICES.forEach { appendLine("  (global-name \"$it\")") }
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
        // Nothing a plugin writes runs as native code: without this, `System.load` of a library it dropped there worked
        // (measured). The kernel holds native code to this policy too, but it reaches far more of the kernel than Java does.
        appendLine("(deny file-map-executable (subpath ${writable.quoted()}))")
        if (network) {
            appendLine("(allow system-socket)")
            if (resolver) appendLine("(allow network-outbound (literal \"/private/var/run/mDNSResponder\"))")
            if (gatewayPort != null) {
                appendLine("(allow network-outbound (remote ip \"localhost:$gatewayPort\"))")
            } else {
                appendLine("(allow network-outbound (remote ip \"*:*\"))")
                // Keeps it away from services on this machine, by that name. Their LAN address still reaches them.
                appendLine("(deny network-outbound (remote ip \"localhost:*\"))")
            }
        }
        extraRules.forEach(::appendLine)
        // Last, so no allow above, the import's included, can win over it. Every host (`BUG-96`).
        appendLine("(deny mach-lookup")
        CERTIFICATE_SERVICES.forEach { appendLine("  (global-name \"$it\")") }
        appendLine(")")
    }

    /** What `system.sb` grants that a host behind the gateway, or one with no network, must not ask (audit A-1). */
    internal val CERTIFICATE_SERVICES = listOf("com.apple.trustd", "com.apple.trustd.agent")

    /**
     * What the libVLC host needs beyond a plugin host, and nothing that opens the home directory.
     *
     * Each entry was added because a denial stopped playback, read from the system log. Reads VLC's own
     * bundle, and maps its libraries as code. Mapping from [writable] is for the one library JNA unpacks
     * there; a host that can already run code gains nothing from it.
     *
     * @param audio false for a check that must make no sound: the kernel then refuses every route to an
     *   audio device, whatever libVLC is told.
     * @param tls whether the host may ask the system's certificate services. A host behind its gateway speaks only plain
     *   http to `127.0.0.1`, and a host that plays a file speaks to nobody, so neither needs them (`KMP-113`). They fetch
     *   on a caller's behalf (OCSP, CRLs, issuers), so a crafted certificate could aim them at an address the kernel
     *   would refuse the host itself.
     */
    fun mediaHostRules(vlcApp: File, writable: File, audio: Boolean = true, tls: Boolean = true): List<String> = buildList {
        add("(allow file-read* (subpath ${vlcApp.quoted()}))")
        add("(allow file-map-executable (subpath ${vlcApp.quoted()}) (subpath ${writable.quoted()}))")
        // Certificate checks are done by trustd, out of process: without it every https stream fails
        // with -9808. Audio devices are CoreAudio's: without it, "No audio output devices found".
        val services = (if (tls) MEDIA_MACH_SERVICES else emptyList()) + (if (audio) MEDIA_AUDIO_MACH_SERVICES else emptyList())
        // Only with names: an `allow mach-lookup` with none would allow every service there is.
        if (services.isNotEmpty()) {
            add("(allow mach-lookup")
            services.forEach { add("  (global-name \"$it\")") }
            add(")")
        }
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

    /** What a JVM and libSystem read about the hardware and the OS, none of which tells this machine from another. */
    private val READABLE_SYSCTLS = listOf(
        "kern.osrelease",
        "kern.ostype",
        "kern.osversion",
        "kern.osproductversion",
        "kern.osproductversioncompat",
        "kern.version",
        "kern.argmax",
        "kern.maxfiles",
        "kern.maxfilesperproc",
        "kern.usrstack64",
        "kern.secure_kernel",
        "kern.safeboot",
        "kern.hv_vmm_present",
        "kern.tcsm_available",
        "kern.tcsm_enable",
        "kern.pagesize",
    )

    private val READABLE_SYSCTL_PREFIXES = listOf("hw.", "machdep.", "vm.", "security.", "sysctl.")

    private val DEVICE_READS = listOf("/dev/random", "/dev/urandom", "/dev/null", "/dev/dtracehelper")

    private val MACH_SERVICES = listOf(
        "com.apple.system.notification_center",
        "com.apple.logd",
        "com.apple.diagnosticd",
    )

    /** Only for a host with the network and the resolver: the plugin host. */
    private val RESOLVER_MACH_SERVICES = listOf(
        "com.apple.SystemConfiguration.configd",
        "com.apple.SystemConfiguration.DNSConfiguration",
    )
}

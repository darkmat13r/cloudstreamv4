package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** How much a finding counts against an extension. */
enum class ScanSeverity {
    /** Worth telling the user about; legitimate plugins do this too. The sandbox contains it. */
    Warn,

    /** No media provider has a reason to do this. The extension is not loaded. */
    Block,
}

/** One thing the scanner saw: which rule, and where. */
data class ScanFinding(
    val severity: ScanSeverity,
    val rule: String,
    val detail: String,
)

/** The scanner's answer for one jar. */
data class ScanReport(
    val findings: List<ScanFinding>,
    val classCount: Int,
    /** Internal names of every class the jar refers to and does not define. */
    val externalClasses: Set<String>,
) {
    val blocked: Boolean get() = findings.any { it.severity == ScanSeverity.Block }
    val warnings: Int get() = findings.count { it.severity == ScanSeverity.Warn }
}

/**
 * Static checks over an extension jar, before any of its code can run.
 *
 * ## What this is for, and what it is not
 *
 * **This is triage. The sandbox is the enforcement.** A static scan of JVM bytecode cannot be
 * complete: reflection, generated strings and a dozen other tricks defeat it, so a clean report
 * means "nothing obvious", never "safe". What it buys is refusing the blatant case early with a
 * reason a person can read, and telling them what a plugin reaches for.
 *
 * ## Parsed by hand, and never class-loaded
 *
 * The class file's constant pool is read directly. `ClassLoader` is not involved, so no static
 * initialiser can run. This runs in the trusted process, so that a plugin cannot forge its own
 * verdict from inside the sandbox, which is why every size here is bounded and any malformed input
 * becomes a [ScanSeverity.Block] rather than an exception.
 *
 * ## `ZipFile`, deliberately
 *
 * `URLClassLoader` resolves entries through the zip's central directory, and `ZipFile` reads the same
 * one. `ZipInputStream` reads local headers, and a crafted zip can make the two views disagree, which
 * would let a scanner inspect different bytes from the ones that get loaded.
 */
object ExtensionScanner {

    fun scan(jar: File): ScanReport {
        val findings = linkedSetOf<ScanFinding>()
        val defined = mutableSetOf<String>()
        val referenced = mutableSetOf<String>()
        var classCount = 0
        try {
            ZipFile(jar).use { zip ->
                var entries = 0
                var total = 0L
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    if (++entries > MAX_ENTRIES) {
                        findings += block("zip-limits", "more than $MAX_ENTRIES entries")
                        break
                    }
                    val name = entry.name
                    if (name.contains("..") || name.startsWith("/") || name.contains('\\')) {
                        findings += block("zip-path", "suspicious path ${clean(name)}")
                    }
                    if (HIDDEN_CODE.containsMatchIn(name)) {
                        findings += block("embedded-code", "bundled native or nested code: ${clean(name)}")
                    }
                    val bytes = zip.getInputStream(entry).use { it.readNBytes(MAX_ENTRY_BYTES + 1) }
                    total += bytes.size
                    if (bytes.size > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
                        findings += block("zip-limits", "decompresses past the size limit at ${clean(name)}")
                        break
                    }
                    val looksLikeClass = bytes.size >= CLASS_MAGIC.size &&
                        CLASS_MAGIC.indices.all { bytes[it] == CLASS_MAGIC[it] }
                    when {
                        // Every `.class` entry, META-INF/versions/ included: a multi-release jar can
                        // otherwise hide code from anything that skips that directory.
                        name.endsWith(".class") -> {
                            classCount++
                            scanClass(name, bytes, findings, defined, referenced)
                        }
                        looksLikeClass -> findings += block("embedded-code", "class bytes in ${clean(name)}")
                    }
                }
            }
        } catch (bad: ZipException) {
            findings += block("unreadable", "not a readable jar: ${clean(bad.message.orEmpty())}")
        } catch (bad: IOException) {
            findings += block("unreadable", "could not be read: ${clean(bad.message.orEmpty())}")
        }
        if (classCount == 0 && findings.none { it.severity == ScanSeverity.Block }) {
            findings += block("empty", "no classes in ${clean(jar.name)}")
        }
        return ScanReport(findings.toList(), classCount, referenced - defined)
    }

    private fun scanClass(
        entryName: String,
        bytes: ByteArray,
        findings: MutableSet<ScanFinding>,
        defined: MutableSet<String>,
        referenced: MutableSet<String>,
    ) {
        val parsed = try {
            ClassFacts.read(bytes)
        } catch (bad: IOException) {
            findings += block("malformed-class", "${clean(entryName)}: ${clean(bad.message.orEmpty())}")
            return
        } catch (bad: IndexOutOfBoundsException) {
            findings += block("malformed-class", "${clean(entryName)}: ${clean(bad.message.orEmpty())}")
            return
        }
        defined += parsed.thisClass
        referenced += parsed.classes
        val where = parsed.thisClass

        if (parsed.declaresNative) findings += block("native-method", "$where declares a native method")
        // `defineClass` is protected, so inside a subclass the call's owner is the subclass and no
        // method rule can match it. Being one is the only reason to subclass, so that is what is refused.
        if (parsed.superClass in LOADER_CLASSES) {
            findings += block("class-loader", "$where extends ${parsed.superClass}")
        }

        for (cls in parsed.classes) {
            BLOCKED_CLASS_PREFIXES.firstOrNull { cls.startsWith(it) }?.let { prefix ->
                findings += block("forbidden-api", "$where uses ${prefix.trimEnd('/')}")
            }
            WARN_CLASS_PREFIXES.entries.firstOrNull { cls.startsWith(it.key) }?.let { (_, rule) ->
                findings += ScanFinding(ScanSeverity.Warn, rule, "$where uses $cls")
            }
        }
        for ((owner, method) in parsed.methods) {
            val key = "$owner.$method"
            if (key in BLOCKED_METHODS) findings += block("forbidden-api", "$where calls $key")
            WARN_METHODS[key]?.let { rule -> findings += ScanFinding(ScanSeverity.Warn, rule, "$where calls $key") }
        }
        for (text in parsed.strings) {
            BLOCKED_STRINGS.firstOrNull { text.contains(it) }?.let { needle ->
                findings += block("suspicious-string", "$where mentions \"$needle\"")
            }
            WARN_STRINGS.firstOrNull { text.contains(it) }?.let { needle ->
                findings += ScanFinding(ScanSeverity.Warn, "suspicious-string", "$where mentions \"$needle\"")
            }
            if (text in TELEMETRY_KEYS) {
                findings += ScanFinding(ScanSeverity.Warn, "telemetry", "$where names a device identifier: \"$text\"")
            }
            encodedCode(text)?.let { what -> findings += block("encoded-code", "$where holds $what, base64-encoded") }
        }
    }

    /**
     * What a long base64 constant decodes to, when that is code: the rest of this scan cannot read code
     * that is only text until it runs. Measured on 53 sampled plugins (`KMP-18`): this fires on exactly
     * the two that ship dozens of encoded scripts, and not on the one other long base64 string.
     *
     * The decoded bytes are only ever matched. A constant is at most 64 KB, by the class-file format.
     */
    private fun encodedCode(text: String): String? {
        if (text.length < MIN_ENCODED_CHARS || !text.all { it in BASE64_ALPHABET }) return null
        val standard = text.filterNot { it.isWhitespace() }.replace('-', '+').replace('_', '/')
        val bytes = runCatching { Base64.getDecoder().decode(standard) }.getOrNull() ?: return null
        val magic = ENCODED_BINARY_MAGIC.firstOrNull { (head, _) ->
            bytes.size >= head.size && head.indices.all { bytes[it] == head[it] }
        }
        return when {
            magic != null -> magic.second
            ENCODED_SCRIPT.containsMatchIn(String(bytes, Charsets.ISO_8859_1)) -> "a script"
            else -> null
        }
    }

    private fun block(rule: String, detail: String) = ScanFinding(ScanSeverity.Block, rule, detail)

    /** A jar's own strings end up in a report someone reads in a terminal. */
    private fun clean(text: String): String =
        text.take(MAX_REPORTED_CHARS).filter { it.code in PRINTABLE_ASCII }

    private val CLASS_MAGIC = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
    private val PRINTABLE_ASCII = 0x20..0x7E
    private const val MAX_REPORTED_CHARS = 160
    private const val MAX_ENTRIES = 20_000
    private const val MAX_ENTRY_BYTES = 16 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 128L * 1024 * 1024
    private val HIDDEN_CODE = Regex("(?i)\\.(so|dylib|jnilib|dll|exe|dex|jar|apk|cs3|sh)$")
    private const val MIN_ENCODED_CHARS = 256
    private val BASE64_ALPHABET = (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('+', '/', '-', '_', '=', '\r', '\n')).toSet()
    private val ENCODED_SCRIPT = Regex("function\\s*\\(|function\\s+\\w|=>|\\bconst\\s+\\w|\\bvar\\s+\\w|\\bfetch\\(|\\beval\\(|\\brequire\\(|^#!/")
    private val ENCODED_BINARY_MAGIC = listOf(
        byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()) to "a class file",
        "dex\n".toByteArray() to "a dex file",
        "PK".toByteArray() to "a zip",
    )
    private val LOADER_CLASSES = setOf("java/lang/ClassLoader", "java/security/SecureClassLoader", "java/net/URLClassLoader")
    private val TELEMETRY_KEYS = setOf("installId", "install_id", "deviceId", "device_id", "androidId", "android_id")

    /** Starting processes, loading code the scan never saw, or reaching past the JVM's own checks. */
    private val BLOCKED_CLASS_PREFIXES = listOf(
        "java/lang/ProcessBuilder",
        "java/lang/ProcessHandle",
        "java/lang/instrument/",
        "java/lang/foreign/",
        "java/rmi/",
        "java/io/ObjectInputStream",
        "java/net/URLClassLoader",
        "java/security/SecureClassLoader",
        "javax/naming/",
        "javax/script/",
        "sun/",
        "jdk/internal/",
        "com/sun/",
        "dalvik/system/",
    )

    private val BLOCKED_METHODS = setOf(
        "java/lang/Runtime.exec",
        "java/lang/Runtime.load",
        "java/lang/Runtime.loadLibrary",
        "java/lang/System.load",
        "java/lang/System.loadLibrary",
        "java/lang/System.setSecurityManager",
        "java/lang/ClassLoader.defineClass",
        "java/lang/invoke/MethodHandles\$Lookup.defineClass",
        "java/lang/invoke/MethodHandles\$Lookup.defineHiddenClass",
        // What else is on the device is nobody's business, and no site needs to know.
        "android/content/pm/PackageManager.getInstalledPackages",
        "android/content/pm/PackageManager.getInstalledApplications",
    )

    /** Text that names a blocked API is how reflection reaches it, and miners have no other excuse. */
    private val BLOCKED_STRINGS = listOf(
        "java.lang.ProcessBuilder",
        "java.lang.Runtime",
        // The class references below block these directly, and only text reaches them by reflection.
        "java.lang.ProcessHandle",
        "java.net.URLClassLoader",
        "java.security.SecureClassLoader",
        "java.lang.foreign.",
        "java.lang.instrument.",
        "javax.script.",
        "jdk.internal.",
        "defineClass",
        "sun.misc.Unsafe",
        "dalvik.system.",
        "stratum+tcp",
        "stratum+ssl",
        "xmrig",
    )

    private val WARN_STRINGS = listOf(
        "/bin/sh",
        "/bin/bash",
        "/etc/passwd",
        ".ssh/",
        "Library/Keychains",
        "LaunchAgents",
        "Cookies.binarycookies",
        "osascript",
    )

    private val WARN_CLASS_PREFIXES = mapOf(
        "java/lang/reflect/" to "reflection",
        "java/lang/ClassLoader" to "classloader-access",
        "java/io/File" to "filesystem",
        "java/io/RandomAccessFile" to "filesystem",
        "java/nio/file/" to "filesystem",
        "java/net/Socket" to "raw-socket",
        "java/net/ServerSocket" to "raw-socket",
        "java/net/DatagramSocket" to "raw-socket",
        "java/nio/channels/" to "raw-socket",
        "org/mozilla/javascript/" to "dynamic-script",
        "android/webkit/" to "webview",
    )

    private val WARN_METHODS = mapOf(
        "java/lang/Class.forName" to "reflection",
        "java/lang/System.getenv" to "environment",
        "java/lang/System.getProperty" to "environment",
        "java/lang/System.getProperties" to "environment",
        "java/lang/System.exit" to "exit",
        "java/lang/Runtime.exit" to "exit",
        "java/lang/Runtime.halt" to "exit",
    )
}

/**
 * The parts of one class file the rules look at.
 *
 * Follows JVMS §4 and reads only as far as the methods table, which is where `ACC_NATIVE` lives.
 */
internal class ClassFacts private constructor(
    val thisClass: String,
    /** Null only for `java/lang/Object`, which no extension defines. */
    val superClass: String?,
    val classes: Set<String>,
    val methods: Set<Pair<String, String>>,
    val strings: Set<String>,
    val declaresNative: Boolean,
) {
    companion object {
        private const val UTF8 = 1
        private const val INTEGER = 3
        private const val FLOAT = 4
        private const val LONG = 5
        private const val DOUBLE = 6
        private const val CLASS = 7
        private const val STRING = 8
        private const val FIELD_REF = 9
        private const val METHOD_REF = 10
        private const val INTERFACE_METHOD_REF = 11
        private const val NAME_AND_TYPE = 12
        private const val METHOD_HANDLE = 15
        private const val METHOD_TYPE = 16
        private const val DYNAMIC = 17
        private const val INVOKE_DYNAMIC = 18
        private const val MODULE = 19
        private const val PACKAGE = 20
        private const val ACC_NATIVE = 0x0100
        private const val MAGIC = 0xCAFEBABE.toInt()
        private val DESCRIPTOR_CLASS = Regex("L([^;]+);")

        /** @throws IOException on anything that is not a well-formed class file. */
        fun read(bytes: ByteArray): ClassFacts {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            if (input.readInt() != MAGIC) throw IOException("bad magic")
            input.readUnsignedShort() // minor
            input.readUnsignedShort() // major

            val count = input.readUnsignedShort()
            val utf8 = arrayOfNulls<String>(count)
            val classIndex = mutableMapOf<Int, Int>()
            val stringIndex = mutableListOf<Int>()
            val methodRefs = mutableListOf<Pair<Int, Int>>()
            val nameAndType = mutableMapOf<Int, Pair<Int, Int>>()
            val descriptorIndex = mutableListOf<Int>()

            var slot = 1
            while (slot < count) {
                when (val tag = input.readUnsignedByte()) {
                    UTF8 -> utf8[slot] = input.readUTF()
                    INTEGER, FLOAT -> input.readInt()
                    LONG, DOUBLE -> {
                        input.readLong()
                        slot++ // JVMS 4.4.5: these take two constant-pool slots.
                    }
                    CLASS -> classIndex[slot] = input.readUnsignedShort()
                    STRING -> stringIndex += input.readUnsignedShort()
                    FIELD_REF -> {
                        input.readUnsignedShort()
                        input.readUnsignedShort()
                    }
                    METHOD_REF, INTERFACE_METHOD_REF ->
                        methodRefs += input.readUnsignedShort() to input.readUnsignedShort()
                    NAME_AND_TYPE ->
                        nameAndType[slot] = input.readUnsignedShort() to input.readUnsignedShort()
                    METHOD_HANDLE -> {
                        input.readUnsignedByte()
                        input.readUnsignedShort()
                    }
                    METHOD_TYPE -> descriptorIndex += input.readUnsignedShort()
                    DYNAMIC, INVOKE_DYNAMIC -> {
                        input.readUnsignedShort()
                        input.readUnsignedShort()
                    }
                    MODULE, PACKAGE -> input.readUnsignedShort()
                    else -> throw IOException("unknown constant-pool tag $tag")
                }
                slot++
            }

            fun text(index: Int): String =
                utf8.getOrNull(index) ?: throw IOException("constant $index is not text")
            fun className(index: Int): String =
                text(classIndex[index] ?: throw IOException("constant $index is not a class"))

            input.readUnsignedShort() // access
            val thisClass = className(input.readUnsignedShort())
            val superClass = input.readUnsignedShort().takeIf { it != 0 }?.let(::className)
            repeat(input.readUnsignedShort()) { input.readUnsignedShort() } // interfaces
            repeat(input.readUnsignedShort()) { skipMember(input) } // fields

            var declaresNative = false
            repeat(input.readUnsignedShort()) {
                if (input.readUnsignedShort() and ACC_NATIVE != 0) declaresNative = true
                input.readUnsignedShort()
                input.readUnsignedShort()
                skipAttributes(input)
            }

            val classes = mutableSetOf<String>()
            classIndex.values.forEach { index -> classes += text(index).arrayElement() }
            (nameAndType.values.map { it.second } + descriptorIndex).forEach { index ->
                DESCRIPTOR_CLASS.findAll(text(index)).forEach { classes += it.groupValues[1] }
            }
            val methods = methodRefs.mapTo(mutableSetOf()) { (owner, nat) ->
                val pair = nameAndType[nat] ?: throw IOException("constant $nat is not a name and type")
                className(owner) to text(pair.first)
            }
            return ClassFacts(
                thisClass = thisClass,
                superClass = superClass,
                classes = classes,
                methods = methods,
                strings = stringIndex.mapTo(mutableSetOf()) { text(it) },
                declaresNative = declaresNative,
            )
        }

        private fun skipMember(input: DataInputStream) {
            input.readUnsignedShort()
            input.readUnsignedShort()
            input.readUnsignedShort()
            skipAttributes(input)
        }

        private fun skipAttributes(input: DataInputStream) {
            repeat(input.readUnsignedShort()) {
                input.readUnsignedShort()
                val length = input.readInt()
                if (length < 0) throw IOException("attribute longer than the class file")
                input.readNBytes(length).also {
                    if (it.size != length) throw IOException("truncated attribute")
                }
            }
        }

        /** `[[Lfoo/Bar;` as `foo/Bar`, because a Class constant may name an array type. */
        private fun String.arrayElement(): String =
            if (startsWith("[")) DESCRIPTOR_CLASS.find(this)?.groupValues?.get(1) ?: this else this
    }
}

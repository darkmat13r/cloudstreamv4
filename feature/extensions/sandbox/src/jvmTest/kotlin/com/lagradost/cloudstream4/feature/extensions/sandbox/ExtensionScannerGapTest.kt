package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The patterns `KMP-18`'s review of real plugins found the scanner passing, each with a control. */
class ExtensionScannerGapTest {
    private lateinit var directory: File

    @BeforeTest
    fun setUp() {
        directory = File.createTempFile("scan-gap", "").apply { delete(); mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun scanClass(bytes: ByteArray, name: String = "fixture/Thing"): ScanReport {
        val file = File(directory, "fixture.jar")
        ZipOutputStream(file.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("$name.class"))
            out.write(bytes)
            out.closeEntry()
        }
        return ExtensionScanner.scan(file)
    }

    private fun scanType(type: Class<*>) =
        scanClass(type.classLoader.getResourceAsStream(type.name.replace('.', '/') + ".class")!!.use { it.readBytes() })

    private fun ScanReport.blockedBy(rule: String) = findings.any { it.severity == ScanSeverity.Block && it.rule == rule }
    private fun ScanReport.warnedBy(rule: String) = findings.any { it.severity == ScanSeverity.Warn && it.rule == rule }

    /** A class file holding just [methodRefs] and [strings], for a call whose owner no shim declares. */
    private fun classFile(methodRefs: List<Pair<String, String>> = emptyList(), strings: List<String> = emptyList(), superName: String = "java/lang/Object"): ByteArray {
        val pool = ByteArrayOutputStream()
        val out = DataOutputStream(pool)
        var next = 1
        fun utf(text: String): Int { out.writeByte(1); out.writeUTF(text); return next++ }
        fun cls(name: String): Int { val u = utf(name); out.writeByte(7); out.writeShort(u); return next++ }
        val self = cls("fixture/Thing")
        val parent = cls(superName)
        methodRefs.forEach { (owner, method) ->
            val o = cls(owner)
            val n = utf(method)
            val d = utf("()V")
            out.writeByte(12); out.writeShort(n); out.writeShort(d)
            val nt = next++
            out.writeByte(10); out.writeShort(o); out.writeShort(nt); next++
        }
        strings.forEach { text ->
            val u = utf(text)
            out.writeByte(8); out.writeShort(u); next++
        }
        val file = ByteArrayOutputStream()
        DataOutputStream(file).apply {
            writeInt(0xCAFEBABE.toInt()); writeShort(0); writeShort(50)
            writeShort(next); write(pool.toByteArray())
            writeShort(0x21); writeShort(self); writeShort(parent)
            repeat(4) { writeShort(0) }
        }
        return file.toByteArray()
    }

    @Test
    fun `a subclass of ClassLoader is refused, since its defineClass call names itself`() {
        assertTrue(scanType(OwnLoader::class.java).blockedBy("class-loader"))
        assertFalse(scanClass(classFile()).blocked, "the control class is clean")
    }

    @Test
    fun `naming a code-loading API as text is refused, the way reflection reaches it`() {
        listOf("java.net.URLClassLoader", "java.lang.ProcessHandle", "jdk.internal.misc.Unsafe", "javax.script.ScriptEngine", "defineClass").forEach { name ->
            assertTrue(scanClass(classFile(strings = listOf(name))).blockedBy("suspicious-string"), name)
        }
        assertFalse(scanClass(classFile(strings = listOf("java.util.ArrayList", "ClassLoading a page"))).blocked)
    }

    @Test
    fun `listing installed packages is refused, and asking about one package is not`() {
        val pm = "android/content/pm/PackageManager"
        assertTrue(scanClass(classFile(methodRefs = listOf(pm to "getInstalledPackages"))).blockedBy("forbidden-api"))
        assertTrue(scanClass(classFile(methodRefs = listOf(pm to "getInstalledApplications"))).blockedBy("forbidden-api"))
        assertFalse(scanClass(classFile(methodRefs = listOf(pm to "getPackageInfo"))).blocked)
    }

    @Test
    fun `a script hidden in base64 is refused`() {
        assertTrue(scanClass(classFile(strings = listOf(ENCODED_SCRIPT))).blockedBy("encoded-code"))
        // Wrapped, and in the URL-safe alphabet: the same bytes, the same answer.
        val wrapped = ENCODED_SCRIPT.chunked(76).joinToString("\n").replace('+', '-').replace('/', '_')
        assertTrue(scanClass(classFile(strings = listOf(wrapped))).blockedBy("encoded-code"))
    }

    @Test
    fun `a class file or dex hidden in base64 is refused`() {
        listOf(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()), "dex\n035\u0000".toByteArray(), "PK\u0003\u0004".toByteArray()).forEach { head ->
            val encoded = java.util.Base64.getEncoder().encodeToString(head + ByteArray(400) { (it * 7).toByte() })
            assertTrue(scanClass(classFile(strings = listOf(encoded))).blockedBy("encoded-code"), encoded.take(12))
        }
    }

    @Test
    fun `other long base64, and short or ordinary text, is left alone`() {
        val cases = listOf(ENCODED_OPAQUE, ENCODED_SCRIPT.take(200), "const fetch( eval( ".repeat(40), "https://example.invalid/".repeat(20))
        cases.forEach { text ->
            val report = scanClass(classFile(strings = listOf(text)))
            assertFalse(report.blocked, report.findings.toString())
        }
    }

    @Test
    fun `an exit and a device identifier are worth telling someone about`() {
        assertTrue(scanClass(classFile(methodRefs = listOf("java/lang/Runtime" to "exit"))).warnedBy("exit"))
        val report = scanClass(classFile(strings = listOf("installId")))
        assertTrue(report.warnedBy("telemetry"))
        assertFalse(report.blocked)
        assertFalse(scanClass(classFile(strings = listOf("install it now"))).warnedBy("telemetry"))
    }

    @Test
    fun `no finding ever reports the content it matched`() {
        val report = scanClass(classFile(strings = listOf(ENCODED_SCRIPT)))
        assertEquals(1, report.findings.size)
        assertFalse(report.findings.single().detail.contains("Y29uc3Q"), report.findings.single().detail)
    }

    class OwnLoader : ClassLoader()

    private companion object {
        const val ENCODED_SCRIPT = "Y29uc3QgcHJvdmlkZXIgPSAneCc7CmZ1bmN0aW9uIHNlYXJjaChxKSB7IHJldHVybiBmZXRjaCgnaHR0cHM6Ly9leGFtcGxlLmludmFsaWQvP3E9JyArIHEpOyB9CmNvbnN0IHByb3ZpZGVyID0gJ3gnOwpmdW5jdGlvbiBzZWFyY2gocSkgeyByZXR1cm4gZmV0Y2goJ2h0dHBzOi8vZXhhbXBsZS5pbnZhbGlkLz9xPScgKyBxKTsgfQpjb25zdCBwcm92aWRlciA9ICd4JzsKZnVuY3Rpb24gc2VhcmNoKHEpIHsgcmV0dXJuIGZldGNoKCdodHRwczovL2V4YW1wbGUuaW52YWxpZC8/cT0nICsgcSk7IH0KY29uc3QgcHJvdmlkZXIgPSAneCc7CmZ1bmN0aW9uIHNlYXJjaChxKSB7IHJldHVybiBmZXRjaCgnaHR0cHM6Ly9leGFtcGxlLmludmFsaWQvP3E9JyArIHEpOyB9CmNvbnN0IHByb3ZpZGVyID0gJ3gnOwpmdW5jdGlvbiBzZWFyY2gocSkgeyByZXR1cm4gZmV0Y2goJ2h0dHBzOi8vZXhhbXBsZS5pbnZhbGlkLz9xPScgKyBxKTsgfQpjb25zdCBwcm92aWRlciA9ICd4JzsKZnVuY3Rpb24gc2VhcmNoKHEpIHsgcmV0dXJuIGZldGNoKCdodHRwczovL2V4YW1wbGUuaW52YWxpZC8/cT0nICsgcSk7IH0K"
        const val ENCODED_OPAQUE = "C1Sd5i94wQpTnOUud8AJUpvkLXa/CFGa4yx1vgdQmeIrdL0GT5jhKnO8BU6X4ClyuwRNlt8ocboDTJXeJ3C5AkuU3SZvuAFKk9wlbrcASZLbJG22/0iR2iNstf5HkNkia7T9Ro/YIWqz/EWO1yBpsvtEjdYfaLH6Q4zVHmew+UKL1B1mr/hBitMcZa73QInSG2St9j+I0RpjrPU+h9AZYqv0PYbPGGGq8zyFzhdgqfI7hM0WX6jxOoPMFV6n8DmCyxRdpu84gcoTXKXuN4DJEluk7TZ/yBFao+w1fscQWaLrNH3GD1ih6jN8xQ5XoOkye8QNVp/oMXrDDFWe5zB5wgtUneYveMEKU5zlLnfACVKb5C12vwhRmuMsdb4HUJniK3S9Bk+Y4SpzvAVOl+ApcrsETZbfKHG6A0yV3idwuQJLlN0mb7gBSpPcJW63AEmS2yRttv9IkdojbLX+R5DZImu0/UaP2CFqs/xFjtcgabL7RI3WH2ix+kOM1R5nsPlCi9QdZq/4QYrTHGWu90CJ0htkrfY/iNEaY6z1PofQGWKr9D2GzxhhqvM8hc4XYKnyO4TNFl+o8TqDzBVep/A5gssUXabvOIHKE1yl7jeAyRJbpO02f8gRWqPsNX7HEFmi6zR9xg9YoeozfMUOV6DpMnvEDVaf6DF6wwxVnucwecILVJ3mL3jBClOc5S53wAlSm+Qtdr8IUZrjLHW+B1CZ4it0vQZPmOEqc7wFTpfgKXK7BE2W3yhxugNMld4ncLkCS5TdJm+4AUqT3CVutwBJktskbbb/SJHa"
    }
}

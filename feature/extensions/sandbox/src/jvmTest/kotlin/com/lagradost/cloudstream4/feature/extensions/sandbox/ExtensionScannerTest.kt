package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtensionScannerTest {
    private lateinit var directory: File

    @BeforeTest
    fun setUp() {
        directory = File.createTempFile("scan-test", "").apply { delete(); mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun jar(vararg entries: Pair<String, ByteArray>): File {
        val file = File(directory, "fixture.jar")
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    private fun bytesOf(type: Class<*>): ByteArray =
        type.classLoader.getResourceAsStream(type.name.replace('.', '/') + ".class")!!.use { it.readBytes() }

    private fun classEntry(type: Class<*>) = type.name.replace('.', '/') + ".class" to bytesOf(type)

    private fun ScanReport.rules(severity: ScanSeverity) =
        findings.filter { it.severity == severity }.map { it.rule }.toSet()

    @Test
    fun `an ordinary provider is clean`() {
        val report = ExtensionScanner.scan(jar(classEntry(Ordinary::class.java)))
        assertFalse(report.blocked, report.findings.toString())
        assertEquals(1, report.classCount)
    }

    @Test
    fun `starting a process is refused`() {
        val report = ExtensionScanner.scan(jar(classEntry(HostileApi::class.java)))
        assertTrue(report.blocked)
        assertTrue(report.findings.any { "ProcessBuilder" in it.detail }, report.findings.toString())
    }

    @Test
    fun `a native method is refused`() {
        assertTrue("native-method" in ExtensionScanner.scan(jar(classEntry(NativeFixture::class.java))).rules(ScanSeverity.Block))
    }

    @Test
    fun `naming a blocked API to reach it by reflection is refused`() {
        val report = ExtensionScanner.scan(jar(classEntry(ReflectiveFixture::class.java)))
        assertTrue("suspicious-string" in report.rules(ScanSeverity.Block), report.findings.toString())
        assertTrue("reflection" in report.rules(ScanSeverity.Warn), report.findings.toString())
    }

    @Test
    fun `native libraries and nested code are refused`() {
        listOf("libevil.dylib", "evil.so", "lib/inner.jar", "payload.dex", "run.sh").forEach { name ->
            val report = ExtensionScanner.scan(jar(classEntry(Ordinary::class.java), name to byteArrayOf(1, 2, 3)))
            assertTrue("embedded-code" in report.rules(ScanSeverity.Block), name)
        }
    }

    @Test
    fun `class bytes under another name are refused`() {
        val report = ExtensionScanner.scan(
            jar(classEntry(Ordinary::class.java), "assets/logo.png" to bytesOf(HostileApi::class.java))
        )
        assertTrue("embedded-code" in report.rules(ScanSeverity.Block))
    }

    @Test
    fun `a class hidden in a multi-release directory is still scanned`() {
        val hidden = "META-INF/versions/17/" + classEntry(HostileApi::class.java).first
        val report = ExtensionScanner.scan(
            jar(classEntry(Ordinary::class.java), hidden to bytesOf(HostileApi::class.java))
        )
        assertTrue(report.blocked, report.findings.toString())
    }

    @Test
    fun `malformed input is a refusal and never an exception`() {
        val valid = bytesOf(Ordinary::class.java)
        val cases = listOf(
            byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()),
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            valid.copyOf(valid.size / 2),
            valid.copyOf(valid.size / 8),
        )
        cases.forEach { bytes ->
            assertTrue(ExtensionScanner.scan(jar("broken/Thing.class" to bytes)).blocked)
        }
        val notAZip = File(directory, "garbage.jar").apply { writeBytes(ByteArray(64) { 7 }) }
        assertTrue("unreadable" in ExtensionScanner.scan(notAZip).rules(ScanSeverity.Block))
    }

    @Test
    fun `a decompression bomb stops at the limit`() {
        val report = ExtensionScanner.scan(jar("bomb.bin" to ByteArray(17 * 1024 * 1024)))
        assertTrue("zip-limits" in report.rules(ScanSeverity.Block))
    }

    @Test
    fun `a jar with no classes is refused`() {
        assertTrue("empty" in ExtensionScanner.scan(jar("readme.txt" to byteArrayOf(1))).rules(ScanSeverity.Block))
    }

    @Test
    fun `control characters in an entry name never reach the report`() {
        val report = ExtensionScanner.scan(jar("evil\u001B[2J.dylib" to byteArrayOf(1)))
        assertTrue(report.findings.all { finding -> finding.detail.none { it.isISOControl() } })
    }

    /** Stands in for a plain provider: strings, a list, no platform reach. */
    class Ordinary {
        fun titles(query: String): List<String> = listOf(query.trim(), "result")
    }
}

class SandboxPolicyTest {
    private val root = File.createTempFile("policy", "").apply { delete(); mkdirs() }.canonicalFile

    @Test
    fun `an extension name cannot leave the sandboxes directory`() {
        // `...jar`, `..jar` and `.jar` give these names, and `..` would be the extensions root.
        listOf("..", ".", "", ".hidden", "a..b", "a/b", "a b", "a\u001Bb", "x".repeat(65)).forEach { name ->
            kotlin.test.assertNull(SandboxPolicy.dataDirectory(root, name), name)
        }
        assertEquals(File(root, "YoutubeProvider"), SandboxPolicy.dataDirectory(root, "YoutubeProvider"))
    }

    @Test
    fun `a symlink at the target is refused`() {
        val elsewhere = File.createTempFile("outside", "").apply { delete(); mkdirs() }
        java.nio.file.Files.createSymbolicLink(File(root, "Linked").toPath(), elsewhere.toPath())
        kotlin.test.assertNull(SandboxPolicy.dataDirectory(root, "Linked"))
    }
}

/** Real bypasses found in review. Literal IPs, so these need no DNS. */
class PlayableUrlTest {
    private val publicHost = "http://8.8.8.8"

    @Test
    fun `an ordinary long signed URL passes, including an escape straddling the old limit`() {
        val query = "x".repeat(2040) + "%20" + "y".repeat(3000)
        assertTrue(SandboxPolicy.isPublicHttpUrl("$publicHost/v?sig=$query"))
    }

    @Test
    fun `padding past the old check length cannot hide the host`() {
        val padded = "http://8.8.8.8:" + "0".repeat(3000) + "80@127.0.0.1:9999/x"
        assertFalse(SandboxPolicy.isPublicHttpUrl(padded))
    }

    @Test
    fun `userinfo is refused, because the two parsers can disagree about it`() {
        assertFalse(SandboxPolicy.isPublicHttpUrl("http://8.8.8.8@127.0.0.1/x"))
        assertFalse(SandboxPolicy.isPublicHttpUrl("http://user:pw@8.8.8.8/x"))
    }

    @Test
    fun `a URL longer than anything checked is refused`() {
        assertFalse(SandboxPolicy.isPublicHttpUrl("$publicHost/" + "a".repeat(SandboxPolicy.MAX_URL_CHARS)))
    }

    @Test
    fun `audio cannot smuggle more inputs into libVLC's hash-separated list`() {
        assertTrue(SandboxPolicy.isPublicAudioUrl("$publicHost/a.m4a"))
        listOf("#file:///Users/me/x", "#/Users/me/x", "#http://10.0.0.1/").forEach { tail ->
            assertFalse(SandboxPolicy.isPublicAudioUrl("$publicHost/a.m4a$tail"), tail)
        }
    }
}

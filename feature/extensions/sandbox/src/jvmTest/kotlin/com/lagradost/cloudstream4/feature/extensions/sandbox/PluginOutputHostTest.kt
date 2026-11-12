package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * `BUG-83`, through a real host under a real Seatbelt policy: what a plugin writes to stdout, stderr and `Log` never
 * reaches [PluginHostClient.hostSummary], and reaches [PluginHostClient.redactedLog] only redacted.
 *
 * Skips where there is no `sandbox-exec`, as the other host tests do.
 */
class PluginOutputHostTest {
    private lateinit var root: File
    private lateinit var data: File

    @BeforeTest
    fun setUp() {
        root = File.createTempFile("plugin-output-test", "").apply { delete(); mkdirs() }.canonicalFile
        data = File(root, "data").apply { mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private val leaked = listOf(
        "SECRET_QUERY", "hunter2", "/watch", "Zq8Xv3Lm9Pt2Rk7Wd4Hs6Nb1Yc", "192.0.2.44", "viewer.one", "sample-user",
    )

    private fun jar(name: String, vararg classes: Class<*>): File {
        val target = File(root, "store/$name.jar").apply { parentFile.mkdirs() }
        JarOutputStream(target.outputStream()).use { out ->
            classes.forEach { type ->
                val path = type.name.replace('.', '/') + ".class"
                out.putNextEntry(JarEntry(path))
                type.classLoader.getResourceAsStream(path)!!.use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return target
    }

    @Test
    fun `a running plugin's own lines reach only the redacted log`() {
        val launcher = HostLauncher.forThisProcess() ?: return
        runBlocking {
            withTimeout(60_000) {
                val hostile = jar("hostile", HostilePlugin::class.java, HostileApi::class.java)
                val (client, providers) = PluginHostClient.start(launcher, hostile, data, "hostile")
                try {
                    providers.getOrElse { throw AssertionError("host: ${client.redactedLog}", it) }
                    assertEquals("leaked", client.search("Hostile", "leak:", 1).items.single().name)
                } finally {
                    client.close()
                }
                assertTrue(Regex("stderr: \\d+ lines, \\d+ bytes").matches(client.hostSummary), client.hostSummary)
                val log = client.redactedLog
                // Positive controls: the plugin's lines were read, through each road, and kept in their redacted form.
                assertTrue("stdout GET https://media.example/<…>" in log, log)
                assertTrue("stderr token <…> from <ip>" in log, log)
                assertTrue("DEBUG Hostile: log <email> at <path>" in log, log)
                leaked.forEach { secret ->
                    assertFalse(secret in client.hostSummary, "$secret in the summary")
                    assertFalse(secret in log, "$secret in $log")
                }
            }
        }
    }

    @Test
    fun `a plugin that fails to load is reported by its class and counts, and nothing it said`() {
        val launcher = HostLauncher.forThisProcess() ?: return
        runBlocking {
            withTimeout(60_000) {
                val leaky = jar("leaky", LeakyPlugin::class.java)
                val (client, providers) = PluginHostClient.start(launcher, leaky, data, "leaky")
                client.close()
                val failure = assertNotNull(providers.exceptionOrNull(), "a plugin that throws in load must not start")
                // The host reports a failed load over the protocol, so the class comes from there, not from stderr.
                assertEquals("IllegalStateException", assertIs<PluginFailed>(failure).reportedClass)
                val summary = client.hostSummary
                assertTrue(Regex("stderr: \\d+ lines, \\d+ bytes").matches(summary), summary)
                leaked.forEach { secret ->
                    assertFalse(secret in summary, "$secret in $summary")
                    assertFalse(secret in failure.message.orEmpty(), "$secret in ${failure.message}")
                    assertFalse(secret in client.redactedLog, "$secret in ${client.redactedLog}")
                }
            }
        }
    }
}

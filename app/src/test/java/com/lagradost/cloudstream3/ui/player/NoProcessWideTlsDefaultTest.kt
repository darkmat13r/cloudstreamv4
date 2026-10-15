package com.lagradost.cloudstream3.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards BUG-10: `:app` must never install a process-wide TLS default.
 *
 * `CS3IPlayer` used to call `HttpsURLConnection.setDefaultSSLSocketFactory` with an
 * [SSLTrustManager] and `setDefaultHostnameVerifier` with an accept-anything lambda. Those two
 * setters are JVM-wide and nothing unset them, so one playback disabled certificate and hostname
 * verification for the rest of the process's life, in every build including release.
 *
 * The replacement for that behaviour already exists and is per-client: `insecureApp`, behind the
 * `@UnsafeSSL` opt-in. Neither setter has a legitimate caller in this module, so this scans the
 * module's own sources rather than trying to drive the player.
 *
 * The scan deliberately fails if it cannot find the source tree, so that a change in the test's
 * working directory makes it red rather than vacuously green.
 */
class NoProcessWideTlsDefaultTest {

    private companion object {
        /** Candidate locations of `:app`'s main sources, relative to plausible working directories. */
        val SOURCE_ROOTS = listOf("src/main/java", "app/src/main/java", "../app/src/main/java")

        /** The JVM-wide setters. Both are static on `javax.net.ssl.HttpsURLConnection`. */
        val BANNED = listOf("setDefaultSSLSocketFactory", "setDefaultHostnameVerifier")
    }

    private fun sourceRoot(): File {
        val root = SOURCE_ROOTS.map(::File).firstOrNull { it.isDirectory }
        requireNotNull(root) {
            "Could not find :app's main sources from ${File(".").absolutePath}; " +
                "tried $SOURCE_ROOTS. Fix the path rather than deleting this test."
        }
        return root
    }

    @Test
    fun `the source scan actually reads the module`() {
        val kotlinFiles = sourceRoot().walkTopDown().filter { it.extension == "kt" }.toList()
        // A sanity floor: :app had well over a thousand Kotlin files when this was written.
        assertTrue(
            "Found only ${kotlinFiles.size} Kotlin files; the scan is not reading :app",
            kotlinFiles.size > 100,
        )
    }

    @Test
    fun `no file installs a process-wide SSL socket factory or hostname verifier`() {
        val offenders = sourceRoot().walkTopDown()
            .filter { it.extension == "kt" || it.extension == "java" }
            .flatMap { file ->
                val text = file.readText()
                BANNED.filter { text.contains(it) }.map { "${file.path}: $it" }
            }
            .toList()

        assertEquals(
            "BUG-10: these install a JVM-wide TLS default, which disables verification for the " +
                "whole process and is never undone. Use insecureApp behind @UnsafeSSL instead.",
            emptyList<String>(),
            offenders,
        )
    }
}

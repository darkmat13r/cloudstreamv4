package com.lagradost.cloudstream3

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.io.PrintStream
import java.nio.file.Files

/**
 * BUG-44: the crash file (`filesDir/last_error`), which people attach to bug reports, keeps the
 * trace's classes, frames and url hosts, and nothing from a url's path or query, nor a user path.
 *
 * It writes the file the way [ExceptionHandler.uncaughtException] does, through
 * [ExceptionHandler.writeReport]. The handler itself cannot run on the JVM: it reads
 * `PluginManager.currentlyLoading`, and `PluginManager`'s initialiser fails without Android's
 * external storage (`ExceptionInInitializerError`, measured).
 */
class CrashFileRedactionTest {

    private val markers = listOf("MARKER_PATH", "MARKER_QUERY", "MARKER_USER")

    @Test
    fun `a fatal exception whose messages hold a url and a path leaves no marker in last_error`() {
        val dir = Files.createTempDirectory("filesDir").toFile()
        val lastError = File(dir, "last_error")
        val cause = FileNotFoundException(
            "/storage/emulated/0/MARKER_USER/MARKER_PATH.mkv: open failed: EACCES (Permission denied)"
        )
        val fatal = IllegalStateException("player failed on https://host.example/MARKER_PATH?t=MARKER_QUERY", cause)

        PrintStream(lastError).use { ps ->
            ExceptionHandler(lastError) {}.writeReport(ps, "SomeExtension", "main", 2L, fatal)
        }
        val text = lastError.readText()
        dir.deleteRecursively()

        markers.forEach { assertFalse("'$it' leaked into last_error:\n$text", text.contains(it)) }
        assertTrue(text, text.startsWith("Currently loading extension: SomeExtension\nFatal exception on thread main (2)\n"))
        assertTrue(text, text.contains("java.lang.IllegalStateException: player failed on https://host.example/<…>"))
        assertTrue(text, text.contains("Caused by: java.io.FileNotFoundException: <path>: open failed: EACCES"))
        assertTrue(text, text.contains("at com.lagradost.cloudstream3.CrashFileRedactionTest"))
    }
}

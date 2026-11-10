package com.lagradost.cloudstream4.extensions.repositories

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `KMP-110` (`#334` review): a repository URL can carry a token, and a save error can carry the home directory. */
class RepositoryRedactionTest {
    private val url = "https://files.example/harbour/repo.json?token=tok_made_up_8f2c"

    @Test
    fun `nothing holding a repository URL prints it`() {
        listOf(
            RepositoryLink.Https(url).toString(),
            SavedRepository(url, "Harbour Lights").toString(),
            InstalledFromRepository("f.cs3", url, "Lanternfish", 3, "a".repeat(64), verified = true).toString(),
        ).forEach { printed ->
            assertFalse("tok_made_up" in printed, printed)
            assertFalse("files.example" in printed, printed)
        }
        assertTrue("Harbour Lights" in SavedRepository(url, "Harbour Lights").toString())
    }

    @Test
    fun `a save that fails names no path`() {
        val home = Files.createTempDirectory("redaction").toFile().canonicalFile
        try {
            // A file where its directory should be: the save cannot create the directory.
            val blocker = File(home, "settings").apply { writeText("in the way") }
            val failure = assertFailsWith<IOException> {
                RepositoryStore(File(blocker, "extensions.repositories")).add(SavedRepository(url, "Harbour Lights"))
            }
            val printed = failure.stackTraceToString()
            assertFalse(home.path in printed, printed)
            assertFalse(home.name in printed, printed)
        } finally {
            home.deleteRecursively()
        }
    }
}

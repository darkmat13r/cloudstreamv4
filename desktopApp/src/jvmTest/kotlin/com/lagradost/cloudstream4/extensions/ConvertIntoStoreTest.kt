package com.lagradost.cloudstream4.extensions

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The conversion's process is assumed taken over, so each `convert` here does what such a host could. */
class ConvertIntoStoreTest {

    private lateinit var root: File
    private lateinit var store: File
    private lateinit var work: File
    private lateinit var target: File

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("convert-test").toFile().canonicalFile
        store = File(root, ".store").apply { mkdirs() }
        work = File(root, ".converting")
        target = File(store, "abc-c2.jar")
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun run(convert: (File, File) -> String?) = runBlocking {
        convertIntoStore(target, work) { output, directory -> convert(output, directory) }
    }

    @Test
    fun `the host is given an empty directory outside the store, and the result lands in the store`() {
        File(store, "other.jar").writeText("another extension")
        run { output, directory ->
            assertEquals(emptyList(), directory.list()!!.toList(), "nothing to tamper with")
            assertFalse(directory.path.startsWith(store.path), "the store is out of reach: $directory")
            assertEquals(directory, output.parentFile)
            output.writeText("converted")
            null
        }
        assertEquals("converted", target.readText())
        assertEquals(emptyList(), work.list()!!.toList(), "nothing left behind")
        assertEquals(setOf("abc-c2.jar", "other.jar"), store.list()!!.toSet())
    }

    @Test
    fun `a link in place of the result is refused, so a file the host chose never enters the store`() {
        val secret = File(root, "secret").apply { writeText("private") }
        assertFailsWith<IllegalStateException> {
            run { output, _ ->
                Files.createSymbolicLink(output.toPath(), secret.toPath())
                null
            }
        }
        assertFalse(target.exists(), "nothing in the store")
        assertEquals("private", secret.readText(), "and the clean-up does not follow the link")
    }

    @Test
    fun `a directory in place of the result is refused`() {
        assertFailsWith<IllegalStateException> {
            run { output, _ ->
                output.mkdirs()
                null
            }
        }
        assertFalse(target.exists())
    }

    @Test
    fun `a hard link the host kept cannot change the jar after it reaches the store`() {
        val kept = File(root, "kept")
        run { output, _ ->
            output.writeText("scanned")
            Files.createLink(kept.toPath(), output.toPath())
            null
        }
        kept.writeText("swapped after the scan")
        assertEquals("scanned", target.readText())
    }

    @Test
    fun `a failed conversion says why and leaves nothing behind`() {
        val failure = assertFailsWith<IllegalStateException> {
            run { output, _ ->
                output.writeText("half a jar")
                "dex2jar fell over"
            }
        }
        assertTrue("dex2jar fell over" in failure.message.orEmpty(), failure.message.orEmpty())
        assertFalse(target.exists())
        assertEquals(emptyList(), work.list()!!.toList())
        assertEquals(emptyList(), store.list()!!.toList())
    }
}

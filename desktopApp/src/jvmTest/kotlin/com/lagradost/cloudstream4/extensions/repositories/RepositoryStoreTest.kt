package com.lagradost.cloudstream4.extensions.repositories

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `KMP-110`: the repositories a person added, kept to them alone. */
class RepositoryStoreTest {
    private lateinit var root: File
    private lateinit var file: File
    private val harbour = SavedRepository("https://files.example/harbour/repo.json?token=made-up", "Harbour Lights")
    private val saltRoad = SavedRepository("https://files.example/salt-road/repo.json", "Salt Road")

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("repositories").toFile().canonicalFile
        file = File(root, "extensions.repositories")
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun mode(): Set<PosixFilePermission> = Files.getPosixFilePermissions(file.toPath())

    @Test
    fun `the file is the person's alone`() {
        RepositoryStore(file).add(harbour)
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), mode())
    }

    @Test
    fun `a file left readable by others is taken back to 0600 on the next write`() {
        RepositoryStore(file).add(harbour)
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
        RepositoryStore(file).add(saltRoad)
        assertEquals(PosixFilePermissions.fromString("rw-------"), mode())
    }

    @Test
    fun `added, kept in order, once each, and removed`() {
        val store = RepositoryStore(file)
        assertTrue(store.add(harbour))
        assertTrue(store.add(saltRoad))
        assertFalse(store.add(harbour.copy(name = "Renamed")), "the same URL twice")
        assertEquals(listOf(harbour, saltRoad), RepositoryStore(file).read().repositories)
        assertTrue(store.remove(harbour.url))
        assertFalse(store.remove(harbour.url))
        assertEquals(listOf(saltRoad), RepositoryStore(file).read().repositories)
    }

    @Test
    fun `no file is no repositories, and readable`() {
        val read = RepositoryStore(file).read()
        assertEquals(emptyList(), read.repositories)
        assertFalse(read.unreadable)
    }

    @Test
    fun `a file it did not write is unreadable, and is not overwritten`() {
        listOf("not json", "{\"format\":2,\"repositories\":[]}", "{\"format\":1}", "[]").forEach { content ->
            file.writeText(content)
            val store = RepositoryStore(file)
            assertTrue(store.read().unreadable, content)
            assertFailsWith<IOException>(content) { store.add(harbour) }
            assertFailsWith<IOException>(content) { store.remove(harbour.url) }
            assertEquals(content, file.readText(), "left as it was")
        }
    }

    @Test
    fun `a link in its place is not read`() {
        val elsewhere = File(root, "elsewhere.json")
        RepositoryStore(elsewhere).add(harbour)
        Files.createSymbolicLink(file.toPath(), elsewhere.toPath())
        assertTrue(RepositoryStore(file).read().unreadable)
    }

    @Test
    fun `a write that fails before its move leaves the old file`() {
        RepositoryStore(file).add(harbour)
        val before = file.readBytes()
        assertFailsWith<IllegalStateException> {
            RepositoryStore(file, beforeMove = { error("stopped") }).add(saltRoad)
        }
        assertContentEquals(before, file.readBytes())
        assertEquals(listOf("extensions.repositories"), root.list()?.sorted(), "no staging file left")
    }

    @Test
    fun `it lives beside the extensions directory, not in it`() {
        val extensions = File(root, "extensions").apply { mkdirs() }
        assertEquals(File(root, "extensions.repositories"), RepositoryStore.fileFor(extensions))
    }
}

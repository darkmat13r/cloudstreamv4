package com.lagradost.cloudstream4.extensions.repositories

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `KMP-110c`: the last list read from a repository, kept as bytes and read back as untrusted. All names are made up. */
class RepositoryCacheTest {
    private lateinit var directory: File
    private val url = "https://files.example/harbour/repo.json?token=tok_made_up_8f2c"

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("cache").toFile().canonicalFile
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun plugin(name: String, url: String = "https://files.example/$name.cs3") =
        """{"internalName":"$name","name":"$name","url":"$url","version":1,"status":1,"apiVersion":1,"authors":[]}"""

    private fun read(vararg lists: String?): RepositoryRead.Read {
        val repository = """{"name":"Harbour Lights","manifestVersion":1,"pluginLists":[${lists.indices.joinToString(",") { "\"https://files.example/l$it.json\"" }}]}"""
        return checkNotNull(RepositoryReader.parse(RawRepository(repository.toByteArray(), lists.map { it?.toByteArray() })))
    }

    private fun only() = directory.listFiles()!!.single()

    @Test
    fun `a list comes back as it was read, with when`() {
        RepositoryCache(directory).write(url, read("[${plugin("Lanternfish")}]", null), readAtMillis = 1_700_000_000_000)
        val cached = assertNotNull(RepositoryCache(directory).read(url))
        assertEquals(1_700_000_000_000, cached.readAtMillis)
        assertEquals(listOf("Lanternfish"), cached.read.listings.map { it.internalName })
        assertEquals(1, cached.read.unreadableLists, "a list that did not arrive stays one that did not")
    }

    @Test
    fun `the file is named by a hash, holds no URL, and is the person's alone`() {
        RepositoryCache(directory).write(url, read("[]"), readAtMillis = 1)
        val file = only()
        assertTrue(Regex("[0-9a-f]{64}\\.json").matches(file.name), file.name)
        val bytes = file.readText()
        assertFalse("tok_made_up" in bytes)
        assertFalse("harbour/repo.json" in bytes)
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file.toPath()))
    }

    @Test
    fun `what is read back goes through the parser again`() {
        RepositoryCache(directory).write(url, read("[${plugin("Lanternfish")}]"), readAtMillis = 1)
        // Edit the cached list on disk to name an http download: a live read would drop it, so a cached one does too.
        val file = only()
        val text = file.readText()
        val list = Regex("\"lists\":\\[\"([^\"]+)\"]").find(text)!!.groupValues[1]
        val edited = "[${plugin("Lanternfish")},${plugin("Mooring", url = "http://files.example/m.cs3")}]"
        file.writeText(text.replace(list, Base64.getEncoder().encodeToString(edited.toByteArray())))
        assertEquals(listOf("Lanternfish"), RepositoryCache(directory).read(url)?.read?.listings?.map { it.internalName })
    }

    @Test
    fun `a damaged cache reads as no cache, never as a failure`() {
        RepositoryCache(directory).write(url, read("[]"), readAtMillis = 1)
        val file = only()
        val good = file.readText()
        // A valid repository, padded past the live cap: read live it would be refused, so cached it is no cache.
        val padded = """{"name":"Harbour Lights","manifestVersion":1,"pluginLists":["https://files.example/l0.json"]}""" +
            " ".repeat(RepositoryLimits.MAX_REPOSITORY_BYTES.toInt())
        val big = Base64.getEncoder().encodeToString(padded.toByteArray())
        listOf(
            "not json",
            good.take(good.length / 2),
            good.replace("\"format\":1", "\"format\":2"),
            good.replace("\"readAtMillis\":1", "\"readAtMillis\":-1"),
            good.replace(Regex("\"repository\":\"[^\"]+\""), "\"repository\":\"!!not base64!!\""),
            good.replace(Regex("\"repository\":\"[^\"]+\""), "\"repository\":\"$big\""),
            good.replace("\"lists\":[", "\"lists\":[null,"),
        ).forEach { damaged ->
            file.writeText(damaged)
            assertNull(RepositoryCache(directory).read(url), damaged.take(60))
        }
        file.delete()
        Files.createSymbolicLink(file.toPath(), File(directory, "elsewhere").toPath())
        assertNull(RepositoryCache(directory).read(url))
    }

    @Test
    fun `removing forgets it`() {
        val cache = RepositoryCache(directory)
        cache.write(url, read("[]"), readAtMillis = 1)
        cache.remove(url)
        assertNull(cache.read(url))
        assertEquals(0, directory.list()!!.size)
    }
}

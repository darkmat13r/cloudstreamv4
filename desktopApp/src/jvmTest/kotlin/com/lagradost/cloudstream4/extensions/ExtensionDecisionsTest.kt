package com.lagradost.cloudstream4.extensions

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtensionDecisionsTest {
    private lateinit var root: File
    private lateinit var file: File
    private val first = "a".repeat(64)
    private val second = "b".repeat(64)

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("decisions").toFile().canonicalFile
        file = File(root, "extensions.decisions")
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun assertUndecidedAndUnreadable(what: String) {
        val snapshot = ExtensionDecisions(file).read()
        assertTrue(snapshot.unreadable, "$what: should be unreadable")
        assertNull(snapshot.choiceFor("A.jar", first), "$what: must not read as a decision")
    }

    @Test
    fun `no file is undecided, and not a failure`() {
        val snapshot = ExtensionDecisions(file).read()
        assertFalse(snapshot.unreadable)
        assertNull(snapshot.choiceFor("A.jar", first))
    }

    @Test
    fun `a decision holds for that name and that hash, and for nothing else`() {
        val store = ExtensionDecisions(file)
        store.record("A.jar", first, ExtensionChoice.Allow)
        val snapshot = store.read()
        assertEquals(ExtensionChoice.Allow, snapshot.choiceFor("A.jar", first))
        assertNull(snapshot.choiceFor("A.jar", second), "an update asks again")
        assertNull(snapshot.choiceFor("B.jar", first), "the same bytes under another name are another plugin")
        assertFalse(snapshot.unreadable)
    }

    @Test
    fun `keep off is remembered, and can be changed, and survives a new instance`() {
        ExtensionDecisions(file).record("A.jar", first, ExtensionChoice.KeepOff)
        assertEquals(ExtensionChoice.KeepOff, ExtensionDecisions(file).read().choiceFor("A.jar", first))
        ExtensionDecisions(file).record("A.jar", first, ExtensionChoice.Allow)
        assertEquals(ExtensionChoice.Allow, ExtensionDecisions(file).read().choiceFor("A.jar", first))
    }

    @Test
    fun `deciding about a new hash forgets the old one, so going back asks again`() {
        val store = ExtensionDecisions(file)
        store.record("A.jar", first, ExtensionChoice.Allow)
        store.record("A.jar", second, ExtensionChoice.Allow)
        assertNull(store.read().choiceFor("A.jar", first))
        assertEquals(ExtensionChoice.Allow, store.read().choiceFor("A.jar", second))
    }

    @Test
    fun `one plugin's decision leaves another's alone`() {
        val store = ExtensionDecisions(file)
        store.record("A.jar", first, ExtensionChoice.Allow)
        store.record("B.cs3", second, ExtensionChoice.KeepOff)
        assertEquals(ExtensionChoice.Allow, store.read().choiceFor("A.jar", first))
        assertEquals(ExtensionChoice.KeepOff, store.read().choiceFor("B.cs3", second))
    }

    @Test
    fun `every way of damaging the file reads as undecided, never approved`() {
        ExtensionDecisions(file).record("A.jar", first, ExtensionChoice.KeepOff)
        val good = file.readText()

        val damaged = mapOf(
            "empty" to "",
            "truncated" to good.substring(0, good.length / 2),
            "no final newline" to good.removeSuffix("\n"),
            "keep-off flipped to allow" to good.replace("keep-off", "allow"),
            "hash swapped" to good.replace(first, second),
            "checksum removed" to good.lineSequence().filterNot { it.startsWith("checksum") }.joinToString("\n"),
            "other version" to good.replace("decisions 1", "decisions 2"),
            "line added" to good.replaceFirst("\n", "\nallow\t$first\tZ.jar\n"),
            "garbage" to "allow\n\u0000‮",
        )
        damaged.forEach { (what, text) ->
            file.writeText(text)
            assertUndecidedAndUnreadable(what)
        }

        file.writeBytes(ByteArray(2 * 1024 * 1024) { 'a'.code.toByte() })
        assertUndecidedAndUnreadable("far too long")

        // Correctly checksummed, and still not what this class writes.
        file.delete()
        val forged = "cloudstream4-extension-decisions 1\nallow\t$first\tA.jar\nallow\t$second\tA.jar\n"
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(forged.toByteArray())
            .joinToString("") { "%02x".format(it) }
        file.writeText("${forged}checksum\t$digest\n")
        assertUndecidedAndUnreadable("two decisions for one name")
    }

    @Test
    fun `a link in place of the file does not count, even to a valid file`() {
        val elsewhere = File(root, "elsewhere").also { ExtensionDecisions(it).record("A.jar", first, ExtensionChoice.Allow) }
        Files.createSymbolicLink(file.toPath(), elsewhere.toPath())
        assertUndecidedAndUnreadable("symlink")
    }

    @Test
    fun `a directory in place of the file reads as undecided, and saving over it fails out loud`() {
        file.mkdirs()
        assertUndecidedAndUnreadable("directory")
        assertFailsWith<IOException> { ExtensionDecisions(file).record("A.jar", first, ExtensionChoice.Allow) }
        assertEquals(listOf("extensions.decisions"), root.list()!!.toList(), "and no staging file is left")
    }

    @Test
    fun `choosing again over a damaged file leaves a good one holding only the new choice`() {
        ExtensionDecisions(file).record("A.jar", first, ExtensionChoice.Allow)
        file.writeText("damaged")
        ExtensionDecisions(file).record("B.jar", second, ExtensionChoice.KeepOff)
        val snapshot = ExtensionDecisions(file).read()
        assertFalse(snapshot.unreadable)
        assertEquals(ExtensionChoice.KeepOff, snapshot.choiceFor("B.jar", second))
        assertNull(snapshot.choiceFor("A.jar", first))
    }

    /** `KMP-110b` review: the damaged file is replaced, and kept first, so nothing is destroyed. */
    @Test
    fun `a damaged file is kept aside, byte for byte and 0600, before it is replaced, and only the latest is kept`() {
        val damaged = ExtensionDecisions.damagedFileFor(file)
        ExtensionDecisions(file).record("A.jar", first, ExtensionChoice.Allow)
        val once = file.readBytes() + "damaged once".toByteArray()
        file.writeBytes(once)
        ExtensionDecisions(file).record("B.jar", second, ExtensionChoice.KeepOff)
        assertContentEquals(once, damaged.readBytes())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(damaged.toPath()))

        val twice = "damaged twice".toByteArray()
        file.writeBytes(twice)
        ExtensionDecisions(file).record("A.jar", first, ExtensionChoice.Allow)
        assertContentEquals(twice, damaged.readBytes(), "the one copy is the latest")
        assertEquals(listOf("extensions.decisions", "extensions.decisions.damaged"), root.list()!!.sorted())

        ExtensionDecisions(file).record("B.jar", second, ExtensionChoice.Allow)
        assertContentEquals(twice, damaged.readBytes(), "a readable file is not copied")
    }

    @Test
    fun `a failure before the move leaves the old file exactly as it was, and nothing beside it`() {
        ExtensionDecisions(file).record("A.jar", first, ExtensionChoice.KeepOff)
        val before = file.readBytes()
        val failing = ExtensionDecisions(file, beforeMove = {
            assertEquals(2, root.list()!!.size, "the new content goes to a file of its own: ${root.list()!!.toList()}")
            assertContentEquals(before, file.readBytes(), "and the real file is untouched until the move")
            throw IOException("disk went away")
        })
        assertFailsWith<IOException> { failing.record("A.jar", first, ExtensionChoice.Allow) }
        assertContentEquals(before, file.readBytes())
        assertEquals(listOf("extensions.decisions"), root.list()!!.toList())
    }

    @Test
    fun `a staging file left by a crash means nothing`() {
        ExtensionDecisions(file).record("A.jar", first, ExtensionChoice.KeepOff)
        File(root, ".extensions.decisions123.part").writeText("allow\t$first\tA.jar\n")
        assertEquals(ExtensionChoice.KeepOff, ExtensionDecisions(file).read().choiceFor("A.jar", first))
    }

    /** With a real file and a real second reader: a torn write would be read as a damaged file. */
    @Test
    fun `a reader never sees half a write`() {
        val names = List(200) { "Extension$it.jar" }
        val store = ExtensionDecisions(file)
        // Long enough that a write in place could not be finished by one call.
        names.forEach { store.record(it, first, ExtensionChoice.Allow) }
        val stop = AtomicBoolean(false)
        val torn = java.util.concurrent.atomic.AtomicInteger()
        // A second instance, because instances share nothing but the file.
        val reader = thread {
            val other = ExtensionDecisions(file)
            while (!stop.get()) if (other.read().unreadable) torn.incrementAndGet()
        }
        repeat(ATOMIC_WRITES) { index ->
            store.record(names[index % names.size], if (index % 2 == 0) first else second, ExtensionChoice.Allow)
        }
        stop.set(true)
        reader.join()
        assertEquals(0, torn.get(), "reads that saw a partial file")
    }

    @Test
    fun `only this user can read or write it`() {
        ExtensionDecisions(file).record("A.jar", first, ExtensionChoice.Allow)
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(file.toPath()),
        )
    }

    @Test
    fun `it lives beside the extensions directory, not in it`() {
        val extensions = File(root, "extensions").apply { mkdirs() }
        val decisions = ExtensionDecisions.fileFor(extensions)
        assertEquals(root, decisions.parentFile)
        assertFalse(decisions.canonicalPath.startsWith(extensions.canonicalPath + File.separator))
    }

    private companion object {
        const val ATOMIC_WRITES = 300
    }
}

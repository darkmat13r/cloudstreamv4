package com.lagradost.cloudstream4.player

import com.sun.jna.Memory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `KMP-74`: what the app opens for the media host, and how it hands the descriptor over. Against the real kernel: the
 * structure layouts in [UnixFds] are written by hand, so only a real call shows they are right.
 */
class LocalMediaTest {
    private val base: File = Files.createTempDirectory("local-media").toFile()
    private val root = File(base, "Downloads").apply { mkdirs() }
    private val series = File(root, "Lantern Bay").apply { mkdirs() }
    private val bytes = ByteArray(4096) { (it % 251).toByte() }
    private val episode = File(series, "S01E01.mp4").apply { writeBytes(bytes) }
    private val opened = mutableListOf<AutoCloseable>()

    @AfterTest
    fun cleanUp() {
        opened.forEach { it.close() }
        base.deleteRecursively()
    }

    private fun open(file: File, under: File = root) = LocalMedia.open(file, under)?.also { opened += it }

    @Test
    fun aRegularFileInsideTheRootOpensReadOnlyAndClosesOnce() {
        val media = assertNotNull(open(episode))
        assertEquals(episode.toPath().toRealPath().toString(), UnixFds.pathOf(media.fd))
        assertTrue(UnixFds.isRegularFile(media.fd))
        // Read-only: writing through a duplicate of it is refused.
        assertFailsWith<java.io.IOException> { FileOutputStream("/dev/fd/${media.fd}").use { it.write(1) } }
        media.close()
        media.close()
        assertFailsWith<IllegalStateException> { media.fd }
    }

    @Test
    fun aFileOutsideTheRootOrNamedThroughDotDotIsRefused() {
        val outside = File(base, "Elsewhere.mp4").apply { writeBytes(bytes) }
        assertNull(open(outside))
        assertNull(open(File(series, "../../Elsewhere.mp4")))
        assertNull(open(root), "the root itself is not a file inside it")
        assertNull(open(File(series, "missing.mp4")))
    }

    @Test
    fun aLinkAtAnyStepBelowTheRootIsRefused() {
        val outside = File(base, "Elsewhere.mp4").apply { writeBytes(bytes) }
        val link = File(series, "S01E02.mp4")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertNull(open(link), "a link to a file outside")
        val inner = File(series, "S01E03.mp4")
        Files.createSymbolicLink(inner.toPath(), episode.toPath())
        assertNull(open(inner), "a link even to a file inside")
        val linkedFolder = File(root, "Mirror")
        Files.createSymbolicLink(linkedFolder.toPath(), series.toPath())
        assertNull(open(File(linkedFolder, "S01E01.mp4")), "a linked folder on the way")
    }

    @Test
    fun aFolderIsRefused() {
        val folder = File(series, "S01E04.mp4").apply { mkdirs() }
        assertNull(open(folder))
        assertNull(open(series))
    }

    @Test
    fun aFifoIsRefusedWithoutWaitingForAWriter() {
        val fifo = File(series, "S01E06.mp4")
        val made = ProcessBuilder("/usr/bin/mkfifo", fifo.path).start()
        assertEquals(0, made.waitFor())
        // O_NONBLOCK: a plain open of a FIFO with no writer would wait here for ever.
        assertNull(open(fifo))
    }

    @Test
    fun aRootReachedThroughALinkStillWorks() {
        // As `/tmp` is a link to `/private/tmp`: the root may be named through one; below it, nothing may be.
        val alias = File(base, "Alias")
        Files.createSymbolicLink(alias.toPath(), root.toPath())
        assertNotNull(open(File(alias, "Lantern Bay/S01E01.mp4"), under = alias))
    }

    @Test
    fun theOpenCallsRefuseALinkAndSayWhatTheyOpened() {
        val link = File(series, "S01E05.mp4")
        Files.createSymbolicLink(link.toPath(), episode.toPath())
        assertNull(UnixFds.openReadOnly(link.path), "O_NOFOLLOW")
        val folder = assertNotNull(UnixFds.openReadOnly(series.path))
        try {
            assertFalse(UnixFds.isRegularFile(folder))
        } finally {
            UnixFds.close(folder)
        }
    }

    @Test
    fun aDescriptorSentAcrossASocketPairArrivesAndReadsTheSameBytes() {
        val media = assertNotNull(open(episode))
        val (app, host) = assertNotNull(UnixFds.socketPair())
        try {
            assertTrue(UnixFds.send(app, media.fd))
            media.close()
            val received = assertNotNull(UnixFds.receive(host))
            try {
                assertContentEquals(bytes, FileInputStream("/dev/fd/$received").use { it.readBytes() })
                assertTrue(UnixFds.isRegularFile(received))
            } finally {
                UnixFds.close(received)
            }
        } finally {
            UnixFds.close(app)
            UnixFds.close(host)
        }
    }

    @Test
    fun twoDescriptorsInOneMessageAreRefusedAndBothClosed() {
        val first = assertNotNull(open(episode))
        val second = assertNotNull(open(episode))
        val (app, host) = assertNotNull(UnixFds.socketPair())
        try {
            assertTrue(UnixFds.send(app, intArrayOf(first.fd, second.fd)))
            val before = openDescriptors()
            assertNull(UnixFds.receive(host))
            // Both arrived as new descriptors in this process; neither may be left open.
            assertEquals(before, openDescriptors(), "every descriptor received was closed")
        } finally {
            UnixFds.close(app)
            UnixFds.close(host)
        }
    }

    @Test
    fun theKernelRefusesTwoControlMessagesInOneSend() {
        val first = assertNotNull(open(episode))
        val second = assertNotNull(open(episode))
        val (app, host) = assertNotNull(UnixFds.socketPair())
        try {
            // Measured on macOS: `sendmsg` takes one `SCM_RIGHTS` message per call, and refuses two (`EINVAL`).
            assertFalse(UnixFds.send(app, listOf(intArrayOf(first.fd), intArrayOf(second.fd))))
        } finally {
            UnixFds.close(app)
            UnixFds.close(host)
        }
    }

    /**
     * What `receive` keeps, over control buffers as the kernel would leave them. So the check holds even where a
     * kernel delivers what this one refuses to send.
     */
    @Test
    fun onlyOneWholeMessageOfOneDescriptorIsTakenAndEverythingElseIsListedToClose() {
        fun control(vararg messages: IntArray) = Memory(32).apply {
            clear()
            var offset = 0L
            for (fds in messages) {
                setInt(offset, 12 + fds.size * 4)
                setInt(offset + 4, 0xFFFF)
                setInt(offset + 8, 1)
                fds.forEachIndexed { index, fd -> setInt(offset + 12 + index * 4, fd) }
                offset += 12 + fds.size * 4
            }
        }
        val one = UnixFds.choose(control(intArrayOf(41)), 16, truncated = false)
        assertEquals(41, one.taken)
        assertEquals(emptyList(), one.refused)

        val two = UnixFds.choose(control(intArrayOf(41), intArrayOf(42)), 32, truncated = false)
        assertNull(two.taken, "a second SCM_RIGHTS message")
        assertEquals(listOf(41, 42), two.refused)

        val longer = UnixFds.choose(control(intArrayOf(41)), 32, truncated = false)
        assertNull(longer.taken, "control length past the first message")
        assertEquals(listOf(41), longer.refused)

        val cut = UnixFds.choose(control(intArrayOf(41)), 16, truncated = true)
        assertNull(cut.taken, "MSG_CTRUNC")
        assertEquals(listOf(41), cut.refused)

        val pair = UnixFds.choose(control(intArrayOf(41, 42)), 20, truncated = false)
        assertNull(pair.taken, "two descriptors in one message")
        assertEquals(listOf(41, 42), pair.refused)
    }

    private fun openDescriptors(): Int = checkNotNull(File("/dev/fd").list()).size

    @Test
    fun aByteWithNoDescriptorIsNotTakenForOne() {
        val (app, host) = assertNotNull(UnixFds.socketPair())
        try {
            FileOutputStream("/dev/fd/$app").use { it.write('F'.code) }
            assertNull(UnixFds.receive(host))
        } finally {
            UnixFds.close(app)
            UnixFds.close(host)
        }
    }
}

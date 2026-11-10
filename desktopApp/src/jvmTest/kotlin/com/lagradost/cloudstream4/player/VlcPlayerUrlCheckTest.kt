package com.lagradost.cloudstream4.player

import com.lagradost.cloudstream4.feature.extensions.sandbox.SandboxPolicy
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `BUG-90`: [VlcPlayer.play] checks a stream's url again, the moment before the host is given it, so a name that has
 * turned private since the link arrived is refused. Made-up names; the lookups are this test's, nothing resolves.
 */
class VlcPlayerUrlCheckTest {
    private val process = ProcessBuilder("/bin/sleep", "60").start()
    private val directory = Files.createTempDirectory("url-check").toFile()
    private val sent = ByteArrayOutputStream()

    private val public = arrayOf(InetAddress.getByAddress("stream.example", byteArrayOf(8, 8, 8, 8)))
    private val loopback = arrayOf(InetAddress.getByAddress("stream.example", byteArrayOf(127, 0, 0, 1)))

    /** Answers public for [publicAnswers] lookups of [name], then loopback: a name rebound after its first check. */
    private fun rebinding(name: String, publicAnswers: Int): (String) -> Array<InetAddress> {
        val asked = AtomicInteger()
        return { host -> if (host == name && asked.incrementAndGet() > publicAnswers) loopback else public }
    }

    private fun player(resolve: (String) -> Array<InetAddress>) = VlcPlayer.forStream(process, directory, sent, resolve)

    @AfterTest
    fun tearDown() {
        process.destroyForcibly()
        directory.deleteRecursively()
    }

    @Test
    fun `a stream whose name turned private after its link was checked is not sent to the host`() {
        val url = "https://stream.example/v.m3u8"
        val resolve = rebinding("stream.example", publicAnswers = 1)
        // The link check, as RemoteLink.isPublic makes it when the link arrives.
        assertTrue(SandboxPolicy.isPublicHttpUrl(url, resolve))
        assertFalse(player(resolve).play(url, null, null, null))
        assertEquals(0, sent.size(), "the host was sent something")
    }

    @Test
    fun `a stream still public when it plays is sent`() {
        val url = "https://stream.example/v.m3u8"
        val resolve = rebinding("stream.example", publicAnswers = Int.MAX_VALUE)
        assertTrue(SandboxPolicy.isPublicHttpUrl(url, resolve))
        assertTrue(player(resolve).play(url, null, null, null))
        assertTrue(url in sent.toString(Charsets.UTF_8), "the url was not sent")
    }

    @Test
    fun `a staged file is sent as its copy under the host's directory, and nothing else`() {
        val outside = Files.createTempFile("staged", ".mkv").toFile().apply { writeText("not media") }
        try {
            assertTrue(player(rebinding("stream.example", publicAnswers = 0)).playStaged(outside))
            val text = sent.toString(Charsets.UTF_8)
            assertTrue(File(directory, outside.name).toPath().toUri().toString() in text, text)
            assertFalse(outside.toPath().toUri().toString() in text, "the original's path was sent")
            assertFalse("http" in text, text)
        } finally {
            outside.delete()
        }
    }

    @Test
    fun `an audio track whose name turned private is not sent either`() {
        val audio = "https://audio.example/a.m4a"
        val resolve = rebinding("audio.example", publicAnswers = 1)
        assertTrue(SandboxPolicy.isPublicAudioUrl(audio, resolve))
        assertFalse(player(resolve).play("https://stream.example/v.m3u8", null, null, audio))
        assertEquals(0, sent.size(), "the host was sent something")
    }
}

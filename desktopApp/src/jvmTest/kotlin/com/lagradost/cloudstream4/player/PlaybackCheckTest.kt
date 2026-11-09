package com.lagradost.cloudstream4.player

import com.lagradost.cloudstream4.feature.extensions.sandbox.RemoteLink
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `BUG-76`: what `--check-playback` copies into the host's directory before playing, and what it passes on as it is. */
class PlaybackCheckTest {
    private val folder: File = Files.createTempDirectory("playback-check").toFile()
    private val clip = File(folder, "Quiet Harbor.mp4").apply { writeBytes(byteArrayOf(0)) }

    @AfterTest
    fun cleanUp() {
        folder.deleteRecursively()
    }

    @Test
    fun aLocalFileByPathOrFileUrlIsStaged() {
        assertEquals(clip, localPlaybackFile(clip.path))
        assertEquals(clip.canonicalFile, localPlaybackFile(clip.toURI().toString())?.canonicalFile, "a file:// URL, its space encoded")
        assertEquals(clip.canonicalFile, localPlaybackFile("FILE://" + clip.toURI().rawPath)?.canonicalFile, "the scheme in any case")
    }

    @Test
    fun aStreamAMissingFileOrAFolderGoesToTheHostAsItIs() {
        assertNull(localPlaybackFile("https://example.invalid/quiet-harbor.m3u8"))
        assertNull(localPlaybackFile("rtsp://example.invalid/stream"))
        assertNull(localPlaybackFile(File(folder, "missing.mp4").path))
        assertNull(localPlaybackFile(folder.path), "a folder is not a clip")
        assertNull(localPlaybackFile("file://%zz"), "a malformed file URL is not a file")
    }

    @Test
    fun onlyACommandLineTargetIsStaged() {
        assertEquals(CheckStream.Staged(clip), directStream(clip.path))
        val stream = "https://example.invalid/quiet-harbor.m3u8"
        assertEquals(CheckStream.AsIs(stream, null, null, null), directStream(stream))
    }

    @Test
    fun aPluginLinkIsNeverStagedEvenWhenItNamesALocalFile() {
        listOf(clip.path, clip.toURI().toString()).forEach { named ->
            val link = RemoteLink(
                source = "Lantern Reel",
                name = "Lantern Reel",
                url = named,
                quality = 1080,
                type = "VIDEO",
                referer = "https://example.invalid/",
                userAgent = "Lantern/1",
                audioUrl = null,
            )
            assertEquals(CheckStream.AsIs(named, "https://example.invalid/", "Lantern/1", null), pluginStream(link), named)
        }
    }
}

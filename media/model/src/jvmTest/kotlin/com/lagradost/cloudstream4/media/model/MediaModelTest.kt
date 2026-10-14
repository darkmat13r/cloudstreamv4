package com.lagradost.cloudstream4.media.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The behaviour these contracts promise. There is no implementation to test, so what is asserted is
 * the derivations and the preconditions — the places where a data class is doing more than holding
 * fields, and therefore the places a future change can break something silently.
 */
class ArgbColorTest {

    @Test
    fun `channels unpack in ARGB order`() {
        val color = ArgbColor(0x12345678)
        assertEquals(0x12, color.alpha)
        assertEquals(0x34, color.red)
        assertEquals(0x56, color.green)
        assertEquals(0x78, color.blue)
    }

    @Test
    fun `of round-trips every channel, including the high bit`() {
        // 0xFF in alpha makes the Int negative; a naive shift without masking loses it.
        val color = ArgbColor.of(alpha = 0xFF, red = 0x00, green = 0x80, blue = 0xFF)
        assertEquals(0xFF, color.alpha)
        assertEquals(0x00, color.red)
        assertEquals(0x80, color.green)
        assertEquals(0xFF, color.blue)
    }

    @Test
    fun `named colours have the expected packing`() {
        assertEquals(0, ArgbColor.Transparent.alpha)
        assertEquals(0xFF, ArgbColor.Black.alpha)
        assertEquals(0, ArgbColor.Black.red)
        assertEquals(0xFF, ArgbColor.White.red)
    }
}

class SubtitleTrackTest {

    @Test
    fun `an embedded track is identified by its track id alone`() {
        val track = SubtitleTrack(SubtitleSource.Embedded("track-3"), label = "English")
        assertEquals("track-3", track.id)
    }

    @Test
    fun `two providers serving the same url are different tracks`() {
        val a = SubtitleTrack(SubtitleSource.Remote("https://host/s.srt"), label = "OpenSubtitles")
        val b = SubtitleTrack(SubtitleSource.Remote("https://host/s.srt"), label = "Subscene")
        assertTrue(a.id != b.id)
    }

    @Test
    fun `relabelling an embedded track does not change its identity`() {
        val a = SubtitleTrack(SubtitleSource.Embedded("t1"), label = "English")
        val b = SubtitleTrack(SubtitleSource.Embedded("t1"), label = "English (SDH)")
        assertEquals(a.id, b.id)
    }
}

class SubtitleCueTest {

    @Test
    fun `end time is start plus duration`() {
        assertEquals(1500L, SubtitleCue(1000L, 500L, listOf("hi")).endTimeMs)
    }

    @Test
    fun `a negative duration is rejected`() {
        assertFailsWith<IllegalArgumentException> { SubtitleCue(0L, -1L, emptyList()) }
    }
}

class EngineCapabilitiesTest {

    private val chromecastLike = EngineCapabilities(
        protocols = setOf(StreamProtocol.PROGRESSIVE, StreamProtocol.HLS, StreamProtocol.DASH),
        drmSchemes = setOf(DrmScheme.WIDEVINE),
        headerSupport = HeaderSupport.NONE,
        supportsExternalAudioTracks = false,
        supportsEmbeddedSubtitles = false,
        supportsExternalSubtitles = true,
    )

    private val exoLike = EngineCapabilities(
        protocols = StreamProtocol.entries.toSet(),
        drmSchemes = setOf(DrmScheme.CLEAR_KEY, DrmScheme.WIDEVINE, DrmScheme.PLAYREADY),
        headerSupport = HeaderSupport.ARBITRARY,
        supportsExternalAudioTracks = true,
        supportsEmbeddedSubtitles = true,
        supportsExternalSubtitles = true,
    )

    private val avPlayerLike = exoLike.copy(
        protocols = setOf(StreamProtocol.PROGRESSIVE, StreamProtocol.HLS),
        drmSchemes = emptySet(),
        headerSupport = HeaderSupport.USER_AGENT_AND_COOKIES,
    )

    private fun remote(headers: Map<String, String> = emptyMap(), referer: String? = null) =
        StreamRequest(StreamSource.Remote("https://host/v.m3u8", headers, referer), StreamProtocol.HLS)

    @Test
    fun `an unsupported protocol is rejected`() {
        assertFalse(avPlayerLike.supports(remote().copy(protocol = StreamProtocol.DASH)))
        assertTrue(exoLike.supports(remote().copy(protocol = StreamProtocol.DASH)))
    }

    @Test
    fun `an unsupported drm scheme is rejected`() {
        val drm = remote().copy(drm = DrmConfiguration(DrmScheme.PLAYREADY))
        assertFalse(avPlayerLike.supports(drm))
        assertTrue(exoLike.supports(drm))
    }

    @Test
    fun `a link with no drm is fine on an engine with no drm`() {
        assertTrue(avPlayerLike.supports(remote()))
    }

    @Test
    fun `user-agent and cookie survive an engine that can express only those`() {
        val pinned = remote(headers = mapOf("User-Agent" to "CS/4", "Cookie" to "cf_clearance=x"))
        assertTrue(avPlayerLike.supports(pinned))
    }

    @Test
    fun `header names are matched case-insensitively`() {
        assertTrue(avPlayerLike.supports(remote(headers = mapOf("user-agent" to "CS/4"))))
    }

    @Test
    fun `any other header defeats an engine that cannot express it`() {
        assertFalse(avPlayerLike.supports(remote(headers = mapOf("X-Token" to "x"))))
        assertTrue(exoLike.supports(remote(headers = mapOf("X-Token" to "x"))))
    }

    @Test
    fun `a referer defeats an engine that cannot express it`() {
        assertFalse(avPlayerLike.supports(remote(referer = "https://host/")))
        assertFalse(chromecastLike.supports(remote(referer = "https://host/")))
        assertTrue(exoLike.supports(remote(referer = "https://host/")))
    }

    @Test
    fun `a local stream needs no header support`() {
        val local = StreamRequest(StreamSource.Local("file:///v.mp4"), StreamProtocol.PROGRESSIVE)
        assertTrue(chromecastLike.supports(local.copy(protocol = StreamProtocol.PROGRESSIVE)))
    }

    @Test
    fun `external audio tracks are rejected by an engine that cannot merge them`() {
        val merged = remote().copy(audioTracks = listOf(AudioTrackSource("https://host/a.m4a")))
        assertFalse(chromecastLike.supports(merged))
        assertTrue(exoLike.supports(merged))
    }
}

class PlaybackPositionTest {

    @Test
    fun `fraction is unknown without a duration`() {
        assertNull(PlaybackPosition(1000L).fraction)
    }

    @Test
    fun `a zero duration is indeterminate, not empty`() {
        assertNull(PlaybackPosition(0L, 0L).fraction)
    }

    @Test
    fun `fraction is clamped when the position overruns the duration`() {
        assertEquals(1f, PlaybackPosition(5000L, 4000L).fraction)
    }

    @Test
    fun `fraction is a plain ratio otherwise`() {
        assertEquals(0.5f, PlaybackPosition(2000L, 4000L).fraction)
    }
}

class PipStateTest {

    private val ready = PipState(isSupported = true, isEnabled = true, isActive = false)

    @Test
    fun `desired while playing and buffering, not otherwise`() {
        assertTrue(ready.isDesired(PlaybackState.PLAYING))
        assertTrue(ready.isDesired(PlaybackState.BUFFERING))
        assertFalse(ready.isDesired(PlaybackState.PAUSED))
        assertFalse(ready.isDesired(PlaybackState.ENDED))
        assertFalse(ready.isDesired(PlaybackState.IDLE))
    }

    @Test
    fun `never desired when unsupported or disabled`() {
        assertFalse(ready.copy(isSupported = false).isDesired(PlaybackState.PLAYING))
        assertFalse(ready.copy(isEnabled = false).isDesired(PlaybackState.PLAYING))
    }
}

class AspectRatioTest {

    @Test
    fun `a ratio within the window is returned unchanged`() {
        val sixteenNine = AspectRatio(1920, 1080)
        assertEquals(sixteenNine, sixteenNine.clampedForPictureInPicture())
    }

    @Test
    fun `an over-wide ratio is clamped to the maximum`() {
        val clamped = AspectRatio(4000, 100).clampedForPictureInPicture()
        assertTrue(clamped.value <= AspectRatio.MAX_PIP_RATIO)
        assertTrue(clamped.value > AspectRatio.MAX_PIP_RATIO - TOLERANCE)
    }

    @Test
    fun `an over-tall ratio is clamped to the minimum`() {
        val clamped = AspectRatio(100, 4000).clampedForPictureInPicture()
        assertTrue(clamped.value >= AspectRatio.MIN_PIP_RATIO - TOLERANCE)
        assertTrue(clamped.value < AspectRatio.MIN_PIP_RATIO + TOLERANCE)
    }

    @Test
    fun `a zero dimension is rejected`() {
        assertFailsWith<IllegalArgumentException> { AspectRatio(0, 1080) }
        assertFailsWith<IllegalArgumentException> { AspectRatio(1920, 0) }
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}

class DownloadProgressTest {

    private fun progress(
        state: DownloadState = DownloadState.DOWNLOADING,
        done: Long = 0L,
        total: Long? = null,
        rate: Long = 0L,
        segments: SegmentProgress? = null,
    ) = DownloadProgress(id = 1, state = state, bytesDownloaded = done, bytesTotal = total, bytesPerSecond = rate, segments = segments)

    @Test
    fun `fraction is unknown without a total`() {
        assertNull(progress(done = 500L).fraction)
    }

    @Test
    fun `segment progress wins over byte progress`() {
        // The byte total of an HLS download only counts segments already seen, so it would read 1.0
        // the whole way through. The segment count is the honest one.
        val hls = progress(done = 1000L, total = 1000L, segments = SegmentProgress(2, 10))
        assertEquals(0.2f, hls.fraction)
    }

    @Test
    fun `a segment total of zero is still indeterminate`() {
        assertNull(progress(segments = SegmentProgress(0, 0)).fraction)
    }

    @Test
    fun `eta needs a total, a rate and an active download`() {
        assertNull(progress(done = 0L, total = 1000L, rate = 0L).estimatedSecondsRemaining)
        assertNull(progress(done = 0L, total = null, rate = 100L).estimatedSecondsRemaining)
        assertNull(
            progress(state = DownloadState.PAUSED, done = 0L, total = 1000L, rate = 100L)
                .estimatedSecondsRemaining
        )
        assertEquals(10L, progress(done = 0L, total = 1000L, rate = 100L).estimatedSecondsRemaining)
    }

    @Test
    fun `eta is zero rather than negative once the total is passed`() {
        assertEquals(0L, progress(done = 1200L, total = 1000L, rate = 100L).estimatedSecondsRemaining)
    }

    @Test
    fun `negative byte counts are rejected`() {
        assertFailsWith<IllegalArgumentException> { progress(done = -1L) }
    }
}

class PlayerCommandTest {

    @Test
    fun `an episode seek of zero is rejected`() {
        assertFailsWith<IllegalArgumentException> { PlayerCommand.SeekEpisode(0) }
    }

    @Test
    fun `playback speed must be positive`() {
        assertFailsWith<IllegalArgumentException> { PlayerCommand.SetPlaybackSpeed(0f) }
        assertFailsWith<IllegalArgumentException> { PlayerCommand.SetPlaybackSpeed(-1f) }
    }

    @Test
    fun `commands default to a user source`() {
        assertEquals(CommandSource.USER, PlayerCommand.Play().source)
        assertEquals(CommandSource.SYNC, PlayerCommand.Play(CommandSource.SYNC).source)
    }
}

class PlaybackRequestTest {

    private val english = SubtitleTrack(SubtitleSource.Embedded("t1"), label = "English")
    private val stream = StreamRequest(StreamSource.Remote("https://host/v.m3u8"), StreamProtocol.HLS)

    @Test
    fun `no selection is always resolvable`() {
        assertTrue(PlaybackRequest(stream).hasResolvablePreferredSubtitle())
    }

    @Test
    fun `a selection present in the list is resolvable`() {
        val request = PlaybackRequest(stream, subtitles = listOf(english), preferredSubtitleId = english.id)
        assertTrue(request.hasResolvablePreferredSubtitle())
    }

    @Test
    fun `a selection absent from the list is not`() {
        val request = PlaybackRequest(stream, subtitles = listOf(english), preferredSubtitleId = "gone")
        assertFalse(request.hasResolvablePreferredSubtitle())
    }
}

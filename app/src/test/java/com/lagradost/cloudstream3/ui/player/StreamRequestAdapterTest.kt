package com.lagradost.cloudstream3.ui.player

import com.lagradost.cloudstream3.newAudioFile
import com.lagradost.cloudstream3.utils.CLEARKEY_DRM_UUID
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.PLAYREADY_DRM_UUID
import com.lagradost.cloudstream3.utils.PLAYREADY_UUID
import com.lagradost.cloudstream3.utils.PlayListItem
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.WIDEVINE_DRM_UUID
import com.lagradost.cloudstream3.utils.WIDEVINE_UUID
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream4.media.model.AudioTrackSource
import com.lagradost.cloudstream4.media.model.DrmScheme
import com.lagradost.cloudstream4.media.model.StreamProtocol
import com.lagradost.cloudstream4.media.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.uuid.Uuid

/**
 * Guards the `KMP-07` adapter from `:library`/`:app` stream carriers to `:media:model`.
 *
 * The `newXxx` builders are `suspend` but never suspend, so [build] drives them without a
 * coroutine library. `fromUri` is not covered here: `ExtractorUri.uri` is an `android.net.Uri`,
 * which a plain JVM test cannot construct, so its Uri-free part, `localStream`, is tested instead.
 */
class StreamRequestAdapterTest {

    private fun <T> build(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
        return outcome!!.getOrThrow()
    }

    private fun remoteOf(link: ExtractorLink) =
        StreamRequestAdapter.fromLink(link)!!.source as StreamSource.Remote

    private val plain: ExtractorLink
        get() = build {
            newExtractorLink("Provider", "Episode 1", "https://cdn.example.com/a.mp4", ExtractorLinkType.VIDEO)
        }

    @Test
    fun `a plain remote link maps every portable field`() {
        val request = StreamRequestAdapter.fromLink(plain)!!

        assertEquals(
            StreamSource.Remote(url = "https://cdn.example.com/a.mp4", headers = emptyMap(), referer = null),
            request.source,
        )
        assertEquals(StreamProtocol.PROGRESSIVE, request.protocol)
        assertEquals("Episode 1", request.label)
        assertEquals("Provider", request.providerName)
        assertEquals(Qualities.Unknown.value, request.quality)
        assertNull(request.drm)
        assertTrue(request.audioTracks.isEmpty())
    }

    @Test
    fun `headers and referer are kept apart`() {
        val link = build {
            newExtractorLink("P", "n", "https://cdn.example.com/a.m3u8", ExtractorLinkType.M3U8) {
                headers = mapOf("User-Agent" to "ua", "Cookie" to "cf_clearance=x")
                referer = "https://site.example.com/"
                quality = Qualities.P1080.value
            }
        }
        val request = StreamRequestAdapter.fromLink(link)!!
        val remote = request.source as StreamSource.Remote

        assertEquals(mapOf("User-Agent" to "ua", "Cookie" to "cf_clearance=x"), remote.headers)
        assertEquals("https://site.example.com/", remote.referer)
        assertEquals(StreamProtocol.HLS, request.protocol)
        assertEquals(1080, request.quality)
    }

    @Test
    fun `a blank referer becomes null, not an empty string`() {
        assertNull(remoteOf(plain).referer)
        val blank = build {
            newExtractorLink("P", "n", "https://cdn.example.com/a.mp4", ExtractorLinkType.VIDEO) { referer = "  " }
        }
        assertNull(remoteOf(blank).referer)
    }

    @Test
    fun `a referer header is carried as a header and the referer field is not invented from it`() {
        val link = build {
            newExtractorLink("P", "n", "https://cdn.example.com/a.mp4", ExtractorLinkType.VIDEO) {
                headers = mapOf("Referer" to "https://in-header.example.com/")
            }
        }
        val remote = remoteOf(link)

        assertEquals(mapOf("Referer" to "https://in-header.example.com/"), remote.headers)
        assertNull(remote.referer)
    }

    @Test
    fun `the mapped headers are a snapshot, not a view of the link`() {
        val mutable = mutableMapOf("a" to "1")
        val link = build {
            newExtractorLink("P", "n", "https://cdn.example.com/a.mp4", ExtractorLinkType.VIDEO) { headers = mutable }
        }
        val remote = remoteOf(link)
        mutable["b"] = "2"

        assertEquals(mapOf("a" to "1"), remote.headers)
    }

    @Test
    fun `every link type has a protocol`() {
        assertEquals(StreamProtocol.PROGRESSIVE, StreamRequestAdapter.protocolOf(ExtractorLinkType.VIDEO))
        assertEquals(StreamProtocol.HLS, StreamRequestAdapter.protocolOf(ExtractorLinkType.M3U8))
        assertEquals(StreamProtocol.DASH, StreamRequestAdapter.protocolOf(ExtractorLinkType.DASH))
        assertEquals(StreamProtocol.TORRENT, StreamRequestAdapter.protocolOf(ExtractorLinkType.TORRENT))
        assertEquals(StreamProtocol.TORRENT, StreamRequestAdapter.protocolOf(ExtractorLinkType.MAGNET))
    }

    @Test
    fun `audio tracks keep their url and headers and have no language`() {
        val withHeaders = build { newAudioFile("https://cdn.example.com/en.aac") { headers = mapOf("k" to "v") } }
        val withoutHeaders = build { newAudioFile("https://cdn.example.com/fr.aac") }
        val link = build {
            newExtractorLink("P", "n", "https://cdn.example.com/a.mp4", ExtractorLinkType.VIDEO) {
                audioTracks = listOf(withHeaders, withoutHeaders)
            }
        }

        assertEquals(
            listOf(
                AudioTrackSource("https://cdn.example.com/en.aac", mapOf("k" to "v"), languageTag = null),
                AudioTrackSource("https://cdn.example.com/fr.aac", emptyMap(), languageTag = null),
            ),
            StreamRequestAdapter.fromLink(link)!!.audioTracks,
        )
    }

    @Test
    fun `a playlist link has no request`() {
        val playlist = ExtractorLinkPlayList(
            source = "P",
            name = "n",
            playlist = listOf(PlayListItem("https://cdn.example.com/1.mp4", 1_000_000L)),
            referer = "",
            quality = Qualities.Unknown.value,
        )

        assertNull(StreamRequestAdapter.fromLink(playlist))
    }

    @Test
    fun `a local stream carries its locator and sidecar paths`() {
        val request = StreamRequestAdapter.localStream(
            locator = "content://downloads/42",
            basePath = "/storage/Download/CloudStream",
            relativePath = "Show/S01E01.mp4",
            displayName = "S01E01.mp4",
            label = "Episode 1",
        )

        assertEquals(
            StreamSource.Local(
                locator = "content://downloads/42",
                basePath = "/storage/Download/CloudStream",
                relativePath = "Show/S01E01.mp4",
                displayName = "S01E01.mp4",
            ),
            request.source,
        )
        assertEquals(StreamProtocol.PROGRESSIVE, request.protocol)
        assertEquals("Episode 1", request.label)
        assertNull(request.providerName)
        assertNull(request.quality)
        assertNull(request.drm)
    }

    @Test
    fun `a local stream not downloaded by this app has no paths`() {
        val source = StreamRequestAdapter.localStream("file:///a.mp4", null, null, null, "a").source
        assertEquals(StreamSource.Local("file:///a.mp4"), source)
    }

    @Test
    fun `each library drm uuid maps to its scheme`() {
        assertEquals(DrmScheme.CLEAR_KEY, StreamRequestAdapter.schemeOf(CLEARKEY_DRM_UUID))
        assertEquals(DrmScheme.WIDEVINE, StreamRequestAdapter.schemeOf(WIDEVINE_DRM_UUID))
        assertEquals(DrmScheme.PLAYREADY, StreamRequestAdapter.schemeOf(PLAYREADY_DRM_UUID))
    }

    @Test
    fun `the java uuid constants map the same way`() {
        assertEquals(DrmScheme.CLEAR_KEY, StreamRequestAdapter.schemeOf(CLEARKEY_UUID))
        assertEquals(DrmScheme.WIDEVINE, StreamRequestAdapter.schemeOf(WIDEVINE_UUID))
        assertEquals(DrmScheme.PLAYREADY, StreamRequestAdapter.schemeOf(PLAYREADY_UUID))
    }

    @Test
    fun `an unknown drm uuid is UNKNOWN, not an error and not null`() {
        val unknown = Uuid.parse("94ce86fb-07ff-4f43-adb8-93d2fa968ca2") // FairPlay's system id
        assertEquals(DrmScheme.UNKNOWN, StreamRequestAdapter.schemeOf(unknown))
        assertEquals(DrmScheme.UNKNOWN, StreamRequestAdapter.schemeOf(Uuid.NIL))
        assertEquals(DrmScheme.UNKNOWN, StreamRequestAdapter.schemeOf(java.util.UUID(0L, 0L)))
    }

    @Test
    fun `a drm link carries its configuration`() {
        val link = build {
            newDrmExtractorLink("P", "n", "https://cdn.example.com/a.mpd", ExtractorLinkType.DASH, WIDEVINE_DRM_UUID) {
                licenseUrl = "https://license.example.com/"
                keyRequestParameters = hashMapOf("p" to "q")
                kid = "kid-b64"
                key = "key-b64"
            }
        }
        val request = StreamRequestAdapter.fromLink(link)!!
        val drm = request.drm

        assertNotNull(drm)
        assertEquals(DrmScheme.WIDEVINE, drm!!.scheme)
        assertEquals("kid-b64", drm.keyId)
        assertEquals("key-b64", drm.key)
        assertEquals("https://license.example.com/", drm.licenseUrl)
        assertEquals(mapOf("p" to "q"), drm.keyRequestParameters)
        assertEquals(StreamProtocol.DASH, request.protocol)
    }

    @Test
    fun `a drm link with an unknown uuid keeps the link and reports UNKNOWN`() {
        val link = build {
            newDrmExtractorLink("P", "n", "https://cdn.example.com/a.mpd", ExtractorLinkType.DASH, Uuid.NIL)
        }

        assertEquals(DrmScheme.UNKNOWN, StreamRequestAdapter.fromLink(link)!!.drm!!.scheme)
    }

    @Test
    fun `key type oct is the library default for every scheme, and is passed through`() {
        val clearKey = build {
            newDrmExtractorLink("P", "n", "https://cdn.example.com/a.mpd", ExtractorLinkType.DASH, CLEARKEY_DRM_UUID)
        }
        val widevine = build {
            newDrmExtractorLink("P", "n", "https://cdn.example.com/a.mpd", ExtractorLinkType.DASH, WIDEVINE_DRM_UUID)
        }

        assertEquals("oct", StreamRequestAdapter.fromLink(clearKey)!!.drm!!.keyType)
        // The library's default kty is not scheme-aware; the adapter does not second-guess it.
        assertEquals("oct", StreamRequestAdapter.fromLink(widevine)!!.drm!!.keyType)
    }
}

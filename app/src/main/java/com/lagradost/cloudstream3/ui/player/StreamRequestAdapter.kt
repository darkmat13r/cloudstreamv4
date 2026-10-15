package com.lagradost.cloudstream3.ui.player

import com.lagradost.cloudstream3.AudioFile
import com.lagradost.cloudstream3.utils.CLEARKEY_DRM_UUID
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.PLAYREADY_DRM_UUID
import com.lagradost.cloudstream3.utils.WIDEVINE_DRM_UUID
import com.lagradost.cloudstream4.media.model.AudioTrackSource
import com.lagradost.cloudstream4.media.model.DrmConfiguration
import com.lagradost.cloudstream4.media.model.DrmScheme
import com.lagradost.cloudstream4.media.model.StreamProtocol
import com.lagradost.cloudstream4.media.model.StreamRequest
import com.lagradost.cloudstream4.media.model.StreamSource
import java.util.UUID
import kotlin.uuid.Uuid
import kotlin.uuid.toKotlinUuid

/**
 * Maps `:library` / `:app` stream carriers onto `:media:model`'s [StreamRequest] (`KMP-07`).
 *
 * One direction only. `:media:model` has no dependencies, so it cannot name [ExtractorLink], and
 * `:library` must not depend on it; this is the one place both are visible. Pure and stateless:
 * the only Android type reached is `ExtractorUri.uri`, and only through `toString()`.
 *
 * **Nothing calls this yet.** `CS3IPlayer` still consumes `ExtractorLink` and `ExtractorUri`
 * directly; rewiring it is a separate task. What each mapping drops or cannot supply is recorded
 * in `docs/tasks/kmp.md` under `KMP-07`.
 *
 * `internal` keeps this off the source-level API, but a Kotlin `internal` class is public in
 * bytecode, so it still reaches `classes.jar` (`INF-07`). Members are therefore members of an
 * object, not top-level functions, so the jar gains one class rather than a facade.
 */
internal object StreamRequestAdapter {

    /**
     * Maps [link], or returns `null` when it has no honest [StreamRequest].
     *
     * `null` means an [ExtractorLinkPlayList]: its `url` is `""` and its content is a list of
     * concatenated slices with durations, which `StreamRequest` has no shape for. Emitting a
     * `Remote("")` would be a request that cannot be played and does not say why.
     */
    fun fromLink(link: ExtractorLink): StreamRequest? {
        if (link is ExtractorLinkPlayList) return null
        return StreamRequest(
            source = StreamSource.Remote(
                url = link.url,
                headers = link.headers.toMap(),
                referer = link.referer.ifBlank { null },
            ),
            protocol = protocolOf(link.type),
            label = link.name,
            providerName = link.source,
            quality = link.quality,
            drm = (link as? DrmExtractorLink)?.let(::drmOf),
            audioTracks = link.audioTracks.map(::audioOf),
        )
    }

    /** Maps a file already on the device. See [localStream] for what a local stream cannot supply. */
    fun fromUri(data: ExtractorUri): StreamRequest = localStream(
        locator = data.uri.toString(),
        basePath = data.basePath,
        relativePath = data.relativePath,
        displayName = data.displayName,
        label = data.name,
    )

    /**
     * The part of [fromUri] that needs no `android.net.Uri`, so a plain JVM test can reach it.
     *
     * The protocol is [StreamProtocol.PROGRESSIVE] because `ExtractorUri` carries nothing to
     * derive another from and the player opens every local file as `MimeTypes.VIDEO_MP4`
     * (`CS3IPlayer.loadOfflinePlayer`). A local HLS or DASH manifest would be mislabelled.
     */
    fun localStream(
        locator: String,
        basePath: String?,
        relativePath: String?,
        displayName: String?,
        label: String?,
    ): StreamRequest = StreamRequest(
        source = StreamSource.Local(
            locator = locator,
            basePath = basePath,
            relativePath = relativePath,
            displayName = displayName,
        ),
        protocol = StreamProtocol.PROGRESSIVE,
        label = label,
    )

    /**
     * `TORRENT` and `MAGNET` both become [StreamProtocol.TORRENT]: `StreamProtocol` documents
     * itself as "a torrent file or magnet link". The distinction survives in the url.
     * Exhaustive on purpose, so a new [ExtractorLinkType] fails to compile here.
     */
    fun protocolOf(type: ExtractorLinkType): StreamProtocol = when (type) {
        ExtractorLinkType.VIDEO -> StreamProtocol.PROGRESSIVE
        ExtractorLinkType.M3U8 -> StreamProtocol.HLS
        ExtractorLinkType.DASH -> StreamProtocol.DASH
        ExtractorLinkType.TORRENT,
        ExtractorLinkType.MAGNET -> StreamProtocol.TORRENT
    }

    /** The replacement for `CS3IPlayer`'s `when (uuid)`. An unrecognised UUID is [DrmScheme.UNKNOWN]. */
    fun schemeOf(uuid: Uuid): DrmScheme = when (uuid) {
        CLEARKEY_DRM_UUID -> DrmScheme.CLEAR_KEY
        WIDEVINE_DRM_UUID -> DrmScheme.WIDEVINE
        PLAYREADY_DRM_UUID -> DrmScheme.PLAYREADY
        else -> DrmScheme.UNKNOWN
    }

    /** For callers holding the `java.util.UUID` form (`CS3IPlayer.DrmMetadata`, `CLEARKEY_UUID`, ...). */
    fun schemeOf(uuid: UUID): DrmScheme = schemeOf(uuid.toKotlinUuid())

    private fun drmOf(link: DrmExtractorLink) = DrmConfiguration(
        scheme = schemeOf(link.uuid),
        keyId = link.kid,
        key = link.key,
        keyType = link.kty,
        licenseUrl = link.licenseUrl,
        keyRequestParameters = link.keyRequestParameters.toMap(),
    )

    private fun audioOf(audio: AudioFile) = AudioTrackSource(
        url = audio.url,
        headers = audio.headers.orEmpty(),
    )
}

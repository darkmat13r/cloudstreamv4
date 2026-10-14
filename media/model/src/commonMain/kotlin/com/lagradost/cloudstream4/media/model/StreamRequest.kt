package com.lagradost.cloudstream4.media.model

/**
 * How to obtain the bytes of one stream.
 *
 * This is the portable restatement of what `:library`'s `ExtractorLink` and `:app`'s `ExtractorUri`
 * carry between them. It is deliberately **not** either of them: `:media:model` must compile for
 * Kotlin/Native (plan §4.1 rule 4), `ExtractorUri.uri` is an `android.net.Uri`, and `ExtractorLink`
 * is published extension ABI in two dumps (`library/api/jvm/library.api`,
 * `app/api/classes-jar.api`) and so cannot change shape to suit a player.
 *
 * `:app` owns the adapter in both directions. The projection is lossy on purpose — `extractorData`
 * (an extension's own verifier payload) and the suspending `getVideoSize()` are extension concerns,
 * not playback concerns, and they stay on the `ExtractorLink`.
 */
sealed interface StreamSource {

    /**
     * A stream fetched over HTTP(S).
     *
     * [referer] is kept beside [headers] rather than folded into it, although
     * `ExtractorLink.getAllHeaders()` folds it. Engines differ: Media3 sets it as a header, while
     * AVPlayer has no arbitrary-header API at all and can only honour a user agent and cookies
     * (plan §10 O6). An engine that can express just one of the two needs to see which is which.
     */
    data class Remote(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val referer: String? = null,
    ) : StreamSource

    /**
     * A stream already on the device.
     *
     * [locator] is opaque to this module — an `android.net.Uri` string today, a file path or a
     * security-scoped bookmark elsewhere. Only the platform resolves it.
     *
     * [basePath] and [relativePath] are **not** a decomposition of [locator] and are not redundant:
     * `:app`'s `DownloadFileGenerator` discovers sidecar subtitle files by walking the directory
     * they name, while the player opens [locator]. A local stream that was not downloaded by this
     * app has a [locator] and neither path.
     */
    data class Local(
        val locator: String,
        val basePath: String? = null,
        val relativePath: String? = null,
        val displayName: String? = null,
    ) : StreamSource
}

/**
 * The container or transport an engine has to speak to read a [StreamSource].
 *
 * Restates `:library`'s `ExtractorLinkType`, which cannot be used here (it is `:library`, and its
 * `getMimeType()` carries a Jackson `@JsonIgnore`). The two enums are a total, cheap mapping that
 * `:app` owns, and they are expected to stay in step; this one is what an engine declares support
 * for in [EngineCapabilities].
 *
 * No mime strings are declared here. The engine boundary uses `androidx.media3.common.MimeTypes`
 * on Android and `AVFileType` on Apple platforms, and the two disagree even on HLS
 * (`ExtractorLinkType.getMimeType()` says `application/x-mpegURL`, Media3 says
 * `MimeTypes.APPLICATION_M3U8`). Picking one here would make this module wrong for one platform.
 */
enum class StreamProtocol {
    /** A single addressable byte range, whatever the container. */
    PROGRESSIVE,

    /** HLS. May be encrypted. */
    HLS,

    /** MPEG-DASH. Not playable by AVPlayer, so iOS engines report it unsupported. */
    DASH,

    /** A torrent file or magnet link, served through a local proxy. */
    TORRENT,
}

/**
 * A separate audio stream to be merged with the video at playback time.
 *
 * Restates `:library`'s `AudioFile`.
 */
data class AudioTrackSource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** BCP-47 where the extension supplied one; engines use it to pre-select a track. */
    val languageTag: String? = null,
)

/**
 * One stream an engine can be asked to open.
 *
 * [quality] is an opaque ordinal carried straight through from `ExtractorLink.quality`
 * (`:library`'s `Qualities`). This module does not restate that scale: it exists so a UI can sort
 * and label, and duplicating it here would create a second ladder to keep in step for no gain.
 */
data class StreamRequest(
    val source: StreamSource,
    val protocol: StreamProtocol,
    /** `ExtractorLink.name` — what the user picks between. */
    val label: String? = null,
    /** `ExtractorLink.source` — which provider produced it. */
    val providerName: String? = null,
    val quality: Int? = null,
    val drm: DrmConfiguration? = null,
    val audioTracks: List<AudioTrackSource> = emptyList(),
)

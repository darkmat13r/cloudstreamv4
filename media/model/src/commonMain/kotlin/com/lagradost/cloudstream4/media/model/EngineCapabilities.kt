package com.lagradost.cloudstream4.media.model

/**
 * How much of an HTTP request an engine can actually express.
 *
 * This is the distinction the iOS player question turns on (plan §10 O6), and it has no model
 * today because on Android the answer has always been [ARBITRARY]. Media3 sets any header on a
 * source; AVPlayer has **no public arbitrary-header API** — `AVURLAssetHTTPHeaderFieldsKey` is
 * private — but does expose a user agent (iOS 16+) and cookies.
 *
 * It matters because extensions routinely pin a user agent or pass a Cloudflare `cf_clearance`
 * cookie, and those two cases survive on an engine that can do nothing else. A caller that knows
 * the difference can pick a link an engine can actually fetch instead of failing at play time.
 */
enum class HeaderSupport {
    /** No request customisation at all. */
    NONE,

    /** User agent and cookies only. */
    USER_AGENT_AND_COOKIES,

    /** Any header, as Media3 does. */
    ARBITRARY,
}

/**
 * What one playback engine can do, as data.
 *
 * There is no such type today: the closest thing is the four `LOADTYPE_*` sets in `:core:model`
 * (`ui/player/IGenerator.kt`), which are published extension API and are typed against `:library`'s
 * `ExtractorLinkType`. Those sets say which link types a *sink* accepts — in-app, in-app download,
 * Chromecast — and they remain the thing extensions read. This type says what an *engine instance*
 * can do, which is a different question the moment there is more than one engine, and it is what
 * makes "pick the best engine for this link" expressible rather than hard-coded.
 *
 * Nothing here is a guess about a future iOS engine: every field is something an existing Android
 * engine already differs on. Chromecast accepts no torrents and no arbitrary headers; the in-app
 * downloader accepts no DASH; the in-app player accepts everything.
 */
data class EngineCapabilities(
    val protocols: Set<StreamProtocol>,
    val drmSchemes: Set<DrmScheme>,
    val headerSupport: HeaderSupport,
    /** Whether a separate audio stream can be merged with the video ([StreamRequest.audioTracks]). */
    val supportsExternalAudioTracks: Boolean,
    /** Whether subtitle tracks muxed into the container are exposed. */
    val supportsEmbeddedSubtitles: Boolean,
    /** Whether [SubtitleSource.Remote] can be side-loaded, as opposed to embedded tracks only. */
    val supportsExternalSubtitles: Boolean,
) {
    /**
     * Whether this engine can open [request] at all.
     *
     * Deliberately total and boring: it answers only from declared capability, never from the
     * stream's content. An engine that passes this can still fail on a codec, and that is a
     * playback error, not a capability error.
     */
    fun supports(request: StreamRequest): Boolean {
        if (request.protocol !in protocols) return false
        val scheme = request.drm?.scheme
        if (scheme != null && scheme !in drmSchemes) return false
        if (request.audioTracks.isNotEmpty() && !supportsExternalAudioTracks) return false
        return headerSupport.canExpress(request.source)
    }

    private fun HeaderSupport.canExpress(source: StreamSource): Boolean {
        if (source !is StreamSource.Remote) return true
        return when (this) {
            HeaderSupport.ARBITRARY -> true
            HeaderSupport.USER_AGENT_AND_COOKIES ->
                source.referer == null && source.headers.keys.all { it.isUserAgentOrCookie() }
            HeaderSupport.NONE -> source.referer == null && source.headers.isEmpty()
        }
    }

    private fun String.isUserAgentOrCookie(): Boolean =
        equals(USER_AGENT_HEADER, ignoreCase = true) || equals(COOKIE_HEADER, ignoreCase = true)

    private companion object {
        /** Header names are case-insensitive (RFC 9110 §5.1), so both are compared that way. */
        const val USER_AGENT_HEADER = "User-Agent"
        const val COOKIE_HEADER = "Cookie"
    }
}

package com.lagradost.cloudstream4.detail

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.DetailSource
import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import com.lagradost.cloudstream4.feature.extensions.sandbox.RemoteLink
import com.lagradost.cloudstream4.feature.extensions.sandbox.RemoteLinks
import com.lagradost.cloudstream4.feature.extensions.sandbox.RemoteSubtitle
import com.lagradost.cloudstream4.feature.extensions.sandbox.SandboxPolicy
import com.lagradost.cloudstream4.feature.extensions.sandbox.SandboxedProvider
import com.lagradost.cloudstream4.feature.search.SearchResultId
import com.lagradost.cloudstream4.media.model.SubtitleSource
import com.lagradost.cloudstream4.media.model.SubtitleTrack
import com.lagradost.cloudstream4.search.DesktopSearchSource
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Titles and streams, from the sandboxed provider that produced the search result.
 *
 * A link leaves here only if it is http(s) to a public address. libVLC runs in a sandboxed host, which
 * cannot reach this machine but can reach its network, so this is what keeps a plugin from aiming it at
 * the LAN. The check covers only the URL it is given. It cannot follow a redirect inside libVLC, stop a
 * name changing address afterwards, or see the segments a public HLS playlist points at (`KMP-16`).
 */
class DesktopDetailSource : DetailSource {

    private fun providerOf(id: String): Pair<SandboxedProvider, String> {
        val (apiName, url) = DesktopSearchSource.decodeId(SearchResultId(id))
            ?: throw IOException(DETAIL_BAD_ID)
        val provider = APIHolder.apis.withLock { APIHolder.apis.filterIsInstance<SandboxedProvider>() }
            .firstOrNull { it.name == apiName }
            ?: throw IOException("$DETAIL_NOT_LOADED · $apiName")
        return provider to url
    }

    override suspend fun details(id: String): TitleDetail {
        val (provider, url) = providerOf(id)
        val details = provider.details(url)
        return TitleDetail(
            title = details.name,
            providerName = provider.name,
            typeName = details.type,
            year = details.year,
            plot = details.plot?.withoutMarkup(),
            posterUrl = details.posterUrl,
            playData = details.dataUrl,
            episodes = details.episodes.map { DetailEpisode(it.name, it.data, it.season, it.episode) },
        )
    }

    // Conflated: each value is the whole list so far, so a screen that cannot keep up misses nothing.
    override fun links(id: String, data: String): Flow<List<StreamLink>> = flow {
        val (provider, _) = providerOf(id)
        emitAll(provider.linksAsTheyArrive(data).toStreamLinks())
    }.conflate().flowOn(Dispatchers.IO)

    /**
     * The plot as plain text. Providers send HTML (`<br>` in a YouTube description) and nothing here
     * renders it, so tags are dropped and never interpreted.
     */
    private fun String.withoutMarkup(): String =
        replace(BREAK_TAG, " ").replace(ANY_TAG, "").replace(RUN_OF_SPACES, " ").trim()

    private companion object {
        const val DETAIL_BAD_ID = "BAD_ID"
        const val DETAIL_NOT_LOADED = "NOT_LOADED"
        val BREAK_TAG = Regex("(?i)<br\\s*/?>")
        val ANY_TAG = Regex("<[^>]*>")
        val RUN_OF_SPACES = Regex("\\s+")
    }
}

/**
 * What a plugin has offered so far, as streams, emitting whenever there is one more or a new subtitle for those.
 *
 * Each link is checked once, when it first appears, and the answer only ever adds to its end. Nothing is
 * emitted until a link has passed, so a plugin offering only what the player will refuse reads as finding nothing.
 */
internal fun Flow<RemoteLinks>.toStreamLinks(
    isPlayable: (RemoteLink) -> Boolean = RemoteLink::isPublic,
): Flow<List<StreamLink>> = flow {
    val playable = ArrayList<RemoteLink>()
    var checked = 0
    collect { answer ->
        answer.links.drop(checked).filterTo(playable) { link ->
            // Resolving a name cannot be interrupted, so a dismissed list stops between links.
            currentCoroutineContext().ensureActive()
            isPlayable(link)
        }
        checked = answer.links.size
        if (playable.isNotEmpty()) {
            val subtitles = answer.subtitles.toTracks()
            emit(playable.map { it.toStreamLink(subtitles) })
        }
    }
}

/** Whether the player may be aimed at it. */
internal fun RemoteLink.isPublic(): Boolean =
    SandboxPolicy.isPublicHttpUrl(url) && audioUrl?.let(SandboxPolicy::isPublicAudioUrl) != false

private fun RemoteLink.toStreamLink(subtitles: List<SubtitleTrack>) = StreamLink(
    label = name,
    qualityLabel = quality.takeIf { it > 0 }?.let { "${it}p" },
    url = url,
    referer = referer,
    userAgent = userAgent,
    audioUrl = audioUrl,
    subtitles = subtitles,
)

/**
 * What the plugin offered, as tracks the player can show.
 *
 * A URL's address is not looked at here: `HttpSubtitleLoader` does that for the track someone chooses.
 */
internal fun List<RemoteSubtitle>.toTracks(): List<SubtitleTrack> {
    val seen = HashMap<String, Int>()
    return map { subtitle ->
        val name = displayName(subtitle)
        val count = (seen[name] ?: 0) + 1
        seen[name] = count
        SubtitleTrack(
            source = SubtitleSource.Remote(
                url = subtitle.url,
                headers = buildMap {
                    subtitle.referer?.let { put(REFERER, it) }
                    subtitle.userAgent?.let { put(USER_AGENT, it) }
                },
            ),
            // Two rows must not read the same, and a plugin often has no more to tell them apart with:
            // ARD offers the same words twice as `deu`, YouTube has an English and an automatic English.
            label = if (count == 1) name else "$name $count",
            languageTag = subtitle.languageTag,
        )
    }
}

/** A plugin's `deu` means nothing to anyone, so a bare code becomes its language's name where it has one. */
private fun displayName(subtitle: RemoteSubtitle): String {
    val language = subtitle.language
    val tag = subtitle.languageTag ?: return language
    if (language.length > MAX_CODE_CHARS || language.any { it !in 'a'..'z' }) return language
    return Locale.forLanguageTag(tag).getDisplayName(Locale.getDefault()).ifEmpty { language }
}

private const val REFERER = "Referer"
private const val USER_AGENT = "User-Agent"
private const val MAX_CODE_CHARS = 3

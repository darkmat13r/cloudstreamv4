package com.lagradost.cloudstream4.detail

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.DetailSource
import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import com.lagradost.cloudstream4.feature.extensions.sandbox.RemoteDetails
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
        val details = try {
            provider.details(url)
        } catch (failure: IOException) {
            // The page shows none of this (BUG-21): it is a class name and a plugin's words. It goes
            // where a person debugging a plugin can find it, without a URL, which can carry a token.
            System.err.println("detail: ${provider.name} could not open a title: ${failure.message.orEmpty().withoutUrls()}")
            throw failure
        }
        return details.toTitleDetail(provider.name)
    }

    // Conflated: each value is the whole list so far, so a screen that cannot keep up misses nothing.
    override fun links(id: String, data: String): Flow<List<StreamLink>> = flow {
        val (provider, _) = providerOf(id)
        emitAll(provider.linksAsTheyArrive(data).toStreamLinks())
    }.conflate().flowOn(Dispatchers.IO)

    private companion object {
        const val DETAIL_BAD_ID = "BAD_ID"
        const val DETAIL_NOT_LOADED = "NOT_LOADED"
    }
}

/**
 * The page's title as the sandbox delivered it. Every fact has already passed the app's reader and its checks
 * (`KMP-76`), so this only changes units: the page keeps a score out of ten, the wire sends tenths.
 */
internal fun RemoteDetails.toTitleDetail(providerName: String) = TitleDetail(
    title = name,
    providerName = providerName,
    typeName = type,
    year = year,
    plot = plot?.withoutMarkup(),
    posterUrl = posterUrl,
    playData = dataUrl,
    episodes = episodes.map { DetailEpisode(it.name, it.data, it.season, it.episode) },
    score = facts.scoreTenths?.let { it / TENTHS },
    tags = facts.tags,
    contentRating = facts.contentRating,
    statusName = facts.status?.name,
    durationMinutes = facts.runtimeMinutes,
    // The sandbox's own, already checked (`KMP-100`). No fallback to the poster: E1 draws plain ground without one.
    backdropUrl = backdropUrl,
)

/**
 * The plot as plain text. Providers send HTML (`<br>` in a YouTube description) and nothing here
 * renders it, so tags are dropped and never interpreted.
 */
private fun String.withoutMarkup(): String =
    replace(BREAK_TAG, " ").replace(ANY_TAG, "").replace(RUN_OF_SPACES, " ").trim()

private const val TENTHS = 10f
private val BREAK_TAG = Regex("(?i)<br\\s*/?>")
private val ANY_TAG = Regex("<[^>]*>")
private val RUN_OF_SPACES = Regex("\\s+")

// BUG-44/47: the fourth of four identical copies of the redaction rules, which must stay in sync: `:app`
// `utils/LogRedaction.kt`, `:library` `mvvm/ArchComponentExt.kt`, `:feature:extensions:sandbox` `PluginHost.kt`, and this one
// (`KMP-56`). The sandbox's is `internal` to its module, so `:desktopApp` cannot call it. `DesktopRedactionTest` runs the same table.

/** `file:/…`, with any number of slashes, up to the next whitespace. */
private val FILE_URI = Regex("(?i)\\bfile:/\\S+")

/**
 * `scheme://[userinfo@]host[:port]`, then whatever follows up to the next whitespace. BUG-52: also
 * when whitespace or a Unicode space (`\p{Z}`, NBSP among them) splits `scheme:` from `//`. BUG-64: and when
 * up to two other characters do, such as a braille blank (U+2800), which a screen draws as nothing, or up to two
 * that are neither letters nor digits sit between the slashes.
 */
private val URL_IN_TEXT = Regex(
    "(?i)\\b([a-z][a-z0-9+.-]*):[\\s\\p{Z}]*(?:[^\\s\\p{Z}/][\\s\\p{Z}]*){0,2}/[\\s\\p{Z}]*(?:[^\\s\\p{Z}/\\p{L}\\p{N}][\\s\\p{Z}]*){0,2}/(?:[^\\s/?#@]*@)?([^\\s/?#@]*)(\\S*)"
)

/**
 * An absolute path under a storage, home or volume root, up to a newline, a quote, `": "` or the end. A path may hold
 * spaces, so it is not cut at whitespace: what follows a path on its line is lost with it, which is the safe way round.
 */
private val USER_PATH = Regex(
    "(?<![\\w.:/-])/(?:storage|sdcard|data/user|mnt|Users|home|Volumes)(?:/[^\\r\\n\"'<>]*?)?(?=: |[\\r\\n\"'<>]|\\z)"
)

/** BUG-47: a Windows home path, `C:\Users\…` (any drive letter, either case, either slash), with the same ends as [USER_PATH]. */
private val WINDOWS_USER_PATH = Regex(
    "(?<![\\w.:/\\\\-])(?i:[a-z]):[\\\\/](?i:users)(?:[\\\\/][^\\r\\n\"'<>]*?)?(?=: |[\\r\\n\"'<>]|\\z)"
)

/**
 * BUG-52: control and format characters (Unicode Cc and Cf: ZWSP, ZWJ, ZWNJ, BOM, bidi marks and the
 * rest) out before any pattern runs, since one inside `://` (`https:<ZWSP>//`) would hide a url from
 * [URL_IN_TEXT]. Line breaks stay, because they shape a stack trace. A tab becomes a space: a screen
 * that strips controls after redaction (Home, Detail, the HUD) would otherwise rejoin `https:<tab>//`.
 * BUG-64: then every code point Unicode calls default-ignorable, which Cc and Cf do not cover.
 */
private fun String.withoutHiddenCharacters(): String = replace('\t', ' ').filterNot { c ->
    c.category == CharCategory.FORMAT ||
        (c.category == CharCategory.CONTROL && c != '\n' && c != '\r')
}.withoutDefaultIgnorable()

/**
 * BUG-64: Unicode's Default_Ignorable_Code_Point (DerivedCoreProperties.txt), the code points a screen draws nothing
 * for. Cc and Cf miss some of them: U+034F, the variation selectors, the Hangul fillers. One of these inside `://` or
 * `/Users/` hides it from a pattern while the text reads the same. The same list is in every copy of this check.
 */
private fun isDefaultIgnorable(codePoint: Int): Boolean = when (codePoint) {
    0x00AD, 0x034F, 0x061C, 0x3164, 0xFEFF, 0xFFA0 -> true
    in 0x115F..0x1160, in 0x17B4..0x17B5, in 0x180B..0x180F, in 0x200B..0x200F, in 0x202A..0x202E,
    in 0x2060..0x206F, in 0xFE00..0xFE0F, in 0xFFF0..0xFFF8, in 0x1BCA0..0x1BCA3, in 0x1D173..0x1D17A,
    in 0xE0000..0xE0FFF -> true
    else -> false
}

/** Without [isDefaultIgnorable]'s code points, read a code point at a time, so a surrogate pair goes or stays whole. */
private fun String.withoutDefaultIgnorable(): String {
    val text = this
    return buildString(text.length) {
        var i = 0
        while (i < text.length) {
            val pair = text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
            val codePoint =
                if (pair) 0x10000 + ((text[i].code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00) else text[i].code
            val end = if (pair) i + 2 else i + 1
            if (!isDefaultIgnorable(codePoint)) appendRange(text, i, end)
            i = end
        }
    }
}

/**
 * Text with a `file:` uri, a path under `/storage`, `/sdcard`, `/data/user`, `/mnt`, `/Users`, `/home` or `/Volumes`, or a
 * Windows `C:\Users\…` path replaced by `<path>`, and every other url cut to `scheme://host[:port]/<…>`. The host is kept
 * because it helps triage and is not a secret; the userinfo, path, query and fragment are not.
 *
 * Invisible characters go first, inside this (BUG-52): a format character inside `://` (`https:<ZWSP>//`) would hide
 * the url from the pattern, and stripping it afterwards would put the whole url back together on screen.
 */
internal fun String.withoutUrls(): String = this
    .withoutHiddenCharacters()
    .replace(FILE_URI, "<path>")
    .replace(URL_IN_TEXT) { match ->
        val (scheme, host, rest) = match.destructured
        "$scheme://$host" + if (rest.isEmpty()) "" else "/<…>"
    }
    .replace(USER_PATH, "<path>")
    .replace(WINDOWS_USER_PATH, "<path>")

/**
 * Control and format characters out, and every default-ignorable code point (BUG-64), so nothing invisible can split a
 * url or a path from [withoutUrls]'s patterns.
 */
internal fun String.withoutInvisible(): String =
    filterNot { it.isISOControl() || it.category == CharCategory.FORMAT }.withoutDefaultIgnorable()

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
    quality = quality,
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

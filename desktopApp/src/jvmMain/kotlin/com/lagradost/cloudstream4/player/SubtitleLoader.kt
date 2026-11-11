package com.lagradost.cloudstream4.player

import com.lagradost.cloudstream4.feature.extensions.sandbox.SandboxPolicy
import com.lagradost.cloudstream4.media.model.SubtitleSource
import com.lagradost.cloudstream4.media.model.SubtitleTrack
import com.lagradost.cloudstream4.media.subtitle.SubtitleParser
import com.lagradost.cloudstream4.media.subtitle.SubtitleParseResult
import com.lagradost.cloudstream4.media.subtitle.SubtitleTimeline
import com.lagradost.cloudstream4.search.posterHttpClient
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Why a chosen track shows nothing. No words: the player turns this into a sentence where it draws it. */
internal enum class SubtitleFailure {
    /** Refused, unreachable, too big, too slow, or an error status. All one fact to the person watching. */
    Fetch,

    /** It arrived, and it is in a format `:media:subtitle` does not read. */
    UnsupportedFormat,

    /** It arrived, in a format this reads, with no cues in it. */
    NoCues,
}

internal sealed interface SubtitleLoad {
    data class Ready(val timeline: SubtitleTimeline, val report: SubtitleParseResult, val bytes: Int) : SubtitleLoad
    data class Failed(val reason: SubtitleFailure) : SubtitleLoad
}

/** A seam for the player's tests, and because a track need not always come over HTTP. */
internal fun interface SubtitleLoader {
    /** Never throws for anything a track or a server does. Cancellation still propagates. */
    suspend fun load(track: SubtitleTrack): SubtitleLoad
}

/**
 * Fetches and parses a track the user chose, **in this process**.
 *
 * The other candidate was the media host, and it is the wrong side for this. Its Seatbelt policy lets it
 * connect anywhere but localhost, so from there a plugin's URL could reach the LAN, and nothing can check
 * the address it really connects to (`SeatbeltProfile`, `DesktopDetailSource`). Here the same client as
 * posters checks the address of every connection, including every redirect. What this process then does
 * with the bytes is bounded and pure: `:media:subtitle` has no `Regex` and runs in linear time over input
 * it treats as hostile, where the media host is there for a C decoder.
 *
 * - **Addresses:** [SandboxPolicy.isPublicHttpUrl] before anything opens, then `posterHttpClient`'s DNS
 *   filter and connection check on every hop, so a redirect is checked like the first request.
 * - **Redirects** are followed here, not by okhttp (`BUG-94`), at most [MAX_REDIRECTS], and each is the URL check's
 *   again. One from https to http is refused, and the track's headers stay on its own host: see [nextHop].
 * - **Size and time:** [MAX_BYTES] after decompression, however the body says it is encoded, and
 *   [CALL_TIMEOUT_SECONDS] over the whole load: every redirect and the body. Each hop is its own call, and is given
 *   only what is left of that, so a chain of slow redirects cannot take 20 s each.
 * - **Sent:** only the track's `Referer` and `User-Agent`, and only to the track's own host. This client has no
 *   cookie jar and no cache.
 *
 * @param allowUrl replaceable, as with `posterHttpClient`, so a test can talk to a loopback server.
 */
internal class HttpSubtitleLoader(
    private val client: OkHttpClient = subtitleHttpClient(),
    private val allowUrl: (String) -> Boolean = SandboxPolicy::isPublicHttpUrl,
) : SubtitleLoader {

    override suspend fun load(track: SubtitleTrack): SubtitleLoad {
        val source = track.source as? SubtitleSource.Remote ?: return SubtitleLoad.Failed(SubtitleFailure.Fetch)
        return withContext(Dispatchers.IO) {
            val body = try {
                fetch(source)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                // okhttp refuses a URL or a header it cannot send. `HttpUrl` is stricter than `URI`.
                null
            } ?: return@withContext SubtitleLoad.Failed(SubtitleFailure.Fetch)
            parse(body, track.languageTag)
        }
    }

    private suspend fun fetch(source: SubtitleSource.Remote): Body? {
        // The exact string first, as the URL check has always seen it; each redirect's target after that.
        var checked = source.url
        var url = Request.Builder().url(source.url).build().url
        var headers = source.headers
        // The client's call limit is for the whole load. A hop past it fails in okhttp as an IOException, as one call
        // past its limit always has; a coroutine timeout could not stop a hop already blocked in `execute`.
        val budget = client.callTimeoutMillis.toLong()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budget)
        repeat(MAX_REDIRECTS + 1) {
            // BUG-98: a load cancelled between hops starts no other.
            currentCoroutineContext().ensureActive()
            if (!allowUrl(checked)) return null
            val request = Request.Builder().url(url)
            headers.forEach { (name, value) -> runCatching { request.header(name, value) } }
            val call = client.newCall(request.build())
            if (budget > 0) {
                val left = deadline - System.nanoTime()
                if (left <= 0) return null
                call.timeout().timeout(left, TimeUnit.NANOSECONDS)
            }
            when (val outcome = cancellable(call) { blockingHop(call, url) }) {
                is HopOutcome.Done -> return outcome.body
                is HopOutcome.Redirect -> {
                    if (!outcome.hop.keepsHeaders) headers = emptyMap()
                    url = outcome.to
                    checked = outcome.to.toString()
                }
            }
        }
        return null
    }

    /**
     * [block] with [call] closed the moment this coroutine is cancelled (`BUG-98`, as `#345` does for repositories).
     * The call blocks its thread, and a handler run on the job's completion waits for that thread, so it only ran once
     * the call had ended by itself: up to the whole load's time limit after a cancel. This child is suspended until the
     * caller is cancelled and closes the call then; the blocked read fails at once.
     */
    private suspend fun <T> cancellable(call: Call, block: () -> T): T = coroutineScope {
        val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            block()
        } finally {
            canceller.cancel()
        }
    }

    /** One hop's request, on this thread. A redirect is returned to be checked; anything else ends the load. */
    private fun blockingHop(call: Call, url: HttpUrl): HopOutcome = call.execute().use { response ->
        if (response.isRedirect) {
            val target = response.header(LOCATION)?.let(url::resolve) ?: return HopOutcome.Done(null)
            val hop = nextHop(url, target) ?: return HopOutcome.Done(null)
            return HopOutcome.Redirect(target, hop)
        }
        if (!response.isSuccessful) return HopOutcome.Done(null)
        val body = response.body ?: return HopOutcome.Done(null)
        val declared = body.contentType()?.charset()
        if (body.contentLength() > MAX_BYTES) return HopOutcome.Done(null)
        val raw = body.byteStream().use { it.readAtMost(MAX_BYTES) } ?: return HopOutcome.Done(null)
        HopOutcome.Done(raw.gunzippedOrNull()?.let { Body(it, declared) })
    }

    private sealed interface HopOutcome {
        class Done(val body: Body?) : HopOutcome

        class Redirect(val to: HttpUrl, val hop: Hop) : HopOutcome
    }

    private class Body(val bytes: ByteArray, val declared: Charset?)

    /**
     * A redirect that may be followed; [keepsHeaders] when it stays on the host the track's headers were for: the same
     * scheme and port, or an upgrade to https there ([nextHop]).
     */
    internal data class Hop(val keepsHeaders: Boolean)

    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        const val CALL_TIMEOUT_SECONDS = 20L
        /** Fewer than okhttp's 20: a subtitle behind more than a couple is not one anyone offers. */
        const val MAX_REDIRECTS = 5
        private const val LOCATION = "Location"
        private const val CONNECT_TIMEOUT_SECONDS = 10L

        /**
         * Most of [posterHttpClient]'s reasoning applies word for word; this adds the time bound, and follows no
         * redirect of its own: [HttpSubtitleLoader] does, so it can check each one.
         */
        fun subtitleHttpClient(allow: ((InetSocketAddress) -> Boolean)? = null): OkHttpClient =
            (allow?.let { posterHttpClient(it) } ?: posterHttpClient()).newBuilder()
                .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

        /**
         * Whether a redirect from [from] to [to] may be followed, and with which headers (`BUG-94`). Pure, for tests.
         *
         * - **https to http is refused:** the track's `Referer` and `User-Agent` would go in clear text, and so would
         *   the file. http to https is an upgrade, and is followed.
         * - **The headers stay on their own host:** the same scheme and port, or an upgrade to https there. A plugin's
         *   `Referer` can carry a token in its query, and another host is the redirecting server's choice, not the
         *   plugin's. An upgrade keeps them because a server that wants a `Referer` often redirects to https first.
         */
        internal fun nextHop(from: HttpUrl, to: HttpUrl): Hop? {
            if (from.isHttps && !to.isHttps) return null
            val sameOrigin = from.scheme == to.scheme && from.port == to.port
            return Hop(keepsHeaders = from.host == to.host && (sameOrigin || (!from.isHttps && to.isHttps)))
        }

        /** Text, parsed, or the reason there is nothing to show. Pure, for tests and the check. */
        fun parse(bytes: ByteArray, declared: Charset?, languageTag: String?): SubtitleLoad =
            parse(Body(bytes, declared), languageTag)

        private fun parse(body: Body, languageTag: String?): SubtitleLoad {
            val result = decodeAndParse(body.bytes, body.declared, languageTag)
            return when {
                result.format == null -> SubtitleLoad.Failed(SubtitleFailure.UnsupportedFormat)
                result.cues.isEmpty() -> SubtitleLoad.Failed(SubtitleFailure.NoCues)
                else -> SubtitleLoad.Ready(SubtitleTimeline(result.cues), result, body.bytes.size)
            }
        }

        private fun decodeAndParse(bytes: ByteArray, declared: Charset?, languageTag: String?): SubtitleParseResult {
            // A BOM decides, and the parser reads those itself.
            if (bytes.startsWithBom()) return SubtitleParser.parse(bytes)
            val text = bytes.strictUtf8() ?: String(bytes, declared ?: legacyCharsetFor(languageTag))
            return SubtitleParser.parse(text)
        }

        private fun ByteArray.startsWithBom(): Boolean {
            fun at(i: Int) = this[i].toInt() and BYTE_MASK
            return (size >= 3 && at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF) ||
                (size >= 2 && at(0) == 0xFF && at(1) == 0xFE) ||
                (size >= 2 && at(0) == 0xFE && at(1) == 0xFF)
        }

        private fun ByteArray.strictUtf8(): String? = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(this))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }

        /**
         * What a file that is not UTF-8 and has no BOM is most likely in, from its track's language.
         *
         * A guess, and the only one available without a new dependency: `:app` uses `UniversalDetector`
         * here. A wrong guess shows wrong letters and never fails, which is what the old default did too.
         */
        internal fun legacyCharsetFor(languageTag: String?): Charset {
            val name = LEGACY_CHARSETS[languageTag?.substringBefore('-')?.lowercase()] ?: DEFAULT_LEGACY_CHARSET
            return runCatching { Charset.forName(name) }.getOrDefault(Charsets.ISO_8859_1)
        }

        private const val DEFAULT_LEGACY_CHARSET = "windows-1252"
        private const val BYTE_MASK = 0xFF
        private val LEGACY_CHARSETS = buildMap {
            listOf("ar", "fa", "ur").forEach { put(it, "windows-1256") }
            listOf("ru", "uk", "bg", "be", "sr", "mk").forEach { put(it, "windows-1251") }
            listOf("cs", "sk", "pl", "hu", "ro", "hr", "sl", "bs").forEach { put(it, "windows-1250") }
            put("el", "windows-1253")
            put("he", "windows-1255")
            put("iw", "windows-1255")
            put("tr", "windows-1254")
            put("th", "windows-874")
            put("vi", "windows-1258")
            put("ja", "Shift_JIS")
            put("ko", "EUC-KR")
            put("zh", "GBK")
        }
    }
}

/** Null when there is more than [limit]. The limit is what this reads, whatever the body claimed. */
internal fun InputStream.readAtMost(limit: Int): ByteArray? {
    val bytes = readNBytes(limit + 1)
    return bytes.takeIf { it.size <= limit }
}

/**
 * The body itself, or its contents if it is a gzip file, or null if those run past the limit.
 *
 * okhttp already undoes `Content-Encoding: gzip` for a request it added the header to. This is for a `.gz`
 * file served as itself, which `:app` also unpacks. A corrupt one is left as it is and fails as a format.
 */
private fun ByteArray.gunzippedOrNull(): ByteArray? {
    if (size < 2 || this[0] != GZIP_FIRST || this[1] != GZIP_SECOND) return this
    return try {
        GZIPInputStream(ByteArrayInputStream(this)).use { it.readAtMost(HttpSubtitleLoader.MAX_BYTES) }
    } catch (_: IOException) {
        this
    }
}

private const val GZIP_FIRST = 0x1F.toByte()
private const val GZIP_SECOND = 0x8B.toByte()

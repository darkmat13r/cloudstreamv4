package com.lagradost.cloudstream4.player

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream4.detail.bodyCap
import com.lagradost.cloudstream4.feature.extensions.sandbox.SandboxPolicy
import com.lagradost.cloudstream4.search.posterHttpClient
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

/**
 * An episode's skip stamps from the services Android's `SkipAPI` asks (`KMP-86`), in its order, stopping at the first
 * that has some: AniSkip, TheIntroDB, then IntroDb. Android's fourth, AnimeSkip, needs an account the Mac cannot log in
 * to, so Android sends it nothing either.
 *
 * What goes out is what Android sends: the same hosts, paths and query, byte for byte, built from constants and from ids
 * and numbers already in their exact shape ([SkipQuery]); the episode's length is the player's own. Nothing else: no
 * cookie, no Referer, and the generic browser User-Agent Android's client sends.
 *
 * - **Off:** [enabled] is asked first, every time, and when it says no nothing is sent at all.
 * - **Hosts:** the three, and only over https. Redirects are not followed, and a network interceptor refuses any other
 *   host or scheme as well, so a response cannot send the call on. Public addresses only, on every hop
 *   ([posterHttpClient]).
 * - **Size and time:** [MAX_BYTES] on the wire and again after decompression, and [CALL_TIMEOUT_SECONDS] over the
 *   whole call, body included.
 * - **What comes back** is hostile: a stamp is kept only if it is finite and `0 <= start < end <= length`, at most
 *   [MAX_STAMPS] of them. No response text reaches the screen or the log, and neither does the title, an id or a URL.
 * - **Kept** in memory only, for the session, [MAX_CACHED] episodes at most, as Android keeps its own.
 *
 * @param scheme,port replaceable only so a test can talk to a server on this machine.
 */
internal class SkipServices(
    private val enabled: () -> Boolean,
    private val client: OkHttpClient = skipHttpClient(),
    private val scheme: String = "https",
    private val port: Int? = null,
) {
    private val cache = object : LinkedHashMap<Pair<SkipQuery, Long>, List<SkipStamp>>(MAX_CACHED, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<SkipQuery, Long>, List<SkipStamp>>?) =
            size > MAX_CACHED
    }

    /** The source for one episode, for the player. */
    fun source(query: SkipQuery): SkipStampSource = SkipStampSource { durationMs -> stamps(query, durationMs) }

    suspend fun stamps(query: SkipQuery, durationMs: Long): List<SkipStamp> {
        if (!enabled()) return emptyList()
        val lengthSeconds = durationMs / MILLIS_PER_SECOND
        if (lengthSeconds <= 0) return emptyList()
        val key = query to durationMs
        synchronized(cache) { cache[key] }?.let { return it }
        for (service in Service.entries) {
            if (!enabled()) return emptyList()
            val url = service.url(query, lengthSeconds, ::base) ?: continue
            val found = fetch(url)?.let { body -> service.parseOrNone(body, durationMs) }.orEmpty()
                .filter { it.startMs >= 0 && it.startMs < it.endMs && it.endMs <= durationMs }
                .take(MAX_STAMPS)
            if (found.isNotEmpty()) {
                synchronized(cache) { cache[key] = found }
                return found
            }
        }
        return emptyList()
    }

    /** The body, or null for any failure at all: a refused hop, a timeout, a status that is not 2xx, too many bytes. */
    private suspend fun fetch(url: HttpUrl): String? = withContext(Dispatchers.IO) {
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
        val cancel = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val source = response.body?.source() ?: return@withContext null
                // After OkHttp's own gunzip, which the network cap below it never sees.
                val read = Buffer()
                while (read.size <= MAX_BYTES) {
                    if (source.read(read, READ_STEP) == -1L) break
                }
                if (read.size > MAX_BYTES) return@withContext null
                read.readUtf8()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            null
        } finally {
            cancel?.dispose()
        }
    }

    private fun base(host: String): HttpUrl.Builder =
        HttpUrl.Builder().scheme(scheme).host(host).also { builder -> port?.let(builder::port) }

    /** Android's services, in its order. */
    private enum class Service {
        AniSkip {
            override fun url(query: SkipQuery, lengthSeconds: Long, base: (String) -> HttpUrl.Builder): HttpUrl? {
                if (query.typeName !in ANIME_TYPES) return null
                val malId = query.ids.malId ?: return null
                val episode = query.episode?.takeIf { it in EPISODE_NUMBERS } ?: return null
                return base(ANISKIP_HOST)
                    .addPathSegments("v2/skip-times")
                    .addPathSegment(malId.toString())
                    .addPathSegment(episode.toString())
                    // Encoded, so `types[]` goes out as Android's string does, brackets and all.
                    .apply { ANISKIP_TYPES.forEach { addEncodedQueryParameter("types[]", it) } }
                    .addQueryParameter("episodeLength", lengthSeconds.toString())
                    .build()
            }

            override fun parse(body: String, durationMs: Long): List<SkipStamp> =
                json.decodeFromString<AniSkipResponse>(body).results.orEmpty().mapNotNull { stamp ->
                    val kind = when (stamp.skipType) {
                        "op" -> SkipKind.Opening
                        "ed" -> SkipKind.Ending
                        "recap" -> SkipKind.Recap
                        "mixed-ed" -> SkipKind.MixedEnding
                        "mixed-op" -> SkipKind.MixedOpening
                        else -> null
                    } ?: return@mapNotNull null
                    stamp.interval.let { stampOf(kind, it.startTime.millis() ?: return@mapNotNull null, it.endTime.millis()) }
                }
        },
        TheIntroDb {
            override fun url(query: SkipQuery, lengthSeconds: Long, base: (String) -> HttpUrl.Builder): HttpUrl? {
                if (query.typeName !in THEINTRODB_TYPES) return null
                val builder = base(THEINTRODB_HOST).addPathSegments("v2/media")
                when {
                    query.ids.tmdbId != null -> builder.addQueryParameter("tmdb_id", query.ids.tmdbId.toString())
                    query.ids.imdbId != null -> builder.addQueryParameter("imdb_id", query.ids.imdbId)
                    else -> return null
                }
                if (!query.movie) {
                    val season = query.season?.takeIf { it in EPISODE_NUMBERS } ?: return null
                    val episode = query.episode?.takeIf { it in EPISODE_NUMBERS } ?: return null
                    builder.addQueryParameter("season", season.toString()).addQueryParameter("episode", episode.toString())
                }
                return builder.build()
            }

            override fun parse(body: String, durationMs: Long): List<SkipStamp> {
                val root = json.decodeFromString<TheIntroDbRoot>(body)
                return listOf(
                    root.intro to SkipKind.Intro,
                    root.credits to SkipKind.Credits,
                    root.recap to SkipKind.Recap,
                    root.preview to SkipKind.Preview,
                ).flatMap { (stamps, kind) ->
                    // Android's reading: no start is the beginning, no end is the end.
                    stamps.mapNotNull { stampOf(kind, it.startMs ?: 0L, it.endMs ?: durationMs) }
                }
            }
        },
        IntroDb {
            override fun url(query: SkipQuery, lengthSeconds: Long, base: (String) -> HttpUrl.Builder): HttpUrl? {
                if (query.typeName !in INTRODB_TYPES) return null
                val season = query.season?.takeIf { it in EPISODE_NUMBERS } ?: return null
                val imdbId = query.ids.imdbId ?: return null
                val episode = query.episode?.takeIf { it in EPISODE_NUMBERS } ?: return null
                return base(INTRODB_HOST)
                    .addPathSegment("segments")
                    .addQueryParameter("imdb_id", imdbId)
                    .addQueryParameter("season", season.toString())
                    .addQueryParameter("episode", episode.toString())
                    .build()
            }

            override fun parse(body: String, durationMs: Long): List<SkipStamp> {
                val response = json.decodeFromString<IntroDbResponse>(body)
                return listOfNotNull(
                    response.intro?.stamp(SkipKind.Opening),
                    response.recap?.stamp(SkipKind.Recap),
                    response.outro?.stamp(SkipKind.Ending),
                )
            }
        };

        /** Built on [base], a constant host; null where this service is not asked about [query]. */
        abstract fun url(query: SkipQuery, lengthSeconds: Long, base: (String) -> HttpUrl.Builder): HttpUrl?

        /** What the body says, unchecked against the episode; [stamps] checks it. Throws on a body it cannot read. */
        abstract fun parse(body: String, durationMs: Long): List<SkipStamp>

        /** An unreadable body is no stamps, and the next service is asked. */
        fun parseOrNone(body: String, durationMs: Long): List<SkipStamp> = try {
            parse(body, durationMs)
        } catch (_: SerializationException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    @Serializable
    private class AniSkipResponse(val results: List<AniSkipStamp>? = null)

    @Serializable
    private class AniSkipStamp(val interval: AniSkipInterval, val skipType: String)

    @Serializable
    private class AniSkipInterval(val startTime: Double, val endTime: Double)

    @Serializable
    private class TheIntroDbRoot(
        val intro: List<TheIntroDbStamp> = emptyList(),
        val recap: List<TheIntroDbStamp> = emptyList(),
        val credits: List<TheIntroDbStamp> = emptyList(),
        val preview: List<TheIntroDbStamp> = emptyList(),
    )

    @Serializable
    private class TheIntroDbStamp(@SerialName("start_ms") val startMs: Long? = null, @SerialName("end_ms") val endMs: Long? = null)

    @Serializable
    private class IntroDbResponse(val intro: IntroDbSegment? = null, val recap: IntroDbSegment? = null, val outro: IntroDbSegment? = null)

    @Serializable
    private class IntroDbSegment(@SerialName("start_ms") val startMs: Long? = null, @SerialName("end_ms") val endMs: Long? = null) {
        fun stamp(kind: SkipKind): SkipStamp? = stampOf(kind, startMs ?: return null, endMs ?: return null)
    }

    companion object {
        const val ANISKIP_HOST = "api.aniskip.com"
        const val THEINTRODB_HOST = "api.theintrodb.org"
        const val INTRODB_HOST = "api.introdb.app"

        /** The only hosts this client will talk to. */
        val HOSTS = setOf(ANISKIP_HOST, THEINTRODB_HOST, INTRODB_HOST)

        /** A stamp list is a few hundred bytes; this is far past any real one. */
        const val MAX_BYTES = 1024L * 1024L
        const val CALL_TIMEOUT_SECONDS = 10L
        const val MAX_STAMPS = 16
        const val MAX_CACHED = 64

        /** The season and episode numbers any service is sent. Anything else is not a number worth asking about. */
        val EPISODE_NUMBERS = 0..10_000

        private val ANIME_TYPES = setOf("Anime", "OVA")
        private val THEINTRODB_TYPES = setOf("TvSeries", "Cartoon", "Anime", "Movie", "AsianDrama")
        private val INTRODB_TYPES = setOf("TvSeries", "AsianDrama")

        /** Android's `types[]`, in its order. */
        private val ANISKIP_TYPES = listOf("ed", "mixed-ed", "mixed-op", "op", "recap")

        private const val MILLIS_PER_SECOND = 1000L
        private const val READ_STEP = 8192L
        private const val LOAD_FACTOR = 0.75f

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The poster client (public addresses only, on every hop), with no redirect followed, any hop to another host or
         * over http refused, the body capped on the wire, and the whole call timed.
         *
         * @param hosts and [httpsOnly] replaceable only so a test can serve from this machine, and [callTimeoutSeconds] so
         *   one can wait less than the real ten.
         */
        fun skipHttpClient(
            allow: (InetSocketAddress) -> Boolean = { SandboxPolicy.isPublicAddress(it.address) },
            hosts: Set<String> = HOSTS,
            httpsOnly: Boolean = true,
            dns: okhttp3.Dns? = null,
            callTimeoutSeconds: Long = CALL_TIMEOUT_SECONDS,
        ): OkHttpClient = posterHttpClient(allow).newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
            .apply { dns?.let(::dns) }
            .addNetworkInterceptor(
                Interceptor { chain ->
                    val url = chain.request().url
                    if (url.host !in hosts) throw IOException("skip stamps refused: not a skip service")
                    if (httpsOnly && !url.isHttps) throw IOException("skip stamps refused: not https")
                    chain.proceed(chain.request())
                }
            )
            .addNetworkInterceptor(bodyCap(MAX_BYTES))
            .build()

        /** Seconds as a skip service writes them, in milliseconds, or null for NaN, an infinity or a negative. */
        private fun Double.millis(): Long? = takeIf { it.isFinite() && it >= 0.0 }?.let { (it * MILLIS_PER_SECOND).toLong() }

        private fun stampOf(kind: SkipKind, startMs: Long, endMs: Long?): SkipStamp? =
            endMs?.let { SkipStamp(kind, startMs, it) }
    }
}

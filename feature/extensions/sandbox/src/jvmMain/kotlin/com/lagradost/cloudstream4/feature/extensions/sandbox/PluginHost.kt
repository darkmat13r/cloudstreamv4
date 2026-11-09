package com.lagradost.cloudstream4.feature.extensions.sandbox

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.EpisodeResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.getMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.getTMDbId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream4.feature.extensions.InstalledExtension
import com.lagradost.cloudstream4.feature.extensions.JvmExtensionRuntime
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.NewPipe

/**
 * The child process: everything here runs inside the sandbox.
 *
 * Two modes, each its own process under its own policy, so the stage that parses hostile bytes
 * cannot reach the network and the stage that runs plugin code cannot reach the converted output.
 *
 * - `convert <in.cs3> <out.jar>`
 * - `serve <storeDir> <jarName> <dataDir>`
 */
object PluginHost {
    // BUG-44: one of three identical copies, which must stay in sync: `:app` `utils/LogRedaction.kt`,
    // `:library` `mvvm/ArchComponentExt.kt` and `:feature:extensions:sandbox` `PluginHost.kt`.
    // `:library` is the published API and cannot depend on an internal module, so they cannot share one.

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
     * An absolute path under a storage, home or volume root, up to a newline, a quote, `": "` or the
     * end. A path may hold spaces, so it is not cut at whitespace: what follows a path on its line is
     * lost with it, which is the safe way round.
     */
    private val USER_PATH = Regex(
        "(?<![\\w.:/-])/(?:storage|sdcard|data/user|mnt|Users|home|Volumes)(?:/[^\\r\\n\"'<>]*?)?(?=: |[\\r\\n\"'<>]|\\z)"
    )
    
    /**
     * BUG-47: a Windows home path, `C:\Users\…` (any drive letter, either case, either slash), with the
     * same ends as [USER_PATH].
     */
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
     * Text with a `file:` uri, a path under `/storage`, `/sdcard`, `/data/user`, `/mnt`, `/Users`, `/home`
     * or `/Volumes`, or a Windows `C:\Users\…` path replaced by `<path>`, and every other url cut to
     * `scheme://host[:port]/<…>`. The host is kept because it helps triage and is not a secret; the
     * userinfo, path, query and fragment are not.
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


    /** Passed first on the command line. It is how a packaged launcher knows to become a host. */
    const val ARGUMENT = "--plugin-host"

    /** True only inside a serving host, and read by [HostMainDispatcherFactory]. */
    @Volatile
    internal var running: Boolean = false
        private set

    fun main(args: Array<String>) {
        // Before anything else: nothing in the host may decide to put an icon in the Dock.
        System.setProperty("java.awt.headless", "true")
        // The protocol owns the real stdout. A plugin's println must not land in a frame.
        val protocolOut = FileOutputStream(FileDescriptor.out)
        System.setOut(System.err)
        val code = try {
            when (args.firstOrNull()) {
                MODE_CONVERT -> {
                    DexConverter.convert(File(args[1]), File(args[2]))
                    EXIT_OK
                }
                MODE_SERVE -> {
                    serve(File(args[1]), args[2], File(args[3]), protocolOut)
                    EXIT_OK
                }
                else -> EXIT_USAGE
            }
        } catch (failure: Throwable) {
            // BUG-83: the one line the app reads by default, fixed in shape. The detail line is for --verbose only.
            val name = failure::class.java.name.takeIf { HostStderr.CLASS_NAME.matches(it) } ?: "<class>"
            System.err.println(HostStderr.FAILURE_PREFIX + name)
            System.err.println("plugin host: ${failure::class.java.name}: ${failure.message?.withoutUrls()}")
            EXIT_FAILED
        }
        // halt, not exit: a plugin's shutdown hook or stuck thread must not be able to outlive us.
        Runtime.getRuntime().halt(code)
    }

    private fun serve(store: File, jarName: String, data: File, out: OutputStream) {
        System.setProperty("user.home", data.path)
        System.setProperty("java.io.tmpdir", data.path)
        HostContext.current = HostContext(data)
        running = true
        val input = FileInputStream(FileDescriptor.`in`)
        System.setIn(ByteArrayInputStream(ByteArray(0)))

        val writer = Writer(out)
        NewPipe.init(HostNewPipeDownloader())
        val runtime = JvmExtensionRuntime(store)
        val loaded = runBlocking {
            runtime.load(
                InstalledExtension(
                    internalName = jarName.removeSuffix(".jar"),
                    location = File(store, jarName).path,
                    version = Int.MIN_VALUE,
                    isFromRepository = false,
                )
            )
        }
        loaded.onFailure { failure ->
            writer.fail(HostProtocol.READY_ID, failure)
            return
        }
        val apis = APIHolder.apis.withLock { APIHolder.apis.toList() }.associateBy { it.name }
        writer.send(HostProtocol.READY_ID) {
            writeProviders(
                apis.values.map { api ->
                    // A plugin's getter can throw, and that must cost it its home page and not its search.
                    val sections = runCatching {
                        api.mainPage.take(HostProtocol.MAX_MAIN_PAGE_SECTIONS)
                            .map { RemoteMainPageSection(it.name, it.horizontalImages) }
                    }.getOrNull()
                    RemoteProvider(
                        name = api.name,
                        mainUrl = api.mainUrl,
                        lang = api.lang,
                        hasQuickSearch = api.hasQuickSearch,
                        supportedTypes = api.supportedTypes.map { it.name },
                        hasMainPage = sections != null && runCatching { api.hasMainPage }.getOrDefault(false),
                        mainPage = sections.orEmpty(),
                        sequentialDelayMillis = runCatching {
                            api.sequentialMainPageDelay.takeIf { api.sequentialMainPage }
                        }.getOrNull(),
                    )
                }
            )
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val linkCalls = ConcurrentHashMap<Int, LinkCall>()
        while (true) {
            // End of stream means the app went away, however it died. Returning halts this process.
            val frame = HostProtocol.readFrame(input) ?: return
            val (id, op, rest) = HostProtocol.decode(frame) { Triple(readInt(), readInt(), readBytes()) }
            if (op == HostProtocol.OP_CANCEL) {
                // Nothing to find if it has just finished: its reply and this crossed on the way.
                linkCalls[id]?.cancel()
                continue
            }
            val linkCall = if (op == HostProtocol.OP_LOAD_LINKS) LinkCall(id, writer).also { linkCalls[id] = it } else null
            val job = scope.launch { answer(id, op, rest, apis, writer, linkCall) }
            if (linkCall != null) {
                linkCall.job = job
                job.invokeOnCompletion { linkCalls.remove(id) }
            }
        }
    }

    private suspend fun answer(
        id: Int,
        op: Int,
        rest: ByteArray,
        apis: Map<String, MainAPI>,
        writer: Writer,
        linkCall: LinkCall?,
    ) {
        try {
            DataInputStream(ByteArrayInputStream(rest)).use { args ->
                val api = checkNotNull(apis[args.readText()]) { "unknown provider" }
                when (op) {
                    HostProtocol.OP_SEARCH -> {
                        val query = args.readText()
                        val page = args.readInt()
                        val found = api.search(query, page) ?: newSearchResponseList(emptyList())
                        writer.send(id) { writeSearchPage(found.toRemote()) }
                    }
                    HostProtocol.OP_QUICK_SEARCH -> {
                        val found = newSearchResponseList(api.quickSearch(args.readText()).orEmpty())
                        writer.send(id) { writeSearchPage(found.toRemote()) }
                    }
                    HostProtocol.OP_LOAD -> {
                        val details = checkNotNull(api.load(args.readText())) { "provider returned nothing" }
                        writer.send(id) { writeDetails(details.toRemote()) }
                    }
                    HostProtocol.OP_LOAD_LINKS -> {
                        val call = checkNotNull(linkCall)
                        api.loadLinks(
                            data = args.readText(),
                            isCasting = false,
                            subtitleCallback = { file -> call.offer(file.toRemote()) },
                            callback = { link ->
                                call.offer(
                                    RemoteLink(
                                        source = link.source,
                                        name = link.name,
                                        url = link.url,
                                        quality = link.quality,
                                        type = link.type.name,
                                        referer = link.referer,
                                        userAgent = link.headers.entries
                                            .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value,
                                        audioUrl = link.audioTracks.firstOrNull()?.url,
                                    )
                                )
                            },
                        )
                        call.finish()
                    }
                    HostProtocol.OP_MAIN_PAGE -> {
                        val section = checkNotNull(api.mainPage.getOrNull(args.readInt())) { "unknown section" }
                        val request = MainPageRequest(section.name, section.data, section.horizontalImages)
                        val found = api.getMainPage(args.readInt(), request)
                        writer.send(id) { writeHomePage(found.toRemote()) }
                    }
                    else -> error("unknown operation $op")
                }
            }
        } catch (failure: Throwable) {
            // Throwable: plugins throw NotImplementedError and NoClassDefFoundError routinely, and
            // WebViewResolver's JVM actual is a `TODO()`. One failed call must not end the host.
            if (linkCall != null) linkCall.fail(failure) else writer.fail(id, failure)
        }
    }

    /**
     * One `loadLinks` on its way to the app: each link and subtitle as the plugin offers it, then one last reply.
     *
     * A plugin holds its callbacks for as long as it likes, and can call them from its own coroutine after
     * `loadLinks` has returned, so anything past the last reply is dropped here. It would otherwise end this host,
     * because the app treats a partial after the final as a violation. The same goes for the bounds: the app ends
     * a host that passes them, so an honest plugin offering too much is cut here instead.
     */
    private class LinkCall(private val id: Int, private val writer: Writer) {
        var job: Job? = null
        private var links = 0
        private var subtitles = 0
        private var bytes = 0L
        private var finished = false

        @Synchronized
        fun offer(link: RemoteLink) {
            if (links < HostProtocol.MAX_LINKS && send(RemoteLinks(listOf(link), emptyList()))) links++
        }

        @Synchronized
        fun offer(subtitle: RemoteSubtitle) {
            if (subtitles < HostProtocol.MAX_SUBTITLES && send(RemoteLinks(emptyList(), listOf(subtitle)))) subtitles++
        }

        private fun send(part: RemoteLinks): Boolean {
            if (finished) return false
            val frame = writer.partial(id, part)
            if (bytes + frame.size > HostProtocol.MAX_LINK_BYTES) return false
            bytes += frame.size
            writer.write(frame)
            return true
        }

        /** The answer has already gone, a piece at a time. */
        @Synchronized
        fun finish() {
            if (finished) return
            finished = true
            writer.send(id) { writeLinks(RemoteLinks(emptyList(), emptyList())) }
        }

        @Synchronized
        fun fail(failure: Throwable) {
            if (finished) return
            finished = true
            writer.fail(id, failure)
        }

        /** Answers now, whatever the plugin does. Stopping it takes its cooperation; the app does not wait for that. */
        fun cancel() {
            fail(CancellationException("cancelled"))
            job?.cancel()
        }
    }

    private fun SubtitleFile.toRemote() = RemoteSubtitle(
        language = lang,
        // `langTag` is `:library`'s table, reached with a string the plugin chose.
        languageTag = runCatching { langTag }.getOrNull(),
        url = url,
        referer = headers.named("Referer"),
        userAgent = headers.named("User-Agent"),
    )

    private fun Map<String, String>?.named(header: String): String? =
        this?.entries?.firstOrNull { it.key.equals(header, ignoreCase = true) }?.value

    private fun com.lagradost.cloudstream3.SearchResponseList.toRemote() = RemoteSearchPage(
        items = items.map { RemoteSearchItem(it.name, it.url, it.type?.name, it.posterUrl) },
        hasNext = hasNext,
    )

    /** Cut here too, so a large honest page is shortened rather than ending the host at the frame limit. */
    private fun HomePageResponse?.toRemote() = RemoteHomePage(
        rows = this?.items.orEmpty().take(HostProtocol.MAX_HOME_ROWS).map { row ->
            RemoteHomeRow(
                name = row.name,
                horizontalImages = row.isHorizontalImages,
                items = row.list.take(HostProtocol.MAX_HOME_ROW_ITEMS)
                    .map { RemoteSearchItem(it.name, it.url, it.type?.name, it.posterUrl) },
            )
        },
        hasNext = this?.hasNext == true,
    )

    private fun LoadResponse.toRemote(): RemoteDetails {
        val episodes: List<Episode> = when (this) {
            is TvSeriesLoadResponse -> episodes
            is AnimeLoadResponse -> episodes.values.flatten()
            else -> emptyList()
        }
        val dataUrl = when (this) {
            is MovieLoadResponse -> dataUrl
            is LiveStreamLoadResponse -> dataUrl
            else -> null
        }
        return RemoteDetails(
            name = name,
            url = url,
            type = type.name,
            plot = plot,
            posterUrl = posterUrl,
            year = year,
            dataUrl = dataUrl,
            episodes = episodes.map { RemoteEpisode(it.name, it.data, it.season, it.episode) },
            facts = facts(),
            // Courtesy, as the facts' cuts are: one too long to be read is not sent. The app checks what arrives.
            backdropUrl = backgroundPosterUrl?.takeIf { it.toByteArray().size <= HostProtocol.MAX_IMAGE_URL_BYTES },
            // `KMP-86`: the getters Android's skip services read. Courtesy again: the app checks each id's shape.
            ids = RemoteIds(
                malId = runCatching { getMalId() }.getOrNull()?.shortId(),
                tmdbId = runCatching { getTMDbId() }.getOrNull()?.shortId(),
                imdbId = runCatching { getImdbId() }.getOrNull()?.shortId(),
            ),
        )
    }

    /** One the app would skip unread is not sent (`KMP-86`). */
    private fun String.shortId(): String? = takeIf { it.toByteArray().size <= HostProtocol.MAX_ID_BYTES }

    /**
     * Cut to the app's bounds here too (`KMP-76`), so an honest provider with too many tags is shortened rather than
     * refused. This is courtesy, not the check: the plugin shares this process and can send what it likes, so the app
     * reads every fact as hostile whatever this does.
     */
    private fun LoadResponse.facts() = RemoteFacts(
        scoreTenths = score?.toInt(HostProtocol.MAX_SCORE_TENTHS)?.takeIf { it in 0..HostProtocol.MAX_SCORE_TENTHS },
        tags = tags.orEmpty().asSequence()
            .map { it.trim().take(HostProtocol.MAX_TAG_CHARS) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(HostProtocol.MAX_TAGS)
            .toList(),
        contentRating = contentRating?.trim()?.takeIf { it.length in 1..HostProtocol.MAX_CONTENT_RATING_CHARS },
        status = when ((this as? EpisodeResponse)?.showStatus) {
            ShowStatus.Ongoing -> RemoteShowStatus.Ongoing
            ShowStatus.Completed -> RemoteShowStatus.Completed
            null -> null
        },
        runtimeMinutes = duration?.takeIf { it in 1..HostProtocol.MAX_RUNTIME_MINUTES },
    )

    private class Writer(private val out: OutputStream) {
        fun send(id: Int, body: java.io.DataOutputStream.() -> Unit) = write(
            HostProtocol.encode {
                writeInt(id)
                writeByte(HostProtocol.REPLY_DONE)
                body()
            }
        )

        fun fail(id: Int, failure: Throwable) = write(
            HostProtocol.encode {
                writeInt(id)
                writeByte(HostProtocol.REPLY_FAILED)
                // #335 review: redacted, then cut. The client redacts again; a plugin can write frames of its own.
                writeText(
                    "${failure::class.java.simpleName}: ${failure.message.orEmpty()}"
                        .redactedWithin(PluginFailed.MAX_MESSAGE_CHARS)
                )
            }
        )

        fun partial(id: Int, part: RemoteLinks): ByteArray = HostProtocol.encode {
            writeInt(id)
            writeByte(HostProtocol.REPLY_PARTIAL)
            writeLinks(part)
        }

        fun write(frame: ByteArray) = synchronized(this) {
            out.write(frame)
            out.flush()
        }
    }

    internal const val MODE_CONVERT = "convert"
    internal const val MODE_SERVE = "serve"
    private const val EXIT_OK = 0
    private const val EXIT_USAGE = 64
    private const val EXIT_FAILED = 70
}

/** The host's entry point when not packaged, launched as a plain `java -cp … PluginHostMainKt`. */
fun main(args: Array<String>) {
    PluginHost.main(args)
    exitProcess(0)
}

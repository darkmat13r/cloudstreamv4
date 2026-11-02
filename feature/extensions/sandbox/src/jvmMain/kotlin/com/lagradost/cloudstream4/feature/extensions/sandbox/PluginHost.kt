package com.lagradost.cloudstream4.feature.extensions.sandbox

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    /** Passed first on the command line. It is how a packaged launcher knows to become a host. */
    const val ARGUMENT = "--plugin-host"

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
            System.err.println("plugin host: ${failure::class.java.name}: ${failure.message}")
            EXIT_FAILED
        }
        // halt, not exit: a plugin's shutdown hook or stuck thread must not be able to outlive us.
        Runtime.getRuntime().halt(code)
    }

    private fun serve(store: File, jarName: String, data: File, out: OutputStream) {
        System.setProperty("user.home", data.path)
        System.setProperty("java.io.tmpdir", data.path)
        HostContext.current = HostContext(data)
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
        while (true) {
            // End of stream means the app went away, however it died. Returning halts this process.
            val frame = HostProtocol.readFrame(input) ?: return
            scope.launch { answer(frame, apis, writer) }
        }
    }

    private suspend fun answer(frame: ByteArray, apis: Map<String, MainAPI>, writer: Writer) {
        val (id, op, rest) = HostProtocol.decode(frame) { Triple(readInt(), readInt(), readBytes()) }
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
                        val links = mutableListOf<RemoteLink>()
                        val subtitles = AtomicInteger()
                        api.loadLinks(
                            data = args.readText(),
                            isCasting = false,
                            subtitleCallback = { subtitles.incrementAndGet() },
                            callback = { link ->
                                synchronized(links) {
                                    links += RemoteLink(
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
                                }
                            },
                        )
                        val snapshot = synchronized(links) { links.toList() }
                        writer.send(id) { writeLinks(RemoteLinks(snapshot, subtitles.get())) }
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
            writer.fail(id, failure)
        }
    }

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
        )
    }

    private class Writer(private val out: OutputStream) {
        fun send(id: Int, body: java.io.DataOutputStream.() -> Unit) {
            val frame = HostProtocol.encode {
                writeInt(id)
                writeBoolean(true)
                body()
            }
            synchronized(this) {
                out.write(frame)
                out.flush()
            }
        }

        fun fail(id: Int, failure: Throwable) {
            val frame = HostProtocol.encode {
                writeInt(id)
                writeBoolean(false)
                writeText("${failure::class.java.simpleName}: ${failure.message.orEmpty()}".take(MAX_ERROR_CHARS))
            }
            synchronized(this) {
                out.write(frame)
                out.flush()
            }
        }
    }

    internal const val MODE_CONVERT = "convert"
    internal const val MODE_SERVE = "serve"
    private const val EXIT_OK = 0
    private const val EXIT_USAGE = 64
    private const val EXIT_FAILED = 70
    private const val MAX_ERROR_CHARS = 400
}

/** The host's entry point when not packaged, launched as a plain `java -cp … PluginHostMainKt`. */
fun main(args: Array<String>) {
    PluginHost.main(args)
    exitProcess(0)
}

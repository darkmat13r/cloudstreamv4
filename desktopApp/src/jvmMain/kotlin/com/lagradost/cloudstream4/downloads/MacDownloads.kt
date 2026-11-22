package com.lagradost.cloudstream4.downloads

import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream4.core.http.HttpClientFactory
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleId
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisodeId
import com.lagradost.cloudstream4.feature.extensions.sandbox.RemoteLink
import com.lagradost.cloudstream4.media.download.DownloadOptions
import com.lagradost.cloudstream4.search.posterHttpClient
import io.ktor.client.HttpClient
import java.io.File
import kotlinx.io.files.Path

/*
 * The Mac's side of the shared queue (`KMP-123`). `DesktopDownloads` moved to `:feature:downloads:queue`, which speaks
 * `kotlinx.io` paths and knows nothing of `:library`; this file keeps what the Mac said before the move, `File`s and
 * `ExtractorLinkType`, so nothing on the Mac changed with it.
 */

/**
 * The Mac's queue, as its constructor was before the move.
 *
 * @param downloadRoot where titles' folders go. Defaults to `~/Downloads/CloudStream`.
 * @param stateFile the downloads file. Defaults to `~/.cloudstream4/downloads.json`.
 */
internal fun DesktopDownloads(
    downloadRoot: File = DesktopDownloads.defaultDownloadRoot(),
    stateFile: File = defaultDownloadsFile(),
    httpClient: HttpClient? = null,
    /** Asks the extension for fresh links when there is none in memory: after a restart, and on every retry. */
    resolver: LinkResolver = SandboxLinkResolver(),
    /** The engine's options. Production takes the defaults; only a test may pass `allowPrivateAddresses`. */
    options: DownloadOptions = DownloadOptions(),
    maxRunning: Int = DesktopDownloads.MAX_RUNNING,
    resolveTimeoutMillis: Long = DesktopDownloads.RESOLVE_TIMEOUT_MILLIS,
    log: (String) -> Unit = ::defaultLog,
    ownsClient: Boolean = true,
    /** The size and free space of the volume holding a directory. The real volume in production; fixed in a test. */
    volumeSpace: (File) -> VolumeSpace = ::volumeSpaceOf,
    /** Settings' Downloads at once (`KMP-90`), read at every start; [maxRunning] when absent. */
    runningLimit: (() -> Int)? = null,
): DesktopDownloads = DesktopDownloads(
    downloadRoot = Path(downloadRoot.absolutePath),
    stateFile = Path(stateFile.absolutePath),
    httpClient = httpClient ?: defaultHttpClient(),
    resolver = resolver,
    options = options,
    maxRunning = maxRunning,
    resolveTimeoutMillis = resolveTimeoutMillis,
    log = log,
    ownsClient = ownsClient,
    disk = JvmQueueDisk,
    volumeSpace = { volumeSpace(File(it.toString())) },
    runningLimit = runningLimit,
)

internal fun DesktopDownloads.Companion.defaultDownloadRoot(): File =
    File(File(System.getProperty("user.home"), "Downloads"), "CloudStream")

private fun defaultDownloadsFile(): File = File(File(System.getProperty("user.home"), ".cloudstream4"), "downloads.json")

/** The volume [directory] is on, as the Mac has always read it. */
internal fun volumeSpaceOf(directory: File): VolumeSpace = VolumeSpace(directory.totalSpace, directory.usableSpace)

/**
 * One Ktor client for every run, over the client posters and subtitles use: its DNS filter and network interceptor
 * refuse a private address at the connection, which also closes the engine's DNS-rebinding residue on the Mac. TLS
 * is OkHttp's default, verified.
 */
private fun defaultHttpClient(): HttpClient = HttpClientFactory(posterHttpClient()).create()

private fun defaultLog(text: String) = System.err.println("DesktopDownloads: $text")

/** A film's file, or a series' folder, once every episode of it is done and on disk. See [DesktopDownloads.pathOf]. */
internal fun DesktopDownloads.fileOf(id: DownloadTitleId): File? = pathOf(id)?.let { File(it.toString()) }

/** The episode's file once it is done and on disk; null before then. */
internal fun DesktopDownloads.fileOf(id: DownloadEpisodeId): File? = pathOf(id)?.let { File(it.toString()) }

/** The file the player opens (`KMP-74`). */
internal val PlayableDownload.file: File get() = File(path.toString())

/** The folder [file] must still be inside when it is opened. */
internal val PlayableDownload.root: File get() = File(rootPath.toString())

/** A link as the Mac's extensions type it: only [ExtractorLinkType.VIDEO] is a direct file the queue downloads. */
internal fun DesktopDownloadLink(
    url: String,
    headers: Map<String, String> = emptyMap(),
    referer: String? = null,
    type: ExtractorLinkType,
): DesktopDownloadLink = DesktopDownloadLink(url, headers, referer, directFile = type == ExtractorLinkType.VIDEO)

/**
 * The link the sandboxed plugin host returned. It carries a user agent and a referer and no other header (see
 * `RemoteSubtitle`). A [RemoteLink.type] that is not an [ExtractorLinkType] name is null, and so is refused rather than
 * guessed at.
 */
internal fun downloadLinkOf(link: RemoteLink): DesktopDownloadLink? {
    val type = ExtractorLinkType.entries.firstOrNull { it.name == link.type } ?: return null
    return DesktopDownloadLink(
        url = link.url,
        headers = link.userAgent?.takeIf { it.isNotBlank() }?.let { mapOf("User-Agent" to it) }.orEmpty(),
        referer = link.referer,
        type = type,
    )
}

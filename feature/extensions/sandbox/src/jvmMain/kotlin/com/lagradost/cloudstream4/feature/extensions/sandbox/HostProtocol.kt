package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * The wire format between the app and a plugin host.
 *
 * ## Hand-written, and why not Java serialization or a reflective JSON mapper
 *
 * The host runs third-party code, so **every byte it sends is hostile input to the app**. Java
 * deserialization across this boundary would hand a compromised plugin code execution in the very
 * process the sandbox exists to protect. So this format can express only strings, numbers and lists
 * of the fixed records below, and every length is checked before anything is allocated.
 */
internal object HostProtocol {
    const val MAX_FRAME_BYTES = 8 * 1024 * 1024
    const val READY_ID = 0

    const val OP_SEARCH = 1
    const val OP_QUICK_SEARCH = 2
    const val OP_LOAD = 3
    const val OP_LOAD_LINKS = 4
    const val OP_MAIN_PAGE = 5

    /** To the host, with the id of a call to stop in place of a new one. It is answered by that call's own reply. */
    const val OP_CANCEL = 6

    /** What follows a reply's id. Anything else is a violation. */
    const val REPLY_FAILED = 0
    const val REPLY_DONE = 1

    /** Part of an answer, ahead of its [REPLY_DONE] or [REPLY_FAILED]. Only a links call may send one. */
    const val REPLY_PARTIAL = 2

    const val MAX_MAIN_PAGE_SECTIONS = 64
    const val MAX_HOME_ROWS = 64
    const val MAX_HOME_ROW_ITEMS = 200
    const val MAX_SEQUENTIAL_DELAY_MILLIS = 10_000L

    /** Far above YouTube's measured count (`KMP-25`). Cut in the host too, so an honest long list still fits a frame. */
    const val MAX_SUBTITLES = 200

    /** Most links one call may send. Past it the host stops sending them, and a host that does not stop is ended. */
    const val MAX_LINKS = 500

    /**
     * Most bytes of links and subtitles one call may send, across all its frames. The most it could ever send
     * when its answer was one frame, so sending it in parts costs the app nothing it did not already accept.
     */
    const val MAX_LINK_BYTES = MAX_FRAME_BYTES

    /** One length-prefixed frame, or `null` at a clean end of stream. */
    fun readFrame(input: InputStream): ByteArray? {
        val first = input.read()
        if (first < 0) return null
        val data = DataInputStream(input)
        val length = (first shl 24) or (data.readUnsignedByte() shl 16) or
            (data.readUnsignedByte() shl 8) or data.readUnsignedByte()
        if (length !in 0..MAX_FRAME_BYTES) throw IOException("frame length $length out of range")
        return ByteArray(length).also { data.readFully(it) }
    }

    fun encode(block: DataOutputStream.() -> Unit): ByteArray {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).use(block)
        val out = ByteArrayOutputStream(body.size() + Int.SIZE_BYTES)
        DataOutputStream(out).use {
            it.writeInt(body.size())
            body.writeTo(it)
        }
        return out.toByteArray()
    }

    fun <T> decode(frame: ByteArray, block: DataInputStream.() -> T): T =
        DataInputStream(ByteArrayInputStream(frame)).use(block)
}

internal fun DataOutputStream.writeText(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

internal fun DataOutputStream.writeOptional(value: String?) {
    writeBoolean(value != null)
    if (value != null) writeText(value)
}

internal fun DataInputStream.readText(): String {
    val length = readInt()
    if (length !in 0..available()) throw IOException("string length $length out of range")
    return String(readNBytes(length), Charsets.UTF_8)
}

internal fun DataInputStream.readOptional(): String? = if (readBoolean()) readText() else null

/** A count that has to be plausible against what is left, so a lying header cannot force a huge list. */
internal fun DataInputStream.readCount(): Int {
    val count = readInt()
    if (count !in 0..available()) throw IOException("count $count out of range")
    return count
}

/** What a loaded plugin registered, as told by its host. */
data class RemoteProvider(
    val name: String,
    val mainUrl: String,
    val lang: String,
    val hasQuickSearch: Boolean,
    val supportedTypes: List<String>,
    val hasMainPage: Boolean = false,
    val mainPage: List<RemoteMainPageSection> = emptyList(),
    /** The provider asks for its sections one at a time, this far apart. Null when it does not mind. */
    val sequentialDelayMillis: Long? = null,
)

/**
 * One entry of a provider's `MainAPI.mainPage`, addressed by its index.
 *
 * The provider's `data` string stays in the host: the app has no use for it, and a value that never
 * crosses this boundary does not have to be validated on the way back.
 */
data class RemoteMainPageSection(val name: String, val horizontalImages: Boolean)

/** One row of a provider's main page. A section may answer with several. */
data class RemoteHomeRow(val name: String, val horizontalImages: Boolean, val items: List<RemoteSearchItem>)

data class RemoteHomePage(val rows: List<RemoteHomeRow>, val hasNext: Boolean)

data class RemoteSearchItem(
    val name: String,
    val url: String,
    val type: String?,
    val posterUrl: String?,
)

data class RemoteSearchPage(val items: List<RemoteSearchItem>, val hasNext: Boolean)

data class RemoteEpisode(val name: String?, val data: String, val season: Int?, val episode: Int?)

data class RemoteDetails(
    val name: String,
    val url: String,
    val type: String,
    val plot: String?,
    val posterUrl: String?,
    val year: Int?,
    /** What to hand [PluginHostClient.loadLinks] for a movie or live stream; null for a series. */
    val dataUrl: String?,
    val episodes: List<RemoteEpisode>,
)

data class RemoteLink(
    val source: String,
    val name: String,
    val url: String,
    val quality: Int,
    val type: String,
    val referer: String?,
    val userAgent: String?,
    /** A separate audio stream, for a video-only link. */
    val audioUrl: String?,
)

/**
 * A subtitle track a plugin offered, as the app receives it.
 *
 * Only the two headers a [RemoteLink] may carry cross this boundary. A plugin's other headers stay in the
 * host, which is why a track that needs `Authorization` or a cookie will fail to fetch.
 *
 * @param language what the plugin called it: a name or a code. It is what the user is shown.
 * @param languageTag the BCP-47 tag `:library` resolved from [language], where it could.
 */
data class RemoteSubtitle(
    val language: String,
    val languageTag: String?,
    val url: String,
    val referer: String?,
    val userAgent: String?,
)

/**
 * @param stoppedEarly why this is not everything the plugin would have offered, for a report. Null for a whole
 *   answer. It does not cross the boundary: the app works it out.
 */
data class RemoteLinks(
    val links: List<RemoteLink>,
    val subtitles: List<RemoteSubtitle>,
    val stoppedEarly: String? = null,
)

internal fun DataOutputStream.writeProviders(providers: List<RemoteProvider>) {
    writeInt(providers.size)
    providers.forEach { p ->
        writeText(p.name)
        writeText(p.mainUrl)
        writeText(p.lang)
        writeBoolean(p.hasQuickSearch)
        writeInt(p.supportedTypes.size)
        p.supportedTypes.forEach { writeText(it) }
        writeBoolean(p.hasMainPage)
        writeInt(p.mainPage.size)
        p.mainPage.forEach { section ->
            writeText(section.name)
            writeBoolean(section.horizontalImages)
        }
        writeLong(p.sequentialDelayMillis ?: NO_DELAY)
    }
}

internal fun DataInputStream.readProviders(): List<RemoteProvider> = List(readCount()) {
    RemoteProvider(
        name = readText(),
        mainUrl = readText(),
        lang = readText(),
        hasQuickSearch = readBoolean(),
        supportedTypes = List(readCount()) { readText() },
        hasMainPage = readBoolean(),
        mainPage = List(readCount()) { RemoteMainPageSection(readText(), readBoolean()) },
        sequentialDelayMillis = readLong().takeIf { it != NO_DELAY },
    )
}

internal fun DataOutputStream.writeSearchPage(page: RemoteSearchPage) {
    writeBoolean(page.hasNext)
    writeInt(page.items.size)
    page.items.forEach { item ->
        writeText(item.name)
        writeText(item.url)
        writeOptional(item.type)
        writeOptional(item.posterUrl)
    }
}

internal fun DataInputStream.readSearchPage(): RemoteSearchPage {
    val hasNext = readBoolean()
    return RemoteSearchPage(
        items = List(readCount()) {
            RemoteSearchItem(readText(), readText(), readOptional(), readOptional())
        },
        hasNext = hasNext,
    )
}

internal fun DataOutputStream.writeHomePage(page: RemoteHomePage) {
    writeBoolean(page.hasNext)
    writeInt(page.rows.size)
    page.rows.forEach { row ->
        writeText(row.name)
        writeBoolean(row.horizontalImages)
        writeInt(row.items.size)
        row.items.forEach { item ->
            writeText(item.name)
            writeText(item.url)
            writeOptional(item.type)
            writeOptional(item.posterUrl)
        }
    }
}

internal fun DataInputStream.readHomePage(): RemoteHomePage {
    val hasNext = readBoolean()
    return RemoteHomePage(
        rows = List(readCount()) {
            RemoteHomeRow(
                name = readText(),
                horizontalImages = readBoolean(),
                items = List(readCount()) { RemoteSearchItem(readText(), readText(), readOptional(), readOptional()) },
            )
        },
        hasNext = hasNext,
    )
}

internal fun DataOutputStream.writeDetails(details: RemoteDetails) {
    writeText(details.name)
    writeText(details.url)
    writeText(details.type)
    writeOptional(details.plot)
    writeOptional(details.posterUrl)
    writeInt(details.year ?: NO_NUMBER)
    writeOptional(details.dataUrl)
    writeInt(details.episodes.size)
    details.episodes.forEach { episode ->
        writeOptional(episode.name)
        writeText(episode.data)
        writeInt(episode.season ?: NO_NUMBER)
        writeInt(episode.episode ?: NO_NUMBER)
    }
}

internal fun DataInputStream.readDetails(): RemoteDetails = RemoteDetails(
    name = readText(),
    url = readText(),
    type = readText(),
    plot = readOptional(),
    posterUrl = readOptional(),
    year = readNumber(),
    dataUrl = readOptional(),
    episodes = List(readCount()) {
        RemoteEpisode(readOptional(), readText(), readNumber(), readNumber())
    },
)

internal fun DataOutputStream.writeLinks(links: RemoteLinks) {
    writeInt(links.subtitles.size)
    links.subtitles.forEach { subtitle ->
        writeText(subtitle.language)
        writeOptional(subtitle.languageTag)
        writeText(subtitle.url)
        writeOptional(subtitle.referer)
        writeOptional(subtitle.userAgent)
    }
    writeInt(links.links.size)
    links.links.forEach { link ->
        writeText(link.source)
        writeText(link.name)
        writeText(link.url)
        writeInt(link.quality)
        writeText(link.type)
        writeOptional(link.referer)
        writeOptional(link.userAgent)
        writeOptional(link.audioUrl)
    }
}

internal fun DataInputStream.readLinks(): RemoteLinks {
    val subtitles = List(readCount()) {
        RemoteSubtitle(readText(), readOptional(), readText(), readOptional(), readOptional())
    }
    return RemoteLinks(
        links = List(readCount()) {
            RemoteLink(readText(), readText(), readText(), readInt(), readText(), readOptional(), readOptional(), readOptional())
        },
        subtitles = subtitles,
    )
}

private fun DataInputStream.readNumber(): Int? = readInt().takeIf { it != NO_NUMBER }

private const val NO_NUMBER = Int.MIN_VALUE
private const val NO_DELAY = Long.MIN_VALUE

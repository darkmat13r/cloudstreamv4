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
)

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
)

data class RemoteLinks(val links: List<RemoteLink>, val subtitles: Int)

internal fun DataOutputStream.writeProviders(providers: List<RemoteProvider>) {
    writeInt(providers.size)
    providers.forEach { p ->
        writeText(p.name)
        writeText(p.mainUrl)
        writeText(p.lang)
        writeBoolean(p.hasQuickSearch)
        writeInt(p.supportedTypes.size)
        p.supportedTypes.forEach { writeText(it) }
    }
}

internal fun DataInputStream.readProviders(): List<RemoteProvider> = List(readCount()) {
    RemoteProvider(
        name = readText(),
        mainUrl = readText(),
        lang = readText(),
        hasQuickSearch = readBoolean(),
        supportedTypes = List(readCount()) { readText() },
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
    writeInt(links.subtitles)
    writeInt(links.links.size)
    links.links.forEach { link ->
        writeText(link.source)
        writeText(link.name)
        writeText(link.url)
        writeInt(link.quality)
        writeText(link.type)
    }
}

internal fun DataInputStream.readLinks(): RemoteLinks {
    val subtitles = readInt()
    return RemoteLinks(
        links = List(readCount()) {
            RemoteLink(readText(), readText(), readText(), readInt(), readText())
        },
        subtitles = subtitles,
    )
}

private fun DataInputStream.readNumber(): Int? = readInt().takeIf { it != NO_NUMBER }

private const val NO_NUMBER = Int.MIN_VALUE

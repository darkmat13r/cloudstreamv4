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

    /**
     * The one version of a title's facts this app reads (`KMP-76`). The section follows the episodes, as this byte and
     * its length, so a reader that does not know a version skips it whole and the page still opens.
     */
    const val FACTS_V1 = 1

    /** A score out of ten, in tenths: 8.4 is 84. A whole number on the wire, so there is no NaN to send. */
    const val MAX_SCORE_TENTHS = 100
    const val MAX_TAGS = 16
    const val MAX_TAG_CHARS = 64

    /** A tag's bytes as sent. Past it the tag is skipped unread: 64 characters need at most 256. */
    const val MAX_TAG_BYTES = 256

    /** All tags' bytes as sent. Past it the rest are skipped unread. */
    const val MAX_TAGS_BYTES = 16 * 1024
    const val MAX_CONTENT_RATING_CHARS = 16

    /** A certification's bytes as sent. Past it it is skipped unread: 16 characters need at most 64. */
    const val MAX_CONTENT_RATING_BYTES = 64
    const val MAX_RUNTIME_MINUTES = 24 * 60

    /** A show's status, as one byte: what the page has words for, and nothing a plugin could spell its own way. */
    const val STATUS_NONE = 0
    const val STATUS_ONGOING = 1
    const val STATUS_COMPLETED = 2

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
    /** What a page says about the title beside its name (`KMP-76`). None from a host that sent no facts. */
    val facts: RemoteFacts = RemoteFacts.NONE,
)

/**
 * A title's facts, as the app keeps them: every one in its bound, or absent.
 *
 * The plugin runs in the host, so it can write any bytes the host could, and these are read as hostile. A value the
 * reader cannot accept loses only itself: the page opens with the rest. Only a structure that lies about its own
 * length, which no reader can stay in step through, fails the call, as anywhere else in this format.
 *
 * @param scoreTenths out of ten, in tenths, `0..`[HostProtocol.MAX_SCORE_TENTHS].
 * @param tags at most [HostProtocol.MAX_TAGS], each non-blank and at most [HostProtocol.MAX_TAG_CHARS], no two alike.
 * @param contentRating a certification such as `TV-MA`: letters, digits, spaces and `-+/`, at most
 *   [HostProtocol.MAX_CONTENT_RATING_CHARS].
 * @param runtimeMinutes `1..`[HostProtocol.MAX_RUNTIME_MINUTES].
 */
data class RemoteFacts(
    val scoreTenths: Int? = null,
    val tags: List<String> = emptyList(),
    val contentRating: String? = null,
    val status: RemoteShowStatus? = null,
    val runtimeMinutes: Int? = null,
) {
    companion object {
        val NONE = RemoteFacts()
    }
}

/** Named as `ShowStatus` names them, which is what the page's words are keyed by. */
enum class RemoteShowStatus { Ongoing, Completed }

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
    writeFacts(details.facts)
}

/**
 * @param dropped told the name of each fact left out and why, in words of this code's own: never the value, which is
 *   the plugin's and can carry an address or a token.
 */
internal fun DataInputStream.readDetails(dropped: (String) -> Unit = {}): RemoteDetails = RemoteDetails(
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
    facts = readFacts(dropped),
)

/** After the episodes, so a reader from before `KMP-76` stops where it always did and never sees it. */
private fun DataOutputStream.writeFacts(facts: RemoteFacts) {
    val section = ByteArrayOutputStream()
    DataOutputStream(section).use { out ->
        out.writeInt(facts.scoreTenths ?: NO_NUMBER)
        out.writeInt(facts.tags.size)
        facts.tags.forEach { out.writeText(it) }
        out.writeOptional(facts.contentRating)
        out.writeByte(
            when (facts.status) {
                null -> HostProtocol.STATUS_NONE
                RemoteShowStatus.Ongoing -> HostProtocol.STATUS_ONGOING
                RemoteShowStatus.Completed -> HostProtocol.STATUS_COMPLETED
            },
        )
        out.writeInt(facts.runtimeMinutes ?: NO_NUMBER)
    }
    writeByte(HostProtocol.FACTS_V1)
    writeInt(section.size())
    section.writeTo(this)
}

/**
 * The facts section, or none. Nothing left means a host from before it: no facts, and the page as it was.
 *
 * Read from its own slice of the frame, so nothing in it can reach past its stated length, and what follows it stays
 * in step whatever the section holds.
 */
private fun DataInputStream.readFacts(dropped: (String) -> Unit): RemoteFacts {
    if (available() == 0) return RemoteFacts.NONE
    val version = readUnsignedByte()
    val length = readInt()
    if (length !in 0..available()) throw IOException("facts length $length out of range")
    if (version != HostProtocol.FACTS_V1) {
        skipNBytes(length.toLong())
        dropped("facts: version $version")
        return RemoteFacts.NONE
    }
    return DataInputStream(ByteArrayInputStream(readNBytes(length))).use { it.readFactsV1(dropped) }
}

private fun DataInputStream.readFactsV1(dropped: (String) -> Unit): RemoteFacts {
    val score = readNumber()
    val tags = ArrayList<String>()
    var tagBytes = 0
    var skipped = 0
    repeat(readCount()) {
        val length = readInt()
        if (length !in 0..available()) throw IOException("tag length $length out of range")
        // Over its own cap, past the list's, or past the count kept: skipped, not decoded, and the reader stays in step.
        // Only what is decoded counts toward the list's bytes, so one oversized tag costs no tag after it.
        val fits = length <= HostProtocol.MAX_TAG_BYTES && tagBytes + length <= HostProtocol.MAX_TAGS_BYTES
        if (!fits || tags.size == HostProtocol.MAX_TAGS) {
            skipNBytes(length.toLong())
            skipped++
        } else {
            tagBytes += length
            tags += String(readNBytes(length), Charsets.UTF_8)
        }
    }
    // Once for the list, so a plugin sending many cannot make as many lines of log.
    if (skipped > 0) dropped("tags: $skipped past a bound")
    val rating = if (readBoolean()) {
        val length = readInt()
        if (length !in 0..available()) throw IOException("rating length $length out of range")
        if (length > HostProtocol.MAX_CONTENT_RATING_BYTES) {
            skipNBytes(length.toLong())
            dropped("contentRating: past its bytes")
            null
        } else {
            String(readNBytes(length), Charsets.UTF_8)
        }
    } else {
        null
    }
    val status = when (val code = readUnsignedByte()) {
        HostProtocol.STATUS_NONE -> null
        HostProtocol.STATUS_ONGOING -> RemoteShowStatus.Ongoing
        HostProtocol.STATUS_COMPLETED -> RemoteShowStatus.Completed
        else -> null.also { dropped("status: code $code") }
    }
    val runtime = readNumber()
    return RemoteFacts(score, tags, rating, status, runtime)
}

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

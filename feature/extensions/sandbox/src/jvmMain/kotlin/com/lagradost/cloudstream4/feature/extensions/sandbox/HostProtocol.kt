package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

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
     *
     * A title's sections, this and those after it, keep three rules, so a reader of any age stays in step with a writer
     * of any age:
     * - **append-only:** a new section takes a tag of its own and goes after the last one; a tag is never reused;
     * - **always written,** each section in every reply, empty when it holds nothing;
     * - **never reordered.**
     *
     * Each is a tag byte, a length, and a slice read on its own. The facts come first, as they always have; after them
     * a reader takes each section by its tag and steps over one whose tag it does not know, rather than read it as the
     * wrong section. Tags: facts `0x01`, art `0x21`, ids `0x31` (`KMP-86`), cast `0x41` and recommendations `0x51`
     * (`KMP-103`). The high nibble is the section and the low its version, so a later cast is `0x42`, written beside
     * `0x41` for as long as apps that read only `0x41` are kept working.
     */
    const val FACTS_V1 = 1

    /** The art section's tag (`KMP-100`), after the facts: a title's backdrop. A frame from before has none. */
    const val ART_V1 = 0x21

    /**
     * The ids section's tag (`KMP-86`), after the art: the title's MAL, TMDb and IMDb ids, which the Mac player sends
     * to the skip services. Read by its tag (see [FACTS_V1]), so a reader that does not know it steps over it whole, and
     * a frame from before has none.
     */
    const val IDS_V1 = 0x31

    /** One id's bytes. Every real one is far shorter; past this it is skipped unread and the id is left out. */
    const val MAX_ID_BYTES = 16

    /** A backdrop URL's bytes. Past this it is skipped unread and the page opens without one. */
    const val MAX_IMAGE_URL_BYTES = 2048

    /**
     * The ids section's bytes at most (`KMP-108`): its three ids, each a presence flag (1 byte), a length (4) and at
     * most [MAX_ID_BYTES] (16), so 3 x (1 + 4 + 16) = 63. Past it the section is stepped over before a byte is copied.
     * Within it, one id past its own bytes is still skipped alone and the others kept.
     */
    const val MAX_IDS_SECTION_BYTES = 3 * (1 + Int.SIZE_BYTES + MAX_ID_BYTES)

    /**
     * The art section's bytes at most (`KMP-108`): a presence flag (1 byte), a length (4) and at most
     * [MAX_IMAGE_URL_BYTES] (2048), so 2053. Not [MAX_LINK_BYTES], which is the whole frame's 8 MiB and so bounds
     * nothing a section adds: the backdrop is the section's one field, and its own cap is the real one. Past it the
     * section is stepped over before a byte is copied.
     */
    const val MAX_ART_SECTION_BYTES = 1 + Int.SIZE_BYTES + MAX_IMAGE_URL_BYTES

    /** The cast section's tag (`KMP-103`): who is in the title. */
    const val CAST_V1 = 0x41

    /** The recommendations section's tag (`KMP-103`): titles the same provider suggests after this one. */
    const val RECOMMENDATIONS_V1 = 0x51

    /**
     * Each of the two sections' bytes. Past it the section is stepped over unread. The host drops trailing entries until
     * its section fits, so an honest one is never past it.
     */
    const val MAX_SECTION_BYTES = 64 * 1024
    const val MAX_CAST = 24
    const val MAX_RECOMMENDATIONS = 20

    /** A name, a role or a voice actor: at most this many characters once cleaned, and these many bytes as sent. */
    const val MAX_PERSON_CHARS = 64
    const val MAX_PERSON_BYTES = 256
    const val MAX_RECOMMENDATION_TITLE_CHARS = 128
    const val MAX_RECOMMENDATION_TITLE_BYTES = 512

    /** What opens a recommendation: an opaque string for the provider that sent it, never a page to the app. */
    const val MAX_KEY_CHARS = 2048

    /** 2048 characters of any script fit: four bytes at most each. */
    const val MAX_KEY_BYTES = 4 * MAX_KEY_CHARS

    /** An image URL here is at most this many characters, as well as [MAX_IMAGE_URL_BYTES] bytes. */
    const val MAX_IMAGE_URL_CHARS = 2048
    const val MIN_YEAR = 1870
    const val MAX_YEAR = 2100

    /** A person's role as a kind, one byte, as `ActorRole` names them. */
    const val ROLE_NONE = 0
    const val ROLE_MAIN = 1
    const val ROLE_SUPPORTING = 2
    const val ROLE_BACKGROUND = 3

    /**
     * A recommendation's kind, one byte: code `n` is the `n`th name here, 0 is none. The protocol's own table, so a
     * plugin can name only one of these and never spell its own. It matches `TvType`'s values today.
     */
    val TYPE_CODES: List<String> = listOf(
        "Movie", "AnimeMovie", "TvSeries", "Cartoon", "Anime", "OVA", "Torrent", "Documentary", "AsianDrama", "Live",
        "NSFW", "Others", "Music", "AudioBook", "CustomMedia", "Audio", "Podcast", "Video",
    )

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
    /** The wide art behind the title page's header (`KMP-100`), as the plugin wrote it until the app checks it. */
    val backdropUrl: String? = null,
    /** The title's ids on other sites (`KMP-86`), as the plugin wrote them until the app checks them. */
    val ids: RemoteIds = RemoteIds.NONE,
    /** Who is in it (`KMP-103`), at most [HostProtocol.MAX_CAST]. None from a host that sent none. */
    val cast: List<RemoteCastMember> = emptyList(),
    /** What the provider suggests after it (`KMP-103`), at most [HostProtocol.MAX_RECOMMENDATIONS]. */
    val recommendations: List<RemoteRecommendation> = emptyList(),
)

/**
 * One person in a title's cast (`KMP-103`). Read as hostile, as the facts are: words are checked and cleaned by the
 * app, and a photo is a URL the app may fetch only once it passes the image checks.
 *
 * @param role the character, or what the provider wrote for the part.
 * @param roleKind the part as a kind, from a fixed code, for when there are no words.
 */
data class RemoteCastMember(
    val name: String,
    val roleKind: RemoteActorRole? = null,
    val role: String? = null,
    val voiceActor: String? = null,
    val imageUrl: String? = null,
) {
    override fun toString(): String = "RemoteCastMember(<hidden>)"
}

/** Named as `ActorRole` names them. */
enum class RemoteActorRole { Main, Supporting, Background }

/**
 * A title the provider suggests (`KMP-103`).
 *
 * @param type a name from [HostProtocol.TYPE_CODES], never the plugin's spelling.
 * @param key what opens it: opaque, and only ever handed back to the provider that sent it, through its own `load`.
 *   The app never fetches it, opens it in a browser, shows it or logs it.
 */
data class RemoteRecommendation(
    val title: String,
    val key: String,
    val type: String? = null,
    val year: Int? = null,
    val posterUrl: String? = null,
) {
    override fun toString(): String = "RemoteRecommendation(<hidden>)"
}

/**
 * A title's ids on other sites (`KMP-86`), each as text. The Mac player puts them in a skip service's URL, so the app
 * keeps only the exact shapes ([cleaned]): a MAL or TMDb id is 1 to 9 ASCII digits, an IMDb id `tt` and 7 to 10.
 */
data class RemoteIds(val malId: String? = null, val tmdbId: String? = null, val imdbId: String? = null) {
    companion object {
        val NONE = RemoteIds()
    }
}

/**
 * A title's facts, as the app keeps them: every one in its bound, or absent.
 *
 * The plugin runs in the host, so it can write any bytes the host could, and these are read as hostile. A value the
 * reader cannot accept loses only itself: the page opens with the rest. A section that cannot be read loses the facts,
 * and the page still opens. Only a section whose own length lies past the frame, which no reader can stay in step
 * through, fails the call, as anywhere else in this format.
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
    writeArt(details.backdropUrl)
    writeIds(details.ids)
    writeCast(details.cast)
    writeRecommendations(details.recommendations)
}

/**
 * @param dropped told the name of each fact left out and why, in words of this code's own: never the value, which is
 *   the plugin's and can carry an address or a token.
 */
internal fun DataInputStream.readDetails(dropped: (String) -> Unit = {}): RemoteDetails {
    val page = RemoteDetails(
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
    val after = readSectionsAfterFacts(dropped)
    return page.copy(backdropUrl = after.backdrop, ids = after.ids, cast = after.cast, recommendations = after.recommendations)
}

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
 * in step whatever the section holds. So a section that cannot be read costs the facts and keeps the page.
 */
private fun DataInputStream.readFacts(dropped: (String) -> Unit): RemoteFacts {
    if (available() == 0) return RemoteFacts.NONE
    val version = readUnsignedByte()
    val length = readInt()
    if (length !in 0..available()) throw IOException("facts length $length out of range")
    if (version != HostProtocol.FACTS_V1) {
        skipNBytes(length.toLong())
        dropped("facts: a version this app does not read")
        return RemoteFacts.NONE
    }
    val section = readNBytes(length)
    // Inside its slice, a section cut short or lying about a length cannot move this reader, which has already
    // stepped past it: the facts are lost, the page is not. A lying length for the slice itself still fails, above.
    return try {
        DataInputStream(ByteArrayInputStream(section)).use { it.readFactsV1(dropped) }
    } catch (unreadable: IOException) {
        dropped("facts: a section that could not be read")
        RemoteFacts.NONE
    }
}

/** After the facts, so a reader from before `KMP-100` stops where it did and never sees it. */
private fun DataOutputStream.writeArt(backdropUrl: String?) {
    val section = ByteArrayOutputStream()
    DataOutputStream(section).use { it.writeOptional(backdropUrl) }
    writeByte(HostProtocol.ART_V1)
    writeInt(section.size())
    section.writeTo(this)
}

/** What follows the facts, each section as read, or none. */
private class AfterFacts(
    val backdrop: String? = null,
    val ids: RemoteIds = RemoteIds.NONE,
    val cast: List<RemoteCastMember> = emptyList(),
    val recommendations: List<RemoteRecommendation> = emptyList(),
)

/**
 * The sections after the facts, each by its tag (see [HostProtocol.FACTS_V1]): the art, the ids (`KMP-86`), the cast
 * and the recommendations. A section whose tag this app does not know, or a second of one it has read, is stepped over
 * whole, and said once. A slice that cannot be read costs its section, not the page. Only a slice whose own length lies
 * past the frame fails the call. Nothing left means a host from before: none of them.
 */
private fun DataInputStream.readSectionsAfterFacts(dropped: (String) -> Unit): AfterFacts {
    var backdrop: String? = null
    var ids = RemoteIds.NONE
    var cast: List<RemoteCastMember> = emptyList()
    var recommendations: List<RemoteRecommendation> = emptyList()
    val read = HashSet<Int>()
    var unknown = false
    while (available() > 0) {
        val tag = readUnsignedByte()
        val length = readInt()
        if (length !in 0..available()) throw IOException("section length $length out of range")
        // `KMP-105`: only a tag this app reads, seen for the first time, is copied out as a slice. Any other is stepped
        // over in place, so a lying host's large unknown or repeated section costs no allocation.
        if (tag !in SECTION_CAPS || !read.add(tag)) {
            skipNBytes(length.toLong())
            unknown = true
            continue
        }
        // Nor is a section past its cap (`KMP-105`, `KMP-108`): it is stepped over before a byte of it is copied.
        val cap = SECTION_CAPS.getValue(tag)
        if (length > cap.maxBytes) {
            skipNBytes(length.toLong())
            dropped("${cap.name}: a section past its bytes")
            continue
        }
        val section = readNBytes(length)
        when (tag) {
            HostProtocol.ART_V1 -> backdrop = slice(section, "art", dropped, null) { readArtV1(dropped) }
            HostProtocol.IDS_V1 -> ids = readIds(section, dropped)
            HostProtocol.CAST_V1 ->
                cast = slice(section, "cast", dropped, emptyList()) { readCastV1(Drops("cast", dropped)) }
            HostProtocol.RECOMMENDATIONS_V1 ->
                recommendations = slice(section, "recommendations", dropped, emptyList()) {
                    readRecommendationsV1(Drops("recommendations", dropped))
                }
        }
    }
    if (unknown) dropped("a section this app does not read")
    return AfterFacts(backdrop, ids, cast, recommendations)
}

/** A section's most bytes, and the name its drop is said under. */
private class SectionCap(val name: String, val maxBytes: Int)

/**
 * The sections [readSectionsAfterFacts] reads, each with a cap so none is copied past what it can hold. Every other tag
 * is stepped over unread.
 */
private val SECTION_CAPS = mapOf(
    HostProtocol.ART_V1 to SectionCap("art", HostProtocol.MAX_ART_SECTION_BYTES),
    HostProtocol.IDS_V1 to SectionCap("ids", HostProtocol.MAX_IDS_SECTION_BYTES),
    HostProtocol.CAST_V1 to SectionCap("cast", HostProtocol.MAX_SECTION_BYTES),
    HostProtocol.RECOMMENDATIONS_V1 to SectionCap("recommendations", HostProtocol.MAX_SECTION_BYTES),
)

/**
 * [parse] over its own [bytes], so nothing in it can reach past them: a section cut short, or lying about a length or a
 * count inside, costs [fallback]. One past its cap never gets here (see [SECTION_CAPS]).
 */
private fun <T> slice(bytes: ByteArray, name: String, dropped: (String) -> Unit, fallback: T, parse: DataInputStream.() -> T): T {
    return try {
        DataInputStream(ByteArrayInputStream(bytes)).use { it.parse() }
    } catch (unreadable: IOException) {
        dropped("$name: a section that could not be read")
        fallback
    }
}

/** Why fields were left out of one section, each reason said once however often it happened: never a value or a count. */
private class Drops(private val section: String, private val dropped: (String) -> Unit) {
    private val said = HashSet<String>()

    fun note(reason: String) {
        if (said.add(reason)) dropped("$section: $reason")
    }
}

/** What [readField] found: the text, or why there is none. */
private sealed interface Field {
    data class Text(val value: String) : Field
    data object PastItsBytes : Field
    data object NotUtf8 : Field
}

/**
 * One length-prefixed text inside a slice. The length is checked against what is left before anything is skipped or
 * read. Past [maxBytes] it is stepped over with `skipNBytes`, never decoded. Bytes that are not UTF-8 are refused, never
 * replaced: a name made of replacement characters is not a name.
 */
private fun DataInputStream.readField(maxBytes: Int): Field {
    val length = readInt()
    if (length !in 0..available()) throw IOException("a field length past its section")
    if (length > maxBytes) {
        skipNBytes(length.toLong())
        return Field.PastItsBytes
    }
    val bytes = readNBytes(length)
    return try {
        Field.Text(
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString(),
        )
    } catch (malformed: CharacterCodingException) {
        Field.NotUtf8
    }
}

/** [readField] as a value, saying in [drops] why one was left out under [name]. */
private fun DataInputStream.readTextField(maxBytes: Int, name: String, drops: Drops): String? =
    when (val field = readField(maxBytes)) {
        is Field.Text -> field.value
        Field.PastItsBytes -> null.also { drops.note("$name past its bytes") }
        Field.NotUtf8 -> null.also { drops.note("$name not UTF-8") }
    }

private fun DataInputStream.readOptionalText(maxBytes: Int, name: String, drops: Drops): String? =
    if (readBoolean()) readTextField(maxBytes, name, drops) else null

/**
 * A count, then entries. A negative count costs the section. Past [max] the rest of the slice is left unread: it is the
 * section's own, so nothing after it is out of step.
 */
private inline fun <T> DataInputStream.readEntries(max: Int, drops: Drops, entry: () -> T?): List<T> {
    val count = readInt()
    if (count < 0) throw IOException("a negative count")
    if (count > max) drops.note("entries past the count")
    val out = ArrayList<T>(minOf(count, max))
    repeat(minOf(count, max)) { entry()?.let(out::add) }
    return out
}

private fun DataInputStream.readCastV1(drops: Drops): List<RemoteCastMember> =
    readEntries(HostProtocol.MAX_CAST, drops) {
        val name = readTextField(HostProtocol.MAX_PERSON_BYTES, "name", drops)
        val kind = when (readUnsignedByte()) {
            HostProtocol.ROLE_NONE -> null
            HostProtocol.ROLE_MAIN -> RemoteActorRole.Main
            HostProtocol.ROLE_SUPPORTING -> RemoteActorRole.Supporting
            HostProtocol.ROLE_BACKGROUND -> RemoteActorRole.Background
            else -> null.also { drops.note("role kind not a code this app knows") }
        }
        val role = readOptionalText(HostProtocol.MAX_PERSON_BYTES, "role", drops)
        val voice = readOptionalText(HostProtocol.MAX_PERSON_BYTES, "voice actor", drops)
        val image = readOptionalText(HostProtocol.MAX_IMAGE_URL_BYTES, "image", drops)
        if (name == null) null.also { drops.note("a person without a name") } else RemoteCastMember(name, kind, role, voice, image)
    }

private fun DataInputStream.readRecommendationsV1(drops: Drops): List<RemoteRecommendation> =
    readEntries(HostProtocol.MAX_RECOMMENDATIONS, drops) {
        val title = readTextField(HostProtocol.MAX_RECOMMENDATION_TITLE_BYTES, "title", drops)
        val code = readUnsignedByte()
        val type = when {
            code == 0 -> null
            code <= HostProtocol.TYPE_CODES.size -> HostProtocol.TYPE_CODES[code - 1]
            else -> null.also { drops.note("type not a code this app knows") }
        }
        val year = readNumber()
        val poster = readOptionalText(HostProtocol.MAX_IMAGE_URL_BYTES, "poster", drops)
        val key = readTextField(HostProtocol.MAX_KEY_BYTES, "key", drops)
        if (title == null || key == null) null.also { drops.note("a recommendation without its title or key") }
        else RemoteRecommendation(title, key, type, year, poster)
    }

/** After the art, so a reader from before `KMP-103` steps over it as a tag it does not know. Always written. */
private fun DataOutputStream.writeCast(cast: List<RemoteCastMember>) = writeSection(HostProtocol.CAST_V1, castBytes(cast))

private fun DataOutputStream.writeRecommendations(recommendations: List<RemoteRecommendation>) =
    writeSection(HostProtocol.RECOMMENDATIONS_V1, recommendationsBytes(recommendations))

private fun DataOutputStream.writeSection(tag: Int, body: ByteArray) {
    writeByte(tag)
    writeInt(body.size)
    write(body)
}

internal fun castBytes(cast: List<RemoteCastMember>): ByteArray = sectionBytes {
    writeInt(cast.size)
    cast.forEach { person ->
        writeText(person.name)
        writeByte(
            when (person.roleKind) {
                null -> HostProtocol.ROLE_NONE
                RemoteActorRole.Main -> HostProtocol.ROLE_MAIN
                RemoteActorRole.Supporting -> HostProtocol.ROLE_SUPPORTING
                RemoteActorRole.Background -> HostProtocol.ROLE_BACKGROUND
            },
        )
        writeOptional(person.role)
        writeOptional(person.voiceActor)
        writeOptional(person.imageUrl)
    }
}

internal fun recommendationsBytes(recommendations: List<RemoteRecommendation>): ByteArray = sectionBytes {
    writeInt(recommendations.size)
    recommendations.forEach { title ->
        writeText(title.title)
        writeByte(title.type?.let { HostProtocol.TYPE_CODES.indexOf(it) + 1 } ?: 0)
        writeInt(title.year ?: NO_NUMBER)
        writeOptional(title.posterUrl)
        writeText(title.key)
    }
}

private fun sectionBytes(body: DataOutputStream.() -> Unit): ByteArray {
    val out = ByteArrayOutputStream()
    DataOutputStream(out).use(body)
    return out.toByteArray()
}

private fun DataInputStream.readArtV1(dropped: (String) -> Unit): String? {
    if (!readBoolean()) return null
    val length = readInt()
    if (length !in 0..available()) throw IOException("backdrop length $length out of range")
    if (length > HostProtocol.MAX_IMAGE_URL_BYTES) {
        skipNBytes(length.toLong())
        dropped("backdropUrl: past its bytes")
        return null
    }
    return String(readNBytes(length), Charsets.UTF_8)
}

/** After the art, appended: a reader from before `KMP-86` steps over it as a tag it does not know. */
private fun DataOutputStream.writeIds(ids: RemoteIds) {
    val section = ByteArrayOutputStream()
    DataOutputStream(section).use { out ->
        out.writeOptional(ids.malId)
        out.writeOptional(ids.tmdbId)
        out.writeOptional(ids.imdbId)
    }
    writeByte(HostProtocol.IDS_V1)
    writeInt(section.size())
    section.writeTo(this)
}

/** The ids section's slice: one that cannot be read costs the ids, never the page. */
private fun readIds(section: ByteArray, dropped: (String) -> Unit): RemoteIds = try {
    DataInputStream(ByteArrayInputStream(section)).use { input ->
        RemoteIds(input.readId("malId", dropped), input.readId("tmdbId", dropped), input.readId("imdbId", dropped))
    }
} catch (unreadable: IOException) {
    dropped("ids: a section that could not be read")
    RemoteIds.NONE
}

/** One id, or null: one past [HostProtocol.MAX_ID_BYTES] is skipped unread, and named by [name], never by its value. */
private fun DataInputStream.readId(name: String, dropped: (String) -> Unit): String? {
    if (!readBoolean()) return null
    val length = readInt()
    if (length !in 0..available()) throw IOException("$name length $length out of range")
    if (length > HostProtocol.MAX_ID_BYTES) {
        skipNBytes(length.toLong())
        dropped("$name: past its bytes")
        return null
    }
    return String(readNBytes(length), Charsets.UTF_8)
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
    val status = when (readUnsignedByte()) {
        HostProtocol.STATUS_NONE -> null
        HostProtocol.STATUS_ONGOING -> RemoteShowStatus.Ongoing
        HostProtocol.STATUS_COMPLETED -> RemoteShowStatus.Completed
        else -> null.also { dropped("status: not a code this app knows") }
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

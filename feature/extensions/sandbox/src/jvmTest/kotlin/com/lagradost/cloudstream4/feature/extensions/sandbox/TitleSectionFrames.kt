package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Load replies written by hand, as a lying host would write them (`KMP-103`): a title's fields, an empty facts section,
 * then whatever sections a test writes after it.
 */
internal object TitleSectionFrames {
    val page = RemoteDetails("n", "https://site.example/title", "TvSeries", null, null, 2024, null, listOf(RemoteEpisode(null, "d", 1, 2)))

    fun received(frame: ByteArray, dropped: MutableList<String> = ArrayList()): RemoteDetails =
        HostProtocol.decode(HostProtocol.readFrame(ByteArrayInputStream(frame))!!) {
            readDetails { dropped += it }.cleaned { dropped += it }
        }

    fun reply(rest: DataOutputStream.() -> Unit) = HostProtocol.encode {
        writeText(page.name)
        writeText(page.url)
        writeText(page.type)
        writeOptional(page.plot)
        writeOptional(page.posterUrl)
        writeInt(page.year!!)
        writeOptional(page.dataUrl)
        writeInt(page.episodes.size)
        page.episodes.forEach { episode ->
            writeOptional(episode.name)
            writeText(episode.data)
            writeInt(episode.season!!)
            writeInt(episode.episode!!)
        }
        emptyFacts()
        rest()
    }

    fun DataOutputStream.emptyFacts() = section(HostProtocol.FACTS_V1) {
        writeInt(Int.MIN_VALUE)
        writeInt(0)
        writeBoolean(false)
        writeByte(HostProtocol.STATUS_NONE)
        writeInt(Int.MIN_VALUE)
    }

    /** A section of [tag] holding [body], its length stated truthfully. */
    fun DataOutputStream.section(tag: Int, body: DataOutputStream.() -> Unit) {
        val section = ByteArrayOutputStream()
        DataOutputStream(section).use(body)
        writeByte(tag)
        writeInt(section.size())
        section.writeTo(this)
    }

    /** A text field of raw [bytes], its length stated truthfully. */
    fun DataOutputStream.rawText(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }

    /** One person as the wire has them, every field given. */
    fun DataOutputStream.person(
        name: String,
        kind: Int = HostProtocol.ROLE_NONE,
        role: String? = null,
        voice: String? = null,
        image: String? = null,
    ) {
        writeText(name)
        writeByte(kind)
        writeOptional(role)
        writeOptional(voice)
        writeOptional(image)
    }

    /** One recommendation as the wire has it. */
    fun DataOutputStream.suggestion(
        title: String,
        key: String,
        type: Int = 0,
        year: Int = Int.MIN_VALUE,
        poster: String? = null,
    ) {
        writeText(title)
        writeByte(type)
        writeInt(year)
        writeOptional(poster)
        writeText(key)
    }

    /** Images the app must never fetch, each with what it tries. */
    val refusedImages = listOf(
        "http://img.example/a.jpg" to "plain http",
        "file:///etc/passwd" to "a file",
        "data:image/png;base64,AAAA" to "a data URI",
        "https://localhost/a.jpg" to "localhost",
        "https://printer.local/a.jpg" to ".local",
        "https://127.0.0.1/a.jpg" to "loopback",
        "https://10.0.0.1/a.jpg" to "a private address",
        "https://192.168.1.1/a.jpg" to "a private address",
        "https://169.254.169.254/latest/meta-data" to "the metadata address",
        "https://[::1]/a.jpg" to "IPv6 loopback",
        "https://user:pass@img.example/a.jpg" to "userinfo",
        "https://img.example/a\u0000.jpg" to "a control character",
        "https://img.example/" + "a".repeat(HostProtocol.MAX_IMAGE_URL_CHARS) to "past its characters",
        "img.example/a.jpg" to "no scheme",
        "" to "empty",
    )
}

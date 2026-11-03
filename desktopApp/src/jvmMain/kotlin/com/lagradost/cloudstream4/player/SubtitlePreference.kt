package com.lagradost.cloudstream4.player

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.concurrent.thread

/**
 * The language a stream's subtitles turn on in, as `SUBTITLE_AUTO_SELECT_KEY` is on Android.
 *
 * English until someone chooses, whatever the system language, and [NONE] once they have chosen Off.
 */
internal interface SubtitlePreference {
    fun language(): String

    fun remember(language: String)

    /** Never turns anything on. For a check that decides for itself what is showing. */
    object Never : SubtitlePreference {
        override fun language() = NONE

        override fun remember(language: String) = Unit
    }

    companion object {
        const val DEFAULT = "en"
        const val NONE = ""
    }
}

/**
 * One line in `~/.cloudstream4/subtitle-language`.
 *
 * Anything else found there reads as never having chosen. The write is atomic and off the caller's thread,
 * because the caller is a click, and a failed one costs the choice at the next launch and nothing now.
 */
internal class FileSubtitlePreference(
    private val file: File = File(File(System.getProperty("user.home"), ".cloudstream4"), "subtitle-language"),
) : SubtitlePreference {
    private val lock = Any()

    @Volatile
    private var current: String? = null

    override fun language(): String =
        current ?: synchronized(lock) { current ?: read().also { current = it } }

    override fun remember(language: String) {
        // A tag comes from a plugin's track, so it is checked as anything else from one is.
        if (!language.isValidTag()) return
        current = language
        thread(isDaemon = true, name = "subtitle-language") {
            synchronized(lock) { runCatching { write() } }
        }
    }

    private fun read(): String {
        val text = runCatching {
            if (file.length() > MAX_FILE_BYTES) null else file.readText()
        }.getOrNull() ?: return SubtitlePreference.DEFAULT
        val tag = text.trim()
        return if (tag.isValidTag()) tag else SubtitlePreference.DEFAULT
    }

    /** Whatever is latest by now, so two quick choices that run out of order still end on the second. */
    private fun write() {
        val value = current ?: return
        val directory = file.parentFile ?: throw IOException("no directory")
        Files.createDirectories(directory.toPath())
        val next = Files.createTempFile(directory.toPath(), file.name, ".tmp")
        try {
            Files.writeString(next, value)
            Files.move(next, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(next)
        }
    }

    private fun String.isValidTag() = length <= MAX_TAG_CHARS && all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }

    private companion object {
        /** RFC 5646's longest tag a registry can produce. */
        const val MAX_TAG_CHARS = 35
        const val MAX_FILE_BYTES = 64L
    }
}

/** Read once per run and shared, because each player opens its own session. */
internal val desktopSubtitlePreference: SubtitlePreference by lazy { FileSubtitlePreference() }

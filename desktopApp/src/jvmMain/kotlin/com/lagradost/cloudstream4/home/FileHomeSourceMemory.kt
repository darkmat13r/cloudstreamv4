package com.lagradost.cloudstream4.home

import com.lagradost.cloudstream4.feature.home.HomeSourceId
import com.lagradost.cloudstream4.feature.home.HomeSourceMemory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.concurrent.thread

/**
 * One line in `~/.cloudstream4/home-source`.
 *
 * A source's id is the name its plugin gave it, so what is read here only ever selects from the sources the app is
 * offering, and anything malformed reads as never having chosen. The write is atomic and off the caller's thread,
 * because the caller is a click, and a failed one costs the choice at the next launch and nothing now.
 */
internal class FileHomeSourceMemory(
    private val file: File = File(File(System.getProperty("user.home"), ".cloudstream4"), "home-source"),
) : HomeSourceMemory {
    private val lock = Any()

    @Volatile
    private var latest: String? = null

    override fun last(): HomeSourceId? {
        latest?.let { return HomeSourceId(it) }
        val text = runCatching {
            if (file.length() > MAX_FILE_BYTES) null else file.readText()
        }.getOrNull()?.trim() ?: return null
        return text.takeIf(::isValid)?.let(::HomeSourceId)
    }

    override fun remember(id: HomeSourceId) {
        if (!isValid(id.value)) return
        latest = id.value
        thread(isDaemon = true, name = "home-source") {
            synchronized(lock) { runCatching { write() } }
        }
    }

    /** Whatever is latest by now, so two quick choices that run out of order still end on the second. */
    private fun write() {
        val value = latest ?: return
        val directory = checkNotNull(file.parentFile).toPath()
        Files.createDirectories(directory)
        val next = Files.createTempFile(directory, file.name, ".tmp")
        try {
            Files.writeString(next, value)
            Files.move(next, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(next)
        }
    }

    private fun isValid(name: String) = name.isNotBlank() && name.length <= MAX_NAME_CHARS && name.none(Char::isISOControl)

    private companion object {
        const val MAX_NAME_CHARS = 256
        const val MAX_FILE_BYTES = 4096L
    }
}

package com.lagradost.cloudstream4.player

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** What the player's volume was left at. */
internal data class SavedVolume(val level: Int = PlayerVolume.MAX, val muted: Boolean = false)

internal interface PlayerVolumeStore {
    fun read(): SavedVolume

    fun remember(volume: SavedVolume)

    /** Remembers nothing, for a test or a check. */
    object None : PlayerVolumeStore {
        override fun read() = SavedVolume()

        override fun remember(volume: SavedVolume) = Unit
    }
}

/**
 * One line in `~/.cloudstream4/player-volume`: `70`, or `70 muted`. Anything else reads as never having chosen.
 *
 * **Mute is kept.** Silence on the next launch is the smaller risk: the controls are on screen while a stream starts,
 * with the crossed speaker, and M puts it right. The other way round, someone who muted in a quiet room is played at
 * full level without having asked, and that cannot be taken back.
 *
 * A drag reports every pixel, so a write waits [writeDelayMillis] and then writes whatever is latest. The write is
 * atomic and off the caller's thread. Quitting inside that wait costs that last change and nothing else.
 *
 * @param onWritten told after each write, for a test to count them.
 */
internal class FilePlayerVolumeStore(
    private val file: File = File(File(System.getProperty("user.home"), ".cloudstream4"), "player-volume"),
    private val writeDelayMillis: Long = WRITE_DELAY_MILLIS,
    private val onWritten: () -> Unit = {},
) : PlayerVolumeStore {
    private val writing = AtomicBoolean(false)

    @Volatile
    private var latest: SavedVolume? = null

    override fun read(): SavedVolume {
        val text = runCatching {
            if (file.length() > MAX_FILE_BYTES) null else file.readText()
        }.getOrNull() ?: return SavedVolume()
        val match = LINE.matchEntire(text.trim()) ?: return SavedVolume()
        val level = match.groupValues[1].toInt()
        return if (level in 0..PlayerVolume.MAX) SavedVolume(level, match.groupValues[2].isNotEmpty()) else SavedVolume()
    }

    override fun remember(volume: SavedVolume) {
        latest = volume
        if (!writing.compareAndSet(false, true)) return
        thread(isDaemon = true, name = "player-volume") {
            Thread.sleep(writeDelayMillis)
            // Before the write, so a change made during it starts another rather than being lost.
            writing.set(false)
            runCatching { write() }
            onWritten()
        }
    }

    private fun write() {
        val value = latest ?: return
        val directory = file.parentFile ?: throw IOException("no directory")
        Files.createDirectories(directory.toPath())
        val next = Files.createTempFile(directory.toPath(), file.name, ".tmp")
        try {
            Files.writeString(next, if (value.muted) "${value.level}$MUTED" else "${value.level}")
            Files.move(next, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(next)
        }
    }

    private companion object {
        const val WRITE_DELAY_MILLIS = 500L
        const val MAX_FILE_BYTES = 16L
        const val MUTED = " muted"
        val LINE = Regex("""(\d{1,3})( muted)?""")
    }
}

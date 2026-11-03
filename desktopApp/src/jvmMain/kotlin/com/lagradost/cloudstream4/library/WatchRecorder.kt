package com.lagradost.cloudstream4.library

import com.lagradost.cloudstream4.feature.library.LibraryStore
import com.lagradost.cloudstream4.feature.library.LibraryTitle
import com.lagradost.cloudstream4.feature.library.WatchPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Writes where playback got to, while it plays, so the library can offer to come back to it.
 *
 * The player reports its position about four times a second (`KMP-25`), and this file is rewritten
 * whole on every write, so it writes at most once per [INTERVAL_MILLIS] and then once more when the
 * screen goes — the last position is the one that matters and it is the one a throttle would drop.
 *
 * The title's row is written **once**, on the first position, and carries the name and poster. That is
 * what makes a continue-watching card drawable without asking a provider again, and it is deliberately
 * not a bookmark: `LibraryStore.remember` keeps whatever list the title is already in, which for
 * something merely played is none.
 *
 * Nothing here throws. A library that cannot be written must not take the player down with it, and the
 * cost of a lost write is one row of "continue watching".
 *
 * @param title the id plus what a card needs to draw it.
 * @param episodeData the episode being played, opaque, or null for a title that plays itself.
 * @param now replaceable, so a test does not have to wait ten seconds.
 */
internal class WatchRecorder(
    private val store: LibraryStore,
    private val title: LibraryTitle,
    private val episodeData: String?,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Null until the first write, because "never" is not the same instant as zero. */
    private var lastWriteAt: Long? = null
    private var remembered = false
    private var latestPosition = 0L
    private var latestDuration = 0L

    /** Called as the player reports. Writes at most once per [INTERVAL_MILLIS]. */
    fun onProgress(positionMillis: Long, durationMillis: Long) {
        latestPosition = positionMillis
        latestDuration = durationMillis
        val at = now()
        if (lastWriteAt?.let { at - it < INTERVAL_MILLIS } == true) return
        if (!worthRecording(positionMillis, durationMillis)) return
        lastWriteAt = at
        write(positionMillis, durationMillis, at)
    }

    /**
     * Writes the last position the player reported, whatever the interval says. Called when the player
     * screen goes, which is the one write a throttle would otherwise always drop.
     */
    fun flush() {
        if (!worthRecording(latestPosition, latestDuration)) return
        write(latestPosition, latestDuration, now())
    }

    /**
     * A length the player never reported makes a fraction meaningless, and the first half-minute is
     * not somewhere to come back to — the same two refusals `WatchPosition.resumable` makes, applied
     * before the write rather than after it, so the file does not fill with rows nothing can use.
     */
    private fun worthRecording(positionMillis: Long, durationMillis: Long): Boolean =
        durationMillis > 0L && positionMillis >= WatchPosition.MINIMUM_RESUME_MILLIS

    private fun write(positionMillis: Long, durationMillis: Long, at: Long) {
        val position = WatchPosition(
            id = title.id,
            episodeData = episodeData,
            positionMillis = positionMillis,
            durationMillis = durationMillis,
            watchedAt = at,
        )
        val first = !remembered
        remembered = true
        scope.launch {
            runCatching {
                if (first) store.remember(title.copy(keptAt = at))
                store.record(position)
            }
        }
    }

    private companion object {
        /** Often enough that a crash loses seconds, rarely enough that the file is not thrashed. */
        const val INTERVAL_MILLIS = 10_000L
    }
}

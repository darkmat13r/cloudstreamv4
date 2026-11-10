package com.lagradost.cloudstream4.feature.settings

import com.lagradost.cloudstream4.core.designsystem.desktop.PosterSize
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SettingsRepositoryTest {
    @Test
    fun everyChangeIsWrittenAtOnceAndReadBackByANewRepository() {
        val storage = SettingsStorage.InMemory()
        val settings = SettingsRepository(storage)
        settings.setAutoplayNext(false)
        settings.setDownloadsAtOnce(5)
        settings.setShowRandom(false)
        settings.setPosterSize(PosterSize.Large)
        settings.setSeekSeconds(30)
        val stored = mapOf(
            "autoplay_next_key" to "false",
            "download_parallel_key" to "5",
            "poster_size_desktop_key" to "large",
            "random_button_key" to "false",
            "seek_amount_desktop_key" to "30",
            "enable_skip_op_from_database" to "true",
        )
        assertEquals(stored, storage.stored)

        val reopened = SettingsRepository(storage)
        assertEquals(false, reopened.autoplayNext.value)
        assertEquals(5, reopened.downloadsAtOnce.value)
        assertEquals(false, reopened.showRandom.value)
        assertEquals(PosterSize.Large, reopened.posterSize.value)
        assertEquals(30, reopened.seekSeconds.value)
    }

    @Test
    fun theReadOnlyViewsFollowTheWindowsChanges() {
        val settings = SettingsRepository(SettingsStorage.InMemory())
        val player: PlaybackSettings = settings
        val downloads: DownloadSettings = settings
        val appearance: AppearanceSettings = settings
        assertEquals(true, player.autoplayNext.value)
        settings.setAutoplayNext(false)
        settings.setDownloadsAtOnce(1)
        assertEquals(false, player.autoplayNext.value)
        assertEquals(1, downloads.downloadsAtOnce.value)
        settings.setShowRandom(false)
        assertEquals(false, appearance.showRandom.value)
        assertEquals(10, player.seekSeconds.value)
        assertEquals(true, player.skipStamps.value)
        settings.setSkipStamps(false)
        assertEquals(false, player.skipStamps.value)
        settings.setSeekSeconds(5)
        assertEquals(5, player.seekSeconds.value)
    }

    @Test
    fun anUnchangedValueIsNotWrittenAgain() {
        var writes = 0
        val storage = object : SettingsStorage {
            override fun load(): Map<String, String> = emptyMap()
            override fun save(values: Map<String, String>) {
                writes++
            }
        }
        val settings = SettingsRepository(storage)
        settings.setAutoplayNext(true)
        assertEquals(0, writes)
        settings.setAutoplayNext(false)
        assertEquals(1, writes)
    }

    @Test
    fun aFailedWriteKeepsTheValueAndSaysOnlyWhatKindOfFailure() {
        val failures = mutableListOf<String>()
        val storage = object : SettingsStorage {
            override fun load(): Map<String, String> = emptyMap()
            override fun save(values: Map<String, String>) = throw java.io.IOException("path-marker")
        }
        val settings = SettingsRepository(storage) { failures += it }
        settings.setDownloadsAtOnce(2)
        assertEquals(2, settings.downloadsAtOnce.value)
        assertEquals(listOf("IOException"), failures)
    }

    /** Two changes to different settings at the same moment: neither is lost, in memory, the views or the file. */
    @Test
    fun twoChangesAtOnceAreBothKept() {
        repeat(ROUNDS) {
            val storage = SettingsStorage.InMemory()
            val settings = SettingsRepository(storage)
            val start = CyclicBarrier(2)
            listOf(
                thread { start.await(); settings.setAutoplayNext(false) },
                thread { start.await(); settings.setDownloadsAtOnce(5) },
            ).forEach { it.join() }
            assertEquals(SettingsValues(autoplayNext = false, downloadsAtOnce = 5), settings.values.value)
            assertEquals(false, settings.autoplayNext.value)
            assertEquals(5, settings.downloadsAtOnce.value)
            val both = mapOf(
                "autoplay_next_key" to "false",
                "download_parallel_key" to "5",
                "poster_size_desktop_key" to "medium",
                "random_button_key" to "true",
                "seek_amount_desktop_key" to "10",
                "enable_skip_op_from_database" to "true",
            )
            assertEquals(both, storage.stored, "the file ends on both")
        }
    }

    /** A save that fails while another change lands: the views still end on the latest value. */
    @Test
    fun twoChangesAtOnceWithEveryWriteFailingStillEndTheViewsOnBoth() {
        repeat(ROUNDS) {
            val storage = object : SettingsStorage {
                override fun load(): Map<String, String> = emptyMap()
                override fun save(values: Map<String, String>) = throw java.io.IOException("path-marker")
            }
            val settings = SettingsRepository(storage)
            val start = CyclicBarrier(2)
            listOf(
                thread { start.await(); settings.setAutoplayNext(false) },
                thread { start.await(); settings.setDownloadsAtOnce(5) },
            ).forEach { it.join() }
            assertEquals(false, settings.autoplayNext.value)
            assertEquals(5, settings.downloadsAtOnce.value)
        }
    }

    @Test
    fun aCountTheWindowDoesNotOfferIsRefused() {
        val settings = SettingsRepository(SettingsStorage.InMemory())
        assertFailsWith<IllegalArgumentException> { settings.setDownloadsAtOnce(6) }
        assertEquals(3, settings.downloadsAtOnce.value)
        assertFailsWith<IllegalArgumentException> { settings.setSeekSeconds(20) }
        assertEquals(10, settings.seekSeconds.value)
    }

    private companion object {
        const val ROUNDS = 500
    }
}

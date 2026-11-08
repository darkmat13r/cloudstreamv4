package com.lagradost.cloudstream4.feature.settings

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
        assertEquals(mapOf("autoplay_next_key" to "false", "download_parallel_key" to "5"), storage.stored)

        val reopened = SettingsRepository(storage)
        assertEquals(false, reopened.autoplayNext.value)
        assertEquals(5, reopened.downloadsAtOnce.value)
    }

    @Test
    fun theReadOnlyViewsFollowTheWindowsChanges() {
        val settings = SettingsRepository(SettingsStorage.InMemory())
        val player: PlaybackSettings = settings
        val downloads: DownloadSettings = settings
        assertEquals(true, player.autoplayNext.value)
        settings.setAutoplayNext(false)
        settings.setDownloadsAtOnce(1)
        assertEquals(false, player.autoplayNext.value)
        assertEquals(1, downloads.downloadsAtOnce.value)
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
            override fun save(values: Map<String, String>) = throw java.io.IOException("/Users/someone/.cloudstream4")
        }
        val settings = SettingsRepository(storage) { failures += it }
        settings.setDownloadsAtOnce(2)
        assertEquals(2, settings.downloadsAtOnce.value)
        assertEquals(listOf("IOException"), failures)
    }

    @Test
    fun aCountTheWindowDoesNotOfferIsRefused() {
        val settings = SettingsRepository(SettingsStorage.InMemory())
        assertFailsWith<IllegalArgumentException> { settings.setDownloadsAtOnce(6) }
        assertEquals(3, settings.downloadsAtOnce.value)
    }
}

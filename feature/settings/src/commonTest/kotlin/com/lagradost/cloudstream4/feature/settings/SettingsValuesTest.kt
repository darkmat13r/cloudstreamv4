package com.lagradost.cloudstream4.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SettingsValuesTest {
    @Test
    fun nothingStoredReadsAsEveryDefault() {
        val values = SettingsValues.fromStored(emptyMap())
        assertEquals(true, values.autoplayNext)
        assertEquals(3, values.downloadsAtOnce)
    }

    @Test
    fun storedValuesReadBackUnderAndroidsKeys() {
        val values = SettingsValues.fromStored(mapOf("autoplay_next_key" to "false", "download_parallel_key" to "5"))
        assertEquals(SettingsValues(autoplayNext = false, downloadsAtOnce = 5), values)
        assertEquals(mapOf("autoplay_next_key" to "false", "download_parallel_key" to "5"), values.toStored())
    }

    @Test
    fun aMalformedOrOutOfRangeValueReadsAsItsDefaultAndLeavesTheOthers() {
        val values = SettingsValues.fromStored(
            mapOf("autoplay_next_key" to "yes", "download_parallel_key" to "99", "unknown_key" to "x"),
        )
        assertEquals(SettingsValues(), values)
        assertEquals(1, SettingsValues.fromStored(mapOf("download_parallel_key" to " 1 ")).downloadsAtOnce)
        assertEquals(3, SettingsValues.fromStored(mapOf("download_parallel_key" to "0")).downloadsAtOnce)
        assertEquals(3, SettingsValues.fromStored(mapOf("download_parallel_key" to "-2")).downloadsAtOnce)
        assertEquals(false, SettingsValues.fromStored(mapOf("autoplay_next_key" to "false", "download_parallel_key" to "x")).autoplayNext)
    }

    @Test
    fun aValueTheWindowCannotOfferCannotBeBuilt() {
        assertFailsWith<IllegalArgumentException> { SettingsValues(downloadsAtOnce = 6) }
        assertFailsWith<IllegalArgumentException> { SettingsValues(downloadsAtOnce = 0) }
    }

    @Test
    fun theDefaultPlaybackSettingsPlayTheNextEpisode() {
        assertEquals(true, PlaybackSettings.Default.autoplayNext.value)
    }
}

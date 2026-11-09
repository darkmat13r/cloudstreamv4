package com.lagradost.cloudstream4.feature.settings

import com.lagradost.cloudstream4.core.designsystem.desktop.PosterSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SettingsValuesTest {
    @Test
    fun nothingStoredReadsAsEveryDefault() {
        val values = SettingsValues.fromStored(emptyMap())
        assertEquals(true, values.autoplayNext)
        assertEquals(3, values.downloadsAtOnce)
        assertEquals(true, values.showRandom, "on, where Android's is off: the Mac has always shown it")
        assertEquals(10, values.seekSeconds)
    }

    @Test
    fun storedValuesReadBackUnderAndroidsKeys() {
        val values = SettingsValues.fromStored(mapOf("autoplay_next_key" to "false", "download_parallel_key" to "5"))
        assertEquals(SettingsValues(autoplayNext = false, downloadsAtOnce = 5), values)
        assertEquals(
            mapOf(
                "autoplay_next_key" to "false",
                "download_parallel_key" to "5",
                "poster_size_desktop_key" to "medium",
                "random_button_key" to "true",
                "seek_amount_desktop_key" to "10",
                "enable_skip_op_from_database" to "true",
            ),
            values.toStored(),
        )
    }

    @Test
    fun everySeekAmountRoundTripsUnderTheMacsKey() {
        assertEquals(listOf(5, 10, 15, 30), SettingsValues.SEEK_SECONDS)
        SettingsValues.SEEK_SECONDS.forEach { seconds ->
            val stored = SettingsValues(seekSeconds = seconds).toStored()
            assertEquals(seconds.toString(), stored["seek_amount_desktop_key"])
            assertEquals(seconds, SettingsValues.fromStored(stored).seekSeconds)
        }
    }

    @Test
    fun aSeekAmountTheWindowDoesNotOfferReadsAsTenAndAndroidsKeysAreNotRead() {
        listOf("7", "0", "-5", "300", "ten", "", "10.5").forEach { bad ->
            assertEquals(10, SettingsValues.fromStored(mapOf("seek_amount_desktop_key" to bad)).seekSeconds, bad)
        }
        assertEquals(30, SettingsValues.fromStored(mapOf("seek_amount_desktop_key" to " 30 ")).seekSeconds)
        val android = mapOf("android_tv_interface_off_seek_key" to "30", "double_tap_seek_time_key2" to "30")
        assertEquals(10, SettingsValues.fromStored(android).seekSeconds)
        assertFailsWith<IllegalArgumentException> { SettingsValues(seekSeconds = 20) }
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
    fun theRandomButtonReadsBackUnderAndroidsKeyAndAnythingElseIsOn() {
        val off = SettingsValues.fromStored(mapOf("random_button_key" to "false"))
        assertEquals(false, off.showRandom)
        assertEquals("false", off.toStored()["random_button_key"])
        assertEquals(true, SettingsValues.fromStored(mapOf("random_button_key" to "0")).showRandom)
        assertEquals(true, SettingsValues.fromStored(mapOf("random_button_key" to "")).showRandom)
        assertEquals(false, SettingsValues.fromStored(mapOf("random_button_key" to " false ")).showRandom)
        assertEquals(true, AppearanceSettings.Default.showRandom.value)
    }

    @Test
    fun posterSizeReadsBackUnderTheMacsOwnKeyAndAnythingElseIsMedium() {
        assertEquals(PosterSize.Medium, SettingsValues().posterSize)
        PosterSize.entries.forEach { size ->
            val stored = SettingsValues(posterSize = size).toStored()
            assertEquals(size.name.lowercase(), stored["poster_size_desktop_key"])
            assertEquals(size, SettingsValues.fromStored(stored).posterSize)
        }
        assertEquals(PosterSize.Large, SettingsValues.fromStored(mapOf("poster_size_desktop_key" to " large ")).posterSize)
        listOf("Large", "huge", "2", "").forEach { bad ->
            assertEquals(PosterSize.Medium, SettingsValues.fromStored(mapOf("poster_size_desktop_key" to bad)).posterSize, bad)
        }
        // Android's slider is not read: the Mac's Small would mean nothing to it, and its steps mean nothing here.
        assertEquals(PosterSize.Medium, SettingsValues.fromStored(mapOf("poster_size_key" to "15")).posterSize)
        assertEquals(PosterSize.Medium, AppearanceSettings.Default.posterSize.value)
    }

    @Test
    fun skipOpIsAndroidsKeyAndOnUntilTurnedOff() {
        // KMP-86: one constant holds the default, and the switch reads Android's own key.
        assertEquals(true, SettingsValues.DEFAULT_SKIP_STAMPS)
        assertEquals(SettingsValues.DEFAULT_SKIP_STAMPS, SettingsValues.fromStored(emptyMap()).skipStamps)
        assertEquals(SettingsValues.DEFAULT_SKIP_STAMPS, PlaybackSettings.Default.skipStamps.value)
        val off = SettingsValues(skipStamps = false).toStored()
        assertEquals("false", off["enable_skip_op_from_database"])
        assertEquals(false, SettingsValues.fromStored(off).skipStamps)
        assertEquals(true, SettingsValues.fromStored(mapOf("enable_skip_op_from_database" to " true ")).skipStamps)
        assertEquals(
            SettingsValues.DEFAULT_SKIP_STAMPS,
            SettingsValues.fromStored(mapOf("enable_skip_op_from_database" to "no")).skipStamps,
        )
    }

    @Test
    fun aValueTheWindowCannotOfferCannotBeBuilt() {
        assertFailsWith<IllegalArgumentException> { SettingsValues(downloadsAtOnce = 6) }
        assertFailsWith<IllegalArgumentException> { SettingsValues(downloadsAtOnce = 0) }
    }

    @Test
    fun theDefaultPlaybackSettingsPlayTheNextEpisode() {
        assertEquals(true, PlaybackSettings.Default.autoplayNext.value)
        assertEquals(10, PlaybackSettings.Default.seekSeconds.value)
    }
}

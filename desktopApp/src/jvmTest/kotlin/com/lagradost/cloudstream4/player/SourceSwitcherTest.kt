package com.lagradost.cloudstream4.player

import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.feature.detail.rankOf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the player screen does when a stream fails, driven without a media host: a "player" here is a label. */
class SourceSwitcherTest {

    private fun link(name: String, quality: Int?) = StreamLink(name, null, "https://a/$name", null, null, null, quality = quality)

    private val uhd = link("uhd", 2160)
    private val fhd = link("fhd", 1080)
    private val hd = link("hd", 720)
    private val unknown = link("unknown", 400)

    @Test
    fun `a failure moves to the next best, then the next, and stops when every link has failed`() {
        val found = listOf(hd, uhd, unknown, fhd)
        val switcher = SourceSwitcher(uhd)
        val played = mutableListOf("uhd")
        var current = uhd
        while (true) {
            switcher.onFailed(current)
            val (next, player) = switcher.fallThrough(current, found) { "player" } ?: break
            assertEquals("player", player)
            current = next
            played += next.label
        }
        assertEquals(listOf("uhd", "fhd", "hd", "unknown"), played)
        assertEquals(4, switcher.failed.size, "all four are marked, and all four can still be chosen")
        assertNull(switcher.fallThrough(current, found) { "player" }, "and it does not go round again")
    }

    @Test
    fun `a link whose player will not start is skipped, marked, and the next is tried`() {
        val switcher = SourceSwitcher(uhd)
        switcher.onFailed(uhd)
        val asked = mutableListOf<String>()
        val result = switcher.fallThrough(uhd, listOf(uhd, fhd, hd)) {
            asked += "x"
            if (asked.size == 1) null else "player"
        }
        assertEquals(hd, result?.first)
        assertEquals(listOf(uhd, fhd), switcher.failed.toList())
    }

    @Test
    fun `when no player will start at all, every link is tried once and it ends`() {
        val switcher = SourceSwitcher(uhd)
        switcher.onFailed(uhd)
        var opens = 0
        assertNull(switcher.fallThrough(uhd, listOf(uhd, fhd, hd, unknown)) { opens++; null as String? })
        assertEquals(3, opens)
        assertEquals(4, switcher.failed.size)
    }

    @Test
    fun `a better link that arrives after the failure is next`() {
        val switcher = SourceSwitcher(hd)
        switcher.onFailed(hd)
        val result = switcher.fallThrough(hd, listOf(hd, unknown, uhd)) { "player" }
        assertEquals(uhd, result?.first)
    }

    @Test
    fun `after everything failed, a link that arrives later is tried`() {
        val switcher = SourceSwitcher(hd)
        switcher.onFailed(hd)
        assertNull(switcher.fallThrough(hd, listOf(hd)) { "player" })
        assertEquals(unknown, switcher.fallThrough(hd, listOf(hd, unknown)) { "player" }?.first)
    }

    @Test
    fun `the notice names the one that failed, each time`() {
        val switcher = SourceSwitcher(uhd)
        assertNull(switcher.fellThrough)
        switcher.fallThrough(uhd, listOf(uhd, fhd, hd)) { "player" }
        val first = switcher.fellThrough
        assertEquals(uhd, first?.from)
        switcher.fallThrough(fhd, listOf(uhd, fhd, hd)) { "player" }
        assertEquals(fhd, switcher.fellThrough?.from)
        assertTrue(switcher.fellThrough != first, "a second one is a new notice")
    }

    @Test
    fun `a person can choose a failed link again, and it is no longer marked`() {
        val switcher = SourceSwitcher(uhd)
        switcher.onFailed(uhd)
        assertEquals("player", switcher.choose(uhd) { "player" })
        assertTrue(switcher.failed.isEmpty())
        assertNull(switcher.choose(fhd) { null as String? })
        assertEquals(listOf(fhd), switcher.failed.toList())
    }

    @Test
    fun `a link someone chose is not tried again by itself`() {
        val switcher = SourceSwitcher(uhd)
        switcher.choose(fhd) { "player" }
        switcher.onFailed(fhd)
        assertEquals(hd, switcher.fallThrough(fhd, listOf(uhd, fhd, hd)) { "player" }?.first)
    }

    @Test
    fun `a stream fails when it cannot start, when libVLC gives up, and when it ends without a frame`() {
        val playing = PlayerStatus(playing = true, buffering = false)
        assertTrue(streamFailed(started = false, status = PlayerStatus(), framed = false))
        assertTrue(streamFailed(started = true, status = playing.copy(failed = true), framed = true))
        assertTrue(streamFailed(started = true, status = PlayerStatus(finished = true), framed = false), "ended with nothing shown")
        assertFalse(streamFailed(started = true, status = PlayerStatus(finished = true), framed = true), "an ending")
        assertFalse(streamFailed(started = true, status = PlayerStatus(), framed = false), "still opening")
    }

    /** The common code restates `Qualities` because it cannot see `:library`. This is the real enum, read here. */
    @Test
    fun `the restated ranks are the old app's, for every quality a plugin could plausibly send`() {
        for (quality in -10..5000) {
            val closest = Qualities.entries.minBy { abs(it.value - quality) }
            assertEquals(closest.defaultPriority, rankOf(quality), "quality $quality")
        }
        assertEquals(Qualities.Unknown.defaultPriority, rankOf(null))
    }
}

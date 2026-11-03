package com.lagradost.cloudstream4.media.subtitle

import com.lagradost.cloudstream4.media.model.SubtitleCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleDelayTest {

    private val cue = SubtitleCue(10_000, 2_000, listOf("x"))

    /** C.TIME_UNSET in Media3, which a player can report before it has a position. */
    private val timeUnset = Long.MIN_VALUE + 1

    @Test
    fun `a positive delay shows the cue later`() {
        assertEquals(10_500, SubtitleDelay.delayed(cue, 500).startTimeMs)
        assertEquals(9_500, SubtitleDelay.delayed(cue, -500).startTimeMs)
    }

    @Test
    fun `delay keeps duration and text`() {
        val moved = SubtitleDelay.delayed(cue, 500)
        assertEquals(2_000, moved.durationMs)
        assertEquals(cue.text, moved.text)
    }

    @Test
    fun `delay agrees with the file time looked up at a position`() {
        assertEquals(9_500, SubtitleDelay.cueTimeAt(positionMs = 10_000, delayMs = 500))
        assertEquals(10_500, SubtitleDelay.cueTimeAt(positionMs = 10_000, delayMs = -500))
    }

    @Test
    fun `an early cue can go negative and an opposite delay restores it`() {
        val early = SubtitleDelay.delayed(cue, -15_000)
        assertEquals(-5_000, early.startTimeMs)
        assertEquals(cue, SubtitleDelay.delayed(early, 15_000))
    }

    @Test
    fun `sync puts the tapped cue on screen at the position`() {
        val delay = SubtitleDelay.delayToSync(cue, positionMs = 12_500)
        assertEquals(2_500, delay)
        assertEquals(cue.startTimeMs, SubtitleDelay.cueTimeAt(12_500, delay))
    }

    @Test
    fun `an extreme delay is clamped`() {
        assertEquals(cue.startTimeMs + SubtitleDelay.MAX_DELAY_MS, SubtitleDelay.delayed(cue, Long.MAX_VALUE).startTimeMs)
        assertEquals(cue.startTimeMs - SubtitleDelay.MAX_DELAY_MS, SubtitleDelay.delayed(cue, Long.MIN_VALUE).startTimeMs)
    }

    @Test
    fun `an unset position saturates instead of wrapping to the far future`() {
        assertEquals(Long.MIN_VALUE, SubtitleDelay.cueTimeAt(timeUnset, delayMs = 500))
    }

    @Test
    fun `a maximal position saturates`() {
        assertEquals(Long.MAX_VALUE, SubtitleDelay.cueTimeAt(Long.MAX_VALUE, delayMs = -500))
    }

    @Test
    fun `a delayed cue at the edge of the range keeps end arithmetic valid`() {
        val edge = SubtitleCue(Long.MAX_VALUE - 10, 5, listOf("x"))
        val moved = SubtitleDelay.delayed(edge, 1_000)
        assertEquals(Long.MAX_VALUE, moved.endTimeMs)
        assertEquals(5, moved.durationMs)
    }

    @Test
    fun `sync with extreme values stays clamped`() {
        val extreme = SubtitleCue(Long.MIN_VALUE, 0, listOf("x"))
        assertEquals(SubtitleDelay.MAX_DELAY_MS, SubtitleDelay.delayToSync(extreme, Long.MAX_VALUE))
    }
}

class SubtitleTimelineTest {

    private fun cue(start: Long, end: Long, text: String = "$start") =
        SubtitleCue(start, end - start, listOf(text))

    private val timeline = SubtitleTimeline(
        listOf(cue(5_000, 6_000), cue(1_000, 2_000), cue(3_000, 4_000)),
    )

    private fun SubtitleTimeline.startsAt(position: Long, delay: Long = 0) =
        activeAt(position, delay).map { it.startTimeMs }

    @Test
    fun `out of order cues are sorted`() {
        assertEquals(listOf(1_000L, 3_000L, 5_000L), timeline.cues.map { it.startTimeMs })
    }

    @Test
    fun `a cue covers its start and not its end`() {
        assertEquals(emptyList(), timeline.startsAt(999))
        assertEquals(listOf(1_000L), timeline.startsAt(1_000))
        assertEquals(listOf(1_000L), timeline.startsAt(1_999))
        assertEquals(emptyList(), timeline.startsAt(2_000))
    }

    @Test
    fun `a gap and the time after the last cue show nothing`() {
        assertEquals(emptyList(), timeline.startsAt(2_500))
        assertEquals(emptyList(), timeline.startsAt(99_000))
    }

    @Test
    fun `a zero length cue is never active`() {
        assertEquals(emptyList(), SubtitleTimeline(listOf(cue(1_000, 1_000))).startsAt(1_000))
    }

    @Test
    fun `an empty timeline answers`() {
        val empty = SubtitleTimeline(emptyList())
        assertTrue(empty.activeAt(0).isEmpty())
        assertNull(empty.latestStartedIndex(0))
    }

    @Test
    fun `overlapping cues are all active earliest first`() {
        val overlapping = SubtitleTimeline(listOf(cue(2_000, 3_000), cue(1_000, 5_000)))
        assertEquals(listOf(1_000L, 2_000L), overlapping.startsAt(2_500))
    }

    @Test
    fun `a long cue is found behind short cues that have ended`() {
        val hidden = SubtitleTimeline(listOf(cue(0, 100_000), cue(1_000, 2_000), cue(3_000, 4_000)))
        assertEquals(listOf(0L), hidden.startsAt(50_000))
        assertEquals(listOf(0L, 3_000L), hidden.startsAt(3_500))
    }

    @Test
    fun `lookup takes the delay without re-parsing`() {
        assertEquals(listOf(1_000L), timeline.startsAt(1_500, delay = 0))
        assertEquals(listOf(1_000L), timeline.startsAt(2_500, delay = 1_000))
        assertEquals(emptyList(), timeline.startsAt(1_500, delay = 1_000))
    }

    @Test
    fun `the returned cues hold file times`() {
        assertEquals(1_000, timeline.activeAt(2_500, delayMs = 1_000).single().startTimeMs)
    }

    @Test
    fun `an unset position shows nothing`() {
        assertEquals(emptyList(), timeline.startsAt(Long.MIN_VALUE + 1, delay = 500))
    }

    @Test
    fun `active cues are capped and the latest started win`() {
        val crowd = SubtitleTimeline(List(10_000) { cue(it.toLong(), 1_000_000) })
        val shown = crowd.activeAt(50_000, maxResults = 3)
        assertEquals(listOf(9_997L, 9_998L, 9_999L), shown.map { it.startTimeMs })
    }

    @Test
    fun `the default cap holds on an all overlapping file`() {
        val crowd = SubtitleTimeline(List(10_000) { cue(it.toLong(), 1_000_000) })
        assertEquals(8, crowd.activeAt(50_000).size)
    }

    @Test
    fun `latest started index finds the last cue begun even after it ended`() {
        assertNull(timeline.latestStartedIndex(999))
        assertEquals(0, timeline.latestStartedIndex(1_000))
        assertEquals(0, timeline.latestStartedIndex(2_500))
        assertEquals(2, timeline.latestStartedIndex(99_000))
        assertEquals(0, timeline.latestStartedIndex(3_500, delayMs = 1_000))
    }

    @Test
    fun `equal starts keep file order`() {
        val same = SubtitleTimeline(listOf(cue(1_000, 2_000, "a"), cue(1_000, 2_000, "b")))
        assertEquals(listOf("a", "b"), same.activeAt(1_500).map { it.text.single() })
    }

    @Test
    fun `parsed cues flow through the timeline`() {
        val parsed = SubtitleParser.parse("00:00:05,000 --> 00:00:06,000\nlate\n\n00:00:01,000 --> 00:00:02,000\nearly")
        val fromFile = SubtitleTimeline(parsed.cues)
        assertEquals(listOf("early"), fromFile.activeAt(1_500).single().text)
        assertEquals(listOf("late"), fromFile.activeAt(5_500).single().text)
    }
}

package com.lagradost.cloudstream4.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackClockTest {
    private var now = 0L
    private val clock = PlaybackClock { now }

    private fun advance(millis: Long) {
        now += millis * 1_000_000
    }

    private fun playing(position: Long, length: Long = 60_000) =
        PlayerStatus(playing = true, buffering = false, positionMillis = position, lengthMillis = length)

    @Test
    fun `between two reports the position runs at wall clock speed`() {
        clock.onStatus(playing(1_000))
        advance(100)
        assertEquals(1_100, clock.positionMillis())
        advance(150)
        assertEquals(1_250, clock.positionMillis())
    }

    @Test
    fun `the next report replaces the estimate`() {
        clock.onStatus(playing(1_000))
        advance(250)
        clock.onStatus(playing(1_230))
        assertEquals(1_230, clock.positionMillis())
        advance(10)
        assertEquals(1_240, clock.positionMillis())
    }

    @Test
    fun `a pause holds what was on screen, however long it lasts`() {
        clock.onStatus(playing(1_000))
        advance(200)
        clock.onStatus(playing(1_000).copy(playing = false))
        assertEquals(1_200, clock.positionMillis())
        advance(60_000)
        assertEquals(1_200, clock.positionMillis())
    }

    @Test
    fun `a resume carries on from the pause`() {
        clock.onStatus(playing(1_000))
        advance(200)
        clock.onStatus(playing(1_000).copy(playing = false))
        advance(5_000)
        clock.onStatus(playing(1_000))
        assertEquals(1_200, clock.positionMillis())
        advance(100)
        assertEquals(1_300, clock.positionMillis())
    }

    @Test
    fun `a report while paused still moves it, because a seek while paused does that`() {
        clock.onStatus(playing(1_000).copy(playing = false))
        clock.onStatus(playing(30_000).copy(playing = false))
        assertEquals(30_000, clock.positionMillis())
    }

    @Test
    fun `buffering stops it`() {
        clock.onStatus(playing(1_000))
        advance(100)
        clock.onStatus(playing(1_000).copy(buffering = true))
        advance(5_000)
        assertEquals(1_100, clock.positionMillis())
    }

    @Test
    fun `a stream that stops reporting cannot run away`() {
        clock.onStatus(playing(1_000))
        advance(60_000)
        assertEquals(2_000, clock.positionMillis())
    }

    @Test
    fun `it never passes the end`() {
        clock.onStatus(playing(59_900))
        advance(800)
        assertEquals(60_000, clock.positionMillis())
    }

    @Test
    fun `a seek moves it at once, and holds there until the stream arrives`() {
        clock.onStatus(playing(10_000))
        clock.onSeekBy(10_000)
        assertEquals(20_000, clock.positionMillis())
        advance(500)
        assertEquals(20_000, clock.positionMillis())
    }

    @Test
    fun `a report from before the seek, still in the pipe, does not undo it`() {
        clock.onStatus(playing(10_000))
        clock.onSeekBy(10_000)
        advance(50)
        clock.onStatus(playing(10_050))
        assertEquals(20_000, clock.positionMillis())
    }

    @Test
    fun `the first report from after the seek wins, even if it landed somewhere else`() {
        clock.onStatus(playing(10_000))
        clock.onSeekBy(10_000)
        advance(50)
        clock.onStatus(playing(18_000))
        assertEquals(18_000, clock.positionMillis())
        advance(100)
        assertEquals(18_100, clock.positionMillis())
    }

    @Test
    fun `a seek that never lands gives way to what the stream says`() {
        clock.onStatus(playing(10_000))
        clock.onSeekBy(10_000)
        advance(4_000)
        clock.onStatus(playing(10_100))
        assertEquals(10_100, clock.positionMillis())
    }

    @Test
    fun `a seek by fraction uses the length, and a seek past either end stops at it`() {
        clock.onStatus(playing(10_000))
        clock.onSeekTo(0.5f)
        assertEquals(30_000, clock.positionMillis())
        clock.onSeekBy(-500_000)
        assertEquals(0, clock.positionMillis())
        clock.onSeekBy(500_000)
        assertEquals(60_000, clock.positionMillis())
    }

    @Test
    fun `a seek by fraction with no length yet moves nothing`() {
        clock.onSeekTo(0.5f)
        assertEquals(0, clock.positionMillis())
    }
}

package com.lagradost.cloudstream4.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** 7d's `HIDDEN` and auto-hide rules, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerHudControllerTest {

    private val actions = mutableListOf<PlayerHudAction>()

    private fun TestScope.controller(playing: Boolean = true): PlayerHudController =
        PlayerHudController(backgroundScope, hiddenSeekMs = 10_000, onAction = { actions += it }).apply {
            state = PlayerHudState(playing = playing, positionMs = 60_000, durationMs = 600_000)
        }

    @Test
    fun shownWhilePlayingItHidesThreeSecondsAfterTheLastKey() = runTest {
        val hud = controller()
        hud.reveal()
        advanceTimeBy(2_000)
        assertFalse(hud.onKey(HudKey.Down), "a key while shown goes on to the focused control")
        advanceTimeBy(2_900)
        assertTrue(hud.shown.value, "the key re-armed the timer")
        advanceTimeBy(200)
        runCurrent()
        assertFalse(hud.shown.value)
    }

    @Test
    fun pausedOrBufferingItStaysAndHidesOnceItPlaysAgain() = runTest {
        val hud = controller(playing = false)
        hud.reveal()
        advanceTimeBy(10_000)
        assertTrue(hud.shown.value, "paused")

        hud.state = hud.state.copy(playing = true, buffering = true)
        advanceTimeBy(10_000)
        assertTrue(hud.shown.value, "buffering")

        hud.state = hud.state.copy(buffering = false)
        advanceTimeBy(3_001)
        runCurrent()
        assertFalse(hud.shown.value)
    }

    @Test
    fun hiddenLeftAndRightScrubWithoutRevealingAndTheReadoutAddsUpTheBurst() = runTest {
        val hud = controller()
        assertTrue(hud.onKey(HudKey.Right))
        assertTrue(hud.onKey(HudKey.Right))
        assertTrue(hud.onKey(HudKey.Right))
        assertEquals(List<PlayerHudAction>(3) { PlayerHudAction.SeekBy(10_000) }, actions)
        assertEquals(ScrubReadout(deltaMs = 30_000, targetMs = 90_000), hud.readout.value)
        assertFalse(hud.shown.value)

        advanceTimeBy(1_400)
        assertTrue(hud.onKey(HudKey.Left))
        assertEquals(ScrubReadout(deltaMs = 20_000, targetMs = 80_000), hud.readout.value, "one burst while the readout is up")
        advanceTimeBy(1_501)
        runCurrent()
        assertNull(hud.readout.value, "it clears 1500 ms after the last key")
    }

    @Test
    fun aScrubLandsInsideTheTitle() = runTest {
        val hud = controller()
        repeat(10) { hud.onKey(HudKey.Left) }
        assertEquals(0, hud.readout.value?.targetMs)
    }

    @Test
    fun hiddenUpAndDownRevealAndCentreTogglesAndReveals() = runTest {
        val hud = controller()
        assertTrue(hud.onKey(HudKey.Up))
        assertTrue(hud.shown.value)
        hud.hide()
        assertTrue(hud.onKey(HudKey.Center))
        assertEquals(listOf<PlayerHudAction>(PlayerHudAction.PlayPause), actions)
        assertTrue(hud.shown.value)
    }

    @Test
    fun hiddenCentreTakesAnOfferedSkipAndDoesNotReveal() = runTest {
        val hud = controller()
        hud.state = hud.state.copy(skip = HudSkip("Skip opening"))
        assertTrue(hud.onKey(HudKey.Center))
        assertEquals(listOf<PlayerHudAction>(PlayerHudAction.Skip), actions)
        assertFalse(hud.shown.value)
    }

    @Test
    fun theMediaKeyTogglesEitherWayAndOtherKeysPassWhenHidden() = runTest {
        val hud = controller()
        assertTrue(hud.onKey(HudKey.PlayPause))
        hud.reveal()
        assertTrue(hud.onKey(HudKey.PlayPause))
        assertEquals(listOf<PlayerHudAction>(PlayerHudAction.PlayPause, PlayerHudAction.PlayPause), actions)
        hud.hide()
        assertFalse(hud.onKey(HudKey.Other))
    }
}

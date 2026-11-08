package com.lagradost.cloudstream4.player

import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** `KMP-94`: Up next's count, when the card is due, and the handover that keeps full screen. */
class UpNextCountdownTest {
    @Test
    fun theCountGoesFromTenAndItsLastSecondStartsTheNextEpisodeOnce() {
        val count = UpNextCountdown(autoplay = true)
        assertEquals(UpNextDefaults.CountdownSeconds, count.left)
        repeat(UpNextDefaults.CountdownSeconds - 1) { assertFalse(count.tick()) }
        assertEquals(1, count.left)
        assertTrue(count.tick(), "the last second starts it")
        assertTrue(count.starting)
        assertFalse(count.tick(), "and only once")
        assertFalse(count.playNow(), "Play now cannot start it again")
    }

    @Test
    fun offThereIsNoCountAndPlayNowStillStarts() {
        val count = UpNextCountdown(autoplay = false)
        assertNull(count.left)
        assertFalse(count.tick())
        assertTrue(count.playNow())
        assertFalse(count.playNow())
    }

    @Test
    fun cancelStopsTheCountAndTurningTheSettingOnDoesNotUndoIt() {
        val count = UpNextCountdown(autoplay = true)
        count.tick()
        count.cancel()
        assertNull(count.left)
        assertFalse(count.dismissed, "Cancel keeps the card")
        count.autoplayChanged(true)
        assertNull(count.left, "a Cancel stays a Cancel")
        assertTrue(count.playNow())
    }

    @Test
    fun theSettingStartsAndStopsACountThatWasNotCancelled() {
        val count = UpNextCountdown(autoplay = false)
        count.autoplayChanged(true)
        assertEquals(UpNextDefaults.CountdownSeconds, count.left)
        count.tick()
        count.autoplayChanged(true)
        assertEquals(UpNextDefaults.CountdownSeconds - 1, count.left, "already counting, it goes on")
        count.autoplayChanged(false)
        assertNull(count.left)
    }

    @Test
    fun theXPutsTheCardAwayAndNothingCountsAfter() {
        val count = UpNextCountdown(autoplay = true)
        count.dismiss()
        assertTrue(count.dismissed)
        assertNull(count.left)
        assertFalse(count.tick())
    }

    @Test
    fun theCardIsDueInTheLastThirtySecondsOrOnceTheStreamHasEnded() {
        val hour = 3_600_000L
        assertFalse(upNextDue(PlayerStatus(positionMillis = hour - UpNextDefaults.LeadMillis - 1, lengthMillis = hour)))
        assertTrue(upNextDue(PlayerStatus(positionMillis = hour - UpNextDefaults.LeadMillis, lengthMillis = hour)))
        assertFalse(upNextDue(PlayerStatus(positionMillis = 0, lengthMillis = 0)), "a length not known yet is not the end")
        assertTrue(upNextDue(PlayerStatus(finished = true)))
    }

    @Test
    fun aHandoverKeepsTheSameFullScreenAndOnlyWhileItLasts() {
        val window = WindowState()
        val handover = PlayerHandover()
        val first = handover.fullscreen { PlayerFullscreen(window) }
        first?.toggle()
        assertEquals(WindowPlacement.Fullscreen, window.placement)
        assertFalse(handover.keepsFullscreen(), "a player closing by Back leaves full screen")

        handover.begin()
        assertTrue(handover.keepsFullscreen(), "the one giving way to the next episode does not")
        val second = handover.fullscreen { PlayerFullscreen(window) }
        assertSame(first, second, "the next one takes over the same, which remembers the window before full screen")
        handover.settled()
        assertFalse(handover.keepsFullscreen())
        second?.leave()
        assertEquals(WindowPlacement.Floating, window.placement, "leaving goes back to how the window was")

        val third = handover.fullscreen { PlayerFullscreen(window) }
        assertTrue(third !== first, "outside a handover, a new player makes its own")
    }
}

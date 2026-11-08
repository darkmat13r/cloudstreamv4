package com.lagradost.cloudstream4.feature.player.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.devicetest.assertScreenshot
import com.lagradost.cloudstream4.core.devicetest.focusedLabel
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.player.HudBanner
import com.lagradost.cloudstream4.feature.player.HudSkip
import com.lagradost.cloudstream4.feature.player.PlayerHudAction
import com.lagradost.cloudstream4.feature.player.PlayerHudState
import com.lagradost.cloudstream4.feature.player.ScrubReadout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Artboard **7d** of `design/TV Detail Player.dc.html` on the device, with the board's data. There is no video and no
 * player here: the HUD is drawn over a flat frame, so nothing can make a sound.
 */
@RunWith(AndroidJUnit4::class)
class PlayerHudTvDeviceTest {

    @get:Rule
    val rule = createComposeRule()

    private val dpad = DpadDriver(readFocus = { rule.focusedLabel() })
    private val actions = CopyOnWriteArrayList<PlayerHudAction>()

    private fun show(state: PlayerHudState = Board, shown: Boolean = true, readout: ScrubReadout? = null) {
        rule.setContent {
            CloudStreamTheme {
                // A flat stand-in for the video, the board's `.art` at mid-grey, so the scrims read.
                Box(Modifier.fillMaxSize().background(Frame)) {
                    PlayerHudTv(
                        state = state,
                        shown = shown,
                        readout = readout,
                        clock = "21:47",
                        shownSeekMs = SHOWN_SEEK_MS,
                        onAction = { actions += it },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    // ---- What it says ----

    @Test
    fun theBoardsReadoutsAreAllThere() {
        show()
        for (text in listOf(
            "Quiet Harbor", "S1:E4 · Low Tide · 1080p · AVC · English 5.1", "DOWNLOADED", "21:47",
            "42:13", "-19:31", "1:01:44", "Up next · S1:E5 The Night the Lighthouse Keeper Stayed",
            "Source One · 1080p", "SOURCE", "English (SRT)", "SUBTITLES", "English · 5.1 · EAC3", "AUDIO", "1.0×", "SPEED",
            "Skip opening",
        )) rule.onNodeWithText(text).assertExists()
    }

    // ---- Focus, as 7d's notes draw it ----

    @Test
    fun revealPutsFocusOnPlayPause() {
        show()
        assertEquals(PAUSE, dpad.focused())
    }

    @Test
    fun theTransportRunsLeftToRightAndDownReachesTheSelectors() {
        show()
        assertEquals(listOf(SEEK_BACK, SEEK_FORWARD, PREVIOUS, NEXT), (1..4).map { dpad.press(Dpad.Right) })
        assertEquals(NEXT, dpad.press(Dpad.Right))
        repeat(4) { dpad.press(Dpad.Left) }
        assertEquals(PAUSE, dpad.focused())
        val below = dpad.press(Dpad.Down).orEmpty()
        assertEquals("Down from play/pause is SOURCE, was $below", true, below.startsWith("Source One · 1080p"))
    }

    @Test
    fun upFromTheTransportIsTheSeekCellAndUpFromAudioToo() {
        show()
        assertEquals(SEEK_CELL, dpad.press(Dpad.Up))
        assertEquals(PAUSE, dpad.press(Dpad.Down))
        dpad.press(Dpad.Down)
        dpad.press(Dpad.Right)
        dpad.press(Dpad.Right)
        assertEquals(SEEK_CELL, dpad.press(Dpad.Up))
    }

    @Test
    fun leftAndRightOnTheSeekCellMoveByTheShownAmount() {
        show()
        assertEquals(SEEK_CELL, dpad.press(Dpad.Up))
        assertEquals(SEEK_CELL, dpad.press(Dpad.Right))
        assertEquals(SEEK_CELL, dpad.press(Dpad.Left))
        assertEquals(listOf(PlayerHudAction.SeekBy(SHOWN_SEEK_MS), PlayerHudAction.SeekBy(-SHOWN_SEEK_MS)), actions.toList())
    }

    @Test
    fun eachControlAsksForWhatItIs() {
        show()
        dpad.center()
        dpad.press(Dpad.Down)
        dpad.center()
        dpad.press(Dpad.Right)
        dpad.center()
        dpad.press(Dpad.Right)
        dpad.center()
        dpad.press(Dpad.Right)
        dpad.center()
        rule.waitForIdle()
        assertEquals(
            listOf(
                PlayerHudAction.PlayPause,
                PlayerHudAction.OpenSource,
                PlayerHudAction.OpenSubtitles,
                PlayerHudAction.OpenAudio,
                PlayerHudAction.OpenSpeed,
            ),
            actions.toList(),
        )
    }

    @Test
    fun theFirstEpisodeHasNoPreviousStop() {
        show(Board.copy(hasPrevious = false))
        assertEquals(listOf(SEEK_BACK, SEEK_FORWARD), (1..2).map { dpad.press(Dpad.Right) })
        assertEquals("a disabled cell is passed over", NEXT, dpad.press(Dpad.Right))
    }

    // ---- Hidden ----

    @Test
    fun hiddenOnlyTheSkipAndTheReadoutDraw() {
        show(shown = false, readout = ScrubReadout(deltaMs = 30_000, targetMs = 2_563_000))
        rule.onNodeWithText("+00:30 → 42:43").assertExists()
        rule.onNodeWithText("Skip opening").assertExists()
        rule.onAllNodesWithText("Quiet Harbor").assertCountEquals(0)
        rule.onAllNodesWithText("SOURCE").assertCountEquals(0)
    }

    // ---- Screenshots ----

    /** 7d as drawn: every readout, the played and buffered bands, two skip ticks, focus on play/pause. */
    @Test
    fun screenshotShown() {
        show()
        assertEquals(PAUSE, dpad.focused())
        rule.assertScreenshot("player-hud-shown")
    }

    /** `HIDDEN`: video only, the readout over its rule, and `Skip intro`, the one control allowed on screen. */
    @Test
    fun screenshotHiddenScrub() {
        show(shown = false, readout = ScrubReadout(deltaMs = 30_000, targetMs = 2_563_000))
        rule.assertScreenshot("player-hud-hidden-scrub")
    }

    /** `BUFFERING`: play/pause stays in the order, the badge in the times row, the playhead frozen. */
    @Test
    fun screenshotBuffering() {
        show(Board.copy(buffering = true, playing = false, skip = null))
        rule.assertScreenshot("player-hud-buffering")
    }

    /** A playback error: the banner over a mounted HUD, whose action is the SOURCE selector below. */
    @Test
    fun screenshotFailed() {
        show(Board.copy(banner = HudBanner.Failed, downloaded = false, skip = null))
        rule.assertScreenshot("player-hud-failed")
    }

    /** `OFFLINE`: the same slot, paired with the `DOWNLOADED` badge. */
    @Test
    fun screenshotOfflineCopy() {
        show(Board.copy(banner = HudBanner.OfflineCopy, skip = null))
        rule.assertScreenshot("player-hud-offline")
    }

    /** A film, not a series, with nothing known yet: no length, no tracks, no next. Nothing invented. */
    @Test
    fun screenshotNothingKnownYet() {
        show(PlayerHudState(title = "Northern Reach", playing = false))
        rule.assertScreenshot("player-hud-nothing-known")
    }

    private companion object {
        const val SHOWN_SEEK_MS = 10_000L
        const val PAUSE = "Pause"
        const val SEEK_BACK = "Seek back"
        const val SEEK_FORWARD = "Seek forward"
        const val PREVIOUS = "Previous episode"
        const val NEXT = "Next episode"
        const val SEEK_CELL = "42:13 / 1:01:44"
        val Frame = Color(0xFF3A3A3A)

        /** 7d's data. */
        val Board = PlayerHudState(
            title = "Quiet Harbor",
            meta = "S1:E4 · Low Tide · 1080p · AVC · English 5.1",
            downloaded = true,
            playing = true,
            positionMs = 2_533_000,
            durationMs = 3_704_000,
            bufferedMs = 3_170_000,
            marks = listOf(2_200_000, 3_390_000),
            skip = HudSkip("Skip opening"),
            hasPrevious = true,
            hasNext = true,
            upNext = "S1:E5 The Night the Lighthouse Keeper Stayed",
            source = "Source One · 1080p",
            subtitles = "English (SRT)",
            audio = "English · 5.1 · EAC3",
            speed = 1f,
        )
    }
}

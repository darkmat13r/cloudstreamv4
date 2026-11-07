package com.lagradost.cloudstream4.feature.player.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.devicetest.assertScreenshot
import com.lagradost.cloudstream4.core.devicetest.focusedLabel
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.player.PanelKind
import com.lagradost.cloudstream4.feature.player.PanelRow
import com.lagradost.cloudstream4.feature.player.PlaybackPanelState
import com.lagradost.cloudstream4.feature.player.PlayerHudAction
import com.lagradost.cloudstream4.feature.player.PlayerHudState
import com.lagradost.cloudstream4.feature.player.RowVerb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Artboard **7e** of `design/TV Detail Player.dc.html` on the device, over 7d, with the board's rows. A stand-in host opens
 * a panel for SOURCE, SUBTITLES and AUDIO and closes it on Done, as `:app`'s does. No video, so no sound.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackPanelTvDeviceTest {

    @get:Rule
    val rule = createComposeRule()

    private val dpad = DpadDriver(readFocus = { rule.focusedLabel() })
    private val actions = CopyOnWriteArrayList<PlayerHudAction>()
    private var open by mutableStateOf<PlaybackPanelState?>(null)

    /** The stand-in host: the three selectors open their panel, Done closes it, every action is recorded. */
    private fun show(initial: PlaybackPanelState? = null, panels: Map<PanelKind, PlaybackPanelState> = Panels) {
        open = initial
        rule.setContent {
            CloudStreamTheme {
                Box(Modifier.fillMaxSize().background(Frame)) {
                    PlayerHudTv(
                        state = Hud,
                        shown = true,
                        readout = null,
                        clock = "21:47",
                        shownSeekMs = 10_000,
                        onAction = { action ->
                            actions += action
                            when (action) {
                                PlayerHudAction.OpenSource -> open = panels[PanelKind.Source]
                                PlayerHudAction.OpenSubtitles -> open = panels[PanelKind.Subtitles]
                                PlayerHudAction.OpenAudio -> open = panels[PanelKind.Audio]
                                PlayerHudAction.PanelDone, PlayerHudAction.PanelBack -> open = null
                                else -> Unit
                            }
                        },
                        panel = open,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun startsWith(label: String?, prefix: String) = label.orEmpty().startsWith(prefix)

    // ---- Focus, as 7e's notes draw it ----

    @Test
    fun sourceOpensThePanelWithFocusInsideItAndDoneGivesItBackToSource() {
        show()
        assertTrue("Down from play/pause is SOURCE", startsWith(dpad.press(Dpad.Down), "Vidsrc"))
        dpad.center()
        rule.waitForIdle()
        rule.onNodeWithText("Switching keeps your position: resumes at 42:13").assertExists()
        assertTrue("Focus entered the panel's first row, was ${dpad.focused()}", startsWith(dpad.focused(), "Vidsrc · 1080p"))

        assertTrue(startsWith(dpad.press(Dpad.Down), "Filemoon"))
        assertTrue("Left stays in the panel", startsWith(dpad.press(Dpad.Left), "Filemoon"))
        assertTrue("Right stays in the panel", startsWith(dpad.press(Dpad.Right), "Filemoon"))
        repeat(4) { dpad.press(Dpad.Down) }
        assertEquals("Done", dpad.focused())

        dpad.center()
        rule.waitForIdle()
        rule.waitUntil(WAIT_MS) { startsWith(dpad.focused(), "Vidsrc · 1080p") && open == null }
        assertTrue("Focus went back to SOURCE", startsWith(dpad.focused(), "Vidsrc · 1080p"))
    }

    @Test
    fun pressingARowIsTheCommit() {
        show(initial = Panels.getValue(PanelKind.Source))
        assertTrue(startsWith(dpad.press(Dpad.Down), "Filemoon"))
        dpad.center()
        rule.waitForIdle()
        assertEquals(PlayerHudAction.PanelRowPressed(PanelKind.Source, "source:1"), actions.last())
    }

    @Test
    fun subtitlesAndAudioOpenTheirOwnPanels() {
        show()
        dpad.press(Dpad.Down)
        assertTrue(startsWith(dpad.press(Dpad.Right), "English (SRT)"))
        dpad.center()
        rule.waitForIdle()
        rule.onNodeWithText("Search online…").assertExists()
        dpad.press(Dpad.Up)
        open = null
        rule.waitForIdle()
        assertEquals(listOf(PlayerHudAction.OpenSubtitles), actions.filter { it is PlayerHudAction.OpenSubtitles })
    }

    // ---- Screenshots ----

    /** 7e's Source panel: the playing row marked three ways, a failed row with its marker, the profile, Done pinned. */
    @Test
    fun screenshotSource() {
        show(initial = Panels.getValue(PanelKind.Source))
        rule.assertScreenshot("playback-panel-source")
    }

    @Test
    fun screenshotSubtitles() {
        show(initial = Panels.getValue(PanelKind.Subtitles))
        rule.assertScreenshot("playback-panel-subtitles")
    }

    /** A subtitle's variants, the second level in the same 472 column. */
    @Test
    fun screenshotSubtitleVariants() {
        show(initial = Variants)
        rule.assertScreenshot("playback-panel-subtitle-variants")
    }

    @Test
    fun screenshotAudio() {
        show(initial = Panels.getValue(PanelKind.Audio))
        rule.assertScreenshot("playback-panel-audio")
    }

    /** 7e's Empty: the first row is Retry all sources. And offline, in the meta block's place: no row moves. */
    @Test
    fun screenshotEmptyAndOffline() {
        show(initial = Empty)
        rule.assertScreenshot("playback-panel-empty-offline")
    }

    private companion object {
        const val WAIT_MS = 5_000L
        val Frame = Color(0xFF3A3A3A)

        val Hud = PlayerHudState(
            title = "Arcane", meta = "S1:E4 · Powder · 1080p · AVC · English 5.1", playing = true,
            positionMs = 2_533_000, durationMs = 3_704_000, bufferedMs = 3_170_000, hasPrevious = true, hasNext = true,
            source = "Vidsrc · 1080p", subtitles = "English (SRT)", audio = "English · 5.1 · EAC3",
        )

        /** 7e's `praw`, `arawA` and `arawB`. */
        val Panels = mapOf(
            PanelKind.Source to PlaybackPanelState(
                kind = PanelKind.Source,
                title = "Source",
                meta = listOf("1 hidden link", "Switching keeps your position: resumes at 42:13"),
                rows = listOf(
                    PanelRow("source:0", "Vidsrc · 1080p", "1920×1080", RowVerb.Playing, active = true),
                    PanelRow("source:1", "Filemoon · 1080p", "keeps 42:13", RowVerb.Switch),
                    PanelRow("source:2", "Streamwish", "quality unknown", RowVerb.Switch),
                    PanelRow("source:3", "Doodstream · 720p", "failed", RowVerb.Retry, failed = true),
                    PanelRow("profile", "Quality profile", "Wi-Fi", RowVerb.Open),
                ),
            ),
            PanelKind.Subtitles to PlaybackPanelState(
                kind = PanelKind.Subtitles,
                title = "Subtitles",
                rows = listOf(
                    PanelRow("off", "No Subtitles", null, RowVerb.Use),
                    PanelRow("group:0", "English", "Online", RowVerb.Open, active = true),
                    PanelRow("group:1", "Spanish", "Online", RowVerb.Use),
                    PanelRow("file", "Load from file", null, RowVerb.Open),
                    PanelRow("online", "Search online…", null, RowVerb.Open),
                    PanelRow("settings", "Subtitle Settings", null, RowVerb.Open),
                ),
            ),
            PanelKind.Audio to PlaybackPanelState(
                kind = PanelKind.Audio,
                title = "Audio",
                rows = listOf(
                    PanelRow("audio:0", "English · 5.1 · EAC3", null, RowVerb.On, active = true),
                    PanelRow("audio:1", "English · Stereo · AAC", null, RowVerb.Use),
                    PanelRow("audio:2", "Japanese · Stereo · AAC", null, RowVerb.Use),
                    PanelRow("video", "Video tracks", "1920×1080", RowVerb.Open),
                ),
            ),
        )

        val Variants = PlaybackPanelState(
            kind = PanelKind.Subtitles,
            title = "English",
            nested = true,
            rows = listOf(
                PanelRow("variant:0:0", "Full", "Online", RowVerb.On, active = true),
                PanelRow("variant:0:1", "Forced", "Online", RowVerb.Use),
                PanelRow("variant:0:2", "SDH", "Embedded", RowVerb.Use),
            ),
        )

        val Empty = PlaybackPanelState(
            kind = PanelKind.Source,
            title = "Source",
            offline = true,
            rows = listOf(
                PanelRow("retryAll", "Retry all sources", null, RowVerb.Retry),
                PanelRow("profile", "Quality profile", "Wi-Fi", RowVerb.Open),
            ),
        )
    }
}

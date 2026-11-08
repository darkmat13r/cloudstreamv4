package com.lagradost.cloudstream4.feature.player.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.devicetest.TouchModeReset
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

    /** `BUG-59`: starts and ends out of touch mode, whatever ran before it. */
    @get:Rule
    val touchMode = TouchModeReset()

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

    /**
     * `BUG-36`: closing a panel gives focus back to the selector that opened it, not to the first focusable in the HUD.
     * The host closes it here as BACK does, by the panel going away, so the opener has to outlive the panel's state.
     *
     * A guard, not a reproduction: measured, the HUD without the opener passes this too. Unlike the screens BUG-36 was
     * found on, no lazy list sits between the content and the selectors. The opener keeps this passing however the HUD's
     * groups change.
     */
    @Test
    fun closingAPanelGivesFocusBackToTheSelectorThatOpenedIt() {
        show()
        dpad.press(Dpad.Down)
        val subtitles = dpad.press(Dpad.Right)
        val audio = dpad.press(Dpad.Right)
        assertTrue("No AUDIO selector", audio != null && audio != subtitles)

        // Focus is compared by node, not label: AUDIO's value and the Audio panel's first row read the same.
        for (selector in listOf(audio, subtitles)) {
            repeat(3) { if (dpad.focused() != selector) dpad.press(if (selector == subtitles) Dpad.Left else Dpad.Right) }
            assertEquals(selector, dpad.focused())
            val opener = focusedNode()
            dpad.center()
            rule.waitUntil(WAIT_MS) { open != null && focusedNode().let { it != null && it != opener } }
            open = null
            rule.waitForIdle()
            runCatching { rule.waitUntil(WAIT_MS) { focusedNode() == opener } }
            assertEquals("Focus did not go back to the selector that opened the panel ($selector)", opener, focusedNode())
        }
    }

    /**
     * `RowScroll`, as Quick Search's: five rows fit and do not move the list; each row past the fifth moves it by one
     * whole pitch, and the focused row sits whole between the header and 7e's rule, never cut against either.
     *
     * The rule is a hairline with no semantics, so the lower bound is where the fifth row ends before anything moves:
     * 7e ends it a `RuleGap` above the rule. Measured, not derived from Done: each dp rounds to whole pixels on its own
     * (1.1 px apart on `Television_720p`).
     */
    @Test
    fun rowsPastTheFifthMoveTheListByWholeRowsAndStayBetweenHeaderAndRule() {
        show(initial = Long)
        val names = Long.rows.map { it.title }
        assertEquals(names[0], dpad.focused())

        val headerBottom = bounds(Long.title).bottom
        val aboveRule = bounds(names[4]).bottom
        val firstTop = bounds(names[0]).top
        val fifthTop = bounds(names[4]).top
        val height = bounds(names[0]).height
        // Measured before anything moves: after, every row is a pitch higher and a difference of two reads nothing.
        val pitch = bounds(names[1]).top - firstTop

        assertEquals(names.subList(1, 5), (1..4).map { dpad.press(Dpad.Down) })
        rule.waitForIdle()
        // The first row is still there: measured on the default scroll, it had left the list entirely by now.
        val first = rule.onAllNodesWithText(names[0]).fetchSemanticsNodes().singleOrNull()
        assertTrue("A row that fits scrolled ${names[0]} out of the list", first != null)
        assertEquals("A row that fits moved the list", firstTop, first!!.boundsInRoot.top, 0.5f)

        for (next in 5 until names.size) {
            assertEquals(names[next], dpad.press(Dpad.Down))
            rule.waitForIdle()
            val focused = bounds(names[next])
            assertEquals("${names[next]} is not where the fifth row was", fifthTop, focused.top, 0.5f)
            assertEquals("Not one whole pitch", fifthTop - pitch, bounds(names[next - 1]).top, 0.5f)
            assertEquals("${names[next]} is cut", height, focused.height, 0.5f)
            assertTrue("${names[next]} runs under the header", focused.top >= headerBottom)
            assertTrue("${names[next]} runs past the rule", focused.bottom <= aboveRule + 0.5f)
        }
    }

    private fun bounds(text: String) = rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot

    private fun focusedNode(): Int? = rule.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.id

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

        /** Seven rows, two more than fit, under a title nothing in the HUD shares. */
        val Long = PlaybackPanelState(
            kind = PanelKind.Subtitles,
            title = "More languages",
            rows = listOf("Arabic", "Czech", "Danish", "Dutch", "Finnish", "French", "German").mapIndexed { i, name ->
                PanelRow("group:$i", name, "Online", RowVerb.Use, active = i == 0)
            },
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

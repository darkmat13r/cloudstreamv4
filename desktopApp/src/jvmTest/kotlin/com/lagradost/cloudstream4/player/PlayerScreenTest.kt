package com.lagradost.cloudstream4.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.pointer.PointerIcon
import com.lagradost.cloudstream4.core.designsystem.desktop.forDesktop
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.LocalCloudStreamTypography
import com.lagradost.cloudstream4.media.model.SubtitleCue
import com.lagradost.cloudstream4.media.model.SubtitleSource
import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.media.model.SubtitleTrack
import com.lagradost.cloudstream4.media.subtitle.SubtitleParseResult
import com.lagradost.cloudstream4.media.subtitle.SubtitleTimeline
import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.player.HudSkip
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/** The real screen under real pointer and key events, with fakes behind it. The real host is `--check-subtitle-render`. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class PlayerScreenTest {

    private val english = SubtitleTrack(SubtitleSource.Remote("https://a/en"), "English", "en")
    private val german = SubtitleTrack(SubtitleSource.Remote("https://a/de"), "German", "de")

    private class Calls : PlayerControls {
        val log = mutableListOf<String>()
        override fun togglePause() {
            log += "toggle"
        }

        override fun seekBy(deltaMillis: Long) {
            log += "by $deltaMillis"
        }

        override fun seekTo(fraction: Float) {
            log += "to"
        }

        val audioChoices = mutableListOf<Int>()

        override fun selectAudioTrack(id: Int) {
            audioChoices += id
        }

        val volumes = mutableListOf<Int>()

        override fun setVolume(percent: Int) {
            volumes += percent
        }
    }

    private inner class Screen(
        tracks: List<SubtitleTrack> = listOf(english, german),
        load: (SubtitleTrack) -> SubtitleLoad = { ready() },
        val width: Int = 1280,
        val height: Int = 800,
        status: PlayerStatus = PlayerStatus(playing = false, buffering = false, positionMillis = 1_000, lengthMillis = 60_000),
        frame: ImageBitmap? = null,
        pictureSize: IntSize? = null,
        fullscreen: PlayerFullscreen? = null,
        val volume: PlayerVolume = PlayerVolume(),
        audio: AudioTracks = AudioTracks(),
        val fit: PictureFitChoice = PictureFitChoice(),
        sources: PlayerSources? = null,
        fellThrough: FallThrough? = null,
        meta: String? = null,
        skip: HudSkip? = null,
        val idle: ManualIdle = ManualIdle(),
    ) {
        val calls = Calls()
        var skips = 0
        val audioChosen = mutableListOf<AudioTrack>()
        val clock = PlaybackClock().also { it.onStatus(status) }
        val session = SubtitleSession(tracks, { load(it) }, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        var escape: () -> Unit = {}
        var backs = 0
        private var time = 0L
        val scene = ImageComposeScene(width, height, Density(1f)) {
            CloudStreamTheme {
                CompositionLocalProvider(LocalCloudStreamTypography provides CloudStreamTheme.typography.forDesktop()) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        PlayerContent(
                            frame = frame,
                            pictureSize = pictureSize,
                            status = status,
                            caption = "Title",
                            controls = calls,
                            subtitles = session,
                            clock = clock,
                            onBack = { backs++ },
                            setEscape = { escape = it },
                            fullscreen = fullscreen,
                            volume = volume,
                            audio = audio,
                            fit = fit,
                            sources = sources,
                            fellThrough = fellThrough,
                            onAudioChosen = { audioChosen += it },
                            meta = meta,
                            skip = skip,
                            onSkip = { skips++ },
                            hudIdle = idle,
                        )
                    }
                }
            }
        }

        fun settle(): Image {
            var image = scene.render(time)
            repeat(SETTLE_FRAMES) {
                time += FRAME_NANOS
                Thread.sleep(SETTLE_SLEEP)
                image = scene.render(time)
            }
            return image
        }

        fun press(key: Key) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
            settle()
        }

        fun click(at: Offset) {
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at)
            scene.sendPointerEvent(PointerEventType.Release, at)
            settle()
        }

        private val rowWidth get() = minOf(width - 48, 720)
        private val rowLeft get() = (width - rowWidth) / 2f
        private val rowRight get() = rowLeft + rowWidth
        private val selectorsY get() = height - 24f - 8f - 20f

        /** The subtitle selector, first in the row under the transport. */
        val button: Offset get() = Offset(rowLeft + 16f + 40f, selectorsY)

        /** The picture size selector, last in that row where there is no full screen control. */
        val fitButton: Offset get() = Offset(rowRight - 16f - 40f, selectorsY)

        /** At the right end of that row. */
        val fullscreenButton: Offset get() = Offset(rowRight - 16f - 18f, height - 24f - 8f - 20f)

        /** Inside a list: `DS-23`'s panel from the right, 360 wide, so its middle. */
        val panelX: Float get() = width - PANEL_HALF_WIDTH

        /** A list's row [index], from the top: under the panel's heading, 48 apart (`KMP-91`). */
        fun panelRow(index: Int): Offset = Offset(panelX, PANEL_FIRST_ROW_Y + index * PANEL_ROW_STEP)

        /** Transport row, at its right end: the volume rule ends here. */
        val ruleRight: Float get() = rowRight - 16f
        val transportY: Float get() = height - 24f - 8f - 40f - 8f - 18f

        fun pixels(): PixelMap = settle().toComposeImageBitmap().toPixelMap()

        fun save(name: String) {
            val dir = System.getProperty("cloudstream.home.renders") ?: return
            File(dir).mkdirs()
            val data = checkNotNull(settle().encodeToData(EncodedImageFormat.PNG))
            File(dir, "$name-${width}x$height.png").writeBytes(data.bytes)
        }
    }

    private fun PixelMap.isWhite(x: Int, y: Int) = this[x, y] == Color.White

    /** The HUD's wait, run by hand (`KMP-87`): [elapse] is the three seconds passing, with no sleep. */
    private class ManualIdle : HudIdle {
        private val waiting = mutableListOf<CompletableDeferred<Unit>>()
        val pending: Int get() = waiting.count { !it.isCompleted }

        override suspend fun await() {
            val gate = CompletableDeferred<Unit>()
            waiting += gate
            try {
                gate.await()
            } finally {
                // A wait that was cancelled, by a move or a key, is not waiting any more.
                waiting -= gate
            }
        }

        fun elapse() {
            waiting.toList().forEach { it.complete(Unit) }
        }
    }

    private fun whiteFrame(width: Int, height: Int): ImageBitmap {
        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE))
        bitmap.erase(WHITE)
        return bitmap.asComposeImageBitmap()
    }

    private fun blackFrame(width: Int, height: Int): ImageBitmap {
        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE))
        bitmap.erase(BLACK)
        return bitmap.asComposeImageBitmap()
    }

    /** First and last lit rows along one column, and columns along one row: the picture's edges, past the HUD. */
    private fun pictureOnScreen(frame: ImageBitmap, pictureSize: IntSize?, width: Int = 1280, height: Int = 800): IntSize {
        val pixels = Screen(width = width, height = height, frame = frame, pictureSize = pictureSize)
            .settle().toComposeImageBitmap().toPixelMap()
        val lit = { x: Int, y: Int -> pixels[x, y].red > LIT }
        val across = (0 until width).filter { lit(it, height / 2) }
        val column = across.last() - across.size / 8
        val down = (0 until height).filter { lit(column, it) }
        return IntSize(across.size, down.last() - down.first() + 1)
    }

    @Test
    fun `a frame stretched into a decoder's buffer is drawn in the picture's shape`() {
        val shown = pictureOnScreen(whiteFrame(1280, 738), IntSize(1280, 720))
        assertEquals(1280, shown.width)
        assertEquals(720, shown.height, "1280 x 738 would fill 738 rows")
    }

    @Test
    fun `non-square pixels are drawn wide`() {
        // 720 x 576 at 16:15 is 4:3, so 1280 x 960 in this window. As stored it would be 1250 x 1000.
        val shown = pictureOnScreen(whiteFrame(720, 576), IntSize(768, 576), height = 1000)
        assertEquals(1280, shown.width)
        assertEquals(960, shown.height)
    }

    @Test
    fun `until the host can say, a frame is drawn in its own shape`() {
        val shown = pictureOnScreen(whiteFrame(1280, 738), pictureSize = null)
        assertEquals(1280, shown.width)
        assertEquals(738, shown.height)
    }

    @Test
    fun `a cue sits on the picture's shape, not on the buffer it arrived in`() {
        fun withCue(frame: ImageBitmap, pictureSize: IntSize?): PixelMap {
            val screen = Screen(height = 1000, frame = frame, pictureSize = pictureSize)
            screen.settle()
            screen.press(Key.C)
            screen.press(Key.DirectionDown)
            screen.press(Key.Enter)
            return screen.settle().toComposeImageBitmap().toPixelMap()
        }
        val stretched = withCue(blackFrame(720, 738), IntSize(1280, 720))
        val honest = withCue(blackFrame(1280, 720), null)
        val differing = (0 until 1000).sumOf { y -> (0 until 1280).count { x -> stretched[x, y] != honest[x, y] } }
        assertEquals(0, differing)
    }

    private fun ready(cue: String = "words") = SubtitleLoad.Ready(
        SubtitleTimeline(listOf(SubtitleCue(0, 60_000, listOf(cue)))),
        SubtitleParseResult(emptyList(), null),
        1,
    )

    @Test
    fun `subtitles start off, and C with the arrows and return choose a track`() {
        val screen = Screen()
        screen.settle()
        assertEquals(SubtitleSelection.Off, screen.session.selection.value)
        screen.press(Key.C)
        screen.save("subtitle-picker")
        screen.press(Key.DirectionDown)
        screen.press(Key.Enter)
        assertEquals(english, assertIs<SubtitleSelection.Showing>(screen.session.selection.value).track)
        screen.save("subtitle-showing")
        assertEquals(emptyList(), screen.calls.log, "none of that touched playback")
        screen.scene.close()
    }

    @Test
    fun `space on a row chooses it, and does not pause`() {
        val screen = Screen()
        screen.settle()
        screen.press(Key.C)
        screen.press(Key.DirectionDown)
        screen.press(Key.DirectionDown)
        screen.press(Key.Spacebar)
        assertEquals(german, assertIs<SubtitleSelection.Showing>(screen.session.selection.value).track)
        assertEquals(emptyList(), screen.calls.log)
        screen.scene.close()
    }

    @Test
    fun `the left and right arrows do not seek while the list is open, and do when it is shut`() {
        val screen = Screen()
        screen.settle()
        screen.press(Key.C)
        screen.press(Key.DirectionDown)
        screen.press(Key.DirectionLeft)
        screen.press(Key.DirectionRight)
        assertEquals(emptyList(), screen.calls.log)
        // Nor did they move focus out of the list: return still chooses the row they were pressed on.
        screen.press(Key.Enter)
        assertEquals(english, assertIs<SubtitleSelection.Showing>(screen.session.selection.value).track)
        screen.press(Key.DirectionRight)
        screen.press(Key.Spacebar)
        assertEquals(listOf("by 10000", "toggle"), screen.calls.log)
        screen.scene.close()
    }

    @Test
    fun `a click on the control opens the list, on a row chooses, and on Off turns it off`() {
        val screen = Screen()
        screen.settle()
        screen.click(screen.button)
        // The panel from the right: Off, then the two tracks, each 48 tall, under its heading.
        val german = screen.panelRow(2)
        val englishRow = screen.panelRow(1)
        val off = screen.panelRow(0)
        screen.click(german)
        assertEquals(this.german, assertIs<SubtitleSelection.Showing>(screen.session.selection.value).track)
        screen.click(screen.button)
        screen.click(englishRow)
        assertEquals(english, assertIs<SubtitleSelection.Showing>(screen.session.selection.value).track)
        screen.click(screen.button)
        screen.click(off)
        assertEquals(SubtitleSelection.Off, screen.session.selection.value)
        assertEquals(emptyList(), screen.calls.log)
        screen.scene.close()
    }

    @Test
    fun `escape closes the list before it leaves the player`() {
        val screen = Screen()
        screen.settle()
        screen.press(Key.C)
        screen.escape()
        screen.settle()
        assertEquals(0, screen.backs)
        assertEquals(SubtitleSelection.Off, screen.session.selection.value)
        // The list is gone: a press of the right arrow seeks again.
        screen.press(Key.DirectionRight)
        assertEquals(listOf("by 10000"), screen.calls.log)
        screen.escape()
        assertEquals(1, screen.backs)
        screen.scene.close()
    }

    @Test
    fun `F and Cmd+F go into full screen and back, and leave playback alone`() {
        val window = WindowState()
        val screen = Screen(fullscreen = PlayerFullscreen(window))
        screen.settle()
        screen.press(Key.F)
        assertEquals(WindowPlacement.Fullscreen, window.placement)
        screen.scene.sendKeyEvent(KeyEvent(Key.F, KeyEventType.KeyDown, isMetaPressed = true))
        screen.settle()
        assertEquals(WindowPlacement.Floating, window.placement)
        assertEquals(emptyList(), screen.calls.log)
        screen.scene.close()
    }

    @Test
    fun `the control at the end of the row goes into full screen and back`() {
        val window = WindowState()
        val screen = Screen(fullscreen = PlayerFullscreen(window))
        screen.settle()
        screen.click(screen.fullscreenButton)
        assertEquals(WindowPlacement.Fullscreen, window.placement)
        screen.click(screen.fullscreenButton)
        assertEquals(WindowPlacement.Floating, window.placement)
        screen.scene.close()
    }

    @Test
    fun `the control fits the smallest window, beside the subtitle control`() {
        val window = WindowState()
        val screen = Screen(width = 800, height = 450, fullscreen = PlayerFullscreen(window))
        screen.settle()
        screen.save("fullscreen-control")
        screen.click(screen.fullscreenButton)
        assertEquals(WindowPlacement.Fullscreen, window.placement)
        screen.click(screen.button)
        screen.press(Key.DirectionRight)
        assertEquals(emptyList(), screen.calls.log, "the control beside it opened the subtitle list")
        screen.scene.close()
    }

    @Test
    fun `escape closes the list, then full screen, and only then leaves the player`() {
        val window = WindowState()
        val screen = Screen(fullscreen = PlayerFullscreen(window))
        screen.settle()
        screen.press(Key.F)
        screen.press(Key.C)
        screen.escape()
        screen.settle()
        assertEquals(WindowPlacement.Fullscreen, window.placement)
        assertEquals(0, screen.backs)
        screen.escape()
        screen.settle()
        assertEquals(WindowPlacement.Floating, window.placement)
        assertEquals(0, screen.backs)
        screen.escape()
        assertEquals(1, screen.backs)
        screen.scene.close()
    }

    @Test
    fun `F does nothing while the list is open`() {
        val window = WindowState()
        val screen = Screen(fullscreen = PlayerFullscreen(window))
        screen.settle()
        screen.press(Key.C)
        screen.press(Key.F)
        assertEquals(WindowPlacement.Floating, window.placement)
        screen.scene.close()
    }

    @Test
    fun `full screen made with the window's own button reads as full screen, and escape leaves it`() {
        val window = WindowState()
        val screen = Screen(fullscreen = PlayerFullscreen(window))
        screen.settle()
        window.placement = WindowPlacement.Fullscreen
        screen.settle()
        screen.escape()
        assertEquals(WindowPlacement.Floating, window.placement)
        assertEquals(0, screen.backs)
        screen.scene.close()
    }

    @Test
    fun `with no window there is no control, and F is not taken`() {
        val screen = Screen()
        screen.settle()
        screen.press(Key.F)
        assertEquals(emptyList(), screen.calls.log)
        screen.escape()
        assertEquals(1, screen.backs)
        screen.scene.close()
    }

    @Test
    fun `closing the player goes back to where the window was`() {
        val floating = WindowState()
        PlayerFullscreen(floating).run {
            toggle()
            leave()
        }
        assertEquals(WindowPlacement.Floating, floating.placement)

        val maximized = WindowState(placement = WindowPlacement.Maximized)
        PlayerFullscreen(maximized).run {
            toggle()
            assertEquals(WindowPlacement.Fullscreen, maximized.placement)
            toggle()
            assertEquals(WindowPlacement.Maximized, maximized.placement)
            toggle()
            leave()
        }
        assertEquals(WindowPlacement.Maximized, maximized.placement)
    }

    @Test
    fun `a window already full screen stays so when the player closes, unless someone left it`() {
        val window = WindowState(placement = WindowPlacement.Fullscreen)
        val fullscreen = PlayerFullscreen(window)
        fullscreen.leave()
        assertEquals(WindowPlacement.Fullscreen, window.placement)
        fullscreen.toggle()
        assertEquals(WindowPlacement.Floating, window.placement)
        fullscreen.leave()
        assertEquals(WindowPlacement.Floating, window.placement)
    }

    @Test
    fun `the player starts at the shared level, and up and down change it without touching playback`() {
        val screen = Screen(volume = PlayerVolume(50))
        screen.settle()
        assertEquals(listOf(50), screen.calls.volumes)
        screen.press(Key.DirectionUp)
        screen.press(Key.DirectionDown)
        screen.press(Key.DirectionDown)
        assertEquals(listOf(50, 55, 50, 45), screen.calls.volumes)
        assertEquals(emptyList(), screen.calls.log)
        screen.scene.close()
    }

    @Test
    fun `M silences the host and comes back to the same level`() {
        val screen = Screen(volume = PlayerVolume(60))
        screen.settle()
        screen.press(Key.M)
        screen.press(Key.M)
        assertEquals(listOf(60, 0, 60), screen.calls.volumes)
        screen.scene.close()
    }

    @Test
    fun `choosing a level while muted unmutes`() {
        val volume = PlayerVolume(60)
        val screen = Screen(volume = volume)
        screen.settle()
        screen.press(Key.M)
        screen.press(Key.DirectionUp)
        assertEquals(false, volume.muted)
        assertEquals(65, volume.audible)
        screen.scene.close()
    }

    @Test
    fun `the volume stops at its ends`() {
        val volume = PlayerVolume(98)
        volume.step(PlayerVolume.STEP)
        assertEquals(100, volume.level)
        volume.set(2)
        volume.step(-PlayerVolume.STEP)
        assertEquals(0, volume.level)
        assertEquals(100, PlayerVolume(900).level)
    }

    @Test
    fun `the arrows move the subtitle list, not the volume, while it is open`() {
        val screen = Screen(volume = PlayerVolume(50))
        screen.settle()
        screen.press(Key.C)
        screen.press(Key.DirectionDown)
        screen.press(Key.M)
        assertEquals(listOf(50), screen.calls.volumes)
        screen.scene.close()
    }

    @Test
    fun `the rule sets the level where it is pressed, and the speaker mutes, at the smallest window`() {
        val screen = Screen(width = 800, height = 450, fullscreen = PlayerFullscreen(WindowState()), volume = PlayerVolume(100))
        screen.settle()
        screen.save("volume-control")
        // The rule ends the transport row; the 36 speaker is before it, with an 8 gap.
        val ruleRight = screen.ruleRight
        screen.click(Offset(ruleRight - 40f, screen.transportY))
        assertEquals(50, screen.volume.level)
        screen.click(Offset(ruleRight - 80f - 8f - 18f, screen.transportY))
        assertEquals(true, screen.volume.muted)
        assertEquals(50, screen.volume.level)
        assertEquals(emptyList(), screen.calls.log)
        screen.scene.close()
    }

    // ---------------------------------------------------------------- KMP-87: DS-23 M1 to M3 and the keyboard map

    @Test
    fun `the band's line is the episode's code and name, and a film has none`() {
        val episode = DetailEpisode(name = "The Lighthouse", data = "e4", season = 1, episode = 4)
        assertEquals("S1:E4 · The Lighthouse", episodeMeta(episode, "S", "E"))
        assertEquals("S1:E4", episodeMeta(episode.copy(name = "  "), "S", "E"))
        assertEquals("The Lighthouse", episodeMeta(episode.copy(season = null, episode = null), "S", "E"))
        assertEquals(null, episodeMeta(episode.copy(name = null, season = null, episode = null), "S", "E"))
    }

    @Test
    fun `the source moves to its selector, and joins the band's line only where there is no selector`() {
        assertEquals("S1:E4", hudMeta("S1:E4", "Source One · 1080p", hasSourceSelector = true))
        assertEquals("S1:E4 · Source One · 1080p", hudMeta("S1:E4", "Source One · 1080p", hasSourceSelector = false))
        assertEquals("Source One · 1080p", hudMeta(null, "Source One · 1080p", hasSourceSelector = false))
        assertEquals(null, hudMeta(null, "Source One · 1080p", hasSourceSelector = true))
    }

    @Test
    fun `M1 the top band draws the episode beside the title`() {
        fun litInBand(meta: String?): Int {
            val screen = Screen(meta = meta)
            val pixels = screen.pixels()
            if (meta != null) screen.save("m1-band")
            screen.scene.close()
            return (BAND_LINE_FROM until BAND_LINE_TO).count { pixels[it, BAND_Y].red > LIT }
        }
        assertEquals(0, litInBand(null), "nothing right of the title without a line")
        assertTrue(litInBand("S1:E4 · The Lighthouse") > 0, "the line is drawn beside the title")
    }

    @Test
    fun `M2 the HUD and the pointer leave after the timeout while playing, and a move brings them back`() {
        val playing = PlayerStatus(playing = true, buffering = false, positionMillis = 1_000, lengthMillis = 60_000)
        val screen = Screen(status = playing)
        val glass = Offset(screen.width / 2f - CONTROLS_INSET, screen.transportY - TRANSPORT_ABOVE)
        fun glassLit() = screen.pixels()[glass.x.toInt(), glass.y.toInt()] != Color.Black
        assertTrue(glassLit(), "the HUD is up at first")
        assertEquals(1, screen.idle.pending, "and waiting out the timeout")
        screen.idle.elapse()
        assertFalse(glassLit(), "the HUD left when the timeout passed")
        screen.scene.sendPointerEvent(PointerEventType.Move, Offset(screen.width / 2f, screen.height / 3f))
        assertTrue(glassLit(), "a move brought it back")
        assertEquals(1, screen.idle.pending, "and started the wait again")
        screen.scene.close()
        assertEquals(HiddenPointer, playerPointer(hudShown = false), "no pointer over a hidden HUD")
        assertEquals(PointerIcon.Default, playerPointer(hudShown = true))
    }

    @Test
    fun `M2 a paused player keeps its HUD, and waits for nothing`() {
        val screen = Screen()
        val glass = Offset(screen.width / 2f - CONTROLS_INSET, screen.transportY - TRANSPORT_ABOVE)
        screen.settle()
        assertEquals(0, screen.idle.pending, "nothing to wait out while paused")
        screen.idle.elapse()
        assertTrue(screen.pixels()[glass.x.toInt(), glass.y.toInt()] != Color.Black)
        screen.scene.close()
    }

    @Test
    fun `M3 resting on the timeline shows the time under the pointer, and leaving it hides the time`() {
        assertEquals("0:30", hoverTime(0.5f, 60_000))
        assertEquals("1:00", hoverTime(2f, 60_000), "past the end reads as the end")
        assertEquals("1:02:03", hoverTime(1f, 3_723_000))
        val screen = Screen()
        val onRule = Offset(screen.width / 2f, screen.transportY)
        val tip = Offset(onRule.x, onRule.y - TIP_ABOVE)
        val before = screen.pixels()[tip.x.toInt(), tip.y.toInt()]
        screen.scene.sendPointerEvent(PointerEventType.Move, onRule)
        val over = screen.pixels()[tip.x.toInt(), tip.y.toInt()]
        screen.save("m3-hover-time")
        assertTrue(over != before, "a time is drawn above the pointer")
        screen.scene.sendPointerEvent(PointerEventType.Move, Offset(onRule.x, screen.height / 3f))
        assertEquals(before, screen.pixels()[tip.x.toInt(), tip.y.toInt()], "and it goes when the pointer leaves")
        assertEquals(emptyList(), screen.calls.log, "resting seeks nothing")
        screen.scene.close()
    }

    @Test
    fun `M3 Skip is there while there is something to skip, HUD or not, and absent otherwise`() {
        val playing = PlayerStatus(playing = true, buffering = false, positionMillis = 1_000, lengthMillis = 60_000)
        val screen = Screen(status = playing, skip = HudSkip("Skip Intro"))
        screen.settle()
        screen.save("m3-skip")
        screen.click(Offset(screen.width - SKIP_FROM_RIGHT, screen.height - PlayerScreenDefaults.HudBottomInset.value - SKIP_ABOVE))
        assertEquals(1, screen.skips)
        assertEquals(1, screen.idle.pending, "the click's move started the wait again")
        screen.idle.elapse()
        screen.save("m3-skip-hud-hidden")
        val glass = Offset(screen.width / 2f - CONTROLS_INSET, screen.transportY - TRANSPORT_ABOVE)
        assertEquals(Color.Black, screen.pixels()[glass.x.toInt(), glass.y.toInt()], "the HUD has gone")
        // Where it sits with the HUD hidden: the window's margin. The click's move brings the HUD back, after the press.
        screen.click(Offset(screen.width - SKIP_FROM_RIGHT, screen.height - 24f - SKIP_ABOVE))
        assertEquals(2, screen.skips, "it stays while the HUD is hidden")
        screen.scene.close()

        val none = Screen(status = playing)
        none.click(Offset(none.width - SKIP_FROM_RIGHT, none.height - PlayerScreenDefaults.HudBottomInset.value - SKIP_ABOVE))
        assertEquals(0, none.skips)
        assertEquals(emptyList(), none.calls.log, "nothing else answers there either")
        none.scene.close()
    }

    @Test
    fun `the keyboard map, every key in one window, as the Playback menu lists it`() {
        val window = WindowState()
        val one = stream("Source One")
        val screen = Screen(
            fullscreen = PlayerFullscreen(window),
            volume = PlayerVolume(50),
            audio = threeTracks,
            sources = PlayerSources(listOf(one), one, emptyList(), 0L) {},
        )
        screen.settle()
        screen.press(Key.Spacebar)
        screen.press(Key.DirectionLeft)
        screen.press(Key.DirectionRight)
        assertEquals(listOf("toggle", "by -10000", "by 10000"), screen.calls.log, "Space, then 10 s back and on")
        screen.press(Key.DirectionUp)
        screen.press(Key.DirectionDown)
        screen.press(Key.M)
        assertEquals(listOf(50, 55, 50, 0), screen.calls.volumes, "up, down, then mute")
        screen.press(Key.M)
        for (key in listOf(Key.C, Key.A, Key.R, Key.S)) {
            screen.press(key)
            screen.press(Key.DirectionRight)
            assertEquals(listOf("toggle", "by -10000", "by 10000"), screen.calls.log, "$key opened a list, which has the arrows")
            screen.escape()
            screen.settle()
            assertEquals(0, screen.backs, "Escape closed $key's list, and did not leave")
        }
        screen.press(Key.F)
        assertEquals(WindowPlacement.Fullscreen, window.placement)
        screen.escape()
        screen.settle()
        assertEquals(WindowPlacement.Floating, window.placement, "Escape leaves full screen before the player")
        screen.escape()
        assertEquals(1, screen.backs, "and then the player")
        screen.scene.close()
    }

    // ---------------------------------------------------------------- KMP-91: DS-23 M4 to M7, the panels from the right

    @Test
    fun `M4 a list is a panel from the right the window's height, and Done closes it`() {
        val screen = Screen()
        screen.settle()
        val right = Offset(screen.width - PANEL_EDGE_INSET, screen.height / 2f)
        val top = Offset(screen.width - PANEL_EDGE_INSET, PANEL_EDGE_INSET)
        assertEquals(Color.Black, screen.pixels()[right.x.toInt(), right.y.toInt()], "no panel before")
        screen.press(Key.C)
        val open = screen.pixels()
        screen.save("m4-panel")
        assertTrue(open[right.x.toInt(), right.y.toInt()] != Color.Black, "the panel is at the right")
        assertTrue(open[top.x.toInt(), top.y.toInt()] != Color.Black, "from the top of the window")
        screen.click(Offset(screen.width - DONE_FROM_RIGHT, screen.height - DONE_FROM_BOTTOM))
        assertEquals(Color.Black, screen.pixels()[right.x.toInt(), right.y.toInt()], "Done closed it")
        assertEquals(SubtitleSelection.Off, screen.session.selection.value, "and chose nothing")
        assertEquals(0, screen.backs)
        screen.scene.close()
    }

    @Test
    fun `M4 at the smallest window the controls end a margin short of the panel, with every selector`() {
        val one = stream("Source One")
        val screen = Screen(
            width = 800,
            height = 450,
            fullscreen = PlayerFullscreen(WindowState()),
            audio = threeTracks,
            sources = PlayerSources(listOf(one), one, emptyList(), 0L) {},
        )
        screen.settle()
        screen.press(Key.S)
        val open = screen.pixels()
        screen.save("m4-panel-minimum-window")
        // The panel starts at 800 - 360 = 440; the controls keep the 24 margin before it, so 416 to 439 is picture.
        val panelLeft = screen.width - PANEL_WIDTH
        val glassTop = screen.height - CONTROLS_FROM_BOTTOM
        val glassBottom = screen.height - CONTROLS_MARGIN - 1
        for (y in glassTop..glassBottom) {
            for (x in panelLeft - CONTROLS_MARGIN until panelLeft) {
                assertEquals(Color.Black, open[x, y], "nothing of the controls at ($x, $y), in the margin before the panel")
            }
        }
        assertTrue(open[panelLeft - CONTROLS_MARGIN - 1, screen.transportY.toInt()] != Color.Black, "the controls reach the margin")
        // And the seek rule keeps its room: the volume rule gave way, so the rule runs unbroken where, with it, the
        // end time and the speaker would be. On the rule's line, every pixel differs from the glass just above it.
        val ruleY = screen.transportY.toInt()
        for (x in SEEK_RULE_FROM..SEEK_RULE_TO) {
            assertNotEquals(open[x, ruleY - RULE_CLEARANCE], open[x, ruleY], "the seek rule at x $x")
        }
        screen.scene.close()
    }

    @Test
    fun `M4 the controls move into the room the panel leaves, and back`() {
        val screen = Screen()
        screen.settle()
        // Left of the controls' glass while they are centred in the window, inside it once they centre beside the panel.
        val probe = Offset(LEFT_OF_CENTRED_CONTROLS, screen.transportY - TRANSPORT_ABOVE)
        assertEquals(Color.Black, screen.pixels()[probe.x.toInt(), probe.y.toInt()])
        screen.press(Key.R)
        assertTrue(screen.pixels()[probe.x.toInt(), probe.y.toInt()] != Color.Black, "the controls moved left of the panel")
        screen.escape()
        assertEquals(Color.Black, screen.pixels()[probe.x.toInt(), probe.y.toInt()], "and back when it closed")
        screen.scene.close()
    }

    private fun stream(name: String, quality: String? = "1080p") =
        StreamLink(label = name, qualityLabel = quality, url = "https://a/$name", referer = null, userAgent = null, audioUrl = null, subtitles = emptyList())

    private fun ranked(name: String, quality: Int?) =
        stream(name, quality?.let { "${it}p" }).copy(quality = quality)

    @Test
    fun `the source list is best first, whatever order the links arrived in, with the playing one in its place`() {
        val sd = ranked("sd", 480)
        val uhd = ranked("uhd", 2160)
        val unknown = ranked("unknown", null)
        val hd = ranked("hd", 720)
        val sources = PlayerSources(listOf(sd, uhd, unknown), hd, emptyList()) {}
        assertEquals(listOf(uhd, hd, sd, unknown), sources.links)
        val screen = Screen(sources = sources)
        screen.settle()
        screen.press(Key.S)
        screen.save("source-list-best-first")
        screen.scene.close()
    }

    @Test
    fun `moving on from a failed source says so, names it, and leaves the controls alone`() {
        val uhd = ranked("Source One", 2160)
        val fhd = ranked("Source Two", 1080)
        val loading = PlayerStatus(buffering = true)
        val sources = PlayerSources(listOf(uhd, fhd), fhd, listOf(uhd)) {}
        val before = Screen(status = loading, sources = sources)
        val clear = before.pixels()
        before.scene.close()
        val screen = Screen(status = loading, sources = sources, fellThrough = FallThrough(uhd, 1))
        val noticed = screen.pixels()
        val rowY = screen.height - 132 - 24 - 20
        var changed = 0
        for (x in 0 until screen.width) if (clear[x, rowY] != noticed[x, rowY]) changed++
        assertTrue(changed > 0, "a notice above the controls")
        screen.save("source-fell-through")
        // Not over the list, whose failed row says the same.
        screen.press(Key.S)
        screen.save("source-fell-through-list")
        assertEquals(emptyList(), screen.calls.log, "playback was not touched")
        screen.scene.close()
    }

    @Test
    fun `S lists the sources, and pressing a row commits it`() {
        val chosen = mutableListOf<StreamLink>()
        val one = stream("Source One")
        val two = stream("Source Two", "720p")
        val screen = Screen(sources = PlayerSources(listOf(one, two), one, emptyList(), 2_533_000L) { chosen += it })
        screen.settle()
        screen.save("source-selector")
        screen.press(Key.S)
        screen.save("source-list")
        screen.press(Key.Enter)
        assertEquals(emptyList(), chosen, "the one playing was chosen again")
        screen.press(Key.S)
        screen.press(Key.DirectionDown)
        screen.press(Key.Enter)
        assertEquals(listOf(two), chosen)
        assertEquals(emptyList(), screen.calls.log, "the arrows moved the list, and seeked nothing")
        screen.scene.close()
    }

    @Test
    fun `a click outside the source list chooses nothing`() {
        val chosen = mutableListOf<StreamLink>()
        val one = stream("one")
        val screen = Screen(sources = PlayerSources(listOf(one, stream("two")), one, emptyList()) { chosen += it })
        screen.settle()
        screen.press(Key.S)
        screen.click(Offset(100f, 300f))
        assertEquals(emptyList(), chosen)
        screen.press(Key.DirectionRight)
        assertEquals(listOf("by 10000"), screen.calls.log, "the list is shut, so the arrow seeks")
        screen.scene.close()
    }

    @Test
    fun `escape closes the source list before it leaves the player`() {
        val one = stream("one")
        val screen = Screen(sources = PlayerSources(listOf(one), one, emptyList()) {})
        screen.settle()
        screen.press(Key.S)
        screen.escape()
        screen.settle()
        assertEquals(0, screen.backs)
        screen.press(Key.DirectionRight)
        assertEquals(listOf("by 10000"), screen.calls.log)
        screen.escape()
        assertEquals(1, screen.backs)
        screen.scene.close()
    }

    @Test
    fun `a failed source can be left for another, and chosen again`() {
        val chosen = mutableListOf<StreamLink>()
        val one = stream("one")
        val two = stream("two")
        val screen = Screen(
            status = PlayerStatus(failed = true),
            sources = PlayerSources(listOf(one, two), one, listOf(one)) { chosen += it },
        )
        screen.settle()
        screen.save("source-failed")
        // The selector stays through a failure, and is the way out of one (artboard 7d).
        screen.click(screen.button)
        screen.save("source-failed-list")
        screen.press(Key.Enter)
        assertEquals(listOf(one), chosen, "the failed one can be tried again")
        screen.press(Key.S)
        screen.press(Key.DirectionDown)
        screen.press(Key.Enter)
        assertEquals(listOf(one, two), chosen)
        screen.scene.close()
    }

    @Test
    fun `a source that never starts can be left while it loads`() {
        val one = stream("one")
        val two = stream("two")
        val chosen = mutableListOf<StreamLink>()
        val screen = Screen(
            status = PlayerStatus(buffering = true),
            sources = PlayerSources(listOf(one, two), one, emptyList()) { chosen += it },
        )
        screen.settle()
        screen.save("source-loading")
        screen.click(screen.button)
        screen.press(Key.DirectionDown)
        screen.press(Key.Enter)
        assertEquals(listOf(two), chosen)
        screen.scene.close()
    }

    @Test
    fun `with no way to switch, S is not taken`() {
        val screen = Screen()
        screen.settle()
        screen.press(Key.S)
        screen.press(Key.DirectionRight)
        assertEquals(listOf("by 10000"), screen.calls.log)
        screen.scene.close()
    }

    @Test
    fun `a playing source the search no longer lists is still on the list`() {
        val one = stream("one")
        val two = stream("two")
        assertEquals(listOf(one, two), PlayerSources(listOf(two), one, emptyList()) {}.links)
        // A later answer can carry it again with other subtitles, and that is still the same stream.
        val again = one.copy(subtitles = listOf(english))
        assertEquals(listOf(again, two), PlayerSources(listOf(again, two), one, emptyList()) {}.links)
    }

    @Test
    fun `the selector reads out what is playing`() {
        assertEquals("Source One · 1080p", stream("Source One").sourceName())
        assertEquals("Source One", stream("Source One", null).sourceName())
    }

    @Test
    fun `a resume seeks once a length is known, once, and reports nothing until it gets there`() {
        val seeks = mutableListOf<Float>()
        val resume = ResumeSeek(600_000L)
        fun status(position: Long, length: Long) = PlayerStatus(positionMillis = position, lengthMillis = length)
        assertEquals(false, resume.onStatus(status(0, 0), seeks::add))
        assertEquals(emptyList(), seeks)
        assertEquals(false, resume.onStatus(status(0, 1_200_000), seeks::add))
        assertEquals(listOf(0.5f), seeks)
        assertEquals(600_000L, resume.resumePoint(0L), "a stream not there yet must not lose the person's place")
        // On a keyframe short of it, and from then on what it says goes, wherever they seek.
        assertEquals(true, resume.onStatus(status(590_000, 1_200_000), seeks::add))
        assertEquals(true, resume.onStatus(status(100_000, 1_200_000), seeks::add))
        assertEquals(100_000L, resume.resumePoint(100_000L))
        assertEquals(listOf(0.5f), seeks)
    }

    @Test
    fun `from the beginning nothing is sought and everything is reported`() {
        val seeks = mutableListOf<Float>()
        val resume = ResumeSeek(0L)
        assertEquals(true, resume.onStatus(PlayerStatus(positionMillis = 0, lengthMillis = 60_000), seeks::add))
        assertEquals(emptyList(), seeks)
        assertEquals(5L, resume.resumePoint(5L))
    }

    @Test
    fun `R opens the fit list, and the keys and a click choose from it`() {
        val screen = Screen()
        screen.settle()
        assertEquals(PictureFit.ORIGINAL, screen.fit.current)
        screen.press(Key.R)
        screen.press(Key.DirectionDown)
        screen.press(Key.Enter)
        assertEquals(PictureFit.FILL_WIDTH, screen.fit.current)
        // Closed by the choice, so the arrows are the volume's again.
        screen.press(Key.DirectionDown)
        assertEquals(95, screen.volume.level)
        screen.scene.close()
    }

    @Test
    fun `the control opens the fit list at the smallest window, and it fits`() {
        val screen = Screen(width = 800, height = 450)
        screen.settle()
        screen.click(screen.fitButton)
        screen.save("picture-fit-list")
        // Rows of 48 under the panel's heading: the third is Fill screen.
        screen.click(screen.panelRow(2))
        assertEquals(PictureFit.FILL_SCREEN, screen.fit.current)
        screen.scene.close()
    }

    @Test
    fun `escape closes the fit list before it leaves the player, and the keys do not reach playback`() {
        val screen = Screen()
        screen.settle()
        screen.press(Key.R)
        screen.press(Key.DirectionLeft)
        assertEquals(emptyList(), screen.calls.log)
        screen.escape()
        screen.settle()
        assertEquals(0, screen.backs)
        screen.press(Key.DirectionLeft)
        assertEquals(listOf("by -10000"), screen.calls.log)
        screen.scene.close()
    }

    @Test
    fun `each fit draws the frame as it says`() {
        val cases = listOf(PictureFit.ORIGINAL, PictureFit.FILL_WIDTH, PictureFit.FILL_SCREEN)
        cases.forEach { fit ->
            val choice = PictureFitChoice().also { it.choose(fit) }
            // A wide picture in a 4:3 window, and a tall one that fill width has to cut.
            val wide = Screen(width = 800, height = 600, frame = whiteFrame(320, 180), pictureSize = IntSize(320, 180), fit = choice)
            wide.save("fit-${fit.name}-wide")
            val pixels = wide.pixels()
            when (fit) {
                PictureFit.ORIGINAL, PictureFit.FILL_WIDTH -> {
                    assertEquals(false, pixels.isWhite(400, 40), "$fit: a bar above the picture")
                    assertEquals(true, pixels.isWhite(400, 200), "$fit: the picture")
                }
                PictureFit.FILL_SCREEN -> assertEquals(true, pixels.isWhite(400, 110), "$fit: no bar")
            }
            wide.scene.close()
            val tall = Screen(width = 800, height = 450, frame = whiteFrame(180, 320), pictureSize = IntSize(180, 320), fit = choice)
            tall.save("fit-${fit.name}-tall")
            val across = tall.pixels().isWhite(20, 200)
            assertEquals(fit != PictureFit.ORIGINAL, across, "$fit: the picture at the window's left edge")
            tall.scene.close()
        }
    }

    @Test
    fun `a cue stays on the part of the picture that can be seen`() {
        val box = IntSize(800, 450)
        assertEquals(IntRect(0, 0, 800, 450), pictureRect(box, IntSize(180, 320), PictureFit.FILL_WIDTH))
        assertEquals(IntRect(0, 0, 800, 450), pictureRect(box, IntSize(320, 180), PictureFit.FILL_SCREEN))
        assertEquals(IntRect(273, 0, 526, 450), pictureRect(box, IntSize(180, 320), PictureFit.ORIGINAL))
        assertEquals(IntRect(0, 112, 800, 337), pictureRect(IntSize(800, 450), IntSize(1600, 450), PictureFit.FILL_WIDTH))
    }

    @Test
    fun `the fit is there at the next launch, and anything else in the file reads as the default`() {
        val directory = Files.createTempDirectory("picture-fit").toFile()
        try {
            val file = File(directory, "nested/picture-fit")
            assertEquals(PictureFit.ORIGINAL, PictureFitChoice(file).current)
            assertEquals(false, file.exists(), "reading writes nothing")
            PictureFitChoice(file).choose(PictureFit.FILL_SCREEN)
            repeat(200) { if (!file.exists() || file.readText() != "FILL_SCREEN") Thread.sleep(10) }
            assertEquals(PictureFit.FILL_SCREEN, PictureFitChoice(file).current)
            assertEquals(listOf("picture-fit"), file.parentFile.list()?.toList(), "no temporary file left")
            file.writeText("<script>")
            assertEquals(PictureFit.ORIGINAL, PictureFitChoice(file).current)
            file.writeText("F".repeat(65))
            assertEquals(PictureFit.ORIGINAL, PictureFitChoice(file).current)
        } finally {
            directory.deleteRecursively()
        }
    }

    private val threeTracks = AudioTracks(
        listOf(
            AudioTrack(1, "Original - [English]", 6, "eac3"),
            AudioTrack(2, "Track 2 - [German]", 2, "mp4a"),
            AudioTrack(3, "Commentary gnp.exe - [Hindi]", 1, "mp4a"),
        ),
        selected = 1,
    )

    @Test
    fun `A and the arrows choose an audio track, and nothing else happens to playback`() {
        val screen = Screen(audio = threeTracks)
        screen.settle()
        screen.press(Key.A)
        screen.press(Key.DirectionDown)
        screen.press(Key.Enter)
        assertEquals(listOf(2), screen.calls.audioChoices)
        // Told to the screen as well, so a change of source can carry it (AudioChoice).
        assertEquals(listOf(2), screen.audioChosen.map { it.id })
        assertEquals(emptyList(), screen.calls.log)
        // The list has gone: the right arrow seeks again.
        screen.press(Key.DirectionRight)
        assertEquals(listOf("by 10000"), screen.calls.log)
        screen.scene.close()
    }

    @Test
    fun `the selector between subtitles and picture size opens the audio tracks, at the smallest window`() {
        val screen = Screen(width = 800, height = 450, audio = threeTracks)
        screen.settle()
        screen.save("audio-selector")
        // Three selectors of one width, so this one is in the middle.
        screen.click(Offset(screen.width / 2f, screen.button.y))
        screen.save("audio-tracks")
        // The third row, the last track.
        screen.click(screen.panelRow(2))
        assertEquals(listOf(3), screen.calls.audioChoices)
        screen.scene.close()
    }

    @Test
    fun `one track is nothing to choose, so no selector and A does nothing`() {
        val one = AudioTracks(listOf(AudioTrack(1, "Only")), selected = 1)
        val screen = Screen(audio = one)
        screen.settle()
        screen.press(Key.A)
        // No list took the keys, so the arrow seeks.
        screen.press(Key.DirectionRight)
        assertEquals(listOf("by 10000"), screen.calls.log)
        screen.scene.close()
    }

    @Test
    fun `escape closes the audio list before full screen and the player, and A closes it too`() {
        val window = WindowState()
        val screen = Screen(fullscreen = PlayerFullscreen(window), audio = threeTracks)
        screen.settle()
        screen.press(Key.F)
        screen.press(Key.A)
        screen.escape()
        screen.settle()
        assertEquals(WindowPlacement.Fullscreen, window.placement)
        assertEquals(0, screen.backs)
        screen.press(Key.A)
        screen.press(Key.A)
        screen.press(Key.DirectionRight)
        assertEquals(listOf("by 10000"), screen.calls.log)
        assertEquals(emptyList(), screen.calls.audioChoices)
        screen.scene.close()
    }

    @Test
    fun `a click on the selector with another list open only closes that list, and the next one opens`() {
        val screen = Screen(audio = threeTracks)
        screen.settle()
        screen.press(Key.C)
        screen.click(Offset(screen.width / 2f, screen.button.y))
        screen.press(Key.DirectionRight)
        assertEquals(listOf("by 10000"), screen.calls.log)
        screen.click(Offset(screen.width / 2f, screen.button.y))
        screen.settle()
        screen.press(Key.DirectionDown)
        screen.press(Key.Enter)
        assertEquals(listOf(2), screen.calls.audioChoices)
        screen.scene.close()
    }

    @Test
    fun `a stream that failed has no audio list to open`() {
        val screen = Screen(status = PlayerStatus(failed = true, buffering = false), audio = threeTracks)
        screen.settle()
        screen.press(Key.A)
        screen.press(Key.DirectionRight)
        assertEquals(listOf("by 10000"), screen.calls.log)
        screen.scene.close()
    }

    @Test
    fun `a click outside the list closes it and chooses nothing`() {
        val screen = Screen()
        screen.settle()
        screen.press(Key.C)
        screen.click(Offset(100f, 300f))
        assertEquals(SubtitleSelection.Off, screen.session.selection.value)
        screen.press(Key.DirectionRight)
        assertEquals(listOf("by 10000"), screen.calls.log)
        screen.scene.close()
    }

    @Test
    fun `with no tracks the list says so and offers nothing`() {
        val screen = Screen(tracks = emptyList())
        screen.settle()
        val before = screen.settle().toComposeImageBitmap().toPixelMap()
        screen.click(screen.button)
        val after = screen.settle().toComposeImageBitmap().toPixelMap()
        screen.save("subtitle-no-tracks")
        val x = screen.panelX.toInt()
        val y = screen.panelRow(0).y.toInt()
        assertNotEquals(before[x, y], after[x, y], "the panel is there")
        // A click where a row would be is not a choice, and it leaves playback alone.
        screen.click(Offset(x.toFloat(), y.toFloat()))
        assertEquals(SubtitleSelection.Off, screen.session.selection.value)
        assertEquals(emptyList(), screen.calls.log)
        screen.scene.close()
    }

    @Test
    fun `a track that arrives in an empty list can be chosen, with the mouse or the keys`() {
        val mouse = Screen(tracks = emptyList())
        mouse.settle()
        mouse.click(mouse.button)
        mouse.session.offer(listOf(english))
        mouse.settle()
        // Off, then the track that arrived.
        mouse.click(mouse.panelRow(1))
        assertEquals(english, assertIs<SubtitleSelection.Showing>(mouse.session.selection.value).track)
        mouse.scene.close()

        val keys = Screen(tracks = emptyList())
        keys.settle()
        keys.press(Key.C)
        keys.session.offer(listOf(german))
        keys.settle()
        keys.press(Key.DirectionDown)
        keys.press(Key.Enter)
        assertEquals(german, assertIs<SubtitleSelection.Showing>(keys.session.selection.value).track)
        keys.scene.close()
    }

    @Test
    fun `a track that arrives while rows are open does not slide them, and is there next time`() {
        val screen = Screen(tracks = listOf(english))
        screen.settle()
        screen.click(screen.button)
        screen.session.offer(listOf(english, german))
        screen.settle()
        // Held while open: under English there is no German row yet, so a click there chooses nothing.
        screen.click(screen.panelRow(2))
        assertEquals(SubtitleSelection.Off, screen.session.selection.value)
        // The list is still open: English is chosen from it.
        screen.click(screen.panelRow(1))
        assertEquals(english, assertIs<SubtitleSelection.Showing>(screen.session.selection.value).track)
        // Opened again, German is there under it.
        screen.click(screen.button)
        screen.click(screen.panelRow(2))
        assertEquals(german, assertIs<SubtitleSelection.Showing>(screen.session.selection.value).track)
        screen.scene.close()
    }

    @Test
    fun `a subtitle that arrives while playing joins without touching playback`() {
        val screen = Screen(tracks = emptyList())
        screen.settle()
        screen.session.offer(listOf(english))
        screen.settle()
        assertEquals(listOf(english), screen.session.tracks.value)
        assertEquals(emptyList(), screen.calls.log)
        screen.scene.close()
    }

    private fun assertFailureShows(reason: SubtitleFailure, width: Int = 1280, height: Int = 800) {
        val calls: Screen
        val screen = Screen(load = { SubtitleLoad.Failed(reason) }, width = width, height = height)
        calls = screen
        val clear = screen.settle().toComposeImageBitmap().toPixelMap()
        screen.press(Key.C)
        screen.press(Key.DirectionDown)
        screen.press(Key.Enter)
        assertEquals(reason, assertIs<SubtitleSelection.Failed>(screen.session.selection.value).reason)
        val failed = screen.settle().toComposeImageBitmap().toPixelMap()
        var changed = 0
        val rowY = height - 132 - 24 - 20
        for (x in 0 until width) if (clear[x, rowY] != failed[x, rowY]) changed++
        assertTrue(changed > 0, "a notice above the controls for $reason")
        screen.save("subtitle-failed-$reason")
        // The row keeps saying it once the list is open again.
        screen.press(Key.C)
        screen.save("subtitle-failed-row-$reason")
        assertEquals(mapOf(english.id to reason), screen.session.failures.value)
        assertEquals(emptyList(), calls.calls.log, "playback was not touched")
        screen.scene.close()
    }

    @Test
    fun `a fetch failure stays legible and leaves playback alone`() = assertFailureShows(SubtitleFailure.Fetch)

    @Test
    fun `an unsupported format says so`() = assertFailureShows(SubtitleFailure.UnsupportedFormat)

    @Test
    fun `a file with no cues says so`() = assertFailureShows(SubtitleFailure.NoCues)

    @Test
    fun `the failures read at the smallest window too`() = assertFailureShows(SubtitleFailure.UnsupportedFormat, 800, 520)

    @Test
    fun `each failure has its own words`() {
        val words = SubtitleFailure.entries.map(::failureWords)
        assertEquals(words.size, words.toSet().size)
    }

    @Test
    fun `the list fits the smallest window with many tracks`() {
        val many = List(40) { SubtitleTrack(SubtitleSource.Remote("https://a/$it"), "Language $it") }
        val screen = Screen(tracks = many, width = 800, height = 520)
        screen.settle()
        screen.press(Key.C)
        repeat(12) { screen.press(Key.DirectionDown) }
        screen.save("subtitle-picker-long")
        screen.press(Key.Enter)
        assertEquals(many[11], assertIs<SubtitleSelection.Showing>(screen.session.selection.value).track)
        screen.scene.close()
    }

    @Test
    fun `a cue appears when it starts, holds through a pause, and a seek changes it at once`() {
        val timeline = SubtitleTimeline(
            listOf(
                SubtitleCue(0, 5_000, listOf("start")),
                SubtitleCue(11_000, 5_000, listOf("later")),
            ),
        )
        val screen = Screen(load = { SubtitleLoad.Ready(timeline, SubtitleParseResult(emptyList(), null), 1) })
        screen.settle()
        screen.press(Key.C)
        screen.press(Key.DirectionDown)
        screen.press(Key.Enter)
        val before = screen.settle().toComposeImageBitmap().toPixelMap()
        val cueRow = screen.height - 84 - 24
        fun differsFrom(other: androidx.compose.ui.graphics.PixelMap): Boolean {
            val now = screen.settle().toComposeImageBitmap().toPixelMap()
            for (y in cueRow - 60 until cueRow) for (x in 400 until 880) if (now[x, y] != other[x, y]) return true
            return false
        }
        assertEquals(false, differsFrom(before), "held while paused")
        screen.press(Key.DirectionRight)
        assertEquals(listOf("by 10000"), screen.calls.log)
        assertEquals(11_000, screen.clock.positionMillis())
        assertEquals(true, differsFrom(before), "the next cue, with no report from the stream at all")
        screen.scene.close()
    }

    private companion object {
        /** KMP-91: the panel's middle, and its rows: under the 24 margin, the 32 heading and an 8 gap, 48 apart. */
        const val PANEL_HALF_WIDTH = 180f
        /** `DesktopDimens.SidePanelWidth`; the controls' window margin; their glass's top, from the window's foot. */
        const val PANEL_WIDTH = 360
        const val CONTROLS_MARGIN = 24
        const val CONTROLS_FROM_BOTTOM = 124
        /** At 800 beside a panel: inside the seek rule once the volume rule gives way; its end time and speaker otherwise. */
        const val SEEK_RULE_FROM = 244
        const val SEEK_RULE_TO = 300
        const val RULE_CLEARANCE = 8
        const val PANEL_FIRST_ROW_Y = 88f
        const val PANEL_ROW_STEP = 48f
        /** Inside the panel, clear of its rows and words. */
        const val PANEL_EDGE_INSET = 12f
        /** Inside Done: the 24 margin, then into the button. */
        const val DONE_FROM_RIGHT = 60f
        const val DONE_FROM_BOTTOM = 42f
        /** At 1280: the controls start at 280 centred in the window, and at 100 centred in the 920 the panel leaves. */
        const val LEFT_OF_CENTRED_CONTROLS = 150f
        /** M1's band: right of "Title" (76 + its width), on the title's line, from `m1-band`. */
        const val BAND_LINE_FROM = 140
        const val BAND_LINE_TO = 400
        const val BAND_Y = 42
        /** On the controls' glass, left of centre and above the transport's buttons. */
        const val CONTROLS_INSET = 300f
        const val TRANSPORT_ABOVE = 22f
        /** Above the rule's 32 target, inside the 24 hover time: 16 + 8 + 12. */
        const val TIP_ABOVE = 36f
        /** Inside the Skip button: the window's margin plus half its height, and in from the right. */
        const val SKIP_FROM_RIGHT = 60f
        const val SKIP_ABOVE = 18f
        const val WHITE = -1
        const val BLACK = -16777216
        const val LIT = 0.1f
        const val SETTLE_FRAMES = 16
        const val SETTLE_SLEEP = 8L
        const val FRAME_NANOS = 50_000_000L
    }
}

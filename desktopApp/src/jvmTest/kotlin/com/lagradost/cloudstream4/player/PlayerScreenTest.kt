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
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.lagradost.cloudstream4.core.designsystem.desktop.forDesktop
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.LocalCloudStreamTypography
import com.lagradost.cloudstream4.media.model.SubtitleCue
import com.lagradost.cloudstream4.media.model.SubtitleSource
import com.lagradost.cloudstream4.media.model.SubtitleTrack
import com.lagradost.cloudstream4.media.subtitle.SubtitleParseResult
import com.lagradost.cloudstream4.media.subtitle.SubtitleTimeline
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

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
    }

    private inner class Screen(
        tracks: List<SubtitleTrack> = listOf(english, german),
        load: (SubtitleTrack) -> SubtitleLoad = { ready() },
        val width: Int = 1280,
        val height: Int = 800,
        status: PlayerStatus = PlayerStatus(playing = false, buffering = false, positionMillis = 1_000, lengthMillis = 60_000),
    ) {
        val calls = Calls()
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
                            frame = null,
                            status = status,
                            caption = "Title",
                            controls = calls,
                            subtitles = session,
                            clock = clock,
                            onBack = { backs++ },
                            setEscape = { escape = it },
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

        /** The control at the right end of the transport row. */
        val button: Offset
            get() {
                val rowWidth = minOf(width - 48, 720)
                val rowRight = (width + rowWidth) / 2
                return Offset(rowRight - 16f - 18f, height - 24f - 26f)
            }

        fun save(name: String) {
            val dir = System.getProperty("cloudstream.home.renders") ?: return
            File(dir).mkdirs()
            val data = checkNotNull(settle().encodeToData(EncodedImageFormat.PNG))
            File(dir, "$name-${width}x$height.png").writeBytes(data.bytes)
        }
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
        // Opened at the panel's foot: Off, then the two tracks, each 48 tall, above the controls.
        val x = screen.button.x - 100
        val foot = screen.height - 84f - 8f - 4f
        val german = Offset(x, foot - 24f)
        val englishRow = Offset(x, foot - 72f)
        val off = Offset(x, foot - 120f)
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
        val x = screen.button.x.toInt() - 100
        val y = screen.height - 84 - 40
        assertNotEquals(before[x, y], after[x, y], "the panel is there")
        // A click where a row would be is not a choice, and it leaves playback alone.
        screen.click(Offset(x.toFloat(), y.toFloat()))
        assertEquals(SubtitleSelection.Off, screen.session.selection.value)
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
        val rowY = height - 84 - 24 - 20
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
        const val SETTLE_FRAMES = 16
        const val SETTLE_SLEEP = 8L
        const val FRAME_NANOS = 50_000_000L
    }
}

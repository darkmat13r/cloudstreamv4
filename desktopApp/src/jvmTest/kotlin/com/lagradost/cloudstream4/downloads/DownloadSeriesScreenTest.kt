package com.lagradost.cloudstream4.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.lagradost.cloudstream4.assertPageBar
import com.lagradost.cloudstream4.core.connectivity.Connectivity
import com.lagradost.cloudstream4.core.connectivity.ConnectivityFlow
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopTypography
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.LocalCloudStreamTypography
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisode
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisodeId
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisodeState
import com.lagradost.cloudstream4.feature.downloads.series.DownloadSeriesContents
import com.lagradost.cloudstream4.feature.downloads.series.DownloadSeriesSource
import com.lagradost.cloudstream4.feature.downloads.series.DownloadSeriesViewModel
import com.lagradost.cloudstream4.feature.downloads.series.SeriesEffect
import com.lagradost.cloudstream4.feature.downloads.series.desktop.DownloadSeriesScreenDesktop
import com.lagradost.cloudstream4.media.model.DownloadFailure
import java.io.File
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat

/**
 * Board D4 (`KMP-54`), against a fake series source and the real `DownloadSeriesViewModel`: real pointer and key events,
 * plus a PNG when `cloudstream.downloads.renders` names a directory. 1060 x 800, the page beside the 220 sidebar.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class DownloadSeriesScreenTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @AfterTest
    fun stop() = scope.cancel()

    private fun episode(n: Int, name: String, state: DownloadEpisodeState, done: Long, total: Long, rate: Long = 0,
        failure: DownloadFailure? = null) =
        DownloadEpisode(DownloadEpisodeId(n), 1, n, name, state, done * MB, total * MB, rate * KB, failure = failure)

    private val episodes = listOf(
        episode(1, "Pilot", DownloadEpisodeState.Done, 1_020, 1_020),
        episode(2, "Cat's in the Bag…", DownloadEpisodeState.Done, 980, 980),
        episode(3, "…And the Bag's in the River", DownloadEpisodeState.Running, 412, 1_000, rate = 2_100),
        episode(4, "Cancer Man", DownloadEpisodeState.Queued, 0, 0),
        // NoUsableLink on purpose: the owner's ruling on KMP-52 is that a resume asks the extension for a fresh link, and
        // this is how a resume that finds none must read.
        episode(5, "Gray Matter", DownloadEpisodeState.Failed, 220, 960, failure = DownloadFailure.NoUsableLink),
        episode(6, "Crazy Handful of Nothin'", DownloadEpisodeState.Done, 1_010, 1_010),
        episode(7, "A No-Rough-Stuff-Type Deal", DownloadEpisodeState.Paused, 300, 1_030),
    )

    private class FakeSource(contents: DownloadSeriesContents) : DownloadSeriesSource {
        override val contents = MutableStateFlow(contents)
        val calls = mutableListOf<String>()
        override fun refresh() = Unit
        override fun pause(id: DownloadEpisodeId) {
            calls += "pause ${id.value}"
        }

        override fun resume(id: DownloadEpisodeId) {
            calls += "resume ${id.value}"
        }

        override fun cancel(id: DownloadEpisodeId) {
            calls += "cancel ${id.value}"
        }
    }

    private class Online : ConnectivityFlow {
        override val status: StateFlow<Connectivity> = MutableStateFlow(Connectivity.ONLINE)
    }

    private fun formatBytes(bytes: Long) = if (bytes >= 1_000_000_000L) {
        String.format(Locale.ROOT, "%.2f GB", bytes / 1_000_000_000.0)
    } else {
        String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
    }

    private inner class Harness {
        val source = FakeSource(DownloadSeriesContents("Breaking Bad", episodes, freeBytes = 12_400 * MB))
        val viewModel = DownloadSeriesViewModel(source, Online(), scope)
        val effects = mutableListOf<SeriesEffect>()
        var confirming by mutableStateOf<List<DownloadEpisodeId>?>(null)

        init {
            scope.launch {
                viewModel.effect.collect { effect ->
                    effects += effect
                    when (effect) {
                        is SeriesEffect.ConfirmDelete -> confirming = listOf(effect.id)
                        is SeriesEffect.ConfirmDeleteAll -> confirming = effect.ids
                        else -> Unit
                    }
                }
            }
        }

        @Composable
        fun Content() {
            val state by viewModel.state.collectAsState()
            DownloadSeriesScreenDesktop(
                state = state,
                onAction = viewModel::onAction,
                formatBytes = ::formatBytes,
                confirming = confirming,
                onCancelDelete = { confirming = null },
                canReveal = { it.state == DownloadEpisodeState.Done },
                artwork = { Box(Modifier.fillMaxSize().background(Color.DarkGray)) },
            )
        }
    }

    private fun scene(harness: Harness) = ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
        CloudStreamTheme {
            CompositionLocalProvider(LocalCloudStreamTypography provides desktopTypography()) {
                Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) { harness.Content() }
            }
        }
    }

    private var clock = 0L

    private fun ImageComposeScene.settle() = repeat(SETTLE_FRAMES) {
        clock += FRAME_NANOS
        render(clock)
    }

    private fun ImageComposeScene.press(at: Offset, timeMillis: Long = clock / NANOS_PER_MILLI) {
        sendPointerEvent(PointerEventType.Move, at, timeMillis = timeMillis)
        sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true),
            button = PointerButton.Primary, timeMillis = timeMillis)
        sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary, timeMillis = timeMillis)
        settle()
    }

    private fun ImageComposeScene.save(name: String) {
        val directory = System.getProperty("cloudstream.downloads.renders") ?: return
        settle()
        clock += FRAME_NANOS
        File(directory, "$name.png").writeBytes(checkNotNull(render(clock).encodeToData(EncodedImageFormat.PNG)).bytes)
    }

    private fun rowY(index: Int) = FIRST_ROW_Y + index * ROW_PITCH

    /** The sheet is on `surface` where the page behind it is `bg`: a point inside it, right of the series' title. */
    private fun ImageComposeScene.sheetOpen(): Boolean {
        clock += FRAME_NANOS
        val pixel = Bitmap.makeFromImage(render(clock)).getColor(SHEET_PROBE_X, SHEET_PROBE_Y)
        return pixel != Color.Black.toArgb()
    }

    @Test
    fun `a series' downloads starts with the page bar, 52 tall with a hairline under it (KMP-98, D4)`() {
        // Back and the path, in the one band every Mac page has.
        val scene = scene(Harness())
        scene.settle()
        scene.assertPageBar(WIDTH, atNanos = SETTLE_FRAMES * FRAME_NANOS)
        scene.close()
    }

    @Test
    fun `hovering the running row offers Pause and Cancel, as the board draws`() {
        val harness = Harness()
        val scene = scene(harness)
        scene.settle()
        scene.sendPointerEvent(PointerEventType.Move, Offset(NAME_X, rowY(2)))
        // Settled here, not only in save(): without renders save() returns at once, and the verbs would not be up yet.
        scene.settle()
        scene.save("D4-series-hovered")
        // Pause, Cancel, Delete: no Play on a partial file.
        scene.press(Offset(FIRST_VERB_X, rowY(2)))
        assertEquals(listOf("pause 3"), harness.source.calls)
        scene.close()
    }

    @Test
    fun `cancelling a download that has fetched something asks first, and Keep downloading is the default`() {
        val harness = Harness()
        val scene = scene(harness)
        scene.settle()
        scene.sendPointerEvent(PointerEventType.Move, Offset(NAME_X, rowY(2)))
        scene.settle()
        scene.press(Offset(SECOND_VERB_X, rowY(2)))
        assertEquals(emptyList(), harness.source.calls, "412 MB are on the disk, so nothing is discarded yet")
        scene.save("D4-series-cancel-confirm")
        assertTrue(scene.sheetOpen(), "the sheet is up")
        scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown))
        scene.settle()
        assertEquals(emptyList(), harness.source.calls, "Return keeps downloading")
        assertFalse(scene.sheetOpen(), "and closes the sheet")
        scene.sendPointerEvent(PointerEventType.Move, Offset(NAME_X, rowY(2)))
        scene.settle()
        scene.press(Offset(SECOND_VERB_X, rowY(2)))
        scene.press(Offset(SHEET_CANCEL_DOWNLOAD_X, SHEET_BUTTON_Y))
        assertEquals(listOf("cancel 3"), harness.source.calls)
        scene.close()
    }

    @Test
    fun `a download that finishes while the cancel sheet is open closes the sheet, and nothing is discarded`() {
        val harness = Harness()
        val scene = scene(harness)
        scene.settle()
        scene.sendPointerEvent(PointerEventType.Move, Offset(NAME_X, rowY(2)))
        scene.settle()
        scene.press(Offset(SECOND_VERB_X, rowY(2)))
        assertTrue(scene.sheetOpen())
        val finished = episodes.map { if (it.id.value == 3) it.copy(state = DownloadEpisodeState.Done, bytesDone = it.bytesTotal) else it }
        harness.source.contents.value = DownloadSeriesContents("Breaking Bad", finished, freeBytes = 12_400 * MB)
        scene.settle()
        assertFalse(scene.sheetOpen(), "the sheet closes itself when the episode is done")
        // Where the sheet's Cancel download was: whatever is under it now, no cancel goes out.
        scene.press(Offset(SHEET_CANCEL_DOWNLOAD_X, SHEET_BUTTON_Y))
        assertTrue(harness.source.calls.none { it.startsWith("cancel") }, harness.source.calls.toString())
        scene.close()
    }

    @Test
    fun `cancelling a waiting download with nothing fetched does it at once`() {
        val harness = Harness()
        val scene = scene(harness)
        scene.settle()
        scene.sendPointerEvent(PointerEventType.Move, Offset(NAME_X, rowY(3)))
        scene.settle()
        // Cancel, Delete.
        scene.press(Offset(SECOND_VERB_X, rowY(3)))
        assertEquals(listOf("cancel 4"), harness.source.calls)
        scene.close()
    }

    @Test
    fun `a failure with no source says so, and its Retry asks again`() {
        val harness = Harness()
        val scene = scene(harness)
        scene.settle()
        scene.sendPointerEvent(PointerEventType.Move, Offset(NAME_X, rowY(4)))
        scene.settle()
        scene.save("D4-series-failed-no-link")
        // Retry, Delete.
        scene.press(Offset(SECOND_VERB_X, rowY(4)))
        assertEquals(listOf("resume 5"), harness.source.calls)
        scene.close()
    }

    /** `KMP-75`: a press on an episode's verb is the verb's, so the episode then its Delete verb is not a double-click. */
    @Test
    fun `a click on an episode then quickly on its Delete verb asks to delete, and plays nothing`() {
        val harness = Harness()
        val scene = scene(harness)
        scene.settle()
        scene.sendPointerEvent(PointerEventType.Move, Offset(NAME_X, rowY(0)))
        scene.settle()
        scene.press(Offset(NAME_X, rowY(0)), timeMillis = 1_000)
        scene.press(Offset(DELETE_VERB_X, rowY(0)), timeMillis = 1_100)
        assertEquals(listOf(DownloadEpisodeId(1)), harness.confirming)
        assertTrue(harness.effects.none { it is SeriesEffect.Play }, "${harness.effects}")
        scene.close()
    }

    @Test
    fun `a double click plays the episode`() {
        val harness = Harness()
        val scene = scene(harness)
        scene.settle()
        scene.press(Offset(NAME_X, rowY(0)), timeMillis = 1_000)
        scene.press(Offset(NAME_X, rowY(0)), timeMillis = 1_100)
        assertEquals(listOf<SeriesEffect>(SeriesEffect.Play(DownloadEpisodeId(1))), harness.effects)
        scene.close()
    }

    @Test
    fun `Delete all asks about every episode first, and Escape cancels`() {
        val harness = Harness()
        val scene = scene(harness)
        scene.settle()
        scene.press(Offset(DELETE_ALL_X, HEADER_BUTTON_Y))
        assertEquals(episodes.map { it.id }, harness.confirming)
        scene.save("D4-series-delete-all")
        scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown))
        scene.settle()
        assertNull(harness.confirming)
        scene.close()
    }

    @Test
    fun `command left bracket goes back`() {
        val harness = Harness()
        val scene = scene(harness)
        scene.settle()
        scene.press(Offset(NAME_X, rowY(0)))
        scene.sendKeyEvent(KeyEvent(Key.LeftBracket, KeyEventType.KeyDown, isMetaPressed = true))
        scene.settle()
        assertTrue(SeriesEffect.Close in harness.effects)
        scene.close()
    }

    private companion object {
        const val MB = 1_000_000L
        const val KB = 1_000L
        const val WIDTH = 1060
        const val HEIGHT = 800
        const val SETTLE_FRAMES = 20
        const val FRAME_NANOS = 50_000_000L
        const val NANOS_PER_MILLI = 1_000_000L

        // Measured from the D4 render.
        const val FIRST_ROW_Y = 312f
        const val ROW_PITCH = 48f
        const val NAME_X = 120f
        const val FIRST_VERB_X = 960f
        /** The last of the hover verbs, Delete: always the trailing one. */
        const val DELETE_VERB_X = 1024f
        const val SHEET_CANCEL_DOWNLOAD_X = 447f
        const val SHEET_BUTTON_Y = 181f
        const val SHEET_PROBE_X = 700
        const val SHEET_PROBE_Y = 120
        const val SECOND_VERB_X = 992f
        const val DELETE_ALL_X = 515f
        const val HEADER_BUTTON_Y = 197f
    }
}

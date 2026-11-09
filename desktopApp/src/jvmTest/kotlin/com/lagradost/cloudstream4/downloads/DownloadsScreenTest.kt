package com.lagradost.cloudstream4.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.unit.Density
import com.lagradost.cloudstream4.assertOutlined
import com.lagradost.cloudstream4.assertPageBar
import com.lagradost.cloudstream4.buttonLabelled
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopOfflinePillDefaults
import com.lagradost.cloudstream4.tagged
import com.lagradost.cloudstream4.textNode
import com.lagradost.cloudstream4.core.connectivity.Connectivity
import com.lagradost.cloudstream4.core.connectivity.ConnectivityFlow
import com.lagradost.cloudstream4.core.designsystem.desktop.forDesktop
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.LocalCloudStreamTypography
import com.lagradost.cloudstream4.feature.downloads.DownloadQueue
import com.lagradost.cloudstream4.feature.downloads.DownloadStorage
import com.lagradost.cloudstream4.feature.downloads.DownloadTitle
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleId
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleKind
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleState
import com.lagradost.cloudstream4.feature.downloads.DownloadsAction
import com.lagradost.cloudstream4.feature.downloads.DownloadsContents
import com.lagradost.cloudstream4.feature.downloads.DownloadsEffect
import com.lagradost.cloudstream4.feature.downloads.DownloadsSource
import com.lagradost.cloudstream4.feature.downloads.DownloadsState
import com.lagradost.cloudstream4.feature.downloads.DownloadsStatus
import com.lagradost.cloudstream4.feature.downloads.DownloadsViewModel
import com.lagradost.cloudstream4.feature.downloads.StorageShortfall
import com.lagradost.cloudstream4.feature.downloads.desktop.DownloadsScreenDesktop
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.jetbrains.skia.EncodedImageFormat

/**
 * Board D1 to D6 of `design/Desktop Downloads.dc.html` (`KMP-54`), against a fake source and the real view model: real
 * pointer and key events through the scene, plus a PNG of each state when `cloudstream.downloads.renders` names a
 * directory. The page is drawn at its size beside the shell's 220 sidebar: 1060 x 800 in a 1280 window, 580 x 520 in the
 * 800 x 520 minimum.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class DownloadsScreenTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @AfterTest
    fun stop() = scope.cancel()

    private fun title(id: Int, name: String, kind: DownloadTitleKind, episodes: Int, done: Long, total: Long,
        state: DownloadTitleState = DownloadTitleState.Done, progress: Float? = null) =
        DownloadTitle(DownloadTitleId(id), name, kind, episodes, done, total, state, progress)

    private val titles = listOf(
        title(1, "Breaking Bad", DownloadTitleKind.Series, 12, 4_240 * MB, 5_100 * MB, DownloadTitleState.Running, 0.62f),
        title(2, "Dune: Part Two", DownloadTitleKind.Film, 1, 6_800 * MB, 6_800 * MB),
        title(3, "Severance", DownloadTitleKind.Series, 9, 3_100 * MB, 3_600 * MB, DownloadTitleState.Failed, 0.86f),
        title(4, "The Bear", DownloadTitleKind.Series, 8, 1_900 * MB, 4_700 * MB, DownloadTitleState.Paused, 0.40f),
        title(5, "Past Lives", DownloadTitleKind.Film, 1, 2_200 * MB, 2_200 * MB),
        title(6, "Shōgun", DownloadTitleKind.Series, 10, 7_900 * MB, 7_900 * MB),
        title(7, "Arrival", DownloadTitleKind.Film, 1, 3_400 * MB, 3_400 * MB),
    )

    private val storage = DownloadStorage(usedBytes = 384_000 * MB, totalBytes = 512_000 * MB, downloadBytes = 128_000 * MB)

    private class FakeSource(contents: DownloadsContents?, failure: String?) : DownloadsSource {
        val latest = MutableStateFlow(contents)
        val resumed = mutableListOf<DownloadTitleId>()
        override val contents = flow {
            if (failure != null) throw IOException(failure)
            emitAll(latest.filterNotNull())
        }
        override fun refresh() = Unit
        override fun resume(id: DownloadTitleId) {
            resumed += id
        }
    }

    private class FixedConnectivity(value: Connectivity) : ConnectivityFlow {
        override val status: StateFlow<Connectivity> = MutableStateFlow(value)
    }

    private fun formatBytes(bytes: Long) = String.format(Locale.ROOT, "%.2f GB", bytes / 1_000_000_000.0)

    /** The screen over a live view model, the way the host will hold it: effects in, the confirm sheet out. */
    private inner class Harness(
        contents: DownloadsContents?,
        offline: Boolean = false,
        failure: String? = null,
        val revealable: Set<DownloadTitleId> = setOf(DownloadTitleId(2), DownloadTitleId(5)),
    ) {
        val source = FakeSource(contents, failure)
        val viewModel = DownloadsViewModel(source, FixedConnectivity(if (offline) Connectivity.OFFLINE else Connectivity.ONLINE), scope)
        val effects = mutableListOf<DownloadsEffect>()
        val revealed = mutableListOf<DownloadTitleId>()
        var deleted: Set<DownloadTitleId>? = null
        var confirming by mutableStateOf<Set<DownloadTitleId>?>(null)

        init {
            scope.launch {
                viewModel.effect.collect { effect ->
                    effects += effect
                    if (effect is DownloadsEffect.ConfirmDelete) confirming = effect.ids
                }
            }
        }

        val state: DownloadsState get() = viewModel.state.value

        @Composable
        fun Content() {
            val state by viewModel.state.collectAsState()
            DownloadsScreenDesktop(
                state = state,
                onAction = viewModel::onAction,
                formatBytes = ::formatBytes,
                confirming = confirming,
                onConfirmDelete = { deleted = confirming; confirming = null },
                onCancelDelete = { confirming = null },
                canReveal = { it.id in revealable },
                onReveal = { revealed += it },
                artwork = { Box(Modifier.fillMaxSize().background(Color.DarkGray)) },
            )
        }
    }

    private fun scene(harness: Harness, width: Int = WIDTH, height: Int = HEIGHT) =
        ImageComposeScene(width, height, Density(1f)) {
            CloudStreamTheme {
                CompositionLocalProvider(LocalCloudStreamTypography provides CloudStreamTheme.typography.forDesktop()) {
                    Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) { harness.Content() }
                }
            }
        }

    private var clock = 0L

    private fun ImageComposeScene.settle() = repeat(SETTLE_FRAMES) {
        clock += FRAME_NANOS
        render(clock)
    }

    private fun ImageComposeScene.hover(at: Offset) {
        sendPointerEvent(PointerEventType.Move, at)
        settle()
    }

    private fun ImageComposeScene.press(
        at: Offset,
        modifiers: PointerKeyboardModifiers = PointerKeyboardModifiers(),
        button: PointerButton = PointerButton.Primary,
        timeMillis: Long = clock / NANOS_PER_MILLI,
    ) {
        val buttons = if (button == PointerButton.Secondary) PointerButtons(isSecondaryPressed = true) else PointerButtons(isPrimaryPressed = true)
        sendPointerEvent(PointerEventType.Move, at, keyboardModifiers = modifiers, timeMillis = timeMillis)
        sendPointerEvent(PointerEventType.Press, at, buttons = buttons, keyboardModifiers = modifiers, button = button, timeMillis = timeMillis)
        sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), keyboardModifiers = modifiers, button = button, timeMillis = timeMillis)
        settle()
    }

    private fun ImageComposeScene.key(key: Key, meta: Boolean = false) {
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isMetaPressed = meta))
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isMetaPressed = meta))
        settle()
    }

    private fun ImageComposeScene.save(name: String) {
        val directory = System.getProperty("cloudstream.downloads.renders") ?: return
        settle()
        clock += FRAME_NANOS
        val png = checkNotNull(render(clock).encodeToData(EncodedImageFormat.PNG)).bytes
        File(directory, "$name.png").writeBytes(png)
    }

    private fun rowY(index: Int) = FIRST_ROW_Y + index * ROW_PITCH

    private fun contents(full: StorageShortfall? = null) =
        DownloadsContents(titles, storage, DownloadQueue(running = 1, total = 4), full)

    private val meta = PointerKeyboardModifiers(isMetaPressed = true)
    private val shift = PointerKeyboardModifiers(isShiftPressed = true)

    @Test
    fun `Downloads starts with the page bar, 52 tall with a hairline under it (KMP-98, D1)`() {
        // Play from link and the queue, in the one band every Mac page has.
        val scene = scene(Harness(contents()))
        scene.settle()
        scene.assertPageBar(WIDTH, atNanos = SETTLE_FRAMES * FRAME_NANOS)
        scene.close()
    }

    @Test
    fun `offline is Home's pill, the wifi-off glyph and its words, beside the title (KMP-101, D5)`() {
        val offline = scene(Harness(contents(), offline = true))
        offline.settle()
        val pill = offline.tagged(DesktopOfflinePillDefaults.TestTag).single().boundsInRoot
        assertEquals(DesktopOfflinePillDefaults.Height.value, pill.height)
        val words = offline.textNode("Offline · no connection").boundsInRoot
        val title = offline.textNode("Downloads").boundsInRoot
        assertTrue(pill.left > title.right, "after the page's title")
        // The glyph leads the words: 8 of padding, a 16 glyph and an 8 gap before them.
        assertEquals(pill.left + PILL_WORDS_INSET, words.left, "a glyph's width before the words")
        offline.close()

        val online = scene(Harness(contents()))
        online.settle()
        assertTrue(online.tagged(DesktopOfflinePillDefaults.TestTag).isEmpty(), "no pill while connected")
        online.close()
    }

    @Test
    fun `the empty page offers the library with an outlined button, not a filled one (KMP-101, D4)`() {
        val empty = Harness(DownloadsContents(emptyList(), storage, DownloadQueue(0, 0), null))
        val scene = scene(empty)
        scene.settle()
        val button = scene.buttonLabelled("Browse library").boundsInRoot
        scene.assertOutlined(button, clock, page = 8 to HEIGHT - 8)
        scene.press(button.center)
        assertEquals(listOf<DownloadsEffect>(DownloadsEffect.OpenLibrary), empty.effects, "it still does what it did")
        scene.close()
    }

    @Test
    fun `a click selects one row, command adds, shift extends, and Escape clears`() {
        val harness = Harness(contents())
        val scene = scene(harness)
        scene.settle()
        scene.press(Offset(NAME_X, rowY(1)))
        assertEquals(setOf(DownloadTitleId(2)), harness.state.selection)
        scene.press(Offset(NAME_X, rowY(3)), shift)
        assertEquals(setOf(2, 3, 4).map(::DownloadTitleId).toSet(), harness.state.selection)
        scene.press(Offset(NAME_X, rowY(5)), meta)
        assertEquals(setOf(2, 3, 4, 6).map(::DownloadTitleId).toSet(), harness.state.selection)
        scene.key(Key.Escape)
        assertNull(harness.state.selection)
        scene.close()
    }

    @Test
    fun `a double click opens the title and leaves nothing selected`() {
        val harness = Harness(contents())
        val scene = scene(harness)
        scene.settle()
        scene.press(Offset(NAME_X, rowY(0)), timeMillis = 1_000)
        scene.press(Offset(NAME_X, rowY(0)), timeMillis = 1_100)
        assertEquals(listOf<DownloadsEffect>(DownloadsEffect.OpenTitle(DownloadTitleId(1))), harness.effects)
        assertNull(harness.state.selection)
        scene.close()
    }

    @Test
    fun `command A then command delete asks first, and Cancel is the default`() {
        val harness = Harness(contents())
        val scene = scene(harness)
        scene.settle()
        scene.press(Offset(NAME_X, rowY(0)))
        scene.key(Key.A, meta = true)
        assertEquals(titles.map { it.id }.toSet(), harness.state.selection)
        scene.key(Key.Backspace, meta = true)
        assertEquals(titles.map { it.id }.toSet(), harness.confirming)
        scene.key(Key.Enter)
        assertNull(harness.confirming)
        assertNull(harness.deleted)
        scene.close()
    }

    @Test
    fun `a right click selects the row it is on and its menu deletes that row after asking`() {
        val harness = Harness(contents())
        val scene = scene(harness)
        scene.settle()
        scene.press(Offset(NAME_X, rowY(1)), button = PointerButton.Secondary)
        assertEquals(setOf(DownloadTitleId(2)), harness.state.selection)
        scene.save("D2-context-menu")
        // The menu opens at the pointer: Show in Finder, a rule, then Delete.
        scene.press(Offset(NAME_X + MENU_ITEM_X, rowY(1) + MENU_DELETE_Y))
        assertEquals(setOf(DownloadTitleId(2)), harness.confirming)
        scene.save("D3-confirm-delete")
        scene.press(Offset(SHEET_DELETE_X, SHEET_BUTTON_Y))
        assertEquals(setOf(DownloadTitleId(2)), harness.deleted)
        scene.close()
    }

    /**
     * `KMP-75`: a press on a verb is the verb's. The row also read it as a press of its own, so a click on the row and
     * then, within the double-click time, on its Delete verb opened the title and asked nothing.
     */
    @Test
    fun `a click on the row then quickly on its Delete verb asks to delete, and opens nothing`() {
        val harness = Harness(contents())
        val scene = scene(harness)
        scene.settle()
        scene.press(Offset(NAME_X, rowY(1)), timeMillis = 5_000)
        scene.press(Offset(DELETE_VERB_X, rowY(1)), timeMillis = 5_150)
        assertEquals(setOf(DownloadTitleId(2)), harness.confirming)
        assertTrue(harness.effects.none { it is DownloadsEffect.OpenTitle }, "${harness.effects}")
        scene.close()
    }

    /** `KMP-75`: a sheet cancelled with Return must leave the row's own Delete verb working, in the same window. */
    @Test
    fun `after Return cancels the sheet, the row's Delete verb asks again`() {
        val harness = Harness(contents())
        val scene = scene(harness)
        scene.settle()
        scene.press(Offset(NAME_X, rowY(1)))
        scene.key(Key.Backspace, meta = true)
        assertEquals(setOf(DownloadTitleId(2)), harness.confirming)
        scene.key(Key.Enter)
        assertNull(harness.confirming)
        assertNull(harness.deleted)

        scene.hover(Offset(NAME_X, rowY(1)))
        scene.press(Offset(DELETE_VERB_X, rowY(1)))
        assertEquals(setOf(DownloadTitleId(2)), harness.confirming, "selection after the cancel: ${harness.state.selection}")
        scene.close()
    }

    @Test
    fun `hovering a failed row shows Retry, which asks the source for what is left`() {
        // Severance's finished episodes are on the disk, so all three verbs show: Retry, Show in Finder, Delete.
        val harness = Harness(contents(), revealable = setOf(DownloadTitleId(3)))
        val scene = scene(harness)
        scene.settle()
        scene.hover(Offset(NAME_X, rowY(2)))
        scene.save("D1-titles-hovered")
        scene.press(Offset(FIRST_VERB_X, rowY(2)))
        assertEquals(listOf(DownloadTitleId(3)), harness.source.resumed)
        scene.close()
    }

    @Test
    fun `command R shows the one selected title in Finder, and only when it is on the disk`() {
        val harness = Harness(contents())
        val scene = scene(harness)
        scene.settle()
        scene.press(Offset(NAME_X, rowY(0)))
        scene.key(Key.R, meta = true)
        assertEquals(emptyList(), harness.revealed)
        scene.press(Offset(NAME_X, rowY(1)))
        scene.key(Key.R, meta = true)
        assertEquals(listOf(DownloadTitleId(2)), harness.revealed)
        scene.close()
    }

    @Test
    fun `renders the empty, offline and full disk page, the narrow window, a failure and loading`() {
        val empty = Harness(DownloadsContents(emptyList(), storage.copy(usedBytes = 498_000 * MB, downloadBytes = 0),
            DownloadQueue(0, 3), StorageShortfall(2_400 * MB)), offline = true)
        scene(empty).apply { settle(); save("D5-empty-offline-full"); close() }
        assertEquals(DownloadsStatus.Empty, empty.state.status)

        val narrow = Harness(contents())
        scene(narrow, NARROW_WIDTH, NARROW_HEIGHT).apply { settle(); save("D6-narrow"); close() }

        val full = Harness(contents(StorageShortfall(2_400 * MB)))
        scene(full).apply { settle(); save("D1-storage-full"); close() }

        val loading = Harness(null)
        scene(loading).apply { settle(); save("loading"); close() }
        assertEquals(DownloadsStatus.Loading, loading.state.status)

        val failed = Harness(contents(), failure = "Downloads folder is not readable")
        scene(failed).apply { settle(); save("failed"); close() }
        assertEquals(DownloadsStatus.Failed("Downloads folder is not readable"), failed.state.status)

        val chosen = Harness(contents())
        chosen.viewModel.onAction(DownloadsAction.SelectionStarted)
        listOf(2, 5).forEach { chosen.viewModel.onAction(DownloadsAction.TitleActivated(DownloadTitleId(it))) }
        scene(chosen).apply { settle(); save("D2-selected-footer"); close() }
    }

    private companion object {
        const val MB = 1_000_000L
        const val WIDTH = 1060
        const val HEIGHT = 800
        const val NARROW_WIDTH = 580
        const val NARROW_HEIGHT = 520
        const val SETTLE_FRAMES = 20
        const val FRAME_NANOS = 50_000_000L
        const val NANOS_PER_MILLI = 1_000_000L
        /** The offline pill's padding, glyph and gap before its words: 8 + 16 + 8. */
        const val PILL_WORDS_INSET = 32f

        // Row centres and targets, measured from the D1 to D3 renders at 1060 x 800.
        const val FIRST_ROW_Y = 214f
        const val ROW_PITCH = 56f
        const val NAME_X = 120f
        const val FIRST_VERB_X = 960f
        /** The last of the hover verbs, Delete: always the trailing one. */
        const val DELETE_VERB_X = 1024f
        const val MENU_ITEM_X = 40f
        const val MENU_DELETE_Y = 61f
        const val SHEET_DELETE_X = 575f
        const val SHEET_BUTTON_Y = 181f
    }
}

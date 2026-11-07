package com.lagradost.cloudstream4.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.lagradost.cloudstream4.DesktopApp
import com.lagradost.cloudstream4.EscapeHandler
import com.lagradost.cloudstream4.core.designsystem.desktop.forDesktop
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.LocalCloudStreamTypography
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleKind
import com.lagradost.cloudstream4.feature.downloads.DownloadsContents
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisodeId
import com.lagradost.cloudstream4.home.DesktopShortcuts
import com.lagradost.cloudstream4.home.TopLevelDestination
import com.lagradost.cloudstream4.library.InMemoryLibraryStore
import com.lagradost.cloudstream4.media.download.DownloadOptions
import com.lagradost.cloudstream4.core.http.HttpClientFactory
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.jetbrains.skia.EncodedImageFormat

/**
 * The sidebar's fourth row over the real service (`KMP-54` over `KMP-52`): a film downloaded from a loopback server into a
 * temporary folder, then deleted, or not, through the page's own sheet.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class DownloadsHostTest {
    private val base: File = Files.createTempDirectory("downloads-host").toFile()
    private val root = File(base, "Downloads")
    private val stateFile = File(File(base, "data"), "downloads.json")
    private val server = RangeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hosts = mutableListOf<DesktopDownloads>()

    @AfterTest
    fun cleanUp() = runBlocking {
        scope.cancel()
        hosts.forEach { it.close() }
        server.close()
        base.deleteRecursively()
        Unit
    }

    /** Loopback is a private address: allowed here only, as in `DesktopDownloadsTest`. */
    private fun host() = DesktopDownloads(
        downloadRoot = root,
        stateFile = stateFile,
        httpClient = HttpClientFactory(OkHttpClient()).create(),
        resolver = LinkResolver { error("no test here expects to be asked") },
        options = DownloadOptions(parts = 1, allowPrivateAddresses = true, backoffBaseMillis = 10, backoffMaxMillis = 50),
        maxRunning = 3,
        resolveTimeoutMillis = DesktopDownloads.RESOLVE_TIMEOUT_MILLIS,
        log = {},
        ownsClient = true,
    ).also { hosts += it }

    private fun DesktopDownloads.downloadFilm(): File = runBlocking {
        server.add("film.bin", 64 * 1024)
        enqueue(
            DesktopDownloadRequest(
                episodeId = 1,
                titleId = 100,
                titleName = "A Film",
                kind = DownloadTitleKind.Film,
                season = null,
                episode = null,
                episodeName = null,
                source = DownloadSourceRef("Test Provider", "episode-data-1"),
                link = DesktopDownloadLink(server.url("film.bin")),
            ),
        )
        withTimeout(TIMEOUT_MILLIS) { while (fileOf(DownloadEpisodeId(1)) == null) kotlinx.coroutines.delay(20) }
        assertNotNull(fileOf(DownloadEpisodeId(1)))
    }

    private suspend fun DesktopDownloads.awaitContents(what: (DownloadsContents) -> Boolean) =
        withTimeout(TIMEOUT_MILLIS) { source().contents.first(what) }

    private fun scene(content: @Composable () -> Unit) =
        ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
            CloudStreamTheme {
                CompositionLocalProvider(LocalCloudStreamTypography provides CloudStreamTheme.typography.forDesktop()) {
                    Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) { content() }
                }
            }
        }

    private var clock = 0L

    private fun ImageComposeScene.settle() = repeat(SETTLE_FRAMES) {
        clock += FRAME_NANOS
        render(clock)
    }

    /** Waits on the real service, rendering as it goes, until [done]. */
    private fun ImageComposeScene.until(done: () -> Boolean) = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            while (!done()) {
                settle()
                kotlinx.coroutines.delay(20)
            }
        }
    }

    /**
     * At the scene's clock, which [settle] moves on by a second: the row reads two presses close together as a
     * double-click, and events that all said "time 0" were read as one, which opened the title instead of deleting.
     */
    private fun ImageComposeScene.press(at: Offset, secondary: Boolean = false) {
        val button = if (secondary) PointerButton.Secondary else PointerButton.Primary
        val down = if (secondary) PointerButtons(isSecondaryPressed = true) else PointerButtons(isPrimaryPressed = true)
        val time = clock / NANOS_PER_MILLI
        sendPointerEvent(PointerEventType.Move, at, timeMillis = time)
        sendPointerEvent(PointerEventType.Press, at, buttons = down, button = button, timeMillis = time)
        sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = button, timeMillis = time)
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
        File(directory, "$name.png").writeBytes(checkNotNull(render(clock).encodeToData(EncodedImageFormat.PNG)).bytes)
    }

    @Test
    fun `delete asks first, Cancel keeps the file, and the sheet's Delete removes it from the disk`() {
        val downloads = host()
        val file = downloads.downloadFilm()
        val scene = scene { DownloadsHost(downloads, scope, setEscape = {}, onOpenTitle = {}, onLibrary = {}) }
        scene.until { runBlocking { downloads.source().contents.first() }.titles.isNotEmpty() }
        scene.settle()
        scene.save("host-D1-one-film")

        scene.press(Offset(NAME_X, FIRST_ROW_Y))
        scene.key(Key.Backspace, meta = true)
        scene.save("host-D3-asking")
        // Cancel is the sheet's default (`DEC-19`).
        scene.key(Key.Enter)
        assertTrue(file.exists(), "Cancel deletes nothing")

        // The second time through the row's own Delete verb, in the same window after the Return-cancel. It shows under
        // the pointer beside Show in Finder, which is there only because the host's `fileOf` found the file.
        scene.sendPointerEvent(PointerEventType.Move, Offset(NAME_X, FIRST_ROW_Y))
        scene.settle()
        scene.save("host-D2-verbs")
        scene.press(Offset(DELETE_VERB_X, FIRST_ROW_Y))
        scene.save("host-D3-asking-again")
        scene.press(Offset(SHEET_DELETE_X, SHEET_BUTTON_Y))
        scene.until { !file.exists() }
        runBlocking { downloads.awaitContents { it.titles.isEmpty() } }
        assertTrue("A Film" !in stateFile.readText(), "the record goes with the file")
        scene.close()
    }

    @Test
    fun `the sidebar's fourth row and command 4 open Downloads, with the service the app was given`() {
        val downloads = host()
        val shortcuts = DesktopShortcuts()
        val screens = mutableListOf<String>()
        val scene = scene {
            DesktopApp(EscapeHandler(), library = InMemoryLibraryStore(), onScreen = { screens += it }, shortcuts = shortcuts, downloads = downloads)
        }
        scene.settle()
        assertEquals("Home", screens.last())
        shortcuts.go(TopLevelDestination.Downloads)
        scene.settle()
        assertEquals("Downloads", screens.last())
        scene.save("host-window-downloads")

        shortcuts.go(TopLevelDestination.Home)
        scene.settle()
        scene.press(Offset(SIDEBAR_X, SIDEBAR_DOWNLOADS_Y))
        assertEquals("Downloads", screens.last())
        scene.close()
    }

    @Test
    fun `sizes read as Finder writes them`() {
        assertEquals("0 B", formatBytes(0, Locale.ROOT))
        assertEquals("999 B", formatBytes(999, Locale.ROOT))
        assertEquals("1.5 KB", formatBytes(1_500, Locale.ROOT))
        assertEquals("4.24 GB", formatBytes(4_240_000_000, Locale.ROOT))
        assertEquals("4,24 GB", formatBytes(4_240_000_000, Locale.GERMANY))
    }

    private companion object {
        const val WIDTH = 1060
        const val HEIGHT = 800
        const val SETTLE_FRAMES = 20
        const val FRAME_NANOS = 50_000_000L
        const val TIMEOUT_MILLIS = 20_000L
        /** `DownloadsScreenTest`'s, at the same size: the first row, and the sheet's Delete. */
        const val FIRST_ROW_Y = 214f
        const val NAME_X = 120f
        const val SHEET_DELETE_X = 575f
        const val SHEET_BUTTON_Y = 181f
        /** The row's Delete verb, the trailing one of its hover verbs, measured from `host-D2-verbs.png`. */
        const val DELETE_VERB_X = 1024f
        const val NANOS_PER_MILLI = 1_000_000L
        /** `HomeScreenTest`'s sidebar: 24 down, then rows 36 apart; the fourth is Downloads. */
        const val SIDEBAR_X = 100f
        const val SIDEBAR_DOWNLOADS_Y = 148f
    }
}

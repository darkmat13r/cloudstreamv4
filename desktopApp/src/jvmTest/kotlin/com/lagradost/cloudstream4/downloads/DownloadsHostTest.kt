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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import kotlinx.coroutines.Job
import org.jetbrains.skia.Bitmap
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleId
import com.lagradost.cloudstream4.core.connectivity.UnknownConnectivityFlow
import com.lagradost.cloudstream4.feature.downloads.DownloadsViewModel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
import com.lagradost.cloudstream4.feature.settings.PlaybackSettings
import com.lagradost.cloudstream4.player.MediaProtocol
import com.lagradost.cloudstream4.player.VlcPlayer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

    /** Two episodes of one series, downloaded to the end. */
    private fun DesktopDownloads.downloadSeries(): Unit = runBlocking {
        listOf(1, 2).forEach { number ->
            server.add("e$number.bin", 16 * 1024)
            enqueue(
                DesktopDownloadRequest(
                    episodeId = 300 + number,
                    titleId = SERIES,
                    titleName = "A Series",
                    kind = DownloadTitleKind.Series,
                    season = 1,
                    episode = number,
                    episodeName = null,
                    source = DownloadSourceRef("Test Provider", "episode-data-$number"),
                    link = DesktopDownloadLink(server.url("e$number.bin")),
                ),
            )
        }
        withTimeout(TIMEOUT_MILLIS) {
            while (fileOf(DownloadEpisodeId(301)) == null || fileOf(DownloadEpisodeId(302)) == null) kotlinx.coroutines.delay(20)
        }
    }

    private fun ImageComposeScene.pixel(x: Int, y: Int): Int {
        settle()
        return Bitmap.makeFromImage(render(clock)).getColor(x, y)
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
        val viewModel = DownloadsViewModel(downloads.source(), UnknownConnectivityFlow, scope)
        val scene = scene { DownloadsHost(downloads, viewModel, scope, setEscape = {}, onOpenTitle = {}, onLibrary = {}) }
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

    /**
     * The list's view model is the window's (`#229` review), as the library's is: leaving Downloads for a series page or
     * another tab and coming back finds the same one, with its readers and its state. Made in the host, it was made again
     * on every visit, with new readers in the window's scope and the old ones left running. What its state keeps that the
     * Mac draws is the selection, so that is what is checked across a visit to Home.
     */
    @Test
    fun `the list is the same one after a visit elsewhere, with its selection, and a series page opens and closes over it`() {
        val downloads = host()
        downloads.downloadSeries()
        val escape = EscapeHandler()
        val shortcuts = DesktopShortcuts()
        val screens = mutableListOf<String>()
        val scene = scene {
            DesktopApp(escape, library = InMemoryLibraryStore(), onScreen = { screens += it }, shortcuts = shortcuts, downloads = downloads)
        }
        scene.settle()
        shortcuts.go(TopLevelDestination.Downloads)
        scene.until { screens.last() == "Downloads" && runBlocking { downloads.source().contents.first() }.titles.isNotEmpty() }
        scene.sendPointerEvent(PointerEventType.Move, Offset(AWAY_X, AWAY_Y))
        val unselected = scene.pixel(ROW_FILL_X, WINDOW_FIRST_ROW_Y.toInt())
        scene.save("host-window-D1")

        scene.press(Offset(WINDOW_NAME_X, WINDOW_FIRST_ROW_Y))
        scene.sendPointerEvent(PointerEventType.Move, Offset(AWAY_X, AWAY_Y))
        val selected = scene.pixel(ROW_FILL_X, WINDOW_FIRST_ROW_Y.toInt())
        scene.save("host-window-D1-selected")
        assertTrue(selected != unselected, "the sample point is not on the row's fill")

        shortcuts.go(TopLevelDestination.Home)
        scene.settle()
        assertEquals("Home", screens.last())
        shortcuts.go(TopLevelDestination.Downloads)
        scene.settle()
        assertEquals("Downloads", screens.last())
        assertEquals(selected, scene.pixel(ROW_FILL_X, WINDOW_FIRST_ROW_Y.toInt()), "the row is still selected")
        scene.save("host-window-D1-again")

        // A double-click opens the series (which ends choosing, as on Android), and Escape is D4's Back.
        val at = Offset(WINDOW_NAME_X, WINDOW_FIRST_ROW_Y)
        val time = clock / NANOS_PER_MILLI
        repeat(2) {
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary, timeMillis = time)
            scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary, timeMillis = time)
        }
        scene.settle()
        assertTrue(screens.last().startsWith("DownloadSeries"), screens.last())
        scene.save("host-window-D4")
        assertTrue(escape.handle(), "D4 has Back on Escape")
        scene.settle()
        assertEquals("Downloads", screens.last())
        scene.close()
    }

    /** A series page reads the service only while it is open: its readers are cancelled with it (`#229` review). */
    @Test
    fun `a series page's readers stop when the page is left`() {
        val downloads = host()
        downloads.downloadSeries()
        val window = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val running = { checkNotNull(window.coroutineContext[Job]).children.sumOf { 1 + it.children.count() } }
        var open by mutableStateOf(true)
        val scene = scene {
            if (open) DownloadSeriesHost(downloads, DownloadTitleId(SERIES), window, setEscape = {}, onClose = {})
        }
        scene.until { running() > 1 }
        open = false
        scene.until { running() == 0 }
        scene.close()
        window.cancel()
    }

    /** Two presses at the scene's clock, together: the row reads them as a double-click, its Play (`D4`). */
    private fun ImageComposeScene.doubleClick(at: Offset) {
        val time = clock / NANOS_PER_MILLI
        repeat(2) {
            sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary, timeMillis = time)
            sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary, timeMillis = time)
        }
        settle()
    }

    /**
     * `KMP-74`: an episode's Play hands the player its file and the download folder it must be inside. One whose file
     * went since the page was drawn plays nothing, and leaves the list.
     */
    @Test
    fun `Play on a finished episode hands over its file and the folder, and a file gone since plays nothing`() {
        val downloads = host()
        downloads.downloadSeries()
        val played = mutableListOf<PlayableDownload>()
        val scene = scene {
            DownloadSeriesHost(downloads, DownloadTitleId(SERIES), scope, setEscape = {}, onClose = {}, onPlay = { played += it })
        }
        // The page reads the service on its own threads: double-click until its first row is there to take it.
        scene.until {
            if (played.isEmpty()) scene.doubleClick(Offset(NAME_X, SERIES_FIRST_ROW_Y))
            played.isNotEmpty()
        }
        val first = played.single()
        assertEquals(DownloadEpisodeId(301), first.id)
        assertEquals(downloads.fileOf(DownloadEpisodeId(301)), first.file)
        assertEquals(root.absoluteFile, first.root)
        assertEquals("A Series", first.title)
        assertEquals(1, first.season)
        assertEquals(1, first.episode)
        assertEquals("PlayableDownload(id=301)", first.toString(), "no path in its name")

        assertTrue(checkNotNull(downloads.fileOf(DownloadEpisodeId(302))).delete())
        scene.doubleClick(Offset(NAME_X, SERIES_FIRST_ROW_Y + SERIES_ROW_PITCH))
        assertEquals(listOf(first), played, "the second episode's file has gone")
        assertNull(downloads.playable(DownloadEpisodeId(302)))
        scene.until { runBlocking { downloads.series(DownloadTitleId(SERIES)).contents.first() }.episodes.size == 1 }
        scene.close()
    }

    /** `KMP-74`: in the window, Play opens the player over the series page, and Escape comes back to it. */
    @Test
    fun `in the window an episode's Play opens the player on that download, and Escape returns to the series`() {
        val downloads = host()
        downloads.downloadSeries()
        val escape = EscapeHandler()
        val shortcuts = DesktopShortcuts()
        val screens = mutableListOf<String>()
        val asked = mutableListOf<PlayableDownload>()
        val scene = scene {
            DesktopApp(
                escape,
                library = InMemoryLibraryStore(),
                onScreen = { screens += it },
                shortcuts = shortcuts,
                downloads = downloads,
                // Nothing is started: the screen shows that no player could be opened, which is enough to see where it is.
                openDownload = { asked += it; null },
            )
        }
        scene.settle()
        shortcuts.go(TopLevelDestination.Downloads)
        scene.until { screens.last() == "Downloads" && runBlocking { downloads.source().contents.first() }.titles.isNotEmpty() }
        scene.until {
            if (!screens.last().startsWith("DownloadSeries")) scene.doubleClick(Offset(WINDOW_NAME_X, WINDOW_FIRST_ROW_Y))
            screens.last().startsWith("DownloadSeries")
        }
        scene.until {
            if (asked.isEmpty()) scene.doubleClick(Offset(WINDOW_NAME_X, SERIES_FIRST_ROW_Y))
            asked.isNotEmpty()
        }
        assertEquals(listOf(DownloadEpisodeId(301)), asked.map { it.id })
        assertEquals("DownloadPlayer(download=PlayableDownload(id=301))", screens.last())
        assertTrue(escape.handle(), "the player has Back on Escape")
        scene.settle()
        assertTrue(screens.last().startsWith("DownloadSeries"), screens.last())
        assertEquals(1, asked.size, "opened once")
        scene.close()
    }

    /**
     * `KMP-74`, new with the rebase onto `KMP-96`: the download player steps by Settings' Seek amount, as a stream's does,
     * and starts with the one command that names no file.
     */
    @Test
    fun `the download player plays its descriptor and steps by Settings' Seek amount`() {
        val downloads = host()
        downloads.downloadSeries()
        val escape = EscapeHandler()
        val shortcuts = DesktopShortcuts()
        val screens = mutableListOf<String>()
        val sent = ByteArrayOutputStream()
        // Waits, writing nothing, so the player neither fails nor plays; it is ended when the player is released, and
        // here whatever happens.
        val process = ProcessBuilder("/bin/sleep", "60").start()
        val directory = Files.createTempDirectory("download-player").toFile()
        try {
            val player = VlcPlayer.forDownload(process, directory, sent)
            val thirtySeconds = object : PlaybackSettings {
                override val autoplayNext: StateFlow<Boolean> = MutableStateFlow(true)
                override val seekSeconds: StateFlow<Int> = MutableStateFlow(SEEK_SECONDS)
            }
            val scene = scene {
                DesktopApp(
                    escape,
                    library = InMemoryLibraryStore(),
                    onScreen = { screens += it },
                    shortcuts = shortcuts,
                    downloads = downloads,
                    playback = thirtySeconds,
                    openDownload = { player },
                )
            }
            scene.settle()
            shortcuts.go(TopLevelDestination.Downloads)
            scene.until { screens.last() == "Downloads" && runBlocking { downloads.source().contents.first() }.titles.isNotEmpty() }
            scene.until {
                if (!screens.last().startsWith("DownloadSeries")) scene.doubleClick(Offset(WINDOW_NAME_X, WINDOW_FIRST_ROW_Y))
                screens.last().startsWith("DownloadSeries")
            }
            scene.until {
                if (!screens.last().startsWith("DownloadPlayer")) scene.doubleClick(Offset(WINDOW_NAME_X, SERIES_FIRST_ROW_Y))
                screens.last().startsWith("DownloadPlayer")
            }
            scene.until { "play file" in commands(sent) }
            scene.key(Key.DirectionRight)
            scene.key(Key.DirectionLeft)
            val seen = commands(sent)
            assertEquals("play file", seen.first(), "the first command plays the descriptor")
            assertEquals(1, seen.count { it == "play file" })
            assertEquals(listOf("by 30000", "by -30000"), seen.filter { it.startsWith("by ") })
            assertTrue(escape.handle())
            scene.settle()
            scene.close()
        } finally {
            process.destroyForcibly()
            directory.deleteRecursively()
        }
    }

    /** What the screen sent the host, decoded as `MediaHost` reads it. */
    private fun commands(sent: ByteArrayOutputStream): List<String> {
        val input = DataInputStream(ByteArrayInputStream(sent.toByteArray()))
        val seen = mutableListOf<String>()
        while (input.available() > 0) {
            seen += when (input.readByte()) {
                MediaProtocol.OP_PLAY_FILE -> "play file"
                MediaProtocol.OP_TOGGLE_PAUSE -> "toggle"
                MediaProtocol.OP_SEEK_BY -> "by ${input.readLong()}"
                MediaProtocol.OP_SEEK_TO -> "to ${input.readFloat()}"
                MediaProtocol.OP_SET_VOLUME -> "volume ${input.readInt()}"
                MediaProtocol.OP_SET_AUDIO_TRACK -> "audio ${input.readInt()}"
                else -> error("a command the download player should never send")
            }
        }
        return seen
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
        const val SERIES = 300
        /** `DownloadSeriesScreenTest`'s, at the same size: D4's first episode row, and the pitch between rows. */
        const val SERIES_FIRST_ROW_Y = 312f
        const val SERIES_ROW_PITCH = 48f
        /** The first row in the whole window, past the 220 sidebar, from `host-window-D1`. */
        const val WINDOW_FIRST_ROW_Y = 214f
        const val WINDOW_NAME_X = 340f
        /** On the row's fill, clear of its words, from `host-window-D1-selected`. */
        const val ROW_FILL_X = 700
        /** Off every row: the pointer waits here, so hover is not what the sample sees. */
        const val AWAY_X = 100f
        const val AWAY_Y = 700f
        /** `HomeScreenTest`'s sidebar: under the 52 title band (`KMP-98`), then rows 36 apart; the fourth is Downloads. */
        const val SIDEBAR_X = 100f
        const val SIDEBAR_DOWNLOADS_Y = 176f
        /** Not Settings' default of 10, so a step that ignored the setting would show. */
        const val SEEK_SECONDS = 30
    }
}

package com.lagradost.cloudstream4.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopTypography
import com.lagradost.cloudstream4.core.http.HttpClientFactory
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.LocalCloudStreamTypography
import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.DetailLoad
import com.lagradost.cloudstream4.feature.detail.DetailState
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import com.lagradost.cloudstream4.feature.detail.desktop.DetailScreenDesktop
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisodeId
import com.lagradost.cloudstream4.core.scenetest.frame
import com.lagradost.cloudstream4.media.download.DownloadOptions
import com.lagradost.cloudstream4.core.scenetest.testScene
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

/**
 * `KMP-122`, a film's page end to end: the title page with [rememberFilmDownloadControl] in its film slot, over a real
 * [DesktopDownloads] and a loopback server. Download is pressed with real pointer events, with nothing hovered first:
 * the action row's control is always drawn.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class FilmDownloadControlTest {
    private val base: File = Files.createTempDirectory("film-control").toFile()
    private val server = RangeServer()
    private val asked = CopyOnWriteArrayList<DownloadSourceRef>()
    private val host = DesktopDownloads(
        downloadRoot = File(base, "Downloads"),
        stateFile = File(File(base, "data"), "downloads.json"),
        httpClient = HttpClientFactory(OkHttpClient()).create(),
        resolver = LinkResolver { ref ->
            asked += ref
            listOf(DesktopDownloadLink(server.url("film.bin")))
        },
        // Loopback is a private address: allowed here, for this test only.
        options = DownloadOptions(parts = 1, allowPrivateAddresses = true, backoffBaseMillis = 10, backoffMaxMillis = 50),
        maxRunning = 3,
        resolveTimeoutMillis = DesktopDownloads.RESOLVE_TIMEOUT_MILLIS,
        log = {},
        ownsClient = true,
    )
    private val scenes = mutableListOf<ImageComposeScene>()

    @AfterTest
    fun cleanUp() = runBlocking {
        scenes.forEach { it.close() }
        host.close()
        server.close()
        base.deleteRecursively()
        Unit
    }

    private val film = TitleDetail(
        title = "Fixture Film", providerName = "Fixture Provider", typeName = "Movie", year = null, plot = null,
        posterUrl = null, playData = FILM_DATA, episodes = emptyList(),
    )

    private val show = TitleDetail(
        title = "Fixture Show", providerName = "Fixture Provider", typeName = "TvSeries", year = null, plot = null,
        posterUrl = null, playData = null,
        episodes = listOf(DetailEpisode("The First", "https://fixture.example/show/1/1", 1, 1)),
    )

    private inner class Page(detail: TitleDetail, key: String) {
        val scene = testScene(WIDTH, HEIGHT, Density(1f)) {
            CloudStreamTheme {
                CompositionLocalProvider(LocalCloudStreamTypography provides desktopTypography()) {
                    Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                        DetailScreenDesktop(
                            state = DetailState(DetailLoad.Loaded(detail)),
                            onAction = {},
                            onBack = {},
                            artwork = {},
                            filmTrailing = rememberFilmDownloadControl(host, key, detail),
                        )
                    }
                }
            }
        }.also { scenes += it }
        private var clock = 0L

        private fun settle() = repeat(SETTLE_FRAMES) {
            clock += FRAME_NANOS
            scene.frame(clock)
        }

        private fun SemanticsNode.all(): List<SemanticsNode> = listOf(this) + children.flatMap { it.all() }

        fun nodes(words: String): List<SemanticsNode> {
            settle()
            return scene.semanticsOwners.flatMap { it.rootSemanticsNode.all() }.filter { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == words } ||
                    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == words }
            }
        }

        fun click(at: Offset) {
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at)
            scene.sendPointerEvent(PointerEventType.Release, at)
            settle()
        }

        /** Renders frames, giving the service's coroutines real time, until [words] are on the page. */
        fun awaitWords(words: String) {
            val until = System.currentTimeMillis() + TIMEOUT
            while (nodes(words).isEmpty()) {
                check(System.currentTimeMillis() < until) { "\"$words\" never appeared" }
                Thread.sleep(POLL_MILLIS)
            }
        }
    }

    @Test
    fun `a film's page offers Download beside Play, and pressing it downloads the film`() {
        val body = server.add("film.bin", 64 * 1024)
        val page = Page(film, FILM_KEY)
        val play = page.nodes("Play Movie").single()
        val download = page.nodes("Download")
        assertEquals(1, download.size, "Download is drawn without hovering anything")
        val at = download.single().boundsInRoot
        assertTrue(at.left > play.boundsInRoot.right, "after Play, in the same row")
        assertTrue(at.top < play.boundsInRoot.bottom && at.bottom > play.boundsInRoot.top, "level with Play")

        page.click(at.center)
        page.awaitWords("Downloaded")
        assertEquals(
            listOf(DownloadSourceRef("Fixture Provider", FILM_DATA)),
            asked.toList(),
            "the service asked for the film's play data, and nothing else",
        )
        val file = assertNotNull(host.fileOf(DownloadEpisodeId(EpisodeDownloadIds.episodeId("Fixture Provider", FILM_DATA))))
        assertContentEquals(body, file.readBytes())
        assertEquals("Fixture Film.mp4", file.name)
        assertTrue(page.nodes("Download").isEmpty(), "a downloaded film offers no second Download")
    }

    @Test
    fun `a series page draws no film Download in its actions`() {
        val page = Page(show, SHOW_KEY)
        assertEquals(1, page.nodes("The First").size, "the page is drawn")
        assertTrue(page.nodes("Download").isEmpty(), "no Download outside the episode rows")
        assertTrue(asked.isEmpty())
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 800
        const val SETTLE_FRAMES = 10
        const val FRAME_NANOS = 50_000_000L
        const val TIMEOUT = 30_000L
        const val POLL_MILLIS = 50L
        const val FILM_DATA = "https://fixture.example/film/play"
        const val FILM_KEY = "Fixture Provider\u0000https://fixture.example/film"
        const val SHOW_KEY = "Fixture Provider\u0000https://fixture.example/show"
    }
}

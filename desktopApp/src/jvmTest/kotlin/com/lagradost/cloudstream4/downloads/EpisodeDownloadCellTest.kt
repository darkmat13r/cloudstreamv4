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
import com.lagradost.cloudstream4.media.download.DownloadOptions
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
 * `KMP-109` part 2b, the page end to end: the title page with [rememberEpisodeDownloadCell] in its slot, over a real
 * [DesktopDownloads] and a loopback server. Download is pressed with real pointer events on the hovered row.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class EpisodeDownloadCellTest {
    private val base: File = Files.createTempDirectory("episode-cell").toFile()
    private val server = RangeServer()
    private val asked = CopyOnWriteArrayList<DownloadSourceRef>()
    private val host = DesktopDownloads(
        downloadRoot = File(base, "Downloads"),
        stateFile = File(File(base, "data"), "downloads.json"),
        httpClient = HttpClientFactory(OkHttpClient()).create(),
        resolver = LinkResolver { ref ->
            asked += ref
            listOf(DesktopDownloadLink(server.url("cell.bin")))
        },
        // Loopback is a private address: allowed here, for this test only.
        options = DownloadOptions(parts = 1, allowPrivateAddresses = true, backoffBaseMillis = 10, backoffMaxMillis = 50),
        maxRunning = 3,
        resolveTimeoutMillis = DesktopDownloads.RESOLVE_TIMEOUT_MILLIS,
        log = {},
        ownsClient = true,
    )

    @AfterTest
    fun cleanUp() = runBlocking {
        host.close()
        server.close()
        base.deleteRecursively()
        Unit
    }

    private val detail = TitleDetail(
        title = "Fixture Show", providerName = "Fixture Provider", typeName = "TvSeries", year = null, plot = null,
        posterUrl = null, playData = null,
        episodes = listOf(
            DetailEpisode("The First", "https://fixture.example/show/1/1", 1, 1),
            DetailEpisode("The Second", "https://fixture.example/show/1/2", 1, 2),
        ),
    )

    private val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
        CloudStreamTheme {
            CompositionLocalProvider(LocalCloudStreamTypography provides desktopTypography()) {
                Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                    DetailScreenDesktop(
                        state = DetailState(DetailLoad.Loaded(detail)),
                        onAction = {},
                        onBack = {},
                        artwork = {},
                        episodeTrailing = rememberEpisodeDownloadCell(host, TITLE_KEY, detail),
                    )
                }
            }
        }
    }
    private var clock = 0L

    private fun settle() = repeat(SETTLE_FRAMES) {
        clock += FRAME_NANOS
        scene.render(clock)
    }

    private fun SemanticsNode.all(): List<SemanticsNode> = listOf(this) + children.flatMap { it.all() }

    private fun nodes(words: String): List<SemanticsNode> {
        settle()
        return scene.semanticsOwners.flatMap { it.rootSemanticsNode.all() }.filter { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == words } ||
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == words }
        }
    }

    private fun move(at: Offset) {
        scene.sendPointerEvent(PointerEventType.Move, at)
        settle()
    }

    private fun click(at: Offset) {
        scene.sendPointerEvent(PointerEventType.Move, at)
        scene.sendPointerEvent(PointerEventType.Press, at)
        scene.sendPointerEvent(PointerEventType.Release, at)
        settle()
    }

    /** Renders frames, giving the service's coroutines real time, until [words] are on the page. */
    private fun awaitWords(words: String) {
        val until = System.currentTimeMillis() + TIMEOUT
        while (nodes(words).isEmpty()) {
            check(System.currentTimeMillis() < until) { "\"$words\" never appeared" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    @Test
    fun `pressing Download on a row downloads that episode, and its row then reads Downloaded`() {
        val body = server.add("cell.bin", 64 * 1024)
        move(nodes("The Second").single().boundsInRoot.center)
        val download = nodes("Download")
        assertEquals(1, download.size, "the hovered row offers Download")
        click(download.single().boundsInRoot.center)

        awaitWords("Downloaded")
        assertEquals(1, nodes("Downloaded").size, "only the pressed row")
        assertEquals(
            listOf(DownloadSourceRef("Fixture Provider", "https://fixture.example/show/1/2")),
            asked.toList(),
            "the service asked for the pressed episode's source, and nothing else",
        )
        val id = EpisodeDownloadIds.episodeId("Fixture Provider", "https://fixture.example/show/1/2")
        val file = assertNotNull(host.fileOf(DownloadEpisodeId(id)))
        assertContentEquals(body, file.readBytes())
        assertTrue(file.parentFile.name == "Fixture Show", "filed under the page's title")
        scene.close()
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 800
        const val SETTLE_FRAMES = 10
        const val FRAME_NANOS = 50_000_000L
        const val TIMEOUT = 30_000L
        const val POLL_MILLIS = 50L
        const val TITLE_KEY = "Fixture Provider\u0000https://fixture.example/show"
    }
}

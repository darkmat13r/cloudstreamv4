package com.lagradost.cloudstream4.downloads

import com.lagradost.cloudstream4.core.http.HttpClientFactory
import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleId
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleKind
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisodeState
import com.lagradost.cloudstream4.media.download.DownloadOptions
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient

/**
 * `KMP-109` part 2b: the title page's downloads, through a real [DesktopDownloads] downloading from a loopback server.
 * What a row shows, what Download starts, and the two guards that keep a row to its own episode.
 */
class PageDownloadsTest {
    private val base: File = Files.createTempDirectory("page-downloads").toFile()
    private val stateFile = File(File(base, "data"), "downloads.json")
    private val server = RangeServer()
    private val hosts = mutableListOf<DesktopDownloads>()
    private val asked = CopyOnWriteArrayList<DownloadSourceRef>()

    @AfterTest
    fun cleanUp() = runBlocking {
        hosts.forEach { it.close() }
        server.close()
        base.deleteRecursively()
        Unit
    }

    /** Loopback is a private address: allowed here, for these tests only. Every source resolves to [file]. */
    private fun host(file: String) = DesktopDownloads(
        downloadRoot = File(base, "Downloads"),
        stateFile = stateFile,
        httpClient = HttpClientFactory(OkHttpClient()).create(),
        resolver = LinkResolver { ref ->
            asked += ref
            listOf(DesktopDownloadLink(server.url(file)))
        },
        options = DownloadOptions(parts = 1, allowPrivateAddresses = true, backoffBaseMillis = 10, backoffMaxMillis = 50),
        maxRunning = 3,
        resolveTimeoutMillis = DesktopDownloads.RESOLVE_TIMEOUT_MILLIS,
        log = {},
        ownsClient = true,
    ).also { hosts += it }

    private fun show(title: String) = TitleDetail(
        title = title, providerName = PROVIDER, typeName = "TvSeries", year = null, plot = null,
        posterUrl = null, playData = null, episodes = listOf(EPISODE),
    )

    private suspend fun PageDownloads.awaitRow(
        detail: TitleDetail,
        what: (Map<Int, EpisodeDownloadRow>) -> Boolean,
    ): Map<Int, EpisodeDownloadRow> = withTimeout(TIMEOUT) { rows(detail).first(what) }

    @Test
    fun `Download starts the row's episode through the resolver, and the row follows it to Done`() = runBlocking {
        val body = server.add("page.bin", 128 * 1024)
        val host = host("page.bin")
        val detail = show("Fixture Show")
        val page = PageDownloads(host, TITLE_KEY)
        val id = page.episodeId(detail, EPISODE)

        assertEquals(emptyMap(), page.awaitRow(detail) { true }, "nothing before Download")
        assertEquals(EnqueueResult.Queued, page.download(detail, EPISODE))
        val done = page.awaitRow(detail) { it[id]?.download?.state == DownloadEpisodeState.Done }.getValue(id)
        assertEquals(false, done.unavailable)
        assertEquals(listOf(DownloadSourceRef(PROVIDER, EPISODE.data)), asked.toList(), "asked for its source alone")
        assertContentEquals(body, assertNotNull(host.fileOf(done.download!!.id)).readBytes())

        assertEquals(EnqueueResult.AlreadyPresent, page.download(detail, EPISODE), "a second press adds nothing")
        val after = page.awaitRow(detail) { true }.getValue(id)
        assertEquals(DownloadEpisodeState.Done, after.download?.state, "and the row still reads it as it is")
        assertEquals(false, after.unavailable, "AlreadyPresent is not a refusal")
    }

    /**
     * Another title holds this page's title id (forced through the id seam) and the same episode id: the same provider
     * and episode data, under another name. Download is refused `IdTaken`; the row says unavailable and never shows the
     * other title's download, though the service files it under the same two ids.
     */
    @Test
    fun `an id held by another title is refused, and the row never shows that title's download`() = runBlocking {
        server.add("other.bin", 4 * 1024 * 1024)
        server.chunkDelayMillis.set(10)
        val host = host("other.bin")
        val forced: (String) -> Int = { FORCED_TITLE_ID }
        val other = PageDownloads(host, "Other key", forced)
        val otherShow = show("Other Show")
        assertEquals(EnqueueResult.Queued, other.download(otherShow, EPISODE))
        val id = other.episodeId(otherShow, EPISODE)
        other.awaitRow(otherShow) { it[id]?.download != null }

        val detail = show("Fixture Show")
        val page = PageDownloads(host, TITLE_KEY, forced)
        assertEquals(id, page.episodeId(detail, EPISODE), "the two pages' episodes share an id")
        assertNull(page.awaitRow(detail) { true }[id], "before Download, this row shows nothing")
        assertEquals(EnqueueResult.Refused(EnqueueRefusal.IdTaken), page.download(detail, EPISODE))
        val row = page.awaitRow(detail) { it[id]?.unavailable == true }.getValue(id)
        assertNull(row.download, "the other title's download is not drawn on this row")
    }

    /**
     * The other title copies this page's name exactly and collides on both 32-bit ids, from another provider: a plugin
     * picks all three strings. The name alone would let its record through; the row must refuse it as enqueue does,
     * on the provider, so this page neither shows nor pauses or cancels another provider's download.
     */
    @Test
    fun `a title with the same name from another provider, on both ids, is refused, and its download never shows`() =
        runBlocking {
            server.add("copied.bin", 4 * 1024 * 1024)
            server.chunkDelayMillis.set(10)
            val host = host("copied.bin")
            val detail = show("Fixture Show")
            val page = PageDownloads(host, TITLE_KEY) { FORCED_TITLE_ID }
            val id = page.episodeId(detail, EPISODE)
            val copied = DesktopDownloadRequest(
                episodeId = id,
                titleId = FORCED_TITLE_ID,
                titleName = "Fixture Show",
                kind = DownloadTitleKind.Series,
                season = 1,
                episode = 2,
                episodeName = "The Second",
                source = DownloadSourceRef("Another Provider", EPISODE.data),
            )
            assertEquals(EnqueueResult.Queued, host.enqueue(copied))
            withTimeout(TIMEOUT) {
                host.series(DownloadTitleId(FORCED_TITLE_ID)).contents.first { c -> c.episodes.any { it.id.value == id } }
            }

            assertNull(page.awaitRow(detail) { true }[id], "before Download, the copied title's download is not shown")
            assertEquals(EnqueueResult.Refused(EnqueueRefusal.IdTaken), page.download(detail, EPISODE))
            val row = page.awaitRow(detail) { it[id]?.unavailable == true }.getValue(id)
            assertNull(row.download, "after it, the row reads unavailable and still shows nothing of the other's")
        }

    @Test
    fun `a downloads file that cannot be read gives rows with no download, and Download reads unavailable`() =
        runBlocking {
            stateFile.parentFile.mkdirs()
            stateFile.writeText("not a downloads file")
            val host = host("unused.bin")
            val detail = show("Fixture Show")
            val page = PageDownloads(host, TITLE_KEY)
            val id = page.episodeId(detail, EPISODE)

            assertEquals(emptyMap(), page.awaitRow(detail) { true }, "no rows, and no throw")
            assertEquals(EnqueueResult.Refused(EnqueueRefusal.StoreUnreadable), page.download(detail, EPISODE))
            assertEquals(EpisodeDownloadRow(null, unavailable = true), page.awaitRow(detail) { id in it }.getValue(id))
            assertTrue(asked.isEmpty(), "no source was asked for")
            assertEquals("not a downloads file", stateFile.readText(), "and the file is not written over")
        }

    @Test
    fun `Pause and Cancel act on the row's own episode`() = runBlocking {
        server.add("verbs.bin", 4 * 1024 * 1024)
        server.chunkDelayMillis.set(10)
        val host = host("verbs.bin")
        val detail = show("Fixture Show")
        val page = PageDownloads(host, TITLE_KEY)
        val id = page.episodeId(detail, EPISODE)
        page.download(detail, EPISODE)
        page.awaitRow(detail) { it[id]?.download?.state == DownloadEpisodeState.Running }

        page.pause(id)
        page.awaitRow(detail) { it[id]?.download?.state == DownloadEpisodeState.Paused }
        page.resume(id)
        page.awaitRow(detail) { it[id]?.download?.state.let { s -> s == DownloadEpisodeState.Running || s == DownloadEpisodeState.Queued } }
        page.cancel(id)
        assertNull(page.awaitRow(detail) { id !in it }[id], "cancelled: the row has nothing again")
    }

    private companion object {
        const val TIMEOUT = 30_000L
        const val PROVIDER = "Fixture Provider"
        const val TITLE_KEY = "Fixture Provider\u0000https://fixture.example/show"
        const val FORCED_TITLE_ID = 4242
        val EPISODE = DetailEpisode(name = "The Second", data = "https://fixture.example/show/1/2", season = 1, episode = 2)
    }
}

package com.lagradost.cloudstream4.home

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream4.feature.home.HomeFeatured
import com.lagradost.cloudstream4.feature.home.HomeItem
import com.lagradost.cloudstream4.feature.home.HomePageReply
import com.lagradost.cloudstream4.feature.home.HomeSourceId
import com.lagradost.cloudstream4.feature.search.SearchResultId
import com.lagradost.cloudstream4.search.SearchResultTarget
import com.lagradost.cloudstream4.search.decodeId
import com.lagradost.cloudstream4.search.encodeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

/**
 * Over fakes handed in directly, the same as `DesktopHomeSourceTest`, and with the one thing it cannot have: nothing here
 * has crossed a sandbox, so every answer below reaches this class as a plugin wrote it.
 */
class AndroidHomeSourceTest {

    private class Fake(
        override var name: String = "Fake",
        sections: Int = 2,
        private val answer: suspend Fake.(Int, MainPageRequest) -> HomePageResponse? = { _, _ -> null },
    ) : MainAPI() {
        override var mainUrl = "https://example.com"
        override var lang = "en"
        override val hasMainPage = true
        override val supportedTypes = setOf(TvType.Movie)
        override val mainPage = List(sections) { MainPageData("Section $it", "$it") }
        val asked = mutableListOf<String>()

        fun hit(
            title: String,
            poster: String? = null,
            url: String = "https://example.com/$title",
            headers: Map<String, String>? = null,
        ): SearchResponse = newMovieSearchResponse(title, url, TvType.Movie, fix = false) {
            posterUrl = poster
            posterHeaders = headers
        }

        override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
            asked += "${request.data}/$page"
            return answer(page, request)
        }
    }

    private fun source(
        vararg apis: MainAPI,
        installed: Int = apis.size,
        stillLoading: Boolean = false,
        keepOnRow: (HomePageList) -> HomePageList = { it },
        labels: (SearchResponse) -> String? = { null },
    ) = AndroidHomeSource(
        providers = { apis.toList() },
        installed = { installed },
        stillLoading = { stillLoading },
        keepOnRow = keepOnRow,
        labelsFor = { labels },
        io = Dispatchers.Unconfined,
    )

    private fun row(api: MainAPI, section: Int = 0, page: Int = 1, vararg more: MainAPI) = runBlocking {
        (source(api, *more).page(HomeSourceId(api.name), section, page) as HomePageReply.Loaded).rows.single()
    }

    @Test
    fun theOffersSayWhatIsThereAndWhetherMoreMayCome() = runBlocking {
        val found = source(Fake("One"), Fake("Two", sections = 3), installed = 5, stillLoading = true).sources()
        assertEquals(listOf("One", "Two"), found.options.map { it.name })
        assertEquals(listOf(2, 3), found.options.map { it.sections })
        assertEquals(5, found.installed)
        assertTrue(found.stillLoading)
    }

    @Test
    fun twoEntriesWithOneNameAreOneSource() = runBlocking {
        assertEquals(1, source(Fake("One"), Fake("One")).sources().options.size)
    }

    @Test
    fun aSourceThatAsksForOneSectionAtATimeSaysSo() = runBlocking {
        val api = Fake().apply {
            sequentialMainPage = true
            sequentialMainPageDelay = 1234
        }
        assertEquals(1234L, source(api).sources().options.single().sequentialDelayMillis)
        assertNull(source(Fake()).sources().options.single().sequentialDelayMillis)
    }

    @Test
    fun aSourceClaimingThousandsOfSectionsIsAskedForAFewOfThem() = runBlocking {
        assertEquals(64, source(Fake(sections = 100_000)).sources().options.single().sections)
    }

    @Test
    fun oneSectionOfOnePageIsAskedAndATitleOpensThroughTheSearchId() = runBlocking {
        val api = Fake { _, request -> newHomePageResponse(request, listOf(hit("One")), hasNext = true) }
        val reply = source(api).page(HomeSourceId("Fake"), 1, 3) as HomePageReply.Loaded
        assertEquals(listOf("1/3"), api.asked)
        assertTrue(reply.hasNext)
        val row = reply.rows.single()
        assertEquals("Section 1", row.title)
        assertEquals(
            SearchResultTarget("Fake", "https://example.com/One"),
            decodeId(SearchResultId(row.items.single().id)),
        )
    }

    /** `APIRepository.getMainPage` treats an index it cannot find as "every section". */
    @Test
    fun aSectionThatDoesNotExistIsRefusedAndDoesNotFanOut() = runBlocking {
        val api = Fake { _, request -> newHomePageResponse(request, listOf(hit("One"))) }
        val reply = source(api).page(HomeSourceId("Fake"), 99, 1) as HomePageReply.Failed
        assertEquals("BAD_SECTION · 99", reply.detail)
        assertTrue(api.asked.isEmpty())
    }

    @Test
    fun aSourceThatHasGoneIsAFailureThatNamesIt() = runBlocking {
        assertEquals("NOT_LOADED · Gone", (source().page(HomeSourceId("Gone"), 0, 1) as HomePageReply.Failed).detail)
    }

    @Test
    fun hostileTextIsCleanedAndAnUnusableTitleIsDropped() {
        val api = Fake { _, _ ->
            newHomePageResponse(
                HomePageList(
                    "‮gnp.exe\u0000 row\n",
                    listOf(hit("‮evil\u001B[2J\n"), hit("   "), hit("​"), hit("x".repeat(5000), url = "https://example.com/long")),
                ),
            )
        }
        val row = row(api)
        assertEquals("gnp.exe row", row.title)
        assertEquals(listOf("evil[2J", "x".repeat(200)), row.items.map { it.title })
    }

    @Test
    fun aRowWithNoNameTakesTheSources() {
        assertEquals("Fake", row(Fake { _, _ -> newHomePageResponse(HomePageList("  ", listOf(hit("One")))) }).title)
    }

    /** Coil would load every one of the refused ones if it were handed it. */
    @Test
    fun onlyAWebPosterReachesTheImageLoader() {
        val api = Fake { _, _ ->
            newHomePageResponse(
                HomePageList(
                    "R",
                    listOf(
                        hit("a", "https://example.com/a.jpg"),
                        hit("b", "HTTP://example.com/b.jpg"),
                        hit("c", "file:///data/data/com.lagradost.cloudstream3/shared_prefs/x.xml"),
                        hit("d", "content://com.lagradost.cloudstream3.provider/x"),
                        hit("e", "android.resource://com.lagradost.cloudstream3/1"),
                        hit("f", "javascript:alert(1)"),
                        hit("g", "https://"),
                        hit("h", "https://example.com/a\r\nX-Injected: 1"),
                        hit("i", "https://example.com/" + "a".repeat(5000)),
                    ),
                ),
            )
        }
        assertEquals(
            listOf("https://example.com/a.jpg", "HTTP://example.com/b.jpg", null, null, null, null, null, null, null),
            row(api).items.map { it.posterUrl },
        )
    }

    @Test
    fun aHeaderThatCouldSplitARequestIsDropped() {
        val api = Fake { _, _ ->
            newHomePageResponse(
                HomePageList(
                    "R",
                    listOf(
                        hit(
                            "a",
                            headers = mapOf(
                                "Referer" to "https://example.com/",
                                "X-Bad" to "value\r\nInjected: 1",
                                "Bad Name" to "value",
                                "" to "value",
                                "X-Long" to "x".repeat(2000),
                            ),
                        ),
                        hit("b", headers = mapOf("Bad Name" to "value")),
                        hit("c", headers = (1..50).associate { "X-$it" to "v" }),
                    ),
                ),
            )
        }
        val items = row(api).items
        assertEquals(mapOf("Referer" to "https://example.com/"), items[0].posterHeaders)
        assertNull("nothing left means none at all", items[1].posterHeaders)
        assertEquals(16, items[2].posterHeaders?.size)
    }

    /** It goes through a Bundle to reach the result page, and a Bundle has a size limit. */
    @Test
    fun aTitleWhoseAddressCannotReachItsPageIsDropped() {
        val api = Fake { _, _ ->
            newHomePageResponse(
                HomePageList("R", listOf(hit("a", url = ""), hit("b", url = "x".repeat(5000)), hit("c"))),
            )
        }
        assertEquals(listOf("c"), row(api).items.map { it.title })
    }

    @Test
    fun aHugePageIsBounded() {
        val api = Fake { _, _ ->
            newHomePageResponse(
                List(200) { rowIndex -> HomePageList("R$rowIndex", List(2000) { hit("t$rowIndex/$it") }) },
            )
        }
        val rows = runBlocking { (source(api).page(HomeSourceId("Fake"), 0, 1) as HomePageReply.Loaded).rows }
        assertEquals(64, rows.size)
        assertEquals(500, rows.first().items.size)
    }

    @Test
    fun theHideTheseQualitiesSettingIsApplied() {
        val api = Fake { _, _ -> newHomePageResponse(HomePageList("R", listOf(hit("keep"), hit("hide")))) }
        val filtered = runBlocking {
            source(api, keepOnRow = { list -> HomePageList(list.name, list.list.filter { it.name != "hide" }) })
                .page(HomeSourceId("Fake"), 0, 1) as HomePageReply.Loaded
        }
        assertEquals(listOf("keep"), filtered.rows.single().items.map { it.title })
    }

    @Test
    fun aBadgeIsCleanedAndBoundedAndNothingDrawsNone() {
        val api = Fake { _, _ -> newHomePageResponse(HomePageList("R", listOf(hit("a"), hit("b"), hit("c")))) }
        val labels = mapOf("a" to "‮4K", "b" to "y".repeat(100), "c" to " ")
        val items = runBlocking {
            (source(api, labels = { labels[it.name] }).page(HomeSourceId("Fake"), 0, 1) as HomePageReply.Loaded)
                .rows.single().items
        }
        assertEquals(listOf("4K", "y".repeat(16), null), items.map { it.badge })
    }

    @Test
    fun aProviderReturningNothingIsAnEmptyPageAndOneThatThrowsHasAReason() = runBlocking {
        val empty = source(Fake()).page(HomeSourceId("Fake"), 0, 1) as HomePageReply.Loaded
        assertTrue(empty.rows.isEmpty())
        assertFalse(empty.hasNext)

        val broken = Fake { _, _ -> throw IOException("HTTP 503\nat some.stack.Trace") }
        val failed = source(broken).page(HomeSourceId("Fake"), 0, 1) as HomePageReply.Failed
        assertTrue(failed.detail, "HTTP 503" in failed.detail)
        assertFalse(failed.detail, "Trace" in failed.detail)
    }

    /**
     * `BUG-61`: what a provider's failure says reaches the screen, so no address and no user path, however it is
     * disguised. An invisible character inside `://` or a path hides it from the redaction, and cleaning the text after
     * it would put it back together; a forged stack frame is not a message at all, so nothing upstream redacts it.
     */
    @Test
    fun aFailureShowsNoAddressTokenOrUserPathHoweverItIsHidden() = runBlocking {
        val forgedFrame = NullPointerException().apply {
            stackTrace = arrayOf(StackTraceElement("a.B", "c", "https://cdn.example/k?token=SECRET4 provider.kt", 7))
        }
        val hostile = listOf(
            IOException("Refused https://cdn.example/v/1.m3u8?token=SECRET0"),
            IOException("Refused https:​//cdn.example/v/1.m3u8?token=SECRET1"),
            IOException("Refused https:/\u0000/cdn.example/v/1.m3u8?token=SECRET2"),
            IOException("Refused https://cdn.example/v/1.m3u8\u0007?token=SECRET3"),
            IOException("Cannot open /Us​ers/someone/Movies/film.mkv: denied"),
            IOException("Cannot open fi​le:///storage/emulated/0/someone/film.mkv"),
            forgedFrame,
        )
        for (thrown in hostile) {
            val api = Fake { _, _ -> throw thrown }
            val failed = source(api).page(HomeSourceId("Fake"), 0, 1) as HomePageReply.Failed
            assertFalse(failed.detail, "SECRET" in failed.detail)
            assertFalse(failed.detail, "someone" in failed.detail)
            assertFalse(failed.detail, "/v/1.m3u8" in failed.detail)
            assertTrue(failed.detail, failed.detail.none { it.isISOControl() || it.category == CharCategory.FORMAT })
        }
        val kept = source(Fake { _, _ -> throw IOException("Refused https:​//cdn.example/a?token=SECRET") })
            .page(HomeSourceId("Fake"), 0, 1) as HomePageReply.Failed
        assertTrue("The reason and the host stay: ${kept.detail}", kept.detail.startsWith("Refused https://cdn.example"))
    }

    @Test
    fun aLostConnectionIsOffline() = runBlocking {
        val api = Fake { _, _ -> throw UnknownHostException("example.com") }
        assertEquals(HomePageReply.Offline, source(api).page(HomeSourceId("Fake"), 0, 1))
    }

    // ---- The hero (3c) -------------------------------------------------------------------------------------------

    /** A provider whose title pages answer with [page], or throw it. */
    private class Paged(name: String, private val page: suspend Paged.(String) -> LoadResponse?) : MainAPI() {
        override var name = name
        override var mainUrl = "https://example.com"
        override var lang = "en"
        override val hasMainPage = true
        override val supportedTypes = setOf(TvType.Movie)
        override val mainPage = listOf(MainPageData("Section", "0"))
        val loaded = mutableListOf<String>()

        override suspend fun load(url: String): LoadResponse? {
            loaded += url
            return page(url)
        }
    }

    private fun item(api: String, url: String) = HomeItem(encodeId(apiName = api, url = url).value, "ignored")

    @Test
    fun theHeroIsTheTitlesOwnPageWithEachFactCleaned() = runBlocking {
        val api = Paged("Hero One") { url ->
            newMovieLoadResponse("Dune\u202E", url, TvType.Movie, url) {
                posterUrl = "https://img.example/poster.jpg"
                backgroundPosterUrl = "content://evil/backdrop"
                posterHeaders = mapOf("Referer" to "https://example.com", "Bad\nName" to "x")
                year = 2021
                duration = 155
                tags = listOf("Sci-Fi", "Sci-Fi", "\u0000", "Drama", "War", "Epic")
                score = Score.from10(8.04)
                contentRating = "PG-13"
                plot = "<p>A <b>noble</b> family&nbsp;&amp; a desert\n\nplanet.</p>"
            }
        }
        val url = "https://example.com/dune"
        val featured = source(api).featured(item("Hero One", url))
        assertEquals(
            HomeFeatured(
                id = item("Hero One", url).id,
                title = "Dune",
                // A backdrop that is not http(s) never reaches the image loader; the poster stands in.
                backdropUrl = "https://img.example/poster.jpg",
                backdropHeaders = mapOf("Referer" to "https://example.com"),
                year = 2021,
                durationMinutes = 155,
                genres = listOf("Sci-Fi", "Drama", "War"),
                score = "8.0",
                contentRating = "PG-13",
                synopsis = "A noble family & a desert planet.",
            ),
            featured,
        )
        assertEquals(listOf(url), api.loaded)
    }

    @Test
    fun aTitleWhosePageFailsOrIsBlankOrWhoseSourceIsGoneHasNoHero() = runBlocking {
        val broken = Paged("Broken") { throw IOException("HTTP 503") }
        assertNull(source(broken).featured(item("Broken", "https://example.com/a")))

        val blank = Paged("Blank") { url -> newMovieLoadResponse("\u200B", url, TvType.Movie, url) }
        assertNull(source(blank).featured(item("Blank", "https://example.com/b")))

        val elsewhere = Paged("Here") { error("not asked") }
        assertNull(source(elsewhere).featured(item("Gone", "https://example.com/c")))
        assertTrue(elsewhere.loaded.isEmpty())

        assertNull(source(elsewhere).featured(HomeItem("not an id at all", "x")))
    }

    @Test
    fun factsThatAreNotThereStayNull() = runBlocking {
        val api = Paged("Sparse") { url -> newMovieLoadResponse("Bare", url, TvType.Movie, url) }
        val featured = source(api).featured(item("Sparse", "https://example.com/bare"))!!
        assertEquals(HomeFeatured(featured.id, "Bare"), featured)
    }
}

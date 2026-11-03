package com.lagradost.cloudstream4.home

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream4.feature.home.HomePageReply
import com.lagradost.cloudstream4.feature.home.HomeSourceId
import com.lagradost.cloudstream4.feature.search.SearchResultId
import com.lagradost.cloudstream4.search.DesktopSearchSource
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Over fakes handed in directly: nothing is registered in `APIHolder`, so no other test can see them. */
class DesktopHomeSourceTest {

    private class Fake(
        override var name: String = "Fake",
        override val hasMainPage: Boolean = true,
        sections: Int = 2,
        private val answer: suspend Fake.(Int, MainPageRequest) -> HomePageResponse? = { _, _ -> null },
    ) : MainAPI() {
        override var mainUrl = "https://example.com"
        override var lang = "en"
        override val supportedTypes = setOf(TvType.Movie)
        override val mainPage = List(sections) { MainPageData("Section $it", "$it") }
        var asked = mutableListOf<String>()

        fun hit(title: String, poster: String? = null, url: String = "https://example.com/$title"): SearchResponse =
            newMovieSearchResponse(title, url, TvType.Movie, fix = false) { posterUrl = poster }

        override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
            asked += request.data
            return answer(page, request)
        }
    }

    private fun source(vararg apis: MainAPI) = DesktopHomeSource { apis.toList() }

    @Test
    fun `only what has a main page is offered, and everything counts as installed`() = runBlocking {
        val found = source(Fake("Home"), Fake("SearchOnly", hasMainPage = false)).sources()
        assertEquals(listOf("Home"), found.options.map { it.name })
        assertEquals(2, found.installed)
        assertEquals(2, found.options.single().sections)
    }

    @Test
    fun `a source that asks for one section at a time says so`() = runBlocking {
        val api = Fake().apply {
            sequentialMainPage = true
            sequentialMainPageDelay = 1234
        }
        assertEquals(1234L, source(api).sources().options.single().sequentialDelayMillis)
        assertNull(source(Fake()).sources().options.single().sequentialDelayMillis)
    }

    @Test
    fun `one section is asked, not all of them, and a title opens through the detail flow`() = runBlocking {
        val api = Fake { _, request -> newHomePageResponse(request, listOf(hit("One")), hasNext = true) }
        val reply = assertIs<HomePageReply.Loaded>(source(api).page(HomeSourceId("Fake"), 1, 1))
        assertEquals(listOf("1"), api.asked)
        assertTrue(reply.hasNext)
        val row = reply.rows.single()
        assertEquals("Section 1", row.title)
        assertEquals(
            "Fake" to "https://example.com/One",
            DesktopSearchSource.decodeId(SearchResultId(row.items.single().id)),
        )
    }

    /** `APIRepository.getMainPage` treats an index it cannot find as "every section". */
    @Test
    fun `a section that does not exist is refused, and does not fan out`() = runBlocking {
        val api = Fake { _, request -> newHomePageResponse(request, listOf(hit("One"))) }
        val reply = assertIs<HomePageReply.Failed>(source(api).page(HomeSourceId("Fake"), 99, 1))
        assertEquals("BAD_SECTION · 99", reply.detail)
        assertTrue(api.asked.isEmpty())
    }

    @Test
    fun `a source that has gone is a failure that names it`() = runBlocking {
        val reply = assertIs<HomePageReply.Failed>(source().page(HomeSourceId("Gone"), 0, 1))
        assertEquals("NOT_LOADED · Gone", reply.detail)
    }

    @Test
    fun `hostile text is cleaned, and an unusable title is dropped`() = runBlocking {
        val api = Fake { _, _ ->
            newHomePageResponse(
                HomePageList(
                    "‮gnp.exe\u0000 row\n",
                    listOf(hit("‮evil\u001B[2J\n"), hit("   "), hit("​"), hit("x".repeat(5000))),
                ),
            )
        }
        val row = assertIs<HomePageReply.Loaded>(source(api).page(HomeSourceId("Fake"), 0, 1)).rows.single()
        assertEquals("gnp.exe row", row.title)
        assertEquals(listOf("evil[2J", "x".repeat(200)), row.items.map { it.title })
    }

    @Test
    fun `a row with no name takes the source's`() = runBlocking {
        val api = Fake { _, _ -> newHomePageResponse(HomePageList("  ", listOf(hit("One")))) }
        val row = assertIs<HomePageReply.Loaded>(source(api).page(HomeSourceId("Fake"), 0, 1)).rows.single()
        assertEquals("Fake", row.title)
    }

    @Test
    fun `only an http poster reaches the image path`() = runBlocking {
        val api = Fake { _, _ ->
            newHomePageResponse(
                HomePageList(
                    "R",
                    listOf(
                        hit("a", "https://example.com/a.jpg"),
                        hit("b", "HTTP://example.com/b.jpg"),
                        hit("c", "file:///etc/passwd"),
                        hit("d", "jar:file:/x!/y"),
                        hit("e", "javascript:alert(1)"),
                    ),
                ),
            )
        }
        val row = assertIs<HomePageReply.Loaded>(source(api).page(HomeSourceId("Fake"), 0, 1)).rows.single()
        assertEquals(
            listOf("https://example.com/a.jpg", "HTTP://example.com/b.jpg", null, null, null),
            row.items.map { it.posterUrl },
        )
    }

    @Test
    fun `a provider that returns nothing has an empty page, and one that throws has a reason`() = runBlocking {
        val empty = assertIs<HomePageReply.Loaded>(source(Fake()).page(HomeSourceId("Fake"), 0, 1))
        assertTrue(empty.rows.isEmpty())

        val broken = Fake { _, _ -> throw IOException("HTTP 503\nat some.stack.Trace") }
        val failed = assertIs<HomePageReply.Failed>(source(broken).page(HomeSourceId("Fake"), 0, 1))
        assertTrue("HTTP 503" in failed.detail, failed.detail)
        assertTrue("Trace" !in failed.detail, failed.detail)
    }

    /** The shape a sandboxed host reports a lost connection in: the class name, as text. */
    @Test
    fun `a host reporting a lost connection is offline`() = runBlocking {
        val api = Fake { _, _ -> throw IOException("UnknownHostException: example.com") }
        assertEquals(HomePageReply.Offline, source(api).page(HomeSourceId("Fake"), 0, 1))
    }
}

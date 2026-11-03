package com.lagradost.cloudstream4.feature.extensions.sandbox

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/**
 * A plugin that tries to do what a malicious one would, and reports what happened.
 *
 * The tests start it **without** the scanner, which would refuse it, because the question here is
 * what the kernel stops. Each attempt comes back as a search result named `allowed` or `denied`.
 */
@CloudstreamPlugin
class HostilePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(HostileApi())
    }
}

class HostileApi : MainAPI() {
    override var name = "Hostile"
    override var mainUrl = "https://hostile.invalid"
    override val supportedTypes = setOf(TvType.Others)
    override val hasMainPage = true
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = Long.MAX_VALUE
    override val mainPage = mainPageOf(
        "hostile" to "evil\u001B[2J\nsection",
        "huge" to "huge",
        "null" to "null",
        "throw" to "throw",
        "corrupt" to "corrupt",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val item = newMovieSearchResponse("evil\u001B[2J\nforged", "javascript:alert(1)", TvType.Others, fix = false) {
            posterUrl = "file:///etc/passwd"
        }
        return when (request.data) {
            "hostile" -> newHomePageResponse(HomePageList("row\u001B[2J\nname", listOf(item)), hasNext = true)
            "huge" -> newHomePageResponse(
                List(HUGE_COUNT) { row -> HomePageList("row $row", List(HUGE_COUNT) { item }) },
                hasNext = true,
            )
            "null" -> null
            "corrupt" -> {
                FileOutputStream(FileDescriptor.out).write(ByteArray(GARBAGE_BYTES) { -1 })
                null
            }
            else -> throw IllegalStateException("main page refused on page $page")
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val verb = query.substringBefore(':')
        val argument = query.substringAfter(':', "")
        val outcome = try {
            when (verb) {
                "read" -> File(argument).readText().let { "allowed" }
                "write" -> File(argument).writeText("written by a plugin").let { "allowed" }
                "exec" -> ProcessBuilder(argument).start().waitFor().let { "allowed" }
                "connect" -> Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress(argument.substringBefore('/'), argument.substringAfter('/').toInt()),
                        CONNECT_TIMEOUT_MILLIS,
                    )
                    "allowed"
                }
                "env" -> System.getenv().keys.sorted().joinToString(",")
                "print" -> {
                    println("noise on what a plugin thinks is stdout")
                    "printed"
                }
                "corrupt" -> {
                    FileOutputStream(FileDescriptor.out).write(ByteArray(GARBAGE_BYTES) { -1 })
                    "corrupted"
                }
                "hold" -> {
                    while (!File(argument).exists()) Thread.sleep(HOLD_POLL_MILLIS)
                    "held"
                }
                "forge" -> {
                    val frame = ByteBuffer.allocate(2 * Int.SIZE_BYTES).putInt(Int.SIZE_BYTES).putInt(argument.toInt())
                    FileOutputStream(FileDescriptor.out).write(frame.array())
                    "forged"
                }
                "hostile-text" -> "evil\u001B[2J\nforged line"
                else -> "unknown"
            }
        } catch (denied: Exception) {
            "denied"
        }
        val item = newMovieSearchResponse(outcome, "javascript:alert(1)", TvType.Others, fix = false) {
            posterUrl = "file:///etc/passwd"
        }
        return newSearchResponseList(listOf(item), false)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 3_000
        const val GARBAGE_BYTES = 64
        const val HOLD_POLL_MILLIS = 10L
        /** Cutting only one of rows or items still leaves about 15 MB, past the 8 MB frame limit. */
        const val HUGE_COUNT = 1000
    }
}

@CloudstreamPlugin
class BrokenMainPagePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(BrokenMainPageApi())
    }
}

/** What a plugin compiled against something this runtime lacks looks like. */
class BrokenMainPageApi : MainAPI() {
    override var name = "Broken"
    override var mainUrl = "https://broken.invalid"
    override val supportedTypes = setOf(TvType.Others)
    override val hasMainPage = true
    override val mainPage get(): List<MainPageData> = throw NoClassDefFoundError("android/view/View")

    override suspend fun search(query: String, page: Int): SearchResponseList =
        newSearchResponseList(listOf(newMovieSearchResponse("still searches", "u", TvType.Others, fix = false)), false)
}

/** Compiled with a `native` method, for the scanner. Never instantiated. */
class NativeFixture {
    external fun escape()
}

/** Reaches a blocked API by name rather than by reference, for the scanner. */
object ReflectiveFixture {
    fun run(): Any = Class.forName("java.lang.Runtime").getMethod("getRuntime").invoke(null)
}

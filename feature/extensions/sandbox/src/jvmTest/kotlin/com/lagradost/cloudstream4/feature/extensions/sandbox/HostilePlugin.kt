package com.lagradost.cloudstream4.feature.extensions.sandbox

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket

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
    }
}

/** Compiled with a `native` method, for the scanner. Never instantiated. */
class NativeFixture {
    external fun escape()
}

/** Reaches a blocked API by name rather than by reference, for the scanner. */
object ReflectiveFixture {
    fun run(): Any = Class.forName("java.lang.Runtime").getMethod("getRuntime").invoke(null)
}

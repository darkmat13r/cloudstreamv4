package com.lagradost.cloudstream4.media.download.testserver

import com.lagradost.cloudstream4.core.http.HttpClientFactory
import com.lagradost.cloudstream4.media.download.DownloadRequest
import com.lagradost.cloudstream4.media.download.SegmentedDownloader
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import kotlin.system.exitProcess

/**
 * The process [KillSuiteTest] kills. Arguments: `url folder fileName parts minPartBytes`.
 * Prints one `SNAP <bytesDone> <state>` line per snapshot and `FINAL <state> <failure>` at the end,
 * so the parent can pick its moment and can tell a killed run from one that simply finished.
 */
fun main(args: Array<String>) {
    val url = args[0]
    val folder = PlatformFile(File(args[1]))
    val name = args[2]
    val parts = args[3].toInt()
    val minPartBytes = args[4].toLong()
    val okHttp = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).build()
    val downloader = SegmentedDownloader(HttpClientFactory(okHttp).create())
    val request = DownloadRequest(url, folder, name, options = suiteOptions(parts = parts, minPartBytes = minPartBytes))
    var last: String = "none"
    runBlocking {
        downloader.download(request).collect { snapshot ->
            last = "${snapshot.state} ${snapshot.failure}"
            println("SNAP ${snapshot.bytesDone} ${snapshot.state}")
            System.out.flush()
        }
    }
    println("FINAL $last")
    System.out.flush()
    exitProcess(0)
}

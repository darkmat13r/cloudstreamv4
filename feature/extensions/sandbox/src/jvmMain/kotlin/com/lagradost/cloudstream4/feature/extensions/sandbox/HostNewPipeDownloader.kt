package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/**
 * The HTTP client NewPipe's extractor needs, for a plugin host.
 *
 * NewPipe has no default. `:app` installs `DownloaderTestImpl` from `CommonActivity.kt:252`, and with
 * nothing installed a provider built on it fails with `NullPointerException: downloader is null`
 * (measured: `YoutubeProvider`). That class is `:app`'s, so this restates it over the JDK's client
 * rather than reaching for OkHttp, which `:library` does not expose. The sandbox applies to it just
 * like everything else in the host.
 */
internal class HostNewPipeDownloader : Downloader() {

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .build()

    override fun execute(request: Request): Response {
        val body = request.dataToSend()
            ?.let { HttpRequest.BodyPublishers.ofByteArray(it) }
            ?: HttpRequest.BodyPublishers.noBody()
        val builder = HttpRequest.newBuilder(URI.create(request.url()))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .method(request.httpMethod(), body)
            .header(USER_AGENT_HEADER, USER_AGENT)
        for ((name, values) in request.headers()) {
            // The JDK client rejects these outright; it sets them itself.
            if (name.lowercase() in RESTRICTED_HEADERS) continue
            if (values.size == 1) builder.setHeader(name, values[0]) else values.forEach { builder.header(name, it) }
        }
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == TOO_MANY_REQUESTS) {
            throw ReCaptchaException("reCaptcha Challenge requested", request.url())
        }
        return Response(
            response.statusCode(),
            "",
            response.headers().map(),
            response.body(),
            response.uri().toString(),
        )
    }

    private companion object {
        const val TIMEOUT_SECONDS = 30L
        const val TOO_MANY_REQUESTS = 429
        const val USER_AGENT_HEADER = "User-Agent"

        /** The same string `:app` sends (`DownloaderTestImpl.kt`), which is what NewPipe was tested with. */
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/116.0.0.0 Safari/537.36"
        val RESTRICTED_HEADERS = setOf("connection", "content-length", "expect", "host", "upgrade")
    }
}

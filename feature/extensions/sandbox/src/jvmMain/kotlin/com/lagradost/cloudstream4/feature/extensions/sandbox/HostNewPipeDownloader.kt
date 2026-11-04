package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
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
 *
 * `HttpURLConnection` and not the JDK's newer `HttpClient`, which ignores a SOCKS proxy and so cannot pass through
 * [OutboundGate]: it connects directly, and the kernel refuses it (measured: YouTube's search failed).
 */
internal class HostNewPipeDownloader : Downloader() {

    override fun execute(request: Request): Response {
        val connection = URL(request.url()).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.requestMethod = request.httpMethod()
        connection.setRequestProperty(USER_AGENT_HEADER, USER_AGENT)
        for ((name, values) in request.headers()) {
            if (name.lowercase() in RESTRICTED_HEADERS) continue
            values.forEachIndexed { index, value ->
                if (index == 0) connection.setRequestProperty(name, value) else connection.addRequestProperty(name, value)
            }
        }
        request.dataToSend()?.let { body ->
            connection.doOutput = true
            connection.outputStream.use { it.write(body) }
        }
        val status = connection.responseCode
        if (status == TOO_MANY_REQUESTS) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
        val stream = if (status >= FIRST_ERROR_STATUS) connection.errorStream else connection.inputStream
        val body = stream?.use { it.readBytes() } ?: ByteArray(0)
        return Response(
            status,
            "",
            connection.headerFields.filterKeys { it != null },
            body.toString(charsetOf(connection.contentType)),
            connection.url.toString(),
        )
    }

    private fun charsetOf(contentType: String?): Charset =
        contentType?.substringAfter("charset=", "")?.substringBefore(';')?.trim()?.trim('"')
            ?.takeIf { it.isNotEmpty() }?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8

    private companion object {
        const val TIMEOUT_MILLIS = 30_000
        const val FIRST_ERROR_STATUS = 400
        const val TOO_MANY_REQUESTS = 429
        const val USER_AGENT_HEADER = "User-Agent"

        /** The same string `:app` sends (`DownloaderTestImpl.kt`), which is what NewPipe was tested with. */
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/116.0.0.0 Safari/537.36"
        val RESTRICTED_HEADERS = setOf("connection", "content-length", "expect", "host", "upgrade")
    }
}

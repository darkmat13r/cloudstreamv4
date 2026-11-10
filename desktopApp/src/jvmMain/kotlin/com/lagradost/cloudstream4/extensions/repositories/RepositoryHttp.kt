package com.lagradost.cloudstream4.extensions.repositories

import com.lagradost.cloudstream4.detail.boundedImageClient
import com.lagradost.cloudstream4.feature.extensions.sandbox.SandboxPolicy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody

/** Why a repository, or one of its files, could not be had. A kind, never a message: a message can carry a URL. */
enum class RepositoryFailure {
    /** The link, or a redirect, was not https. */
    NotHttps,

    /** The host is this computer or the local network: loopback, private, link-local and the rest `SandboxPolicy` refuses. */
    PrivateAddress,

    /** No answer, a timeout, or a broken connection. */
    Unreachable,

    /** More bytes than the cap for what was asked for. */
    TooLarge,

    /** An answer that was not a success: 404 and the like. */
    NotFound,

    /** More than [MAX_REDIRECTS] redirects. */
    TooManyRedirects,

    /** It answered, and what it sent is not a repository. */
    NotARepository,

    /** A repository in a format this build does not read. */
    UnsupportedFormat,
}

/** One fetch: the bytes, or why not. */
sealed interface Fetched {
    class Bytes(val bytes: ByteArray) : Fetched

    data class Failed(val failure: RepositoryFailure) : Fetched
}

/**
 * Fetches a repository's files for `KMP-110`, from this process: the same shape as the backdrop's client (`#299`,
 * [boundedImageClient]), because a repository is a third party naming URLs for this app to fetch, as a plugin's
 * artwork is.
 *
 * - **Public addresses only.** The name is filtered before any connection, and the address actually connected to is
 *   checked again before a request is sent, on every hop: an IP literal never meets [Dns], so the second check is the
 *   only one it gets. Both use `SandboxPolicy.isPublicAddress`.
 * - **https on every hop.** Redirects are followed here, not by OkHttp, so each `Location` is read before it is
 *   followed: an http one is [RepositoryFailure.NotHttps], and the sixth is [RepositoryFailure.TooManyRedirects]. The
 *   backdrop client's interceptor refusing a hop that is not https stays underneath.
 * - **Bounded.** A declared length past the cap is refused before the body is read, and a body that states none, or
 *   lies, fails once it passes the cap. The client's own cap, on the wire and once decompressed, sits a little above
 *   this one ([BACKSTOP_MARGIN_BYTES]), so that it is a backstop and the count here is what names the failure.
 * - **Timed.** A connect, a read and the whole call each have a limit. `#299` sets none, so a server that answers one
 *   byte a minute would hold a call open for ever.
 * - **No proxy.** A JVM proxy setting would send the request to the proxy by name, and the target would never be
 *   resolved here, so neither address check would see it.
 * - **Nothing kept.** No cookies, no cache.
 *
 * @param allow decides an address; replaceable so a test can use a server on this machine.
 * @param requireHttps false only for a test, which can serve plain http on this machine and nothing else. It turns off
 *   the client's own https interceptor, the backstop.
 * @param secure whether a URL, the first or a redirect's, may be asked for; https. Replaceable so a test can serve
 *   plain http and still see a redirect refused.
 * @param lookup the name lookup; replaceable so a test can make a public-looking name resolve to loopback.
 */
class RepositoryHttp(
    private val allow: (InetSocketAddress) -> Boolean = { SandboxPolicy.isPublicAddress(it.address) },
    private val requireHttps: Boolean = true,
    private val secure: (HttpUrl) -> Boolean = HttpUrl::isHttps,
    private val callTimeoutMillis: Long = JSON_CALL_TIMEOUT_MILLIS,
    private val lookup: Dns = Dns.SYSTEM,
) {
    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // A proxy would be asked for the target by name, so the target is never resolved here and neither
            // address check below ever sees it. No JVM proxy setting applies to this client.
            .proxy(Proxy.NO_PROXY)
            .dns(
                object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        lookup.lookup(hostname).filter { allow(InetSocketAddress(it, 0)) }
                            .ifEmpty { throw RefusedAddress() }
                }
            )
            .addNetworkInterceptor(
                Interceptor { chain ->
                    val remote = chain.connection()?.route()?.socketAddress
                    if (remote == null || !allow(remote)) throw RefusedAddress()
                    chain.proceed(chain.request())
                }
            )
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
            .build()
    }

    /** One client per cap, sharing [base]'s connections. */
    private val clients = ConcurrentHashMap<Long, OkHttpClient>()

    private fun client(maxBytes: Long): OkHttpClient = clients.getOrPut(maxBytes) {
        boundedImageClient(base, maxBytes = maxBytes + BACKSTOP_MARGIN_BYTES, requireHttps = requireHttps)
    }

    /**
     * [url]'s body, at most [maxBytes] of it, following at most [MAX_REDIRECTS] redirects, each checked first.
     *
     * @param progress told the bytes received so far and the length the server stated (null when it stated none), as
     *   they arrive (`KMP-110c`'s "0.7 MB of 1.2 MB"). Cancelling the calling coroutine cancels the call.
     */
    suspend fun fetch(url: String, maxBytes: Long, progress: (received: Long, stated: Long?) -> Unit = { _, _ -> }): Fetched =
        withContext(Dispatchers.IO) {
            val client = client(maxBytes)
            var current = url.toHttpUrlOrNull() ?: return@withContext Fetched.Failed(RepositoryFailure.NotFound)
            repeat(MAX_REDIRECTS + 1) {
                if (!secure(current)) return@withContext Fetched.Failed(RepositoryFailure.NotHttps)
                if (refusedLiteral(current)) return@withContext Fetched.Failed(RepositoryFailure.PrivateAddress)
                when (val hop = hop(client, current, maxBytes, progress)) {
                    is Hop.Done -> return@withContext hop.fetched
                    is Hop.Redirect -> current = hop.to
                }
            }
            Fetched.Failed(RepositoryFailure.TooManyRedirects)
        }

    /**
     * Whether [url] names a refused address as an IP literal. Checked before connecting: the connected-address check
     * would refuse it too, but only once a connection is open, and to a port nothing listens on there is no connection,
     * so the refusal would read as [RepositoryFailure.Unreachable]. Parsing a literal does not look anything up.
     */
    private fun refusedLiteral(url: HttpUrl): Boolean {
        val host = url.host
        if (!IPV4_LITERAL.matches(host) && ':' !in host) return false
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return true
        return !allow(InetSocketAddress(address, url.port))
    }

    private sealed interface Hop {
        class Done(val fetched: Fetched) : Hop

        class Redirect(val to: HttpUrl) : Hop
    }

    private suspend fun hop(client: OkHttpClient, url: HttpUrl, maxBytes: Long, progress: (Long, Long?) -> Unit): Hop {
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
        // `#345` review: cancelled means the socket closes now. The call below blocks its thread, so a handler run on
        // completion would wait for the whole body (up to the cap, or the call's time limit). This child is suspended
        // until the caller is cancelled, and closes the call the moment it is; the blocked read then fails at once.
        return coroutineScope {
            val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                blockingHop(call, url, maxBytes, progress)
            } finally {
                canceller.cancel()
            }
        }
    }

    private fun blockingHop(call: Call, url: HttpUrl, maxBytes: Long, progress: (Long, Long?) -> Unit): Hop {
        fun failed(failure: RepositoryFailure) = Hop.Done(Fetched.Failed(failure))
        return try {
            call.execute().use { response ->
                val body: ResponseBody = response.body ?: return@use failed(RepositoryFailure.NotFound)
                when {
                    response.code in REDIRECT_CODES ->
                        response.header("Location")?.let(url::resolve)?.let { Hop.Redirect(it) }
                            ?: failed(RepositoryFailure.NotFound)
                    !response.isSuccessful -> failed(RepositoryFailure.NotFound)
                    body.contentLength() > maxBytes -> {
                        call.cancel()
                        failed(RepositoryFailure.TooLarge)
                    }
                    else -> Hop.Done(
                        readAtMost(body.byteStream(), maxBytes, call::cancel) { received ->
                            progress(received, body.contentLength().takeIf { it >= 0 })
                        },
                    )
                }
            }
        } catch (_: RefusedAddress) {
            failed(RepositoryFailure.PrivateAddress)
        } catch (_: IOException) {
            // Timeouts (`InterruptedIOException`) included: to the person, both are "could not reach it".
            failed(RepositoryFailure.Unreachable)
        }
    }

    private fun readAtMost(stream: InputStream, maxBytes: Long, stop: () -> Unit, received: (Long) -> Unit): Fetched {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) {
                stop()
                return Fetched.Failed(RepositoryFailure.TooLarge)
            }
            out.write(buffer, 0, read)
            received(total)
        }
        return Fetched.Bytes(out.toByteArray())
    }

    /** Thrown by the address checks, so a refusal is told apart from a network failure without reading a message. */
    private class RefusedAddress : IOException()

    companion object {
        const val MAX_REDIRECTS = 5
        const val JSON_CALL_TIMEOUT_MILLIS = 30_000L
        const val PLUGIN_CALL_TIMEOUT_MILLIS = 120_000L
        private const val CONNECT_TIMEOUT_MILLIS = 10_000L
        private const val READ_TIMEOUT_MILLIS = 15_000L
        private const val BUFFER_BYTES = 8 * 1024

        /**
         * How far past the cap the client's own count may run. Its reads are buffered ahead of this one's, by up to a
         * segment, so with no margin it would fail first and a body too large would read as a broken connection.
         */
        private const val BACKSTOP_MARGIN_BYTES = 64L * 1024
        private const val USER_AGENT = "CloudStream"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val IPV4_LITERAL = Regex("[0-9.]+")
    }
}

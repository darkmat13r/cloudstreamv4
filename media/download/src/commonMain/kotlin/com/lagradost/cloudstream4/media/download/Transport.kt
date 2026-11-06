package com.lagradost.cloudstream4.media.download

import com.lagradost.cloudstream4.media.model.DownloadFailure
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

private const val CLEANUP_GRACE_MILLIS = 2_000L

private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

/**
 * One GET at a time, with the redirects followed here so every hop goes back through [UrlGuard]
 * (rule 11). [client] must have `followRedirects = false` and `expectSuccess = false`.
 *
 * `Authorization` and `Cookie` are dropped when a hop leaves the origin the download started at. The
 * redirect target is never remembered: every request starts again at [startUrl], so a token that is
 * good for one request still works.
 */
internal class Transport(
    private val client: HttpClient,
    private val guard: UrlGuard,
    private val startUrl: String,
    private val callerHeaders: Map<String, String>,
    private val maxRedirects: Int,
) {
    private sealed interface Step<out T> {
        class Done<T>(val value: T) : Step<T>
        class Redirect(val status: Int, val location: String?) : Step<Nothing>
    }

    /**
     * Runs [handler] on the final response, inside the call, so the body can be streamed. Throws
     * [FatalFailure] for a refused address or an unusable redirect and [TransientFailure] for anything the
     * network did. Whatever [handler] throws that is an [EngineException] passes through.
     */
    suspend fun <T> get(
        rangeStart: Long?,
        rangeEndInclusive: Long?,
        ifRange: String?,
        handler: suspend (HttpResponse) -> T,
    ): T {
        var current = startUrl
        var hops = 0
        var firstOrigin: String? = null
        while (true) {
            val target = when (val check = guard.check(current)) {
                is UrlCheck.Allowed -> check.url
                UrlCheck.Refused -> throw FatalFailure(DownloadFailure.AddressRefused)
                UrlCheck.ResolveFailed -> throw TransientFailure(DownloadFailure.NetworkLost)
            }
            val origin = originOf(target)
            val sameOrigin = firstOrigin == null || firstOrigin == origin
            if (firstOrigin == null) firstOrigin = origin

            val step: Step<T> = try {
                executeBounded(target, rangeStart, rangeEndInclusive, ifRange, sameOrigin, handler)
            } catch (e: EngineException) {
                throw e
            } catch (e: CancellationException) {
                // Ours, or a transport that reports a dropped connection as a cancellation?
                currentCoroutineContext().ensureActive()
                throw TransientFailure(DownloadFailure.NetworkLost)
            } catch (_: Throwable) {
                throw TransientFailure(DownloadFailure.NetworkLost)
            }

            when (step) {
                is Step.Done -> return step.value
                is Step.Redirect -> {
                    if (++hops > maxRedirects) throw FatalFailure(DownloadFailure.ServerRefused(step.status))
                    val location = step.location ?: throw FatalFailure(DownloadFailure.ServerRefused(step.status))
                    current = (resolveRedirect(target, location) ?: throw FatalFailure(DownloadFailure.ServerRefused(step.status))).toString()
                }
            }
        }
    }


    /**
     * Runs one request and returns as soon as [handler] has, without waiting indefinitely for Ktor to
     * release the call. `execute` joins the call's job after the handler returns, and a body that was
     * not read to its end (a part clipped by a split) has been seen to leave that join waiting forever,
     * with the part marked active and every byte already on disk. The call is given [CLEANUP_GRACE_MILLIS]
     * to finish and is then cancelled, which abandons the connection.
     */
    private suspend fun <T> executeBounded(
        target: Url,
        rangeStart: Long?,
        rangeEndInclusive: Long?,
        ifRange: String?,
        sameOrigin: Boolean,
        handler: suspend (HttpResponse) -> T,
    ): Step<T> = coroutineScope {
        val outcome = CompletableDeferred<Step<T>>()
        val call = async {
            try {
                client.prepareRequest {
                    method = HttpMethod.Get
                    url(target)
                    for ((name, value) in callerHeaders) {
                        if (!sameOrigin && (name.equals("authorization", ignoreCase = true) || name.equals("cookie", ignoreCase = true))) continue
                        header(name, value)
                    }
                    header(HttpHeaders.AcceptEncoding, "identity")
                    if (rangeStart != null && rangeEndInclusive != null) header(HttpHeaders.Range, "bytes=$rangeStart-$rangeEndInclusive")
                    if (ifRange != null) header(HttpHeaders.IfRange, ifRange)
                }.execute { response ->
                    val status = response.status.value
                    val step: Step<T> = if (status in REDIRECT_STATUSES) {
                        Step.Redirect(status, response.headers[HttpHeaders.Location])
                    } else {
                        Step.Done(handler(response))
                    }
                    outcome.complete(step)
                    step
                }
            } catch (e: CancellationException) {
                outcome.completeExceptionally(e)
                throw e
            } catch (e: Throwable) {
                // Before the handler finished this is the request's failure; afterwards nobody is listening.
                outcome.completeExceptionally(e)
            }
            Unit
        }
        val step = outcome.await()
        if (withTimeoutOrNull(CLEANUP_GRACE_MILLIS) { call.join() } == null) call.cancel()
        step
    }

    private fun originOf(url: Url): String = "${url.protocol.name}://${url.host}:${url.port}"
}

/** The part of a response's headers that says which version of the file it is. */
internal class Validators(val etag: ETag?, val modified: String) {
    val hasIdentity: Boolean get() = etag != null || modified.isNotEmpty()

    /** What goes in `If-Range`: a strong ETag, else Last-Modified, else nothing (a weak tag is compared, never sent). */
    val ifRange: String? get() = if (etag != null && etag.strong) etag.raw else modified.ifEmpty { null }

    /** Both sides name a version and the names differ. A side that says nothing cannot contradict. */
    fun conflictsWith(other: Validators): Boolean {
        if (etag != null && other.etag != null && !etag.weaklyMatches(other.etag)) return true
        if (modified.isNotEmpty() && other.modified.isNotEmpty() && modified != other.modified) return true
        return false
    }

    companion object {
        val NONE = Validators(null, "")

        fun of(response: HttpResponse): Validators = Validators(
            etag = parseETag(response.headers[HttpHeaders.ETag]),
            modified = storableValidator(response.headers[HttpHeaders.LastModified], StateFile.MAX_MODIFIED),
        )

        fun fromState(etag: String, modified: String): Validators =
            Validators(parseETag(etag.ifEmpty { null }), modified)
    }
}

internal fun HttpResponse.declaredLength(): Long? =
    headers[HttpHeaders.ContentLength]?.trim()?.toLongOrNull()?.takeIf { it >= 0L }

/** Non-identity `Content-Encoding`: the bytes are not the file's (rule 6). */
internal fun HttpResponse.requireIdentityEncoding() {
    val encoding = headers[HttpHeaders.ContentEncoding]?.trim()?.lowercase()
    if (!encoding.isNullOrEmpty() && encoding != "identity") {
        throw FatalFailure(DownloadFailure.ServerRefused(status.value))
    }
}

internal fun HttpResponse.looksLikeHtml(): Boolean {
    val type = headers[HttpHeaders.ContentType]?.trim()?.lowercase() ?: return false
    return type.startsWith("text/html") || type.startsWith("application/xhtml")
}

/** What a non-2xx status means (rule 8). Never returns: the answer is the exception. */
internal fun statusFailure(response: HttpResponse, backoffCapMillis: Long): EngineException {
    val status = response.status.value
    return when {
        status == 408 || status == 425 || status == 429 || status in 500..599 -> {
            val seconds = parseRetryAfterSeconds(response.headers[HttpHeaders.RetryAfter], 3600L)
            TransientFailure(
                failure = DownloadFailure.ServerRefused(status),
                retryAfterMillis = seconds?.times(1000L)?.coerceAtMost(backoffCapMillis),
                throttled = status == 429 || status == 503,
            )
        }
        else -> FatalFailure(DownloadFailure.ServerRefused(status))
    }
}

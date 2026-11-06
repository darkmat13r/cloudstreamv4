package com.lagradost.cloudstream4.media.download

import com.lagradost.cloudstream4.media.model.DownloadFailure
import kotlinx.coroutines.delay

/** What the server said about the file, from one `Range: bytes=0-0` request (rule 10). */
internal sealed interface ProbeResult {
    /** Ranges work and the size is known. */
    class Ranged(val total: Long, val validators: Validators) : ProbeResult

    /** A plain stream: no ranges, or no length. [length] is `null` when undeclared. */
    class Single(val length: Long?, val validators: Validators) : ProbeResult

    /** A file with no bytes. */
    data object Empty : ProbeResult
}

/**
 * One probe attempt. HEAD is not used because it lies; the body is never read, and closing the
 * response discards the connection. Throws [TransientFailure] when the answer says to try again, which
 * the caller retries and never reads as "no range support" (rule 17).
 */
internal suspend fun probe(transport: Transport, options: DownloadOptions): ProbeResult =
    transport.get(0L, 0L, null) { response ->
        val status = response.status.value
        when {
            status == 206 -> {
                response.requireIdentityEncoding()
                if (options.rejectHtml && response.looksLikeHtml()) throw FatalFailure(DownloadFailure.NoUsableLink)
                val range = parseContentRange(response.headers["Content-Range"])
                    ?: throw FatalFailure(DownloadFailure.ServerRefused(206))
                if (range.start != 0L) throw FatalFailure(DownloadFailure.ServerRefused(206))
                response.declaredLength()?.let { if (it != (range.endInclusive ?: 0L) + 1L) throw FatalFailure(DownloadFailure.ServerRefused(206)) }
                val validators = Validators.of(response)
                val total = range.total
                if (total == null) ProbeResult.Single(null, validators) else ProbeResult.Ranged(total, validators)
            }
            status == 200 -> {
                response.requireIdentityEncoding()
                if (options.rejectHtml && response.looksLikeHtml()) throw FatalFailure(DownloadFailure.NoUsableLink)
                val length = response.declaredLength()
                if (length == 0L) ProbeResult.Empty else ProbeResult.Single(length, Validators.of(response))
            }
            status == 204 -> ProbeResult.Empty
            status == 416 -> {
                val range = parseContentRange(response.headers["Content-Range"])
                if (range != null && range.start == null && range.total == 0L) {
                    ProbeResult.Empty
                } else {
                    ProbeResult.Single(null, Validators.NONE)
                }
            }
            status in 200..299 -> ProbeResult.Single(response.declaredLength(), Validators.of(response))
            else -> throw statusFailure(response, options.backoffMaxMillis)
        }
    }

/** Retries a probe on [TransientFailure] with backoff; when the attempts run out the failure is final. Touches nothing on disk. */
internal suspend fun probeWithRetries(transport: Transport, options: DownloadOptions): ProbeResult {
    var failures = 0
    while (true) {
        try {
            return probe(transport, options)
        } catch (t: TransientFailure) {
            failures++
            if (failures >= options.maxAttemptsPerPart) throw FatalFailure(t.failure)
            delay(backoffMillis(failures, t.retryAfterMillis, options))
        }
    }
}

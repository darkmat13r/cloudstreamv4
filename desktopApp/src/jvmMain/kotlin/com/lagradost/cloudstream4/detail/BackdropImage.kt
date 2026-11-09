package com.lagradost.cloudstream4.detail

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.SkiaImageDecoder
import coil3.disk.DiskCache
import coil3.fetch.SourceFetchResult
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale
import coil3.toBitmap
import com.lagradost.cloudstream4.feature.extensions.sandbox.SandboxPolicy
import com.lagradost.cloudstream4.search.posterHttpClient
import java.io.IOException
import java.net.InetSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data

/**
 * A title page's backdrop (`KMP-100`), as wide as the header and cropped to it. Nothing is drawn until it arrives, and
 * nothing if it fails: the header keeps its plain ground.
 *
 * The URL has passed the sandbox's checks (`httpsUrlOrNull`). This loader adds what a poster's does not have yet:
 * - **https on every hop.** A redirect from https to http is not followed, and a network interceptor refuses any hop
 *   that is not https, so the URL's scheme cannot be stepped down after the check.
 * - **A bounded decode.** The image is asked for at the size it is drawn, so the bitmap kept is no larger than the
 *   header. Before that, [BoundedDecoder] reads the size the image claims from its header, without decoding a pixel,
 *   and refuses one past [BACKDROP_MAX_PIXELS] or [BACKDROP_MAX_BYTES]: Coil's desktop decoder draws the whole image
 *   before scaling it, so a small file claiming 20000 by 20000 would otherwise cost 1.6 GB on the way.
 */
@Composable
internal fun BackdropImage(url: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight || width <= 0 || height <= 0) return@BoxWithConstraints
        val image by produceState<ImageBitmap?>(initialValue = null, url, width, height) {
            value = withContext(Dispatchers.IO) { loadBackdrop(url, width, height) }
        }
        image?.let { Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/** [url] decoded to cover [width] by [height] pixels, or null when it cannot be had within the bounds. */
internal suspend fun loadBackdrop(url: String, width: Int, height: Int, loader: ImageLoader = backdropLoader): ImageBitmap? =
    (loader.execute(backdropRequest(url, width, height)) as? SuccessResult)?.image?.toBitmap()?.asComposeImageBitmap()

/** The request [loadBackdrop] makes: [url], drawn [width] by [height]. */
internal fun backdropRequest(url: String, width: Int, height: Int): ImageRequest = ImageRequest.Builder(PlatformContext.INSTANCE)
    .data(url)
    .size(width, height)
    .scale(Scale.FILL)
    .precision(Precision.INEXACT)
    .build()

/**
 * The poster's client (public addresses only, on every hop), with https required on every hop as well, and no body read
 * past [maxBytes].
 *
 * The byte cap is here, on the wire, because Coil fetches a whole body (to its disk cache, or to memory) before any
 * decoder runs: [BoundedDecoder]'s own byte check comes too late to stop a server sending 600 MiB. A declared length past
 * the cap is refused before a byte of the body is read; a body with no length, or one that lies, fails once it passes the
 * cap.
 */
internal fun backdropHttpClient(
    allow: (InetSocketAddress) -> Boolean = { SandboxPolicy.isPublicAddress(it.address) },
    maxBytes: Long = BACKDROP_MAX_BYTES,
): OkHttpClient = posterHttpClient(allow).newBuilder()
    .followSslRedirects(false)
    .addNetworkInterceptor(
        Interceptor { chain ->
            if (!chain.request().isHttps) throw IOException("backdrop refused: not https")
            chain.proceed(chain.request())
        }
    )
    .build()
    .let { client -> withBodyCap(client, maxBytes) }

/** [client], with every hop's body capped at [maxBytes]: [backdropHttpClient]'s cap, apart, so a test can serve http. */
internal fun withBodyCap(client: OkHttpClient, maxBytes: Long): OkHttpClient = client.newBuilder()
    .addNetworkInterceptor(Interceptor { chain ->
        val call = chain.call()
        chain.proceed(chain.request()).capped(maxBytes, stop = call::cancel)
    })
    .build()

/**
 * This response, with a body that fails on its first read if it says it is longer than [maxBytes], and once more than
 * that is read from it if it does not.
 *
 * The refusal is made when the body is read, not here: Coil drops the failure of a call that was cancelled, so a call
 * cancelled and failed inside the chain leaves its image loading for ever (measured: the test hung for hours).
 *
 * A refusal, or a body closed before its end, calls [stop] first, which cancels the call and so closes its socket.
 * Closing the response alone is not enough: OkHttp reads what is left of a body it is asked to close, to keep the
 * connection, for up to 100 ms, and on a fast link that is tens of megabytes (measured: all 32 MiB of a test body).
 */
internal fun Response.capped(maxBytes: Long, stop: () -> Unit): Response {
    val body: ResponseBody = body ?: return this
    return newBuilder().body(CappedBody(body, maxBytes, stop)).build()
}

/** [body], read through a count that fails past [maxBytes]: a chunked body states no length to refuse up front. */
private class CappedBody(
    private val body: ResponseBody,
    private val maxBytes: Long,
    private val stop: () -> Unit,
) : ResponseBody() {
    private var ended = false

    private fun refuse(): Nothing {
        stop()
        throw IOException("backdrop refused: past $maxBytes bytes")
    }

    private val capped: BufferedSource by lazy {
        object : ForwardingSource(body.source()) {
            private var read = 0L

            override fun read(sink: Buffer, byteCount: Long): Long {
                if (body.contentLength() > maxBytes) refuse()
                val count = super.read(sink, byteCount)
                if (count == -1L) ended = true
                if (count > 0) {
                    read += count
                    if (read > maxBytes) refuse()
                }
                return count
            }
        }.buffer()
    }

    override fun contentType(): MediaType? = body.contentType()

    override fun contentLength(): Long = body.contentLength()

    override fun source(): BufferedSource = capped

    override fun close() {
        if (!ended) stop()
        body.close()
    }
}

/**
 * Coil with [client], and every image through [BoundedDecoder] before Skia sees it.
 *
 * @param diskCache null for Coil's own: the one the poster's loader shares, under the temporary directory, files named
 *   by a SHA-256 of the URL, readable by this user's other processes (0644), the response headers stored with each body.
 */
internal fun backdropImageLoader(
    client: OkHttpClient,
    decoder: BoundedDecoder.Factory = BoundedDecoder.Factory(),
    diskCache: (() -> DiskCache)? = null,
): ImageLoader = ImageLoader.Builder(PlatformContext.INSTANCE)
    .components {
        add(OkHttpNetworkFetcherFactory(callFactory = { client }))
        add(decoder)
    }
    .apply { if (diskCache != null) diskCache(diskCache) }
    .build()

private val backdropLoader: ImageLoader by lazy { backdropImageLoader(backdropHttpClient()) }

/**
 * Refuses an image whose file is past [maxBytes] or whose header claims more than [maxPixels], then hands the same
 * bytes to Coil's own Skia decoder. The size is read from the header by Skia's codec, which decodes no pixels for it.
 */
internal class BoundedDecoder(
    private val result: SourceFetchResult,
    private val options: Options,
    private val imageLoader: ImageLoader,
    private val maxBytes: Long,
    private val maxPixels: Long,
    private val then: Decoder.Factory,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val peek = result.source.source().peek()
        if (peek.request(maxBytes + 1)) throw IOException("backdrop refused: past $maxBytes bytes")
        val bytes = peek.readByteArray()
        val (width, height) = Codec.makeFromData(Data.makeFromBytes(bytes)).use { it.width.toLong() to it.height.toLong() }
        if (width <= 0 || height <= 0 || width * height > maxPixels) throw IOException("backdrop refused: past its pixels")
        val decoder = checkNotNull(then.create(result, options, imageLoader)) { "backdrop: no decoder" }
        return checkNotNull(decoder.decode()) { "backdrop: nothing decoded" }
    }

    /** @param then the decoder the bytes go to once they pass: Coil's own Skia decoder, or a test's watch on it. */
    class Factory(
        private val maxBytes: Long = BACKDROP_MAX_BYTES,
        private val maxPixels: Long = BACKDROP_MAX_PIXELS,
        private val then: Decoder.Factory = SkiaImageDecoder.Factory(),
    ) : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder =
            BoundedDecoder(result, options, imageLoader, maxBytes, maxPixels, then)
    }
}

/** 8 MB: a large backdrop is a few hundred kilobytes, a 4K photograph a few megabytes. */
internal const val BACKDROP_MAX_BYTES: Long = 8L * 1024 * 1024

/** 4096 by 4096, Coil's own ceiling for a bitmap: 64 MB at four bytes a pixel, the most one decode may cost. */
internal const val BACKDROP_MAX_PIXELS: Long = 4096L * 4096

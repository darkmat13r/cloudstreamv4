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
import okhttp3.OkHttpClient
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
internal suspend fun loadBackdrop(url: String, width: Int, height: Int, loader: ImageLoader = backdropLoader): ImageBitmap? {
    val request = ImageRequest.Builder(PlatformContext.INSTANCE)
        .data(url)
        .size(width, height)
        .scale(Scale.FILL)
        .precision(Precision.INEXACT)
        .build()
    return (loader.execute(request) as? SuccessResult)?.image?.toBitmap()?.asComposeImageBitmap()
}

/** The poster's client (public addresses only, on every hop), with https required on every hop as well. */
internal fun backdropHttpClient(
    allow: (InetSocketAddress) -> Boolean = { SandboxPolicy.isPublicAddress(it.address) },
): OkHttpClient = posterHttpClient(allow).newBuilder()
    .followSslRedirects(false)
    .addNetworkInterceptor(
        Interceptor { chain ->
            if (!chain.request().isHttps) throw IOException("backdrop refused: not https")
            chain.proceed(chain.request())
        }
    )
    .build()

/** Coil with [client], and every image through [BoundedDecoder] before Skia sees it. */
internal fun backdropImageLoader(
    client: OkHttpClient,
    decoder: BoundedDecoder.Factory = BoundedDecoder.Factory(),
): ImageLoader = ImageLoader.Builder(PlatformContext.INSTANCE)
    .components {
        add(OkHttpNetworkFetcherFactory(callFactory = { client }))
        add(decoder)
    }
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

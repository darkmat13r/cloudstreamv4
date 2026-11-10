package com.lagradost.cloudstream4.detail

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil3.ImageLoader
import coil3.decode.Decoder
import coil3.decode.SkiaImageDecoder
import coil3.disk.DiskCache
import java.net.InetSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import com.lagradost.cloudstream4.feature.extensions.sandbox.SandboxPolicy
import com.lagradost.cloudstream4.search.posterHttpClient

/**
 * A cast photo or a recommendation's poster on a title's page (`KMP-104`), bounded more tightly than the backdrop
 * (`KMP-103`'s condition 5): there can be 44 of them on one page, all from hosts a plugin chose.
 *
 * - **Only while on screen.** The page composes a line of people or cards only when it is in the window, and nothing is
 *   asked for until this is composed, so a host learns this Mac's address only for what is shown (condition 6).
 * - **At the size it is drawn.** The request is for the box's own pixels, [coil3.size.Precision.INEXACT], so the bitmap
 *   kept is about the box, never the file's size.
 * - **A lower decode cap,** [THUMBNAIL_MAX_PIXELS] (1024 by 1024), and **a smaller byte cap,** [THUMBNAIL_MAX_BYTES]
 *   (2 MB), both checked before Skia decodes a pixel, by `KMP-100`'s [BoundedDecoder] and body cap.
 * - **The same client and cache as backdrops:** https on every hop, public addresses re-checked on connect, and Coil's
 *   shared disk cache, `KMP-100`'s decision (the PM, 2026-11-10), which `KMP-102` will change for all three kinds at once.
 *
 * Nothing is drawn until it arrives, and nothing if it fails: the page's own ground, or a person's initials, stay.
 */
@Composable
internal fun ThumbnailImage(url: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight || width <= 0 || height <= 0) return@BoxWithConstraints
        val image by produceState<ImageBitmap?>(initialValue = null, url, width, height) {
            value = withContext(Dispatchers.IO) { loadBackdrop(url, width, height, thumbnailLoader) }
        }
        image?.let { Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/** The backdrop's client with [THUMBNAIL_MAX_BYTES] in place of its cap. */
internal fun thumbnailHttpClient(
    allow: (InetSocketAddress) -> Boolean = { SandboxPolicy.isPublicAddress(it.address) },
): OkHttpClient = boundedImageClient(posterHttpClient(allow), maxBytes = THUMBNAIL_MAX_BYTES)

/**
 * The backdrop's loader, with the thumbnail's client and decode caps.
 *
 * @param then and [diskCache] for a test only: a watch on the decoder, and a cache of its own.
 */
internal fun thumbnailImageLoader(
    client: OkHttpClient = thumbnailHttpClient(),
    then: Decoder.Factory = SkiaImageDecoder.Factory(),
    diskCache: (() -> DiskCache)? = null,
): ImageLoader = backdropImageLoader(
    client,
    BoundedDecoder.Factory(maxBytes = THUMBNAIL_MAX_BYTES, maxPixels = THUMBNAIL_MAX_PIXELS, then = then),
    diskCache,
)

private val thumbnailLoader: ImageLoader by lazy { thumbnailImageLoader() }

/** 2 MB: a headshot or a poster is tens of kilobytes; a large one a few hundred. */
internal const val THUMBNAIL_MAX_BYTES: Long = 2L * 1024 * 1024

/** 1024 by 1024: 4 MB at four bytes a pixel, the most one decode may cost, against the backdrop's 64 MB. */
internal const val THUMBNAIL_MAX_PIXELS: Long = 1024L * 1024

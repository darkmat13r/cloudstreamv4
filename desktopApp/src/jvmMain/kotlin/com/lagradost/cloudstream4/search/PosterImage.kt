package com.lagradost.cloudstream4.search

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A result's poster, through Coil's loader rather than its `AsyncImage`.
 *
 * `AsyncImage` draws nothing here (measured, offscreen): desktop has no `Dispatchers.Main`, because
 * `kotlinx-coroutines-swing` is not on the classpath (`Main.kt`), and adding it is a dependency
 * decision. `ImageLoader.execute` does not need one (measured: `SuccessResult` with Main missing), and
 * it keeps Coil's caching. A failed poster is the card's own placeholder, not an error state.
 */
@Composable
internal fun PosterImage(url: String?, modifier: Modifier = Modifier) {
    val poster by produceState<ImageBitmap?>(initialValue = null, url) {
        val model = url ?: return@produceState
        value = withContext(Dispatchers.IO) {
            val context = PlatformContext.INSTANCE
            val request = ImageRequest.Builder(context).data(model).build()
            val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult
            result?.image?.toBitmap()?.asComposeImageBitmap()
        }
    }
    poster?.let { image ->
        Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    }
}

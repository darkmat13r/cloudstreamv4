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
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import kotlinx.coroutines.withContext
import com.lagradost.cloudstream4.detail.extensionImageLoader
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import com.lagradost.cloudstream4.feature.extensions.sandbox.SandboxPolicy

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
            val result = posterLoader.execute(request) as? SuccessResult
            result?.image?.toBitmap()?.asComposeImageBitmap()
        }
    }
    poster?.let { image ->
        Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    }
}

/**
 * The backdrop's loader (`KMP-102`): a poster is fetched and decoded in this process as a backdrop is, so it meets the
 * same bounds: https on every hop; no more than 8 MB and one read taken of a body, on the wire or once decompressed; no
 * image past 4096 by 4096 decoded. [posterHttpClient] stays as it is: downloads and subtitles use it, and their bodies
 * are not images.
 */
internal val posterLoader: ImageLoader get() = extensionImageLoader

/**
 * The client that fetches plugin-supplied poster URLs, from this process and so outside the sandbox.
 *
 * Without this, a plugin could reach the user's local network by naming it as a poster, either with
 * a public name resolving to a private address or a public URL redirecting to one.
 *
 * - The [Dns] filter stops a name before any connection is made.
 * - The network interceptor checks the address actually connected to, before a request is sent. It
 *   runs once per redirect hop, and it is the only check an IP literal meets, since OkHttp does not
 *   call [Dns] for one. So a redirect to a private IP literal opens a TCP connection and sends nothing.
 * - No proxy (`BUG-88`). A proxy is asked for the target by name, so the target would never be resolved here, and
 *   both checks above would see only the proxy. No JVM proxy setting (`http(s).proxyHost`, `useSystemProxies`, a
 *   default `ProxySelector`) applies to this client, nor to the image, subtitle and download clients built on it.
 *
 * @param allow decides a connected address; replaceable so a test can use a loopback server.
 */
internal fun posterHttpClient(
    allow: (InetSocketAddress) -> Boolean = { SandboxPolicy.isPublicAddress(it.address) },
): OkHttpClient = OkHttpClient.Builder()
    .proxy(Proxy.NO_PROXY)
    .dns(
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                Dns.SYSTEM.lookup(hostname).filter { address -> allow(InetSocketAddress(address, 0)) }
                    .ifEmpty { throw IOException("poster host has no public address") }
        }
    )
    .addNetworkInterceptor(
        Interceptor { chain ->
            val remote = chain.connection()?.route()?.socketAddress
            if (remote == null || !allow(remote)) throw IOException("poster refused: not a public address")
            chain.proceed(chain.request())
        }
    )
    .build()

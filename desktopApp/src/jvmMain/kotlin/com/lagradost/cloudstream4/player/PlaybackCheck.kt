package com.lagradost.cloudstream4.player

import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream4.extensions.SandboxedExtensions
import com.lagradost.cloudstream4.feature.extensions.sandbox.RemoteLink
import com.lagradost.cloudstream4.feature.extensions.sandbox.SandboxPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URI

/**
 * `--check-playback <url> [seconds]`, or `--check-playback plugin:<provider>:<query> [seconds]`, which
 * plays the first link that provider returns. Either way through the real sandboxed host, no window.
 *
 * Exists because the window cannot be driven from a locked session, and frames arriving is the one
 * thing that shows the host's policy still lets libVLC work. Prints counts and status, never a URL:
 * a plugin's stream links can carry tokens.
 *
 * A local file, by path or `file://` URL, is copied into the host's own directory first ([VlcPlayer.stage]), as
 * `--check-subtitle-render` does: the sandboxed host can read nothing outside it, so the file as named would never
 * play (`BUG-76`).
 *
 * @return 0 when frames arrived and the stream did not fail.
 */
internal fun runPlaybackCheck(target: String, seconds: Int): Int {
    if (!target.startsWith(PLUGIN_PREFIX)) return play(target, null, null, null, seconds)
    val (provider, query) = target.removePrefix(PLUGIN_PREFIX).split(':', limit = 2)
    return runBlocking {
        val extensions = SandboxedExtensions()
        try {
            extensions.loadInstalled()
            val link = firstLink(extensions, provider, query) ?: return@runBlocking NO_LINK
            println("playback: ${link.name} ${link.quality}p separateAudio=${link.audioUrl != null}")
            play(link.url, link.referer, link.userAgent, link.audioUrl, seconds)
        } finally {
            extensions.close()
        }
    }
}

private suspend fun firstLink(extensions: SandboxedExtensions, name: String, query: String): RemoteLink? {
    val provider = extensions.providers.firstOrNull { it.name == name } ?: return null
    val first: SearchResponse = provider.search(query, 1).items.firstOrNull() ?: return null
    val details = provider.details(first.url)
    val data = details.dataUrl ?: details.episodes.firstOrNull()?.data ?: return null
    return provider.links(data).links.firstOrNull { link ->
        SandboxPolicy.isPublicHttpUrl(link.url) && link.audioUrl?.let(SandboxPolicy::isPublicAudioUrl) != false
    }
}

private fun play(url: String, referer: String?, userAgent: String?, audioUrl: String?, seconds: Int): Int {
    val player = VlcPlayer.createOrNull() ?: run {
        println("playback: no sandboxed player here (VLC missing, or no sandbox-exec)")
        return NO_PLAYER
    }
    val scope = CoroutineScope(Dispatchers.Default)
    var frames = 0L
    var size = "none"
    player.frame.onEach { image ->
        if (image != null) {
            frames++
            size = "${image.width}x${image.height}"
        }
    }.launchIn(scope)
    val started = player.play(localPlaybackFile(url)?.let(player::stage) ?: url, referer, userAgent, audioUrl)
    repeat(seconds) { Thread.sleep(MILLIS_PER_SECOND) }
    val status = player.status.value
    println(
        "playback: started=$started frames=$frames size=$size playing=${status.playing} " +
            "position=${status.positionMillis} length=${status.lengthMillis} failed=${status.failed}"
    )
    scope.cancel()
    player.release()
    return if (started && frames > 0 && !status.failed) 0 else 1
}

/**
 * The file [target] names when it is a local one, by path or `file://` URL, and exists; null for anything else, a
 * stream's URL included, which goes to the host as it is.
 */
internal fun localPlaybackFile(target: String): File? {
    val file = when {
        target.startsWith(FILE_SCHEME, ignoreCase = true) -> runCatching { File(URI(target)) }.getOrNull()
        target.contains("://") -> null
        else -> File(target)
    }
    return file?.takeIf { it.isFile }
}

private const val FILE_SCHEME = "file:"
private const val PLUGIN_PREFIX = "plugin:"
private const val MILLIS_PER_SECOND = 1000L
private const val NO_LINK = 3
private const val NO_PLAYER = 2

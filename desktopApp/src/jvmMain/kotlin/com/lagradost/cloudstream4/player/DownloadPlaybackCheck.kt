package com.lagradost.cloudstream4.player

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * `--check-download-playback <file> [seconds]` (`KMP-74`): a download's whole way to the screen, through the real
 * sandboxed host. The file is opened here, as the series page's Play opens it ([LocalMedia]), with its own folder
 * standing in for the download folder; the host gets only the descriptor. Plays for [seconds], then seeks to
 * [SEEK_TO] of the length and plays for [AFTER_SEEK_SECONDS] more.
 *
 * Prints counts and positions, never the path.
 *
 * @return 0 when frames arrived, the seek landed past [SEEK_LANDS] of the length, frames went on after it, and the
 *   stream never failed.
 */
internal fun runDownloadPlaybackCheck(path: String, seconds: Int): Int {
    val file = File(path).absoluteFile
    val media = LocalMedia.open(file, file.parentFile) ?: run {
        println("playback: refused: not a regular file reached without a link")
        return REFUSED
    }
    val player = VlcPlayer.createOrNull(media) ?: run {
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
    val started = player.playFile()
    repeat(seconds) { Thread.sleep(MILLIS_PER_SECOND) }
    val before = player.status.value
    val framesBefore = frames
    player.seekTo(SEEK_TO)
    repeat(AFTER_SEEK_SECONDS) { Thread.sleep(MILLIS_PER_SECOND) }
    val after = player.status.value
    println(
        "playback: started=$started frames=$framesBefore size=$size playing=${before.playing} " +
            "position=${before.positionMillis} length=${before.lengthMillis} failed=${before.failed}"
    )
    println(
        "seek: to=$SEEK_TO position=${after.positionMillis} length=${after.lengthMillis} " +
            "framesAfter=${frames - framesBefore} playing=${after.playing} failed=${after.failed}"
    )
    scope.cancel()
    player.release()
    val landed = after.lengthMillis > 0 && after.positionMillis >= after.lengthMillis * SEEK_LANDS
    val ok = started && framesBefore > 0 && landed && frames > framesBefore && !before.failed && !after.failed
    return if (ok) 0 else 1
}

private const val SEEK_TO = 0.75f
private const val SEEK_LANDS = 0.7
private const val AFTER_SEEK_SECONDS = 2
private const val REFUSED = 4
private const val NO_PLAYER = 2
private const val MILLIS_PER_SECOND = 1000L

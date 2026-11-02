package com.lagradost.cloudstream4.player

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.impl.BufferUtil
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurfaceAdapters
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat

/** What the HUD draws. [failed] means libVLC gave up on this stream. */
data class PlayerStatus(
    val playing: Boolean = false,
    val buffering: Boolean = true,
    val positionMillis: Long = 0,
    val lengthMillis: Long = 0,
    val failed: Boolean = false,
    val finished: Boolean = false,
)

/**
 * One stream, played by the user's installed libVLC and delivered as frames to Compose.
 *
 * ## Frames, not a native view
 *
 * A heavyweight video view would sit above Compose and hide the HUD. So libVLC writes into memory and
 * two `Bitmap`s take turns: the one being filled is never the one published. A draw that overlaps the
 * next copy can tear for a frame, but cannot read freed memory.
 *
 * ## libVLC is the one thing here that is not sandboxed
 *
 * It parses media a third-party plugin chose, in this process. [DEFAULT_OPTIONS] turn off what a
 * media player does not need for that: its Lua scripts (which can fetch and run code), and picking up
 * subtitle files beside a stream. The user's VLC configuration is ignored, so it cannot widen them.
 *
 * Per vlcj's threading rules, nothing here calls libVLC back from one of its own callbacks.
 */
class VlcPlayer private constructor(private val factory: MediaPlayerFactory) {

    private val player: EmbeddedMediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()
    private val _frame = MutableStateFlow<ImageBitmap?>(null)
    private val _status = MutableStateFlow(PlayerStatus())

    val frame: StateFlow<ImageBitmap?> = _frame.asStateFlow()
    val status: StateFlow<PlayerStatus> = _status.asStateFlow()

    private var targets: Array<ByteBuffer> = emptyArray()
    private var bitmaps: Array<Bitmap> = emptyArray()
    private var images: Array<ImageBitmap> = emptyArray()
    private var back = 0

    init {
        player.videoSurface().set(
            CallbackVideoSurface(
                object : BufferFormatCallback {
                    override fun getBufferFormat(width: Int, height: Int): BufferFormat {
                        val (cappedWidth, cappedHeight) = capped(width, height)
                        allocate(cappedWidth, cappedHeight)
                        return RV32BufferFormat(cappedWidth, cappedHeight)
                    }

                    override fun newFormatSize(a: Int, b: Int, c: Int, d: Int) = Unit
                    override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit
                },
                object : RenderCallback {
                    override fun lock(mediaPlayer: MediaPlayer) = Unit
                    override fun unlock(mediaPlayer: MediaPlayer) = Unit
                    override fun display(
                        mediaPlayer: MediaPlayer,
                        buffers: Array<out ByteBuffer>,
                        format: BufferFormat,
                        displayWidth: Int,
                        displayHeight: Int,
                    ) = publish(buffers[0])
                },
                true,
                VideoSurfaceAdapters.getVideoSurfaceAdapter(),
            )
        )
        player.events().addMediaPlayerEventListener(
            object : MediaPlayerEventAdapter() {
                override fun playing(mediaPlayer: MediaPlayer) =
                    _status.update { it.copy(playing = true, buffering = false) }

                override fun paused(mediaPlayer: MediaPlayer) = _status.update { it.copy(playing = false) }
                override fun error(mediaPlayer: MediaPlayer) =
                    _status.update { it.copy(failed = true, playing = false, buffering = false) }

                override fun finished(mediaPlayer: MediaPlayer) =
                    _status.update { it.copy(finished = true, playing = false) }

                override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) =
                    _status.update { it.copy(buffering = newCache < FULL_CACHE) }

                override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) =
                    _status.update { it.copy(positionMillis = newTime) }

                override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) =
                    _status.update { it.copy(lengthMillis = newLength) }
            }
        )
    }

    private fun allocate(width: Int, height: Int) {
        val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        // allocPixels and a view of its memory, not installPixels: that copies the array once and
        // keeps the copy, so later frames would never reach the screen (measured: solid white).
        val nextBitmaps = Array(FRAME_BUFFERS) { Bitmap() }
        if (!nextBitmaps.all { it.allocPixels(info) }) {
            targets = emptyArray()
            _status.update { it.copy(failed = true, playing = false, buffering = false) }
            return
        }
        bitmaps = nextBitmaps
        targets = Array(FRAME_BUFFERS) { index ->
            val pixmap = checkNotNull(bitmaps[index].peekPixels()) { "bitmap has no pixels" }
            BufferUtil.getByteBufferFromPointer(pixmap.addr, width * height * BYTES_PER_PIXEL)
        }
        back = 0
        images = Array(FRAME_BUFFERS) { index -> bitmaps[index].asComposeImageBitmap() }
    }

    /** A stream's header states its own size, so a hostile one must not choose how much memory we take. */
    private fun capped(width: Int, height: Int): Pair<Int, Int> {
        val scale = minOf(1.0, MAX_FRAME_WIDTH.toDouble() / width, MAX_FRAME_HEIGHT.toDouble() / height)
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }

    private fun publish(buffer: ByteBuffer) {
        val target = targets.getOrNull(back) ?: return
        buffer.rewind()
        target.rewind()
        buffer.limit(minOf(buffer.capacity(), target.capacity()))
        target.put(buffer)
        bitmaps[back].notifyPixelsChanged()
        _frame.value = images[back]
        back = (back + 1) % FRAME_BUFFERS
    }

    /**
     * @param url already refused unless http(s) to a public host. Each option below is passed as its
     *   own argument, so a value cannot introduce another option. `:input-slave` is itself a
     *   `#`-separated list, which is why a `#` is refused here as well as in `SandboxPolicy`.
     * @return false when libVLC would not start at all.
     */
    fun play(url: String, referer: String?, userAgent: String?, audioUrl: String?): Boolean {
        val options = buildList {
            referer?.takeIf { it.isNotEmpty() }?.let { add(":http-referrer=$it") }
            userAgent?.takeIf { it.isNotEmpty() }?.let { add(":http-user-agent=$it") }
            audioUrl?.takeUnless { '#' in it }?.let { add(":input-slave=$it") }
        }
        return player.media().play(url, *options.toTypedArray())
    }

    fun togglePause() = player.controls().pause()

    fun seekBy(deltaMillis: Long) {
        player.controls().skipTime(deltaMillis)
    }

    fun seekTo(fraction: Float) {
        player.controls().setPosition(fraction.coerceIn(0f, 1f))
    }

    /** Blocks until libVLC has stopped, so call it off the UI thread. */
    fun release() {
        player.controls().stop()
        player.release()
        factory.release()
    }

    companion object {
        private const val FRAME_BUFFERS = 2
        private const val MAX_FRAME_WIDTH = 3840
        private const val MAX_FRAME_HEIGHT = 2160
        private const val BYTES_PER_PIXEL = 4
        private const val FULL_CACHE = 100f
        private val DEFAULT_OPTIONS = arrayOf(
            "--ignore-config",
            "--no-video-title-show",
            "--no-lua",
            "--no-sub-autodetect-file",
            "--no-metadata-network-access",
            "--quiet",
        )

        /** Null when no VLC is installed where vlcj can find it. */
        fun createOrNull(): VlcPlayer? {
            if (!NativeDiscovery().discover()) return null
            return VlcPlayer(MediaPlayerFactory(*DEFAULT_OPTIONS))
        }
    }
}

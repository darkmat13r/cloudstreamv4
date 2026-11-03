package com.lagradost.cloudstream4.player

import com.lagradost.cloudstream4.feature.extensions.sandbox.HostLauncher
import com.lagradost.cloudstream4.player.MediaProtocol.readText
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.media.VideoOrientation
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurfaceAdapters
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat

/**
 * The process libVLC runs in, one per stream, under the Seatbelt policy `HostLauncher` starts it with.
 *
 * libVLC decodes media a third-party plugin chose, and a decoder bug is code execution. So the app
 * process never loads it, and this one holds nothing worth taking: no home directory, no environment,
 * and a directory of its own that is deleted with it. It lives for one stream and exits when the app's
 * end of stdin closes, so a crashed app cannot leave it playing.
 *
 * Per vlcj's threading rules, nothing here calls libVLC back from one of its own callbacks. [FrameShapes] has its own
 * thread for that.
 */
internal object MediaHost {
    const val ARGUMENT = "--media-host"

    private val OPTIONS = arrayOf(
        "--ignore-config",
        "--no-video-title-show",
        "--no-lua",
        "--no-sub-autodetect-file",
        "--no-metadata-network-access",
        "--no-plugins-cache",
        "--quiet",
    )

    fun main() {
        // Taken before anything can print, and everything else goes to stderr. A stray println must
        // not become a frame.
        val stdout = FileOutputStream(FileDescriptor.out)
        System.setOut(PrintStream(FileOutputStream(FileDescriptor.err), true))
        val input = DataInputStream(System.`in`.buffered())
        if (!NativeDiscovery().discover()) exitProcess(EXIT_NO_VLC)

        val silent = System.getProperty(HostLauncher.SILENT_PROPERTY) == "true"
        val factory = MediaPlayerFactory(*OPTIONS, *(if (silent) arrayOf(NO_AUDIO) else emptyArray()))
        val player = factory.mediaPlayers().newEmbeddedMediaPlayer()
        val output = FrameWriter(stdout)
        player.videoSurface().set(surface(FrameShapes(player, output), output))
        player.events().addMediaPlayerEventListener(StatusListener(output))

        // End of stdin is the app going away. Halt rather than release: stopping libVLC can block for
        // seconds on a stalled stream, and there is nothing here to clean up.
        runCatching { commands(input, player) }
        Runtime.getRuntime().halt(0)
    }

    private fun commands(input: DataInputStream, player: MediaPlayer) {
        while (true) {
            when (input.readByte()) {
                MediaProtocol.OP_PLAY -> {
                    val url = checkNotNull(input.readText())
                    val options = buildList {
                        input.readText()?.takeIf { it.isNotEmpty() }?.let { add(":http-referrer=$it") }
                        input.readText()?.takeIf { it.isNotEmpty() }?.let { add(":http-user-agent=$it") }
                        input.readText()?.let { add(":input-slave=$it") }
                    }
                    player.media().play(url, *options.toTypedArray())
                }
                MediaProtocol.OP_TOGGLE_PAUSE -> player.controls().pause()
                MediaProtocol.OP_SEEK_BY -> player.controls().skipTime(input.readLong())
                MediaProtocol.OP_SEEK_TO -> player.controls().setPosition(input.readFloat().coerceIn(0f, 1f))
                else -> return
            }
        }
    }

    private fun surface(shapes: FrameShapes, output: FrameWriter) = CallbackVideoSurface(
        object : BufferFormatCallback {
            override fun getBufferFormat(width: Int, height: Int): BufferFormat {
                val size = shapes.onFormat(FrameSize(width, height))
                return RV32BufferFormat(size.width, size.height)
            }

            // vlcj passes back the size just returned above, twice. It says nothing about the picture.
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
            ) = output.frame(buffers[0], format.width * format.height * MediaProtocol.BYTES_PER_PIXEL)
        },
        true,
        VideoSurfaceAdapters.getVideoSurfaceAdapter(),
    )

    private const val EXIT_NO_VLC = 3
    private const val NO_AUDIO = "--no-audio"
}

/**
 * Tells the app each frame size, and what shape to draw it in once libVLC can say.
 *
 * libVLC is only asked from [worker]. A format callback waits for it, briefly: if it does not answer the frames are
 * still right in everything but their shape, and a later look at the tracks sends that.
 */
private class FrameShapes(private val player: MediaPlayer, private val output: FrameWriter) {
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "media-host-tracks").apply { isDaemon = true }
    }
    private val lock = Any()
    private var reported: FrameSize? = null
    private var sent: FrameSize? = null

    /** Called from libVLC's callback, on its thread. */
    fun onFormat(size: FrameSize): FrameSize {
        val tracks = runCatching {
            worker.submit<List<VideoTrackShape>>(::tracks).get(TRACKS_WAIT_MILLIS, TimeUnit.MILLISECONDS)
        }.getOrDefault(emptyList())
        val buffer = FrameGeometry.bufferSize(size, tracks)
        synchronized(lock) {
            if (reported == null) {
                worker.scheduleWithFixedDelay(::lookAgain, LOOK_MILLIS, LOOK_MILLIS, TimeUnit.MILLISECONDS)
            }
            reported = size
            sent = null
            output.format(buffer.width, buffer.height)
            sendShape(tracks)
        }
        return buffer
    }

    private fun lookAgain() {
        val tracks = tracks()
        synchronized(lock) { sendShape(tracks) }
    }

    private fun sendShape(tracks: List<VideoTrackShape>) {
        val picture = FrameGeometry.pictureSize(reported ?: return, tracks) ?: return
        if (picture == sent) return
        sent = picture
        output.picture(picture.width, picture.height)
    }

    /** Never throws, because a repeating task that throws is not run again. */
    private fun tracks(): List<VideoTrackShape> = runCatching {
        player.media().info()?.videoTracks().orEmpty().map { track ->
            VideoTrackShape(
                track.width(),
                track.height(),
                track.sampleAspectRatio(),
                track.sampleAspectRatioBase(),
                sideways = track.orientation() in SIDEWAYS,
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val TRACKS_WAIT_MILLIS = 200L
        const val LOOK_MILLIS = 100L
        val SIDEWAYS = setOf(
            VideoOrientation.LEFT_TOP,
            VideoOrientation.LEFT_BOTTOM,
            VideoOrientation.RIGHT_TOP,
            VideoOrientation.RIGHT_BOTTOM,
        )
    }
}

/** The one writer of stdout. A full pipe blocks libVLC's video thread, which is the backpressure. */
private class FrameWriter(stdout: FileOutputStream) {
    private val channel = stdout.channel
    private val data = DataOutputStream(stdout)
    private val lock = Any()

    private fun send(block: () -> Unit) = synchronized(lock) {
        // The app has gone or has refused us, so there is nothing left to do.
        runCatching { block() }.onFailure { Runtime.getRuntime().halt(0) }
        Unit
    }

    fun format(width: Int, height: Int) = send {
        data.writeByte(MediaProtocol.MESSAGE_FORMAT.toInt())
        data.writeInt(width)
        data.writeInt(height)
        data.flush()
    }

    fun picture(width: Int, height: Int) = send {
        data.writeByte(MediaProtocol.MESSAGE_PICTURE.toInt())
        data.writeInt(width)
        data.writeInt(height)
        data.flush()
    }

    fun frame(pixels: ByteBuffer, size: Int) = send {
        data.writeByte(MediaProtocol.MESSAGE_FRAME.toInt())
        data.flush()
        val view = pixels.duplicate()
        view.clear()
        view.limit(size)
        while (view.hasRemaining()) channel.write(view)
    }

    fun status(status: PlayerStatus) = send {
        var flags = 0
        if (status.playing) flags = flags or MediaStreamReader.FLAG_PLAYING
        if (status.buffering) flags = flags or MediaStreamReader.FLAG_BUFFERING
        if (status.failed) flags = flags or MediaStreamReader.FLAG_FAILED
        if (status.finished) flags = flags or MediaStreamReader.FLAG_FINISHED
        data.writeByte(MediaProtocol.MESSAGE_STATUS.toInt())
        data.writeInt(flags)
        data.writeLong(status.positionMillis.coerceAtLeast(0))
        data.writeLong(status.lengthMillis.coerceAtLeast(0))
        data.flush()
    }
}

/** Sends whole statuses, so the app never has to merge a partial one. */
private class StatusListener(private val output: FrameWriter) : MediaPlayerEventAdapter() {
    private var current = PlayerStatus()

    private fun update(change: PlayerStatus.() -> PlayerStatus) = synchronized(this) {
        current = current.change()
        output.status(current)
    }

    override fun playing(mediaPlayer: MediaPlayer) = update { copy(playing = true, buffering = false) }
    override fun paused(mediaPlayer: MediaPlayer) = update { copy(playing = false) }
    override fun error(mediaPlayer: MediaPlayer) = update { copy(failed = true, playing = false, buffering = false) }
    override fun finished(mediaPlayer: MediaPlayer) = update { copy(finished = true, playing = false) }
    override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) = update { copy(buffering = newCache < FULL_CACHE) }
    override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) = update { copy(positionMillis = newTime) }
    override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) = update { copy(lengthMillis = newLength) }

    private companion object {
        const val FULL_CACHE = 100f
    }
}

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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
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
        // Before anything reads stdin: the descriptor rides on its first byte, and a buffered read would drop it.
        val file = if (System.getProperty(HostLauncher.FILE_PROPERTY) == "true") {
            UnixFds.receive(STDIN) ?: exitProcess(EXIT_NO_FILE)
        } else {
            null
        }
        val input = DataInputStream(System.`in`.buffered())
        if (!NativeDiscovery().discover()) exitProcess(EXIT_NO_VLC)

        val silent = System.getProperty(HostLauncher.SILENT_PROPERTY) == "true"
        val factory = MediaPlayerFactory(*OPTIONS, *(if (silent) arrayOf(NO_AUDIO) else emptyArray()))
        val player = factory.mediaPlayers().newEmbeddedMediaPlayer()
        val output = FrameWriter(stdout)
        player.videoSurface().set(surface(FrameShapes(player, output), output))
        val volume = AtomicInteger(PlayerVolume.MAX)
        player.events().addMediaPlayerEventListener(StatusListener(output))
        keepVolume(player, volume)
        val audioTrack = AtomicInteger(AudioTracks.NONE)
        watchAudioTracks(player, audioTrack, output)

        // End of stdin is the app going away. Halt rather than release: stopping libVLC can block for
        // seconds on a stalled stream, and there is nothing here to clean up.
        runCatching { commands(input, player, volume, audioTrack, file) }
        Runtime.getRuntime().halt(0)
    }

    /**
     * @param file the descriptor the app handed over at the start, for [MediaProtocol.OP_PLAY_FILE]; null for a stream.
     *   Named by number here, so libVLC is never given a path. The kernel will still tell this process the path of a
     *   descriptor it holds (`F_GETPATH`, measured): the owner accepted that (2026-11-09). It can open nothing by it.
     */
    private fun commands(
        input: DataInputStream,
        player: MediaPlayer,
        volume: AtomicInteger,
        audioTrack: AtomicInteger,
        file: Int?,
    ) {
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
                // Built here, not sent: the app has no say in what libVLC opens.
                MediaProtocol.OP_PLAY_FILE -> file?.let { player.media().play("$FD_SCHEME$it") }
                MediaProtocol.OP_TOGGLE_PAUSE -> player.controls().pause()
                MediaProtocol.OP_SEEK_BY -> player.controls().skipTime(input.readLong())
                MediaProtocol.OP_SEEK_TO -> player.controls().setPosition(input.readFloat().coerceIn(0f, 1f))
                MediaProtocol.OP_SET_VOLUME -> {
                    volume.set(input.readInt().coerceIn(0, PlayerVolume.MAX))
                    player.audio().setVolume(volume.get())
                }
                // Only remembered here. The watcher applies it, and only if the stream has it.
                MediaProtocol.OP_SET_AUDIO_TRACK -> audioTrack.set(input.readInt())
                else -> return
            }
        }
    }

    /**
     * libVLC refuses a level, and reads as -1, for as long as its audio output is starting. That includes the moment it
     * reports playing, so nothing can be sent once: measured, a level sent just after play was lost, and a stream muted
     * by the person would have started at full volume. This puts it right as soon as the output can take it.
     */
    private fun keepVolume(player: MediaPlayer, volume: AtomicInteger) {
        thread(isDaemon = true, name = "media-host-volume") {
            while (true) {
                val wanted = volume.get()
                if (player.audio().volume() != wanted) player.audio().setVolume(wanted)
                Thread.sleep(VOLUME_CHECK_MILLIS)
            }
        }
    }

    /**
     * Tells the app the stream's audio tracks when they change, and holds the one chosen.
     *
     * Asked rather than told: libVLC has no list until the stream is open, about a second in when measured, and the
     * channels and codec come from a second list keyed by the same id. A choice is applied only if the stream lists it,
     * so a wrong id from the app does nothing.
     */
    private fun watchAudioTracks(player: MediaPlayer, wanted: AtomicInteger, output: FrameWriter) {
        thread(isDaemon = true, name = "media-host-audio-tracks") {
            var told: AudioTracks? = null
            while (true) {
                val now = readAudioTracks(player)
                val choice = wanted.get()
                if (now.selected != choice && now.tracks.any { it.id == choice }) player.audio().setTrack(choice)
                if (now != told) {
                    output.audioTracks(now)
                    told = now
                }
                Thread.sleep(AUDIO_TRACK_CHECK_MILLIS)
            }
        }
    }

    private fun readAudioTracks(player: MediaPlayer): AudioTracks {
        val info = runCatching { player.media().info().audioTracks() }.getOrNull().orEmpty().associateBy { it.id() }
        val tracks = runCatching { player.audio().trackDescriptions() }.getOrDefault(emptyList())
            .filter { it.id() >= 0 }
            .take(MediaProtocol.MAX_AUDIO_TRACKS)
            .map { track ->
                val details = info[track.id()]
                AudioTrack(
                    id = track.id(),
                    name = track.description().orEmpty().take(MediaProtocol.MAX_AUDIO_NAME_CHARS),
                    channels = details?.channels()?.coerceIn(0, MediaProtocol.MAX_AUDIO_CHANNELS) ?: 0,
                    codec = details?.codecName().orEmpty().take(MediaProtocol.MAX_AUDIO_CODEC_BYTES),
                )
            }
        val current = player.audio().track().takeIf { id -> tracks.any { it.id == id } } ?: AudioTracks.NONE
        return AudioTracks(tracks, current)
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
    private const val EXIT_NO_FILE = 4
    private const val STDIN = 0
    private const val FD_SCHEME = "fd://"
    private const val VOLUME_CHECK_MILLIS = 20L
    private const val AUDIO_TRACK_CHECK_MILLIS = 250L
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

    fun audioTracks(audio: AudioTracks) = send {
        data.writeByte(MediaProtocol.MESSAGE_AUDIO_TRACKS.toInt())
        data.writeInt(audio.tracks.size)
        data.writeInt(audio.selected)
        audio.tracks.forEach { track ->
            data.writeInt(track.id)
            data.writeInt(track.channels)
            with(MediaProtocol) {
                data.writeText(track.name)
                data.writeText(track.codec)
            }
        }
        data.flush()
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

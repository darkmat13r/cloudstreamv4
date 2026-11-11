package com.lagradost.cloudstream4.player

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.IntSize
import com.lagradost.cloudstream4.feature.extensions.sandbox.HostLauncher
import com.lagradost.cloudstream4.feature.extensions.sandbox.SandboxPolicy
import com.lagradost.cloudstream4.player.gateway.HlsRewriter
import com.lagradost.cloudstream4.player.gateway.MediaGateway
import com.lagradost.cloudstream4.player.MediaProtocol.writeText
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.impl.BufferUtil

/** What the player's controls do. A seam, so the screen can be driven without a media host. */
interface PlayerControls {
    fun togglePause()
    fun seekBy(deltaMillis: Long)
    fun seekTo(fraction: Float)

    /** @param percent 0 to 100. */
    fun setVolume(percent: Int)

    /** One of the ids the host listed. */
    fun selectAudioTrack(id: Int)
}

/** What the HUD draws. [failed] means libVLC gave up on this stream, or its host died. */
data class PlayerStatus(
    val playing: Boolean = false,
    val buffering: Boolean = true,
    val positionMillis: Long = 0,
    val lengthMillis: Long = 0,
    val failed: Boolean = false,
    val finished: Boolean = false,
)

/**
 * The app's handle on one stream, played by the user's installed libVLC in a sandboxed child process.
 *
 * Nothing in this process loads libVLC, or may: there is deliberately no vlcj import in this file. See
 * [MediaHost] for why, and [MediaStreamReader] for how what comes back is treated.
 *
 * ## Frames, not a native view
 *
 * A heavyweight video view would sit above Compose and hide the HUD. So frames arrive as bytes and two
 * `Bitmap`s take turns: the one being filled is never the one published. A draw that overlaps the next
 * copy can tear for a frame, but cannot read freed memory.
 */
class VlcPlayer private constructor(
    private val process: Process,
    private val directory: File,
    commandStream: OutputStream = process.outputStream,
    /** Whether this host was handed a file at its start, and so plays [playFile] rather than [play]. */
    private val local: Boolean = false,
    /** The name lookup [play]'s check uses; replaceable so a test can change the answer between two checks. */
    private val resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName,
    /**
     * The only way out of a stream's host (`KMP-113`): [play] hands libVLC gateway urls, and the host's sandbox allows
     * the gateway's port and nothing else. Null for a host that plays a file, which [play] then refuses.
     */
    private val gateway: MediaGateway? = null,
) : PlayerControls {
    private val commands = DataOutputStream(commandStream.buffered())
    private val _frame = MutableStateFlow<ImageBitmap?>(null)
    private val _pictureSize = MutableStateFlow<IntSize?>(null)
    private val _status = MutableStateFlow(PlayerStatus())
    private val _audio = MutableStateFlow(AudioTracks())
    @Volatile private var released = false

    val frame: StateFlow<ImageBitmap?> = _frame.asStateFlow()

    /** The shape to draw [frame] in, which is not always the frame's own. Null until the host can say. */
    val pictureSize: StateFlow<IntSize?> = _pictureSize.asStateFlow()
    val status: StateFlow<PlayerStatus> = _status.asStateFlow()

    /** Empty until libVLC has the stream open. */
    internal val audio: StateFlow<AudioTracks> = _audio.asStateFlow()

    init {
        thread(isDaemon = true, name = "media-host-out") { readHost() }
        // Read and dropped. Unread, a host writing to stderr would block, and nothing in it is for us.
        thread(isDaemon = true, name = "media-host-err") {
            runCatching { process.errorStream.use { it.copyTo(NullOutput) } }
        }
    }

    private fun readHost() {
        val clean = runCatching {
            process.inputStream.use { input ->
                MediaStreamReader(input, ::allocate, { _audio.value = it }) { status -> _status.value = status }.readAll()
            }
        }.isSuccess
        // A host that exits on its own, or says something invalid, has failed. Only release() is quiet.
        if (!released && !(clean && _status.value.finished)) {
            _status.update { it.copy(failed = true, playing = false, buffering = false) }
        }
    }

    private fun allocate(width: Int, height: Int): FrameTarget? {
        val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        // allocPixels and a view of its memory, not installPixels: that copies the array once and
        // keeps the copy, so later frames would never reach the screen (measured: solid white).
        val bitmaps = Array(FRAME_BUFFERS) { Bitmap() }
        if (!bitmaps.all { it.allocPixels(info) }) return null
        val size = width * height * MediaProtocol.BYTES_PER_PIXEL
        val buffers = Array(FRAME_BUFFERS) { index ->
            val pixmap = checkNotNull(bitmaps[index].peekPixels()) { "bitmap has no pixels" }
            BufferUtil.getByteBufferFromPointer(pixmap.addr, size)
        }
        val images = Array(FRAME_BUFFERS) { index -> bitmaps[index].asComposeImageBitmap() }
        _pictureSize.value = null
        return object : FrameTarget {
            private var back = 0

            override fun next(): ByteBuffer = buffers[back]

            override fun publish() {
                bitmaps[back].notifyPixelsChanged()
                _frame.value = images[back]
                back = (back + 1) % FRAME_BUFFERS
            }

            override fun showAs(width: Int, height: Int) {
                _pictureSize.value = IntSize(width, height)
            }
        }
    }

    private fun send(command: DataOutputStream.() -> Unit): Boolean = try {
        synchronized(commands) {
            commands.command()
            commands.flush()
        }
        true
    } catch (_: IOException) {
        false
    }

    /**
     * @param url already refused unless http(s) to a public host when the link arrived. Each option is passed as its
     *   own argument, so a value cannot introduce another option. `:input-slave` is itself a `#`-separated
     *   list, which is why a `#` is refused here as well as in `SandboxPolicy`.
     * @return false when the host has already gone, or when [url] or [audioUrl] no longer passes.
     */
    fun play(streamUrl: String, referer: String?, userAgent: String?, streamAudioUrl: String?): Boolean {
        // KMP-113: as a playlist's urls are, so an IDN host or a character java.net.URI refuses does not fail it here.
        val url = HlsRewriter.lenient(streamUrl)
        val audioUrl = streamAudioUrl?.let(HlsRewriter::lenient)
        // BUG-90: checked again now, so a link that has turned private since it arrived fails at once. Every connection
        // after this is checked by the gateway (KMP-113): libVLC can reach nothing else.
        if (!SandboxPolicy.isPublicHttpUrl(url, resolve)) return false
        if (audioUrl != null && !SandboxPolicy.isPublicAudioUrl(audioUrl, resolve)) return false
        val gateway = gateway ?: return false
        // The gateway sends the stream's own Referer and User-Agent; libVLC is given neither (§4).
        gateway.useHeaders(referer, userAgent)
        val routed = gateway.urlFor(url) ?: return false
        val routedAudio = audioUrl?.takeUnless { '#' in it }?.let { gateway.urlFor(it) ?: return false }
        val fields = listOf(routed, null, null, routedAudio)
        if (fields.any { it != null && it.encodeToByteArray().size > MediaProtocol.MAX_TEXT_BYTES }) return false
        return send {
            writeByte(MediaProtocol.OP_PLAY.toInt())
            fields.forEach { writeText(it) }
        }
    }

    /**
     * Plays the file this host was handed when it started (`KMP-74`). Sends no text: the host names it by its
     * descriptor. False for a host made for a stream, or one that has already gone.
     */
    fun playFile(): Boolean = local && send { writeByte(MediaProtocol.OP_PLAY_FILE.toInt()) }

    override fun togglePause() {
        send { writeByte(MediaProtocol.OP_TOGGLE_PAUSE.toInt()) }
    }

    override fun seekBy(deltaMillis: Long) {
        send {
            writeByte(MediaProtocol.OP_SEEK_BY.toInt())
            writeLong(deltaMillis)
        }
    }

    override fun seekTo(fraction: Float) {
        send {
            writeByte(MediaProtocol.OP_SEEK_TO.toInt())
            writeFloat(fraction.coerceIn(0f, 1f))
        }
    }

    override fun setVolume(percent: Int) {
        send {
            writeByte(MediaProtocol.OP_SET_VOLUME.toInt())
            writeInt(percent.coerceIn(0, PlayerVolume.MAX))
        }
    }

    override fun selectAudioTrack(id: Int) {
        send {
            writeByte(MediaProtocol.OP_SET_AUDIO_TRACK.toInt())
            writeInt(id)
        }
    }

    /**
     * Copies [file] where the host may read it, and returns the URL to play it by. For a check: the host
     * can read nothing outside its own directory, and it cannot reach this machine over HTTP either.
     */
    internal fun stage(file: File): String =
        file.copyTo(File(directory, file.name), overwrite = true).toPath().toUri().toString()

    /**
     * Copies [file] into the host's own directory ([stage]) and plays that copy. For `--check-playback` on a local
     * file (`BUG-76`): a `file:` url is not a stream, so it skips [play]'s address check, and names only the copy.
     */
    internal fun playStaged(file: File): Boolean {
        val url = stage(file)
        return send {
            writeByte(MediaProtocol.OP_PLAY.toInt())
            listOf(url, null, null, null).forEach { writeText(it) }
        }
    }

    /** Ends the host, so the audio stops now. Waits for it, so call it off the UI thread. */
    fun release() {
        released = true
        gateway?.close()
        process.destroyForcibly()
        process.waitFor(EXIT_WAIT_SECONDS, TimeUnit.SECONDS)
        // Now, not at collection: for a downloaded file this is the app's end of the host's socket (`KMP-74`). The host
        // has gone, so what is still buffered cannot be sent, and that failure is the expected one.
        runCatching { synchronized(commands) { commands.close() } }
        deleteHostDirectory(directory.toPath())
    }

    companion object {
        private const val FRAME_BUFFERS = 2
        private const val EXIT_WAIT_SECONDS = 5L

        /** Where vlcj looks on macOS. Also the only place the sandbox policy lets the host read it. */
        private val VLC_APP = File("/Applications/VLC.app")

        /**
         * Null when VLC is not installed, or this OS has no sandbox. There is no unsandboxed fallback.
         *
         * @param silent for a check run where nobody is listening. See [HostLauncher.startMediaHost].
         */
        fun createOrNull(silent: Boolean = false): VlcPlayer? =
            createWith(silent, startGateway = { MediaGateway.start(null, null) })

        /**
         * [createOrNull], with the gateway made by [startGateway], so a test can give it its own address check.
         *
         * The gateway is bound first, then the host's policy names its port (`KMP-113`). No gateway, no player: a
         * stream never plays with the host's network open.
         */
        internal fun createWith(
            silent: Boolean,
            startGateway: () -> MediaGateway?,
            resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName,
            libraries: File = nativeLibraries(),
        ): VlcPlayer? {
            val launcher = HostLauncher.forMediaHost(MediaHost.ARGUMENT, MEDIA_HOST_MAIN) ?: return null
            if (!File(VLC_APP, "Contents/MacOS/lib/libvlc.dylib").isFile) return null
            val gateway = startGateway() ?: return null
            val base = File(System.getProperty("user.home"), ".cloudstream4/.media-host").apply { mkdirs() }
            val directory = newHostDirectory(base)
            return runCatching {
                VlcPlayer(
                    launcher.startMediaHost(VLC_APP, directory, libraries, silent, gatewayPort = gateway.port),
                    directory,
                    resolve = resolve,
                    gateway = gateway,
                )
            }
                .onFailure {
                    gateway.close()
                    deleteHostDirectory(directory.toPath())
                }
                .getOrNull()
        }

        /**
         * A player for a downloaded file over [process], whose commands go to [commands]: what [createOrNull] makes,
         * without a host. A seam, so a window test can read what the screen sends (`KMP-74`).
         */
        internal fun forDownload(process: Process, directory: File, commands: OutputStream): VlcPlayer =
            VlcPlayer(process, directory, commands, local = true)

        /**
         * A player for a stream over [process], whose commands go to [commands], looking names up with [resolve], its
         * urls routed through [gateway].
         */
        internal fun forStream(
            process: Process,
            directory: File,
            commands: OutputStream,
            resolve: (String) -> Array<InetAddress>,
            gateway: MediaGateway?,
        ): VlcPlayer = VlcPlayer(process, directory, commands, resolve = resolve, gateway = gateway)

        /**
         * A host for one downloaded file, which it gets as [media]'s descriptor and never by name (`KMP-74`). [media]
         * is closed here, whatever happens: the app keeps no copy once it is sent. Then [playFile] plays it.
         *
         * The host's stdin is one end of a socket pair rather than a pipe, because only a socket carries a
         * descriptor, and Java starts a process with nothing open above stderr. The descriptor goes first, with one
         * byte; the commands follow on the same socket, in the same form as ever. Its policy is the stream host's.
         */
        internal fun createOrNull(media: LocalMedia): VlcPlayer? = media.use {
            val launcher = HostLauncher.forMediaHost(MediaHost.ARGUMENT, MEDIA_HOST_MAIN) ?: return null
            if (!File(VLC_APP, "Contents/MacOS/lib/libvlc.dylib").isFile) return null
            val base = File(System.getProperty("user.home"), ".cloudstream4/.media-host").apply { mkdirs() }
            val directory = newHostDirectory(base)
            val (app, host) = UnixFds.socketPair() ?: return null.also { deleteHostDirectory(directory.toPath()) }
            var appEnd: Int? = app
            var hostEnd: Int? = host
            var process: Process? = null
            var player: VlcPlayer? = null
            try {
                player = if (UnixFds.send(app, media.fd)) {
                    media.close()
                    // Opening /dev/fd/N duplicates descriptor N: Java gives the child its own copy as stdin.
                    val started = launcher.startMediaHost(VLC_APP, directory, nativeLibraries(), input = ProcessBuilder.Redirect.from(descriptor(host)))
                    process = started
                    UnixFds.close(host)
                    hostEnd = null
                    val commands = FileOutputStream(descriptor(app))
                    UnixFds.close(app)
                    appEnd = null
                    VlcPlayer(started, directory, commands, local = true)
                } else {
                    null
                }
            } catch (_: IOException) {
                // A host that could not start, or a socket end that could not be opened: no player, as with no VLC.
            } finally {
                // Whatever went wrong, thrown or not: nothing of a host that never became a player is left behind.
                hostEnd?.let(UnixFds::close)
                appEnd?.let(UnixFds::close)
                if (player == null) {
                    process?.destroyForcibly()
                    deleteHostDirectory(directory.toPath())
                }
            }
            player
        }

        private fun descriptor(fd: Int) = File("/dev/fd/$fd")

        private const val MEDIA_HOST_MAIN = "com.lagradost.cloudstream4.player.MediaHostMainKt"
    }
}

/**
 * Clears what crashes left in [base], then makes a new directory there for one host, which it alone may use.
 *
 * Every running copy of the app shares [base], so what is in it may be another copy's live host. A host
 * still starting needs its directory (measured: deleting it in the first 0.2 s or so loses the stream). So
 * each name says which app made it, and only directories whose app has gone are cleared. A directory is
 * never reused, so a host that outlives its app has no rights over a later one.
 */
/**
 * Where JNA's native part is kept for the media host (`BUG-96`, audit A-9): beside `.media-host`, never inside it, so
 * no host can write it and the sweep of unused host directories never touches it.
 */
internal fun nativeLibraries(): File = File(System.getProperty("user.home"), ".cloudstream4/.jna")

internal fun newHostDirectory(base: File): File {
    base.listFiles()?.filterNot { hostDirectoryInUse(it.name) }?.forEach { deleteHostDirectory(it.toPath()) }
    val self = ProcessHandle.current()
    val prefix = "$HOST_DIRECTORY_PREFIX${self.pid()}-${self.startMillis() ?: 0}-"
    return Files.createTempDirectory(base.toPath(), prefix).toFile().canonicalFile
}

/**
 * Whether the app that made this directory is still running. The start time is there because a pid is
 * reused. A name not in this form is a leftover, or from a build before this one.
 */
internal fun hostDirectoryInUse(name: String): Boolean {
    if (!name.startsWith(HOST_DIRECTORY_PREFIX)) return false
    val fields = name.removePrefix(HOST_DIRECTORY_PREFIX).split('-')
    val pid = fields.getOrNull(0)?.toLongOrNull() ?: return false
    val started = fields.getOrNull(1)?.toLongOrNull() ?: return false
    val owner = ProcessHandle.of(pid).orElse(null) ?: return false
    // Cannot tell, so keep it: a leftover costs a few bytes, and deleting a live one can cost a stream.
    val actual = owner.startMillis() ?: return true
    return actual == started
}

internal fun ProcessHandle.startMillis(): Long? = info().startInstant().map { it.toEpochMilli() }.orElse(null)

internal const val HOST_DIRECTORY_PREFIX = "host-"

/**
 * Deletes a directory a host could write to, without ever following a link in it.
 *
 * Not `File.deleteRecursively`, which does follow them (measured: it emptied the target of a planted
 * symlink). A host could otherwise have the app delete anything the user can, on its way out.
 */
internal fun deleteHostDirectory(root: Path) {
    runCatching {
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}

private object NullOutput : java.io.OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}

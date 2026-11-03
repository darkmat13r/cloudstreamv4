package com.lagradost.cloudstream4.player

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.IntSize
import com.lagradost.cloudstream4.feature.extensions.sandbox.HostLauncher
import com.lagradost.cloudstream4.player.MediaProtocol.writeText
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
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
) : PlayerControls {
    private val commands = DataOutputStream(process.outputStream.buffered())
    private val _frame = MutableStateFlow<ImageBitmap?>(null)
    private val _pictureSize = MutableStateFlow<IntSize?>(null)
    private val _status = MutableStateFlow(PlayerStatus())
    @Volatile private var released = false

    val frame: StateFlow<ImageBitmap?> = _frame.asStateFlow()

    /** The shape to draw [frame] in, which is not always the frame's own. Null until the host can say. */
    val pictureSize: StateFlow<IntSize?> = _pictureSize.asStateFlow()
    val status: StateFlow<PlayerStatus> = _status.asStateFlow()

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
                MediaStreamReader(input, ::allocate) { status -> _status.value = status }.readAll()
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
     * @param url already refused unless http(s) to a public host. Each option is passed as its own
     *   argument, so a value cannot introduce another option. `:input-slave` is itself a `#`-separated
     *   list, which is why a `#` is refused here as well as in `SandboxPolicy`.
     * @return false when the host has already gone.
     */
    fun play(url: String, referer: String?, userAgent: String?, audioUrl: String?): Boolean {
        val fields = listOf(url, referer, userAgent, audioUrl?.takeUnless { '#' in it })
        if (fields.any { it != null && it.encodeToByteArray().size > MediaProtocol.MAX_TEXT_BYTES }) return false
        return send {
            writeByte(MediaProtocol.OP_PLAY.toInt())
            fields.forEach { writeText(it) }
        }
    }

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

    /**
     * Copies [file] where the host may read it, and returns the URL to play it by. For a check: the host
     * can read nothing outside its own directory, and it cannot reach this machine over HTTP either.
     */
    internal fun stage(file: File): String =
        file.copyTo(File(directory, file.name), overwrite = true).toPath().toUri().toString()

    /** Ends the host, so the audio stops now. Waits for it, so call it off the UI thread. */
    fun release() {
        released = true
        process.destroyForcibly()
        process.waitFor(EXIT_WAIT_SECONDS, TimeUnit.SECONDS)
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
        fun createOrNull(silent: Boolean = false): VlcPlayer? {
            val launcher = HostLauncher.forMediaHost(MediaHost.ARGUMENT, MEDIA_HOST_MAIN) ?: return null
            if (!File(VLC_APP, "Contents/MacOS/lib/libvlc.dylib").isFile) return null
            val base = File(System.getProperty("user.home"), ".cloudstream4/.media-host").apply { mkdirs() }
            // One stream at a time, so anything here was left by a crash, or is a host on its way out.
            base.listFiles()?.forEach { deleteHostDirectory(it.toPath()) }
            val directory = Files.createTempDirectory(base.toPath(), "host").toFile().canonicalFile
            return runCatching { VlcPlayer(launcher.startMediaHost(VLC_APP, directory, silent), directory) }.getOrNull()
        }

        private const val MEDIA_HOST_MAIN = "com.lagradost.cloudstream4.player.MediaHostMainKt"
    }
}

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

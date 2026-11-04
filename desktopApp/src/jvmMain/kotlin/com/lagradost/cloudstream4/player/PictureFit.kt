package com.lagradost.cloudstream4.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.unit.IntSize
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.concurrent.thread

/** How a frame sits in the window. */
internal enum class PictureFit {
    /** All of the picture in its own shape, with bars where the window is a different shape. */
    ORIGINAL,

    /** As wide as the window, still in its own shape: bars above and below, or the top and bottom cut off. */
    FILL_WIDTH,

    /** Every part of the window, whatever that does to the picture's shape. */
    FILL_SCREEN,
    ;

    /**
     * Scales the frame's own buffer, not the picture: a frame libVLC stretched into a decoder's buffer, or one with
     * non-square pixels, is not the shape of what it shows (`BUG-15`).
     */
    fun contentScale(picture: IntSize?): ContentScale = when (this) {
        FILL_SCREEN -> ContentScale.FillBounds
        ORIGINAL, FILL_WIDTH -> PictureScale(this, picture)
    }

    /** The picture's size in [box], which is what a caption has to keep inside when part of it is cut off. */
    fun scaleFor(box: IntSize, picture: IntSize): Float = when (this) {
        FILL_WIDTH -> box.width.toFloat() / picture.width
        else -> minOf(box.width.toFloat() / picture.width, box.height.toFloat() / picture.height)
    }
}

private class PictureScale(private val fit: PictureFit, private val picture: IntSize?) : ContentScale {
    override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor {
        val shape = picture?.takeIf { it.width > 0 && it.height > 0 }
            ?: IntSize(srcSize.width.toInt().coerceAtLeast(1), srcSize.height.toInt().coerceAtLeast(1))
        val scale = fit.scaleFor(IntSize(dstSize.width.toInt(), dstSize.height.toInt()), shape)
        return ScaleFactor(scale * shape.width / srcSize.width, scale * shape.height / srcSize.height)
    }
}

/** The fit in use, for every stream, and the one the next launch starts with. */
internal class PictureFitChoice(private val file: File? = null) {
    var current by mutableStateOf(file?.let(::read) ?: PictureFit.ORIGINAL)
        private set

    private val lock = Any()

    fun choose(fit: PictureFit) {
        current = fit
        val target = file ?: return
        // Off the caller's thread, because the caller is a click, and a failed write costs only the next launch.
        thread(isDaemon = true, name = "picture-fit") { synchronized(lock) { runCatching { write(target) } } }
    }

    private fun read(from: File): PictureFit? = runCatching {
        if (from.length() > MAX_FILE_BYTES) return null
        PictureFit.entries.firstOrNull { it.name == from.readText().trim() }
    }.getOrNull()

    /** Whatever is latest by now, so two quick choices that run out of order still end on the second. */
    private fun write(target: File) {
        val directory = checkNotNull(target.parentFile).toPath()
        Files.createDirectories(directory)
        val next = Files.createTempFile(directory, target.name, ".tmp")
        try {
            Files.writeString(next, current.name)
            Files.move(next, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(next)
        }
    }

    private companion object {
        const val MAX_FILE_BYTES = 64L
    }
}

/** Read once per run and shared, because each player opens its own screen. */
internal val desktopPictureFit: PictureFitChoice by lazy {
    PictureFitChoice(File(File(System.getProperty("user.home"), ".cloudstream4"), "picture-fit"))
}

package com.lagradost.cloudstream4.search

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.detail.backdropImageLoader
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/** `KMP-106`: a poster is asked for, and kept, at the size it is drawn, not the size of its file. */
class PosterImageTest {

    private val folder = createTempDirectory("poster-image-test")

    @AfterTest
    fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

    /** The size [PosterImage] asks [load] for, drawn with [modifier] at density 2. */
    private fun askedSize(modifier: Modifier): Pair<Int?, Int?> = runBlocking {
        val asked = CompletableDeferred<Pair<Int?, Int?>>()
        val scene = ImageComposeScene(SCENE, SCENE, Density(2f)) {
            PosterImage(URL, modifier, load = { _, width, height -> asked.complete(width to height); null })
        }
        try {
            withTimeout(60_000) {
                while (!asked.isCompleted) {
                    scene.render()
                    yield()
                }
            }
        } finally {
            scene.close()
        }
        asked.await()
    }

    @Test
    fun `a poster is asked for at the size of its box, in pixels`() {
        assertEquals(128 to 192, askedSize(Modifier.size(64.dp, 96.dp)))
    }

    @Test
    fun `a poster in a slot with no bound is asked for at its own size, as before`() {
        assertEquals(null to null, askedSize(Modifier.wrapContentSize(unbounded = true)))
    }

    @Test
    fun `a large poster asked for at a card's size is kept no larger than twice that`() = runBlocking<Unit> {
        val file = folder.resolve("poster.png").apply { writeBytes(png(1600, 2400)) }
        val loader = backdropImageLoader(OkHttpClient())
        withTimeout(60_000) {
            val card = assertNotNull(loadPoster(file.toString(), 128, 192, loader))
            assertTrue(card.width in 128..256 && card.height in 192..384, "${card.width} x ${card.height}")
            // The same file at its own size, which is what a poster was before.
            val whole = assertNotNull(loadPoster(file.toString(), null, null, loader))
            assertEquals(1600 to 2400, whole.width to whole.height)
        }
    }

    /** A real PNG of [width] by [height], drawn by Skia. */
    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap().apply { allocN32Pixels(width, height) }
        bitmap.erase(0xFF336699.toInt())
        return checkNotNull(Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)).bytes
    }

    private companion object {
        const val SCENE = 400
        const val URL = "https://img.example/p.jpg"
    }
}

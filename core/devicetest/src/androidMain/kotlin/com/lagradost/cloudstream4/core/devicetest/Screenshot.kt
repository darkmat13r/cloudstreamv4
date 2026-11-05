package com.lagradost.cloudstream4.core.devicetest

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * What [assertScreenshot] forgives, which is nothing, because that is what was measured: recorded on one boot of
 * `Television_720p` and compared on later boots, every case differed in 0 pixels with a largest channel delta of 0. A
 * tolerance above the noise only hides small changes, and one step of one channel is a real colour change. If a
 * toolchain ever shows noise, read `build/screenshots/comparisons.tsv` and raise these to what it measured.
 */
object ScreenshotTolerance {
    /** The most a colour channel may move, out of 255, before a pixel counts as different. */
    const val CHANNEL_DELTA: Int = 0

    /** How many pixels may count as different before the case fails. */
    const val DIFFERENT_PIXELS: Int = 0
}

/**
 * Compares the screen with `src/androidDeviceTest/resources/screenshots/<name>.png`, and fails on a real difference.
 *
 * Run it through `scripts/device-tests.sh`, which takes what this writes off the device before the emulator stops.
 * `-Pandroid.testInstrumentationRunnerArguments.screenshots=record` writes the PNG instead of comparing, and the
 * script copies it over the reference. Recording is a separate run because a person has to look at the result, so a
 * missing reference fails here and never records itself.
 *
 * The rule's clock settles animations first. It also cancels infinite ones while `autoAdvance` is on, so a blinking
 * caret or a shimmer holds still.
 *
 * Only the root window is captured, so a `Dialog` or `Popup`, which is a second window, makes `onRoot` throw. The
 * keyboard, a `SurfaceView` and video are not in the window's surface and are never in the image.
 *
 * @param name lower-case words and hyphens, naming the state: `search-results-focus-card`.
 * @param node compares only this node and its ring (see [captureScreenshot]). The default is the whole window.
 */
fun ComposeTestRule.assertScreenshot(name: String, node: SemanticsNodeInteraction? = null) {
    require(name.matches(Regex("[a-z0-9-]+"))) { "A screenshot name is lower-case words and hyphens, not '$name'" }
    val actual = captureScreenshot(node)
    val output = ScreenshotOutput()
    if (arguments.getString(MODE_ARGUMENT) == MODE_RECORD) {
        output.write("recorded/$name.png", actual.png())
        output.log(name, "recorded", actual.width, actual.height, different = 0, largestDelta = 0)
        return
    }

    val stored = Screenshots::class.java.classLoader?.getResourceAsStream("screenshots/$name.png")
    if (stored == null) {
        output.write("failures/$name.actual.png", actual.png())
        output.log(name, "missing", actual.width, actual.height, different = -1, largestDelta = -1)
        throw AssertionError(
            "No reference for screenshot '$name' in src/androidDeviceTest/resources/screenshots. A missing reference " +
                "fails. Look at $name.actual.png, then record it with -P$RECORD_PROPERTY=record and commit the PNG.",
        )
    }
    val reference = stored.use { BitmapFactory.decodeStream(it) }

    val failure = if (reference.width != actual.width || reference.height != actual.height) {
        output.log(name, "size", actual.width, actual.height, different = -1, largestDelta = -1)
        "is ${actual.width}x${actual.height} and its reference is ${reference.width}x${reference.height}"
    } else {
        val comparison = compare(actual, reference)
        val passed = comparison.different <= ScreenshotTolerance.DIFFERENT_PIXELS
        output.log(
            name, if (passed) "same" else "different", actual.width, actual.height,
            comparison.different, comparison.largestDelta,
        )
        if (passed) return
        output.write("failures/$name.diff.png", comparison.diff.png())
        "differs in ${comparison.different} pixels by more than ${ScreenshotTolerance.CHANNEL_DELTA}/255, up to " +
            "${comparison.largestDelta}/255, and ${ScreenshotTolerance.DIFFERENT_PIXELS} are allowed"
    }
    output.write("failures/$name.actual.png", actual.png())
    output.write("failures/$name.reference.png", reference.png())
    throw AssertionError(
        "Screenshot '$name' $failure. Look at $name.actual.png, .reference.png and .diff.png in " +
            "build/screenshots/failures. Fix it, or if the change is the intended one, say so in the PR and re-record.",
    )
}

/**
 * The window as it is on screen, once the rule's clock has settled.
 *
 * `node.captureToImage()` crops to the node's bounds and so loses a focus ring, which is drawn entirely outside them.
 * This captures the window and crops to [node] grown by [bleed], so the ring stays in the picture.
 *
 * @param bleed mirrors `CloudStreamDimens.FocusRingBleed`, which this module cannot see.
 */
fun ComposeTestRule.captureScreenshot(node: SemanticsNodeInteraction? = null, bleed: Dp = 4.dp): Bitmap {
    waitForIdle()
    val window = onRoot().captureToImage().asAndroidBitmap()
    val bounds = (node ?: return window).fetchSemanticsNode().boundsInRoot
    val grow = with(density) { bleed.toPx() }
    val left = max(0, floor(bounds.left - grow).toInt())
    val top = max(0, floor(bounds.top - grow).toInt())
    val right = min(window.width, ceil(bounds.right + grow).toInt())
    val bottom = min(window.height, ceil(bounds.bottom + grow).toInt())
    return Bitmap.createBitmap(window, left, top, right - left, bottom - top)
}

/** A one-colour poster for a fake to hand a card: an image with no network and nothing to load. */
fun solidPoster(argb: Int): ImageBitmap =
    Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(argb) }.asImageBitmap()

private class Comparison(val different: Int, val largestDelta: Int, val diff: Bitmap)

/** The diff is the reference dimmed, with every pixel over [ScreenshotTolerance.CHANNEL_DELTA] in magenta. */
private fun compare(actual: Bitmap, reference: Bitmap): Comparison {
    val width = actual.width
    val height = actual.height
    val actualPixels = IntArray(width * height).also { actual.getPixels(it, 0, width, 0, 0, width, height) }
    val referencePixels = IntArray(width * height).also { reference.getPixels(it, 0, width, 0, 0, width, height) }
    var different = 0
    var largestDelta = 0
    val diff = IntArray(width * height) { i ->
        val delta = channelDelta(actualPixels[i], referencePixels[i])
        largestDelta = max(largestDelta, delta)
        if (delta > ScreenshotTolerance.CHANNEL_DELTA) {
            different++
            DIFF_MARK
        } else {
            referencePixels[i] and DIFF_DIM or OPAQUE
        }
    }
    return Comparison(different, largestDelta, Bitmap.createBitmap(diff, width, height, Bitmap.Config.ARGB_8888))
}

private fun channelDelta(a: Int, b: Int): Int =
    (0..24 step 8).maxOf { shift -> abs((a ushr shift and 0xFF) - (b ushr shift and 0xFF)) }

private fun Bitmap.png(): ByteArray =
    ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }.toByteArray()

/**
 * Where a run's images wait for `scripts/device-tests.sh`: `/data/local/tmp`, because Gradle uninstalls the test APK
 * when it finishes, and everything the app itself may write goes with it. Only the shell may write there, so each
 * file goes through the app's own external cache and is copied by a shell command.
 */
private class ScreenshotOutput {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val module = arguments.getString(MODULE_ARGUMENT) ?: target.packageName
    private val scratch = checkNotNull(target.externalCacheDir) { "No external storage to write screenshots to" }

    fun write(path: String, bytes: ByteArray) {
        val file = File(scratch, path.replace('/', '-')).apply { writeBytes(bytes) }
        publish(file, path)
    }

    /** One line per case, kept for passes too, which is how the noise in `ScreenshotTolerance` was measured. */
    fun log(name: String, result: String, width: Int, height: Int, different: Int, largestDelta: Int) {
        val file = File(scratch, LOG).apply {
            appendText("$name\t$result\t${width}x$height\t$different\t$largestDelta\n")
        }
        publish(file, LOG)
    }

    private fun publish(file: File, path: String) {
        val destination = "$DEVICE_ROOT/$module/$path"
        device.executeShellCommand("mkdir -p ${destination.substringBeforeLast('/')}")
        device.executeShellCommand("cp ${file.absolutePath} $destination")
        check(destination in device.executeShellCommand("ls $destination")) { "Could not write $destination" }
    }
}

/** Only so the helper has a class loader to read the test APK's resources with. */
private object Screenshots

private val arguments get() = InstrumentationRegistry.getArguments()

private const val MODE_ARGUMENT = "screenshots"
private const val MODE_RECORD = "record"
private const val RECORD_PROPERTY = "android.testInstrumentationRunnerArguments.$MODE_ARGUMENT"
private const val MODULE_ARGUMENT = "screenshotModule"
private const val DEVICE_ROOT = "/data/local/tmp/cloudstream-shots"
private const val LOG = "comparisons.tsv"
private const val PNG_QUALITY = 100
private const val DIFF_MARK = 0xFFFF00FF.toInt()
private const val DIFF_DIM = 0x003F3F3F
private const val OPAQUE = 0xFF000000.toInt()

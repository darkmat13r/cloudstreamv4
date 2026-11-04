package com.lagradost.cloudstream4.core.designsystem.harness

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Density
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * An offscreen Compose scene at `Density(1f)`, so **one pixel is one dp** and a bound read off the
 * layout is the dp number the design asked for.
 *
 * This is the pattern `:desktopApp`'s `DesktopInteractionTest` established and the only one
 * available here: `DEC-10` leaves the screenshot plugin unable to render on this toolchain
 * (`.claude/rules/screenshot-testing.md`), and there is no `compose-ui-test` in the catalog, so a
 * test that needs real focus, real key events or a real measured frame drives a scene directly.
 * Adding a test library is a dependency decision, not something a component PR does.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
internal class Harness(
    width: Int = WIDTH,
    height: Int = HEIGHT,
    content: @Composable () -> Unit,
) : AutoCloseable {

    private val scene = ImageComposeScene(width, height, Density(1f)) {
        CloudStreamTheme { content() }
    }

    /**
     * Runs enough frames for composition, layout, focus and any animation shorter than
     * [SETTLE_FRAMES] × [FRAME_MILLIS] to finish.
     *
     * Frame time is advanced explicitly because an [ImageComposeScene] has no real clock: an
     * animation only moves when a frame is rendered with a later timestamp.
     */
    fun settle() = repeat(SETTLE_FRAMES) { advance(FRAME_MILLIS) }

    /** Renders one frame [millis] after the last one, returning the new elapsed time. */
    fun advance(millis: Long): Long {
        elapsedMillis += millis
        scene.render(elapsedMillis * NANOS_PER_MILLI)
        return elapsedMillis
    }

    /**
     * Renders the **two** frames a just-launched animation needs before it has moved at all.
     *
     * Measured, not assumed, and it is two for two separate reasons:
     *
     * 1. Flipping a state does nothing until a frame is rendered, so the first frame is the one
     *    that recomposes and lets `LaunchedEffect` launch `animateTo`.
     * 2. `animateTo` then suspends on `withFrameNanos` to learn its start time, so the *second*
     *    frame is the one it takes that time from. Progress is counted from there.
     *
     * A test that flips `expanded` and renders a single frame 120ms into a 240ms slide therefore
     * finds the panel at zero, having spent both of those frames in one jump. Call this after
     * flipping the state, then [advance] by the animation time you actually want.
     *
     * Note that [advance] returns the scene's **absolute** elapsed time, not the animation's —
     * `settle()` alone puts 600ms on that clock — so assert on what is rendered rather than on
     * that number.
     */
    fun seedAnimation() {
        advance(FRAME_MILLIS)
        advance(FRAME_MILLIS)
    }

    private var elapsedMillis = 0L

    /** The frame as pixels. */
    fun pixels(): PixelMap = scene.render(elapsedMillis * NANOS_PER_MILLI)
        .toComposeImageBitmap()
        .toPixelMap()

    /** Sends a key down and up, settling after, and reports whether either phase was consumed. */
    fun press(key: Key): Boolean {
        val down = scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
        val up = scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
        settle()
        return down || up
    }

    override fun close() = scene.close()

    internal companion object {
        const val WIDTH = 600
        const val HEIGHT = 300
        const val SETTLE_FRAMES = 30
        const val FRAME_MILLIS = 20L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * The x of the first pixel on row [y] that differs from the row's leftmost pixel, or `null` when
 * the whole row is that one colour.
 *
 * Used to find a sliding panel's leading edge without asserting anything about what the panel is
 * made of: the content behind it is flat, so the first change along the row *is* the edge.
 */
internal fun PixelMap.firstChangeOnRow(y: Int, width: Int): Int? {
    val ground = this[0, y]
    for (x in 1 until width) if (!this[x, y].closeTo(ground)) return x
    return null
}

/** Within one 8-bit step per channel, which is what rounding a composite can cost. */
private fun Color.closeTo(other: Color): Boolean {
    val step = 1f / 255f
    return kotlin.math.abs(red - other.red) <= step &&
        kotlin.math.abs(green - other.green) <= step &&
        kotlin.math.abs(blue - other.blue) <= step
}

/**
 * Moves focus the way a D-pad does, which in an offscreen scene is **not** an arrow key.
 *
 * Measured, not assumed: a scene is given `Key.DirectionDown` and nothing at all takes focus, not
 * even an ordinary enabled button. Desktop Compose wires Tab and Shift-Tab to focus traversal and
 * leaves the arrows to text fields and scroll containers; it is the *Android* focus owner that maps
 * a D-pad to a direction. What both platforms share underneath is
 * [FocusManager.moveFocus], which is what an Android D-pad press ends up calling and therefore what
 * a test about reachability should call.
 *
 * Key events themselves do arrive — the panel's BACK handler and `clickable`'s Enter both work off
 * them in these tests. It is only the arrow-to-direction mapping that is absent.
 *
 * Install this inside the scene's content and drive it from the test:
 *
 * ```kotlin
 * val dpad = Dpad()
 * Harness { dpad.Install(); MyScreen() }.use { harness ->
 *     harness.settle()
 *     dpad.press(FocusDirection.Down, harness)
 * }
 * ```
 */
internal class Dpad {

    private var manager: FocusManager? = null

    @Composable
    fun Install() {
        manager = LocalFocusManager.current
    }

    /** Moves focus and settles, reporting whether anything took it. */
    fun press(direction: FocusDirection, harness: Harness): Boolean {
        val moved = requireNotNull(manager) { "Dpad.Install() was never composed" }
            .moveFocus(direction)
        harness.settle()
        return moved
    }
}

/** Collects a composable's measured bounds, in dp, for an assertion after the frame. */
internal class Bounds {
    private val byName = mutableMapOf<String, Rect>()

    fun record(name: String): (LayoutCoordinates) -> Unit = { coordinates ->
        byName[name] = coordinates.boundsInRoot()
    }

    operator fun get(name: String): Rect = requireNotNull(byName[name]) {
        "nothing recorded for \"$name\"; recorded: ${byName.keys}"
    }
}

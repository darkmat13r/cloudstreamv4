package com.lagradost.cloudstream4.core.devicetest

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import java.util.ArrayDeque

/** The four directions on a remote. */
enum class Dpad(val keyCode: Int) {
    Up(KeyEvent.KEYCODE_DPAD_UP),
    Down(KeyEvent.KEYCODE_DPAD_DOWN),
    Left(KeyEvent.KEYCODE_DPAD_LEFT),
    Right(KeyEvent.KEYCODE_DPAD_RIGHT),
}

/**
 * Presses real D-pad keys and reads what holds focus, the way a remote and a person would.
 *
 * Keys enter at the input dispatcher, so they pass through the activity and any `dispatchKeyEvent` on
 * the way, which a Compose `performKeyInput` skips.
 *
 * ## Why not `UiDevice.pressDPadDown()`
 *
 * Every injected event arrives from the virtual keyboard, device -1, whatever device id it was given
 * (measured on API 36; `input dpad keyevent` is the same, and `sendevent` is denied by SELinux on this
 * image). Compose's text field moves focus for a virtual device only when the source is
 * `SOURCE_HDMI` (`interceptDPadAndMoveFocus`), so `UiDevice`'s keys make a focused field keep all four
 * directions and look like a trap on a screen that has none. This sends `SOURCE_HDMI`, which is what
 * a TV's own remote over HDMI-CEC produces.
 *
 * A stop is named by its text, then its content description, then its class and bounds. Two stops
 * with the same label read as one, so give fixtures distinct titles.
 *
 * @param readFocus names what holds focus. The default reads the accessibility tree, which works over
 *   Compose and Views in the real app. Under `createComposeRule` pass
 *   `{ rule.focusedLabel() }`: exact under the rule's clock, and no wait for the tree to catch up.
 */
class DpadDriver(private val readFocus: (() -> String?)? = null) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    /** What holds focus now, or `null` if nothing does. Waits for it to stop changing. */
    fun focused(): String? {
        // The accessibility tree trails the screen: on `Television_720p` a press still read the old
        // stop straight after it and the new one 2 s later, so it gets that long to catch up.
        if (readFocus == null) Thread.sleep(ACCESSIBILITY_LAG_MS)
        var last = readFocused()
        val deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MS
        var stable = 0
        while (stable < STABLE_READS && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
            val now = readFocused()
            if (now == last) stable++ else stable = 0
            last = now
        }
        return last
    }

    /** Presses [direction] and returns what holds focus afterwards. */
    fun press(direction: Dpad): String? {
        send(direction.keyCode)
        return focused()
    }

    /** Presses D-pad centre on whatever holds focus. */
    fun center() {
        send(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    /** Presses Enter on whatever holds focus: what OK sends on some remotes. */
    fun enterKey() {
        send(KeyEvent.KEYCODE_ENTER)
    }

    /**
     * Presses Back if the on-screen keyboard is open, as a person would. While it is open the D-pad
     * moves between its keys, not between stops on the screen.
     */
    fun closeKeyboard() {
        val deadline = System.currentTimeMillis() + KEYBOARD_CLOSE_MS
        var nextBack = 0L
        while (true) {
            val state = device.executeShellCommand("dumpsys input_method")
            val requested = "mInputShown=true" in state
            if (!requested && "mImeWindowVis=0" in state) return
            val now = System.currentTimeMillis()
            check(now < deadline) { "The keyboard did not close" }
            // Again if it is still up: on a cold keyboard the first Back can arrive before it listens.
            // Only while shown is requested, because a Back with no keyboard would leave the screen.
            if (requested && now >= nextBack) {
                send(KeyEvent.KEYCODE_BACK)
                nextBack = now + KEYBOARD_BACK_RETRY_MS
            }
            Thread.sleep(POLL_MS)
        }
    }

    /** Whether the keyboard comes up within [withinMs]. */
    fun keyboardOpens(withinMs: Long = KEYBOARD_ARRIVAL_MS): Boolean {
        val deadline = System.currentTimeMillis() + withinMs
        while (System.currentTimeMillis() < deadline) {
            if (keyboardShown()) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    /** `mImeWindowVis` and not `mInputShown`: the keyboard keeps taking keys until its window is gone. */
    fun keyboardShown(): Boolean =
        device.executeShellCommand("dumpsys input_method").let { "mInputShown=true" in it || "mImeWindowVis=0" !in it }

    /** Presses until something holds focus: a screen with none yet takes it on the first key. */
    fun enter(direction: Dpad = Dpad.Down): String {
        focused()?.let { return it }
        repeat(ENTER_ATTEMPTS) { press(direction)?.let { label -> return label } }
        error("Nothing took focus after $ENTER_ATTEMPTS presses of $direction")
    }

    /**
     * Maps the screen from where focus is now: presses all four directions on every stop it can get
     * to, moving between stops with key presses only. It stops when no stop it can reach has a
     * direction left to try, so a trap ends the walk rather than hanging it, and [FocusGraph.traps]
     * names it. A stop that opens the keyboard on arrival is noted in [FocusGraph.keyboardOpenedOn] and
     * the keyboard closed with Back, or the walk would be mapping the keyboard's keys.
     */
    fun walk(maxPresses: Int = DEFAULT_MAX_PRESSES): FocusGraph {
        val graph = FocusGraph(enter())
        var current = graph.start
        repeat(maxPresses) {
            val route = graph.routeToUntried(current) ?: return graph
            val direction = route.firstOrNull() ?: Dpad.entries.first { !graph.tried(current, it) }
            // Written even on a known edge: where a key leads can depend on where focus came from,
            // and the last thing measured is the better guess.
            val landed = press(direction)
            graph.record(current, direction, landed)
            if (landed != null && landed != current && keyboardOpens()) {
                graph.keyboardOpenedOn += landed
                closeKeyboard()
            }
            current = landed ?: return graph
        }
        error("Not finished after $maxPresses presses:\n$graph")
    }

    private fun send(keyCode: Int) {
        val time = SystemClock.uptimeMillis()
        listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
            val event = KeyEvent(
                time, time, action, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_HDMI,
            )
            check(instrumentation.uiAutomation.injectInputEvent(event, true)) { "Key $keyCode was not injected" }
        }
    }

    private fun readFocused(): String? {
        readFocus?.let { return it() }
        val node = device.findObject(By.focused(true)) ?: return null
        return node.label() ?: "${node.className}@${node.visibleBounds.toShortString()}"
    }

    /** Compose keeps a merged node's text on its children, so the name can be one level down or more. */
    private fun UiObject2.label(): String? =
        text?.takeIf { it.isNotBlank() }
            ?: contentDescription?.takeIf { it.isNotBlank() }
            ?: children.firstNotNullOfOrNull { it.label() }

    private companion object {
        const val POLL_MS = 120L
        const val ACCESSIBILITY_LAG_MS = 2_000L
        const val KEYBOARD_ARRIVAL_MS = 800L
        const val KEYBOARD_CLOSE_MS = 15_000L
        const val KEYBOARD_BACK_RETRY_MS = 2_000L
        const val STABLE_READS = 2
        const val SETTLE_TIMEOUT_MS = 3_000L
        const val ENTER_ATTEMPTS = 3
        const val DEFAULT_MAX_PRESSES = 500
    }
}

/**
 * What [DpadDriver.walk] found: every stop, and where each direction goes from it.
 *
 * Three questions, one per clause of "every focusable reachable, no dead ends, no traps".
 */
class FocusGraph(val start: String) {
    private val edges = LinkedHashMap<String, MutableMap<Dpad, String?>>()

    /** Stops where landing with the D-pad opened the on-screen keyboard. */
    val keyboardOpenedOn = linkedSetOf<String>()

    internal fun record(from: String, direction: Dpad, to: String?) {
        edges.getOrPut(from) { LinkedHashMap() }[direction] = to
    }

    internal fun tried(stop: String, direction: Dpad): Boolean = edges[stop]?.containsKey(direction) == true

    /** The shortest key sequence from [from] to a stop with a direction not yet pressed. */
    internal fun routeToUntried(from: String): List<Dpad>? =
        search(from) { stop -> Dpad.entries.any { !tried(stop, it) } }

    /** Every stop focus reached, in the order it was found. */
    val reached: Set<String>
        get() = linkedSetOf(start) + edges.keys + edges.values.flatMap { it.values }.filterNotNull()

    /** Which of [expected] no key sequence reached. */
    fun unreachable(expected: Collection<String>): List<String> = expected.filterNot { it in reached }

    /** Stops where no direction moves focus anywhere else. */
    fun deadEnds(): List<String> = reached.filter { stop ->
        edges[stop].orEmpty().values.none { it != null && it != stop }
    }

    /** Stops focus cannot get back to [start] from, including one that lost focus altogether. */
    fun traps(): List<String> = reached.filter { route(it, start) == null } +
        edges.flatMap { (from, out) -> out.filterValues { it == null }.map { "$from -> ${it.key} lost focus" } }

    /** The shortest known key sequence from [from] to [to], or `null` if none is known. */
    fun route(from: String, to: String): List<Dpad>? = search(from) { it == to }

    private fun search(from: String, found: (String) -> Boolean): List<Dpad>? {
        val routes = hashMapOf(from to emptyList<Dpad>())
        val queue = ArrayDeque(listOf(from))
        while (queue.isNotEmpty()) {
            val here = queue.removeFirst()
            if (found(here)) return routes.getValue(here)
            edges[here].orEmpty().forEach { (direction, next) ->
                if (next != null && next !in routes) {
                    routes[next] = routes.getValue(here) + direction
                    queue.addLast(next)
                }
            }
        }
        return null
    }

    override fun toString(): String = edges.entries.joinToString("\n") { (from, out) -> "$from: $out" }
}

/** What holds focus according to this rule's semantics tree, which is exact under its test clock. */
fun ComposeTestRule.focusedLabel(): String? {
    val node = onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull() ?: return null
    val config = node.config
    return config.getOrNull(SemanticsProperties.EditableText)?.text?.takeIf { it.isNotBlank() }
        ?: config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
        ?: config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
        ?: "node#${node.id}"
}

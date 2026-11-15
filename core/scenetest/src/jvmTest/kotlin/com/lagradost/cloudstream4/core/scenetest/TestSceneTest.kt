package com.lagradost.cloudstream4.core.scenetest

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ImageComposeScene
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay

/** `INF-13`: the desktop UI tests' scene runs its coroutines on the test thread, and a frame always ends. */
class TestSceneTest {

    /** `BUG-108`'s cause: a coroutine woken by a real-time wait, while no frame is drawn, ran on `DefaultExecutor`. */
    @Test
    fun `a coroutine woken by a real-time wait runs on the thread that draws the next frame`() {
        val ranOn = ConcurrentLinkedQueue<String>()
        val scene = testScene(SIZE, SIZE) {
            LaunchedEffect(Unit) {
                delay(WAKE_MILLIS)
                ranOn += Thread.currentThread().name
            }
        }
        repeat(FRAMES) { scene.frame(it * FRAME_NANOS) }
        // Its wait ends here, with no frame being drawn.
        Thread.sleep(WAKE_MILLIS * 20)
        repeat(FRAMES) { scene.frame((FRAMES + it) * FRAME_NANOS) }
        val test = Thread.currentThread().name
        assertEquals(1, ranOn.size, "it ran once: ${ranOn.toList()}")
        assertTrue(ranOn.single().startsWith(test), "ran on ${ranOn.single()}, not the test thread $test")
        scene.close()
    }

    /**
     * The reviewer's question on `#385`: does a plain `render`, with no drain, run work woken between frames? Measured:
     * it does not. The work waits for the next `frame`, which runs it on the drawing thread. So a test that misses a
     * `frame` and calls `render` finds that work never done, and fails, rather than racing.
     */
    @Test
    fun `work woken between frames waits for frame, and a plain render leaves it waiting`() {
        val ranOn = ConcurrentLinkedQueue<String>()
        val scene = testScene(SIZE, SIZE) {
            LaunchedEffect(Unit) {
                delay(WAKE_MILLIS)
                ranOn += Thread.currentThread().name
            }
        }
        // Started with frames, so the effect is running and inside its wait.
        repeat(FRAMES) { scene.frame(it * FRAME_NANOS) }
        Thread.sleep(WAKE_MILLIS * 20)
        repeat(FRAMES) { scene.render((FRAMES + it) * FRAME_NANOS) }
        assertTrue(ranOn.isEmpty(), "a plain render ran it: ${ranOn.toList()}")
        repeat(FRAMES) { scene.frame((2 * FRAMES + it) * FRAME_NANOS) }
        val test = Thread.currentThread().name
        assertEquals(1, ranOn.size, "frame ran it once: ${ranOn.toList()}")
        assertTrue(ranOn.single().startsWith(test), "ran on ${ranOn.single()}, not the test thread $test")
        scene.close()
    }

    /**
     * The reviewer's case for `BUG-108`'s helper: a drain runs only what was waiting when it began, so work that
     * dispatches itself forever takes one step a drain, and the drain ends. Drained on a thread of its own, joined with
     * a deadline, so a hang fails this test rather than stopping the suite.
     */
    @Test
    fun `a drain runs what was waiting when it began, so work that re-dispatches forever cannot hang it`() {
        val dispatcher = TestThreadDispatcher()
        val steps = AtomicInteger()
        lateinit var again: Runnable
        again = Runnable {
            steps.incrementAndGet()
            dispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext, again)
        }
        dispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext, again)
        val drainer = thread(isDaemon = true) { repeat(FRAMES) { dispatcher.drain() } }
        drainer.join(DEADLINE_MILLIS)
        assertFalse(drainer.isAlive, "a drain did not end within $DEADLINE_MILLIS ms: ${steps.get()} steps")
        assertEquals(FRAMES, steps.get(), "one step a drain")
    }

    /** `INF-13` part 2: a scene not made by [testScene] has no queue to drain, so [frame] refuses it, not just draws. */
    @Test
    fun `frame refuses a scene that testScene did not make`() {
        val scene = ImageComposeScene(SIZE, SIZE) {}
        val refused = assertFailsWith<IllegalStateException> { scene.frame(0) }
        assertTrue("testScene" in refused.message.orEmpty(), "the message names the fix: ${refused.message}")
        scene.close()
    }

    private companion object {
        const val SIZE = 100
        const val FRAMES = 10
        const val FRAME_NANOS = 16_000_000L
        const val WAKE_MILLIS = 10L
        const val DEADLINE_MILLIS = 10_000L
    }
}

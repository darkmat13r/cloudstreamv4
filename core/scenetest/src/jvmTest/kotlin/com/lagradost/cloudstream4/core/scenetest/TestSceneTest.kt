package com.lagradost.cloudstream4.core.scenetest

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ImageComposeScene
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
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
     *
     * `BUG-118`: the wait starts only when the test opens `opened`, after the start frames. It used to start in the
     * first of them, and under load ten frames outlast a 10 ms wait: a start frame ran the work, and the check below
     * blamed a plain `render` that never touched it. Opened after the start frames, the wait begins in one known
     * frame's drain, and from then on only a drain can run what it wakes, so how long a frame takes no longer matters.
     */
    @Test
    fun `work woken between frames waits for frame, and a plain render leaves it waiting`() {
        val ranOn = ConcurrentLinkedQueue<String>()
        val opened = CompletableDeferred<Unit>()
        val waiting = AtomicBoolean()
        val scene = testScene(SIZE, SIZE) {
            LaunchedEffect(Unit) {
                opened.await()
                waiting.set(true)
                delay(WAKE_MILLIS)
                ranOn += Thread.currentThread().name
            }
        }
        // Started with frames, so the effect is running and held before its wait.
        repeat(FRAMES) { scene.frame(it * FRAME_NANOS) }
        opened.complete(Unit)
        // Frames until the effect has reached its wait.
        var frames = FRAMES
        while (!waiting.get() && frames < 2 * FRAMES) scene.frame(frames++ * FRAME_NANOS)
        assertTrue(waiting.get(), "the effect did not reach its wait within $FRAMES frames of being opened")
        // And exactly one more. The scene's `delay` is a job launched on the scene's dispatcher, which makes the real
        // `delay` on `DefaultExecutor` (Compose's `FlushCoroutineDispatcher`): this frame's drain starts it. A further
        // frame could run what the wait wakes.
        scene.frame(frames++ * FRAME_NANOS)
        Thread.sleep(WAKE_MILLIS * 20)
        assertTrue(ranOn.isEmpty(), "the wait ended before the plain renders, in a frame: ${ranOn.toList()}")
        repeat(FRAMES) { scene.render((frames + it) * FRAME_NANOS) }
        assertTrue(ranOn.isEmpty(), "a plain render ran it: ${ranOn.toList()}")
        repeat(FRAMES) { scene.frame((frames + FRAMES + it) * FRAME_NANOS) }
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

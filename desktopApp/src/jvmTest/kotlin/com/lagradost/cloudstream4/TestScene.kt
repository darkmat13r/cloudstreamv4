package com.lagradost.cloudstream4

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.jetbrains.skia.Image

/**
 * `INF-13`: a scene for a desktop UI test whose coroutines run only on the test thread (`BUG-108`).
 *
 * `ImageComposeScene`'s default context is `Dispatchers.Unconfined`, behind a dispatcher that also runs queued work at
 * once on whatever thread queued it. So a coroutine woken by a real-time wait (the mouse wheel's 50 ms timeout, a
 * tooltip's delay) ran on `kotlinx.coroutines.DefaultExecutor`, and could scroll, and so measure, the page while the
 * test thread was inside `render`: "performMeasureAndLayout called during measure layout", under load.
 *
 * A scene made here hands its coroutines to a [TestThreadDispatcher], and [frame] runs what is waiting, on the thread
 * that draws, before it draws. Work woken between frames, by a real-time wait, waits there for the next [frame]: a
 * plain `render` leaves it waiting (measured, `TestSceneTest`), so a test that misses a [frame] finds that work never
 * done, rather than done on another thread. `render`'s own flush does run what is dispatched while it flushes, on the
 * same thread. Either way no scene work runs on `DefaultExecutor`.
 */
internal fun testScene(
    width: Int,
    height: Int,
    density: Density = Density(1f),
    content: @Composable () -> Unit,
): ImageComposeScene {
    val dispatcher = TestThreadDispatcher()
    return ImageComposeScene(width, height, density, dispatcher, content).also { dispatchers[it] = dispatcher }
}

/** One frame at [nanos], after the work this scene's coroutines left waiting has run, on this thread. */
internal fun ImageComposeScene.frame(nanos: Long): Image {
    dispatchers[this]?.drain()
    return render(nanos)
}

private val dispatchers: MutableMap<ImageComposeScene, TestThreadDispatcher> =
    Collections.synchronizedMap(WeakHashMap())

/**
 * Keeps what is dispatched to it until [drain], on the caller's thread.
 *
 * A drain runs only what was waiting when it began, each once. Work they dispatch waits for the next drain, so work
 * that dispatches itself forever costs one step a drain and the drain still ends: this helper adds no hang of its own.
 * `render`'s own flush is another matter. Compose runs its queue there until it is empty, and work dispatched during
 * that flush joins it, so a coroutine that yields forever spins inside `render` whatever this does (measured: the test
 * JVM ran out of memory in its first frames).
 */
internal class TestThreadDispatcher : CoroutineDispatcher() {
    private val queue = ConcurrentLinkedQueue<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue.add(block)
    }

    fun drain() {
        repeat(queue.size) { queue.poll()?.run() }
    }
}

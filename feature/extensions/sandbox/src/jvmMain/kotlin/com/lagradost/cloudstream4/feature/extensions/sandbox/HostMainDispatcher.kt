package com.lagradost.cloudstream4.feature.extensions.sandbox

import android.os.Handler
import android.os.Looper
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.internal.MainDispatcherFactory

/**
 * `Dispatchers.Main` for a plugin host, on the same thread as `Looper.getMainLooper()`.
 *
 * 15 of 53 sampled plugins refer to it, and without one their first `withContext(Dispatchers.Main)`
 * throws `MissingMainCoroutineDispatcher`. A plugin uses it because its Android original is where UI
 * goes, and here there is no UI: it is a thread, and grants nothing.
 *
 * This file's service entry is on the app's classpath too, and the app runs without a Main dispatcher
 * on purpose (`Main.kt`). So outside a host this refuses, which kotlinx turns into the same missing
 * dispatcher the app has always had. The lowest priority means any real one, if ever added, wins.
 */
@OptIn(InternalCoroutinesApi::class)
class HostMainDispatcherFactory : MainDispatcherFactory {
    override val loadPriority: Int = Int.MIN_VALUE

    override fun createDispatcher(allFactories: List<MainDispatcherFactory>): MainCoroutineDispatcher {
        check(PluginHost.running) { "only a plugin host has a main thread here" }
        return HostMainDispatcher
    }

    override fun hintOnError(): String = "Desktop has no Main dispatcher outside a plugin host."
}

private object HostMainDispatcher : MainCoroutineDispatcher() {
    private val handler = Handler(Looper.getMainLooper())

    override val immediate: MainCoroutineDispatcher = this

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = Looper.myLooper() == null

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        handler.post(block)
    }
}

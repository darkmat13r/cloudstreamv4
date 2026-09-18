package com.lagradost.cloudstream4.core.strings

import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Recomposer
import java.lang.reflect.Proxy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs a `@Composable` that draws nothing, so [UiString.resolve] can be tested — with **no new dependency**.
 *
 * `resolve()` is a `@Composable` that returns a `String` and emits no nodes, so the whole of Compose UI is beside
 * the point: a `BroadcastFrameClock`, a `Recomposer` and an `Applier` that owns no tree are enough, and all three
 * are public API of `androidx.compose.runtime`, which `:core:strings` already `api()`s. Nothing here needs
 * `ui-test`, skiko or a window.
 *
 * ## The one thing that needs reflection, and why
 *
 * `stringResource` and `pluralStringResource` reach the locale through `LocalComposeEnvironment`, whose default
 * calls `isSystemInDarkTheme()` and `LocalDensity.current` — Compose **UI** and **foundation**, neither of which is
 * on this module's classpath, and skiko's native library does not load in this headless JVM anyway. The local and
 * the `ComposeEnvironment` interface it carries are `internal` in Kotlin, so they cannot be named or implemented
 * from here; the value is fetched through its public JVM accessor and the interface is satisfied by a
 * `java.lang.reflect.Proxy`, whose single method returns a `ResourceEnvironment` and never touches the composer.
 *
 * That is a third reflection site in this module's tests, next to `ComposeRuntime` (the `ResourceEnvironment`
 * constructor) and `SystemEnvironment` (the `getResourceEnvironment` var). It is the same trade as those two:
 * `GeneratedResourcesParityTest.composeVersionIsTheVerifiedOne` fails first when Compose moves.
 */
internal object HeadlessComposition {

    /**
     * Composes [content] as if the machine were in [language], and returns the value each composition pass
     * produced — at least [frames] of them, then a few more to prove the value has settled.
     *
     * [frames] is 1 for everything Compose resolves synchronously and 2 for the [CmpPluralGuard] branch, which
     * renders empty for one frame while `Res.readBytes` suspends.
     */
    fun <T> frames(language: String, frames: Int, content: @Composable () -> T): List<T> = runBlocking {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(NoNodes, recomposer)
        val seen = mutableListOf<T>()
        val environment = LOCAL_COMPOSE_ENVIRONMENT provides environmentProxy(language)
        val loop = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        try {
            composition.setContent {
                CompositionLocalProvider(environment) { seen += content() }
            }
            withTimeoutOrNull(TIMEOUT_MILLIS) {
                while (seen.size < frames) {
                    delay(1)
                    clock.sendFrame(0L)
                }
            }
            repeat(SETTLE_FRAMES) {
                delay(1)
                clock.sendFrame(0L)
            }
        } finally {
            composition.dispose()
            recomposer.cancel()
            loop.cancel()
            recomposer.join()
        }
        seen
    }

    /** A `ComposeEnvironment` pinned to one language: no `isSystemInDarkTheme()`, no `LocalDensity`, no skiko. */
    private fun environmentProxy(language: String): Any {
        val environment = ComposeRuntime.environment(language, "", "")
        val type = Class.forName("org.jetbrains.compose.resources.ComposeEnvironment")
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                // (Composer, Int) — a @Composable that reads no state and remembers nothing may ignore both.
                "rememberEnvironment" -> environment
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "ComposeEnvironment($language)"
                else -> error("ComposeEnvironment gained a method: ${method.name}")
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private val LOCAL_COMPOSE_ENVIRONMENT: ProvidableCompositionLocal<Any> =
        Class.forName("org.jetbrains.compose.resources.ResourceEnvironmentKt")
            .getDeclaredMethod("getLocalComposeEnvironment")
            .apply { isAccessible = true }
            .invoke(null) as ProvidableCompositionLocal<Any>

    /** An applier over no tree at all: `resolve()` returns a `String` and emits nothing for it to hold. */
    private object NoNodes : Applier<Unit> {
        override val current = Unit
        override fun down(node: Unit) = Unit
        override fun up() = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun clear() = Unit
    }

    private const val TIMEOUT_MILLIS = 10_000L
    private const val SETTLE_FRAMES = 3
}

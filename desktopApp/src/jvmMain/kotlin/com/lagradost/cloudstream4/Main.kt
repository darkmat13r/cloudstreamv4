package com.lagradost.cloudstream4

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.extensions.DesktopExtensionBootstrap
import com.lagradost.cloudstream4.feature.search.SearchAction
import com.lagradost.cloudstream4.feature.search.SearchEffect
import com.lagradost.cloudstream4.feature.search.SearchViewModel
import com.lagradost.cloudstream4.feature.search.tv.SearchScreenTv
import com.lagradost.cloudstream4.search.DesktopSearchHistory
import com.lagradost.cloudstream4.search.DesktopSearchSource
import com.lagradost.cloudstream4.search.rememberDesktopSearchCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The desktop app.
 *
 * ## What changed here, and why
 *
 * This used to render `Hello, World!`, two theme buttons and one Mihon switch widget. The owner's
 * direction on 2026-10-15 is **desktop first, tested, then the other platforms**, so the window
 * now hosts a real feature: the search vertical's screen, driven by its real state machine with
 * real seams behind it.
 *
 * Desktop is the cheapest surface in this project to build on, and that is measurable rather than
 * a feeling: `:desktopApp` is in no extension-API list and gets no `iosCanary` task, so nothing
 * here can touch `classes.jar` or the iOS canary. It is also the only host where a `commonMain`
 * screen can be run without a device.
 *
 * ## It is the TV screen, on desktop, and that is temporary
 *
 * `docs/plans/kmp-migration.md` §4.4 says screens live in `commonMain` **one per form factor**,
 * and the desktop one does not exist: `docs/plans/screen-implementation-handover.md` §7 records
 * "Mobile — nothing. Desktop — nothing", and its Phase B forbids improvising a screen that has no
 * artboard. So this mounts the drawn TV screen rather than inventing a desktop layout. The window
 * is sized to the TV artboard (960×540 dp) for that reason.
 *
 * ## Providers now come from a jar on disk (`KMP-13`)
 *
 * `APIHolder.apis` used to be permanently empty here, because it is filled only by `PluginManager`,
 * which is Android. [DesktopExtensionBootstrap] now loads the cross-platform extension jars in
 * `~/.cloudstream4/extensions` through `:feature:extensions`' `JvmExtensionRuntime` before the
 * window opens, so `Sources` counts what actually registered and a search queries it.
 *
 * **Before the window, not inside the composition, and that is forced rather than chosen.**
 * `SearchViewModel` reads `source.sources()` once in its `init` block
 * (`SearchViewModel.kt:72-75`), so an extension that loads after the ViewModel exists would leave
 * the chrome row reading `0 / 0` until something else refreshed it. Loading first costs a directory
 * scan on an empty directory in the normal case; the trade is named in the bootstrap's KDoc.
 */
fun main() {
    // The report is printed, not logged: desktop has no logging and `JvmExtensionRuntime`
    // deliberately has none either, so the host is where the words belong.
    runBlocking { DesktopExtensionBootstrap().loadInstalled() }.lines().forEach(::println)

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = WINDOW_TITLE,
            state = rememberWindowState(size = DpSize(ARTBOARD_WIDTH, ARTBOARD_HEIGHT)),
        ) {
            CloudStreamTheme {
                Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                    SearchHost()
                }
            }
        }
    }
}

/**
 * Wires the one screen desktop has.
 *
 * The ViewModel is constructed here rather than fetched from a store because there is one window
 * and one screen; when desktop grows navigation this becomes a real owner. `.claude/rules/mvi-viewmodel.md`
 * asks the UI to read `uiState.collectAsState()`, which is what happens below — the screen itself
 * stays stateless so it can be previewed in all six states.
 */
@Composable
private fun SearchHost() {
    val (copy, prompts) = rememberDesktopSearchCopy()
    val history = remember { DesktopSearchHistory() }

    // `viewModelScope` is Dispatchers.Main.immediate, and desktop JVM has no Main dispatcher
    // unless kotlinx-coroutines-swing is on the classpath — it is not, and adding it is a
    // dependency decision. The host owns a scope instead and cancels it with the composition,
    // which is what SearchViewModel's `scope` parameter is for.
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    DisposableEffect(scope) { onDispose { scope.cancel() } }

    val viewModel = remember(prompts, history, scope) {
        SearchViewModel(
            source = DesktopSearchSource(),
            history = history,
            prompts = prompts,
            scope = scope,
        )
    }
    val state by viewModel.state.collectAsState()

    // NOT `:core:ui`'s ObserveEffect, and that is a finding rather than a preference.
    // `ObserveEffect` wraps `repeatOnLifecycle` (core/ui/.../compose/Viewmodel.kt:95), which does
    // `withContext(Dispatchers.Main.immediate)` inside — so on desktop JVM it throws
    // "Module with the Main dispatcher is missing" the first time an effect flow is observed, and
    // takes the window down with it. `.claude/rules/mvi-viewmodel.md` tells every screen to use it,
    // so every Phase 7 vertical will hit this on desktop. Reported, not changed: it is existing
    // `:core:ui` code on the settings screens' path.
    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                // Opening a result needs a detail screen, which desktop does not have yet.
                // Reported rather than silently dropped: a no-op looks like a broken card.
                is SearchEffect.OpenResult ->
                    println("OpenResult(${effect.id.value}) — no detail screen on desktop yet")
            }
        }
    }
    SearchScreenTv(
        state = state,
        copy = copy,
        // Recording a submitted search is the host's job, the same split :app uses — its
        // SearchViewModel writes history inside search(). The state machine stays unaware of
        // storage, which is why SearchHistoryStore is a separate seam from SearchSource.
        onAction = { action ->
            if (action is SearchAction.Submit) {
                val query = state.query
                scope.launch { history.record(query, System.currentTimeMillis()) }
            }
            viewModel.onAction(action)
        },
    )
}

private const val WINDOW_TITLE = "CloudStream"

/** The TV artboard at its own size: 1920×1080 artboard px halved. */
private val ARTBOARD_WIDTH = 960.dp
private val ARTBOARD_HEIGHT = 540.dp

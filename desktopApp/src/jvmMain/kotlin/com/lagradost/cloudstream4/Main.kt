package com.lagradost.cloudstream4

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.lagradost.cloudstream4.core.designsystem.desktop.forDesktop
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.LocalCloudStreamTypography
import com.lagradost.cloudstream4.detail.DesktopDetailViewModels
import com.lagradost.cloudstream4.extensions.ExtensionApprovals
import com.lagradost.cloudstream4.extensions.ExtensionReviewScreen
import com.lagradost.cloudstream4.extensions.ExtensionsNotice
import com.lagradost.cloudstream4.extensions.SandboxedExtensions
import com.lagradost.cloudstream4.extensions.runExtensionCheck
import com.lagradost.cloudstream4.extensions.runExtensionScan
import com.lagradost.cloudstream4.feature.extensions.sandbox.PluginHost
import com.lagradost.cloudstream4.feature.detail.DetailAction
import com.lagradost.cloudstream4.feature.detail.DetailEffect
import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.feature.detail.desktop.DetailScreenDesktop
import com.lagradost.cloudstream4.feature.home.HomeSource
import com.lagradost.cloudstream4.feature.home.HomeViewModel
import com.lagradost.cloudstream4.feature.search.SearchAction
import com.lagradost.cloudstream4.feature.search.SearchEffect
import com.lagradost.cloudstream4.feature.search.SearchViewModel
import com.lagradost.cloudstream4.feature.search.desktop.SearchScreenDesktop
import com.lagradost.cloudstream4.home.DesktopHomeSource
import com.lagradost.cloudstream4.home.HomeHost
import com.lagradost.cloudstream4.home.TopLevelScreen
import com.lagradost.cloudstream4.home.TopLevelTab
import com.lagradost.cloudstream4.player.MediaHost
import com.lagradost.cloudstream4.player.PlayerScreen
import com.lagradost.cloudstream4.player.PlayerUnavailable
import com.lagradost.cloudstream4.player.VlcPlayer
import com.lagradost.cloudstream4.player.runPlaybackCheck
import com.lagradost.cloudstream4.search.DesktopSearchHistory
import com.lagradost.cloudstream4.search.DesktopSearchSource
import com.lagradost.cloudstream4.search.PosterImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

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
 * ## Desktop has its own screens
 *
 * The owner's direction on 2026-11-02 settles what `docs/plans/screen-implementation-handover.md` left
 * open: colours, type and the 4dp step come from `design/`, and layout and interaction follow Apple's
 * principles for a resizable pointer-driven window. See `docs/plans/desktop-design.md`.
 *
 * ## Providers now come from a jar on disk (`KMP-13`)
 *
 * `APIHolder.apis` used to be permanently empty here, because it is filled only by `PluginManager`,
 * which is Android. [SandboxedExtensions] now starts everything in `~/.cloudstream4/extensions` in
 * sandboxed child processes before the window opens, and registers a proxy for each provider, so
 * `Sources` counts what actually registered and a search queries it. No plugin code runs in this JVM.
 *
 * **Before the window, not inside the composition, and that is forced rather than chosen.**
 * `SearchViewModel` reads `source.sources()` once in its `init` block
 * (`SearchViewModel.kt:72-75`), so an extension that loads after the ViewModel exists would leave
 * the chrome row reading `0 / 0` until something else refreshed it. Loading first costs a directory
 * scan on an empty directory in the normal case; the trade is named in the bootstrap's KDoc.
 *
 * ## An extension with warnings waits to be asked (`KMP-24`)
 *
 * [SandboxedExtensions] holds it back until its exact file has been allowed, and [ExtensionApprovals] carries the
 * answer. A choice takes effect at once, and the two ViewModels that read the provider list once are rebuilt then.
 */
fun main(args: Array<String>) {
    // First, before any window or toolkit class: a packaged app starts its plugin hosts by running
    // itself again, because a jpackage runtime has no `bin/java` to run instead.
    if (args.firstOrNull() == PluginHost.ARGUMENT) {
        PluginHost.main(args.copyOfRange(1, args.size))
        return
    }
    if (args.firstOrNull() == MediaHost.ARGUMENT) {
        MediaHost.main()
        return
    }
    if (args.firstOrNull() == PLAYBACK_CHECK_ARGUMENT) {
        val url = requireNotNull(args.getOrNull(1)) { "$PLAYBACK_CHECK_ARGUMENT needs a url" }
        exitProcess(runPlaybackCheck(url, args.getOrNull(2)?.toIntOrNull() ?: DEFAULT_PLAYBACK_CHECK_SECONDS))
    }
    if (args.firstOrNull() == CHECK_ARGUMENT) {
        exitProcess(runExtensionCheck(args.getOrNull(1) ?: DEFAULT_CHECK_QUERY, verbose = VERBOSE_ARGUMENT in args))
    }

    if (args.firstOrNull() == SCAN_ARGUMENT) exitProcess(runExtensionScan())

    // The report is printed, not logged: desktop has no logging, so the host is where the words go.
    val extensions = SandboxedExtensions()
    val report = runBlocking { extensions.loadInstalled() }
    report.lines().forEach(::println)
    Runtime.getRuntime().addShutdownHook(Thread { extensions.close() })
    val approvals = ExtensionApprovals(extensions, report, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    application {
        val escape = remember { EscapeHandler() }
        Window(
            onCloseRequest = ::exitApplication,
            title = WINDOW_TITLE,
            state = rememberWindowState(size = DpSize(WINDOW_WIDTH, WINDOW_HEIGHT)),
            // At the window, because it has to work wherever focus is, including nowhere at all.
            onPreviewKeyEvent = { event ->
                event.key == Key.Escape && event.type == KeyEventType.KeyDown && escape.handle()
            },
        ) {
            LaunchedEffect(window) {
                window.minimumSize = java.awt.Dimension(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT)
            }
            CloudStreamTheme {
                CompositionLocalProvider(LocalCloudStreamTypography provides CloudStreamTheme.typography.forDesktop()) {
                    Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                        DesktopApp(escape, approvals = approvals)
                    }
                }
            }
        }
    }
}

/** Where the window is. There are few enough screens that a value and a `when` is the whole router. */
private sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object Extensions : Screen
    data class Detail(val id: String) : Screen
    data class Player(val title: String, val link: StreamLink) : Screen
}

/**
 * Home and search at the top level, then a title, then playback.
 *
 * A title's page stays in the back stack under the player, so leaving the player lands on the source
 * list it came from.
 *
 * `viewModelScope` is Dispatchers.Main.immediate, and desktop JVM has no Main dispatcher unless
 * kotlinx-coroutines-swing is on the classpath. The host owns a scope instead and cancels it with the
 * composition, which is what each ViewModel's `scope` parameter is for.
 *
 * @param approvals what the scan held back and what was chosen about it. Null where nothing was loaded.
 * @param onScreen told where the window is, for a test to read.
 */
@Composable
internal fun DesktopApp(
    escape: EscapeHandler,
    home: HomeSource = remember { DesktopHomeSource() },
    approvals: ExtensionApprovals? = null,
    onScreen: (String) -> Unit = {},
) {
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    DisposableEffect(scope) { onDispose { scope.cancel() } }
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val review = approvals?.state?.collectAsState()?.value
    // Allowing or stopping an extension changes what is installed, and both ViewModels read that once when made.
    val providerChanges = review?.providerChanges ?: 0
    val homeViewModel = remember(providerChanges) {
        if (providerChanges > 0) DesktopSearchViewModels.live = null
        HomeViewModel(home, scope)
    }
    val notice: @Composable () -> Unit = {
        if (review != null) {
            ExtensionsNotice(review, onOpen = { stack += Screen.Extensions }) { label, onClick ->
                TopLevelTab(label, selected = false, onClick)
            }
        }
    }
    // A swap and not a push: neither top-level screen is under the other, so Back never goes between them.
    val top: (Screen) -> Unit = { next -> if (stack.size == 1) stack[0] = next }
    // A double click reaches here twice, and must not take two presses of Back to undo.
    val open: (String) -> Unit = { id -> if (stack.last() !is Screen.Detail) stack += Screen.Detail(id) }
    // Pops only when the caller is still on top, so two queued back events cannot empty the stack.
    val back: (Screen) -> Unit = { from ->
        if (stack.size > 1 && stack.last() == from) {
            stack.removeLast()
            if (from is Screen.Detail) DesktopDetailViewModels.forget(from.id)
        }
    }
    // Keeps the scroll position of what is under the top screen, so back returns to the same place.
    val saved = rememberSaveableStateHolder()
    val screen = stack.last()
    SideEffect { onScreen(screen.toString()) }
    saved.SaveableStateProvider(screen.toString()) {
        when (screen) {
            Screen.Home -> TopLevelScreen(true, onHome = {}, onSearch = { top(Screen.Search) }, trailing = notice) {
                HomeHost(
                    homeViewModel,
                    setEscape = { escape.onEscape = it },
                    onOpen = open,
                    onSearch = { top(Screen.Search) },
                )
            }
            Screen.Search -> TopLevelScreen(false, onHome = { top(Screen.Home) }, onSearch = {}, trailing = notice) {
                SideEffect { escape.onEscape = null }
                SearchHost(scope, onOpen = open, onBrowseHome = { top(Screen.Home) })
            }
            Screen.Extensions -> {
                SideEffect { escape.onEscape = { back(screen) } }
                if (approvals != null && review != null) {
                    ExtensionReviewScreen(review, approvals::onAction, onBack = { back(screen) })
                }
            }
            is Screen.Detail -> key(screen.id) {
                DetailHost(
                    id = screen.id,
                    scope = scope,
                    escape = escape,
                    onBack = { back(screen) },
                    onPlay = { title, link -> stack += Screen.Player(title, link) },
                )
            }
            is Screen.Player -> key(screen.link) {
                SideEffect { escape.onEscape = { back(screen) } }
                val player = remember { VlcPlayer.createOrNull() }
                if (player == null) {
                    PlayerUnavailable(onBack = { back(screen) })
                } else {
                    PlayerScreen(player, screen.title, screen.link, onBack = { back(screen) })
                }
            }
        }
    }
}

/** What Escape does on the current screen; null where there is nothing to go back to. */
internal class EscapeHandler {
    var onEscape: (() -> Unit)? = null

    fun handle(): Boolean = onEscape?.let { it(); true } ?: false
}

/** The ViewModel outlives this composable's branch, so going back returns to the results. */
private object DesktopSearchViewModels {
    var live: SearchViewModel? = null
}

@Composable
private fun SearchHost(scope: CoroutineScope, onOpen: (String) -> Unit, onBrowseHome: () -> Unit) {
    val history = remember { DesktopSearchHistory() }
    val viewModel = remember {
        DesktopSearchViewModels.live
            ?: SearchViewModel(source = DesktopSearchSource(), history = history, scope = scope)
                .also { DesktopSearchViewModels.live = it }
    }
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is SearchEffect.OpenResult -> onOpen(effect.id.value)
                SearchEffect.BrowseHome -> onBrowseHome()
            }
        }
    }
    SearchScreenDesktop(
        state = state,
        // Recording a submitted search is the host's job, the same split :app uses. The state machine
        // stays unaware of storage, which is why it is a separate seam.
        onAction = { action ->
            if (action is SearchAction.Submit) {
                val query = state.query
                scope.launch { history.record(query, System.currentTimeMillis()) }
            }
            viewModel.onAction(action)
        },
        artwork = { item ->
            // Only public http(s) addresses get this far. See `posterHttpClient`.
            PosterImage(url = item.posterUrl, modifier = Modifier.fillMaxSize())
        },
    )
}

@Composable
private fun DetailHost(
    id: String,
    scope: CoroutineScope,
    escape: EscapeHandler,
    onBack: () -> Unit,
    onPlay: (String, StreamLink) -> Unit,
) {
    val viewModel = remember(id) { DesktopDetailViewModels.get(id, scope) }
    val state by viewModel.state.collectAsState()
    SideEffect {
        // The same two steps as the visible Back button: the source list first, then the page.
        escape.onEscape = {
            if (viewModel.state.value.links != null) viewModel.onAction(DetailAction.LinksDismissed) else onBack()
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is DetailEffect.Play -> onPlay(effect.title, effect.link)
            }
        }
    }
    DetailScreenDesktop(
        state = state,
        onAction = viewModel::onAction,
        onBack = onBack,
        artwork = { url -> PosterImage(url = url, modifier = Modifier.fillMaxSize()) },
    )
}

private const val WINDOW_TITLE = "CloudStream"
private const val CHECK_ARGUMENT = "--check-extensions"
private const val SCAN_ARGUMENT = "--scan-extensions"
private const val DEFAULT_CHECK_QUERY = "nasa"
private const val VERBOSE_ARGUMENT = "--verbose"
private const val PLAYBACK_CHECK_ARGUMENT = "--check-playback"
private const val DEFAULT_PLAYBACK_CHECK_SECONDS = 15

private val WINDOW_WIDTH = 1280.dp
private val WINDOW_HEIGHT = 800.dp
private const val MIN_WINDOW_WIDTH = 800
private const val MIN_WINDOW_HEIGHT = 520

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import com.lagradost.cloudstream4.core.connectivity.UnknownConnectivityFlow
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
import com.lagradost.cloudstream4.feature.detail.DetailLoad
import com.lagradost.cloudstream4.feature.library.LibraryResume
import com.lagradost.cloudstream4.feature.library.LibraryStore
import com.lagradost.cloudstream4.feature.library.LibraryTitle
import com.lagradost.cloudstream4.feature.library.LibraryTitleId
import com.lagradost.cloudstream4.feature.library.LibraryViewModel
import com.lagradost.cloudstream4.home.FileHomeSourceMemory
import com.lagradost.cloudstream4.downloads.DesktopDownloads
import com.lagradost.cloudstream4.downloads.DownloadSeriesHost
import com.lagradost.cloudstream4.downloads.DownloadsHost
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleId
import com.lagradost.cloudstream4.feature.downloads.DownloadsViewModel
import com.lagradost.cloudstream4.home.TopLevelDestination
import com.lagradost.cloudstream4.library.DesktopLibraryStore
import com.lagradost.cloudstream4.library.LibraryHost
import com.lagradost.cloudstream4.library.WatchRecorder
import com.lagradost.cloudstream4.feature.detail.DetailEffect
import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.feature.detail.desktop.DetailScreenDesktop
import com.lagradost.cloudstream4.feature.home.HomeSource
import com.lagradost.cloudstream4.feature.home.HomeSourceMemory
import com.lagradost.cloudstream4.feature.home.HomeViewModel
import com.lagradost.cloudstream4.feature.search.SearchAction
import com.lagradost.cloudstream4.feature.search.SearchEffect
import com.lagradost.cloudstream4.feature.search.SearchViewModel
import com.lagradost.cloudstream4.feature.search.desktop.SearchScreenDesktop
import com.lagradost.cloudstream4.home.DesktopHomeBookmarks
import com.lagradost.cloudstream4.home.DesktopHomeHistory
import com.lagradost.cloudstream4.home.DesktopHomeSource
import com.lagradost.cloudstream4.home.DesktopMenuBar
import com.lagradost.cloudstream4.home.DesktopShortcuts
import com.lagradost.cloudstream4.home.label
import com.lagradost.cloudstream4.home.HomeHost
import com.lagradost.cloudstream4.home.TopLevelScreen
import com.lagradost.cloudstream4.home.TopLevelTab
import com.lagradost.cloudstream4.player.MediaHost
import com.lagradost.cloudstream4.player.PlayerFullscreen
import com.lagradost.cloudstream4.player.PlayerScreen
import com.lagradost.cloudstream4.player.PlayerUnavailable
import com.lagradost.cloudstream4.player.VlcPlayer
import com.lagradost.cloudstream4.player.runPlaybackCheck
import com.lagradost.cloudstream4.player.runSubtitleRenderCheck
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
 * **Before the window, not inside the composition.** `SearchViewModel` reads `source.sources()` in
 * its `init` block, so an extension that loads after the ViewModel exists would leave the chrome row
 * reading `0 / 0` until something refreshed it. Loading first costs a directory scan on an empty
 * directory in the normal case; the trade is named in the bootstrap's KDoc.
 *
 * Since `BUG-24` the count is no longer read only once: [SearchHost] re-asks on `SourcesChanged`
 * whenever an approval changes what is installed. The pre-window load is what makes the **first**
 * paint right, and that is still the reason for the ordering.
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
    if (args.firstOrNull() == SUBTITLE_RENDER_CHECK_ARGUMENT) {
        exitProcess(runSubtitleRenderCheck(args.drop(1)))
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
    // `KMP-52`'s service: ~/Downloads/CloudStream and ~/.cloudstream4/downloads.json. Closed on the way out, so a running
    // download stops cleanly and comes back Paused.
    val downloads = DesktopDownloads()
    Runtime.getRuntime().addShutdownHook(Thread { runBlocking { downloads.close() } })

    application {
        val escape = remember { EscapeHandler() }
        val shortcuts = remember { DesktopShortcuts() }
        val windowState = rememberWindowState(size = DpSize(WINDOW_WIDTH, WINDOW_HEIGHT))
        val homeMemory = remember { FileHomeSourceMemory() }
        Window(
            onCloseRequest = ::exitApplication,
            title = WINDOW_TITLE,
            state = windowState,
            // At the window, because it has to work wherever focus is, including nowhere at all.
            onPreviewKeyEvent = { event ->
                event.key == Key.Escape && event.type == KeyEventType.KeyDown && escape.handle()
            },
        ) {
            // The window's ⌘ keys live here and nowhere else, so none fires twice.
            DesktopMenuBar(shortcuts) { it.label() }
            LaunchedEffect(window) {
                window.minimumSize = java.awt.Dimension(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT)
            }
            CloudStreamTheme {
                CompositionLocalProvider(LocalCloudStreamTypography provides CloudStreamTheme.typography.forDesktop()) {
                    Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                        DesktopApp(
                            escape,
                            approvals = approvals,
                            window = windowState,
                            homeMemory = homeMemory,
                            shortcuts = shortcuts,
                            downloads = downloads,
                        )
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
    data object Library : Screen
    data object Downloads : Screen

    /** Board D4: one series' episodes, over the list. */
    data class DownloadSeries(val id: DownloadTitleId) : Screen
    data object Extensions : Screen

    /** @param resume non-null when the library asked to come back to where this title was left. */
    data class Detail(val id: String, val resume: LibraryResume? = null) : Screen

    /**
     * @param kept the id plus what a card needs, so the position written while this plays can be drawn
     *   later without asking a provider again.
     * @param episodeData which episode is playing, opaque, or null for a title that plays itself.
     */
    data class Player(
        val kept: LibraryTitle,
        val episodeData: String?,
        val title: String,
        val link: StreamLink,
        val startAtMillis: Long = 0L,
    ) : Screen
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
 * @param window what the player puts in full screen. Null where there is no window, as in a test.
 * @param homeMemory the home source chosen last. Keeps nothing by default, so a test cannot write to a real home.
 * @param shortcuts what the menu bar's Go menu and its keys do; this sets it for the screen on top (`KMP-53`).
 * @param downloads the download service. Null by default, so a test cannot read or write a real downloads file, and the
 *   page is then empty; the app passes the real one.
 */
@Composable
internal fun DesktopApp(
    escape: EscapeHandler,
    home: HomeSource = remember { DesktopHomeSource() },
    approvals: ExtensionApprovals? = null,
    // After `approvals` so that every existing positional call still means what it did.
    library: LibraryStore = remember { DesktopLibraryStore() },
    onScreen: (String) -> Unit = {},
    window: WindowState? = null,
    homeMemory: HomeSourceMemory = HomeSourceMemory.None,
    shortcuts: DesktopShortcuts = remember { DesktopShortcuts() },
    downloads: DesktopDownloads? = null,
) {
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    DisposableEffect(scope) { onDispose { scope.cancel() } }
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val review = approvals?.state?.collectAsState()?.value
    // Allowing or stopping an extension changes what is installed. `HomeViewModel` reads that once
    // when made, so it is rebuilt; `SearchViewModel` is told with `SourcesChanged` instead (BUG-24),
    // which keeps the results and the typed query the user is looking at.
    val providerChanges = review?.providerChanges ?: 0
    val homeViewModel = remember(providerChanges) {
        // KMP-55: Continue watching and Bookmarks come from the same store the library page reads.
        HomeViewModel(home, scope, homeMemory, DesktopHomeHistory(library), DesktopHomeBookmarks(library))
    }
    // Outlives the tab so that changing list is instant; `LibraryHost` refreshes it on arrival. It
    // reads no providers, so it does not need resetting when what is installed changes.
    val libraryViewModel = remember { LibraryViewModel(library, scope) }
    // The same for Downloads: it reads the service for the window's life, and keeps the selection and the row to return
    // to while a series is open over it. Made in the host, it would be made again, with its collectors, on every visit.
    val downloadsViewModel = remember(downloads) {
        downloads?.let { DownloadsViewModel(it.source(), UnknownConnectivityFlow, scope) }
    }
    val notice: @Composable () -> Unit = {
        if (review != null) {
            ExtensionsNotice(review, onOpen = { stack += Screen.Extensions }) { label, onClick ->
                TopLevelTab(label, selected = false, onClick)
            }
        }
    }
    // A swap and not a push: no top-level screen is under another, so Back never goes between them.
    val top: (Screen) -> Unit = { next -> if (stack.size == 1) stack[0] = next }
    // A double click reaches here twice, and must not take two presses of Back to undo.
    val open: (String) -> Unit = { id -> if (stack.last() !is Screen.Detail) stack += Screen.Detail(id) }
    // Resume is the same page, told which episode to ask for and where that episode was left.
    val resume: (LibraryTitleId, LibraryResume) -> Unit = { id, where ->
        if (stack.last() !is Screen.Detail) stack += Screen.Detail(id.value, where)
    }
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
        // One place decides what a tab press does, so a new tab cannot be wired up two ways.
        val toTab: (TopLevelDestination) -> Unit = { destination ->
            top(
                when (destination) {
                    TopLevelDestination.Home -> Screen.Home
                    TopLevelDestination.Search -> Screen.Search
                    TopLevelDestination.Library -> Screen.Library
                    TopLevelDestination.Downloads -> Screen.Downloads
                },
            )
        }
        // ⌘1 to ⌘4 work only at the top level, as the sidebar is only there; ⌘[ wherever there is something under.
        SideEffect {
            shortcuts.onDestination = if (stack.size == 1) toTab else null
            shortcuts.onBack = if (stack.size > 1) ({ back(screen) }) else null
        }
        when (screen) {
            Screen.Home -> TopLevelScreen(TopLevelDestination.Home, toTab, trailing = notice) {
                HomeHost(
                    homeViewModel,
                    setEscape = { escape.onEscape = it },
                    onOpen = open,
                    onSearch = { top(Screen.Search) },
                )
            }
            Screen.Search -> TopLevelScreen(TopLevelDestination.Search, toTab, trailing = notice) {
                SideEffect { escape.onEscape = null }
                SearchHost(scope, providerChanges, onOpen = open, onBrowseHome = { top(Screen.Home) })
            }
            Screen.Library -> TopLevelScreen(TopLevelDestination.Library, toTab, trailing = notice) {
                LibraryHost(
                    libraryViewModel,
                    setEscape = { escape.onEscape = it },
                    onOpen = { id -> open(id.value) },
                    onResume = resume,
                    onSearch = { top(Screen.Search) },
                )
            }
            Screen.Downloads -> TopLevelScreen(TopLevelDestination.Downloads, toTab, trailing = notice) {
                if (downloads != null && downloadsViewModel != null) {
                    DownloadsHost(
                        downloads,
                        downloadsViewModel,
                        scope,
                        setEscape = { escape.onEscape = it },
                        onOpenTitle = { id -> if (stack.last() !is Screen.DownloadSeries) stack += Screen.DownloadSeries(id) },
                        onLibrary = { top(Screen.Library) },
                    )
                }
            }
            is Screen.DownloadSeries -> key(screen.id) {
                if (downloads != null) {
                    DownloadSeriesHost(downloads, screen.id, scope, setEscape = { escape.onEscape = it }, onClose = { back(screen) })
                }
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
                    resume = screen.resume,
                    onBack = { back(screen) },
                    onPlay = { kept, episodeData, title, link ->
                        stack += Screen.Player(kept, episodeData, title, link, screen.resume?.positionMillis ?: 0L)
                    },
                )
            }
            is Screen.Player -> key(screen.link) {
                SideEffect { escape.onEscape = { back(screen) } }
                val player = remember { VlcPlayer.createOrNull() }
                val arrivingSubtitles = remember(screen) { DesktopDetailViewModels.subtitles(screen.kept.id.value) }
                val recorder = remember(screen) {
                    WatchRecorder(library, screen.kept, screen.episodeData, scope)
                }
                if (player == null) {
                    PlayerUnavailable(onBack = { back(screen) })
                } else {
                    PlayerScreen(
                        player,
                        screen.title,
                        screen.link,
                        onBack = { back(screen) },
                        setEscape = { escape.onEscape = it },
                        startAtMillis = screen.startAtMillis,
                        onProgress = recorder::onProgress,
                        arrivingSubtitles = arrivingSubtitles,
                        fullscreen = remember(screen, window) { window?.let(::PlayerFullscreen) },
                        links = remember(screen) { DesktopDetailViewModels.links(screen.kept.id.value) },
                        openPlayer = VlcPlayer::createOrNull,
                    )
                }
                DisposableEffect(screen) {
                    onDispose { recorder.flush() }
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
private fun SearchHost(
    scope: CoroutineScope,
    providerChanges: Int,
    onOpen: (String) -> Unit,
    onBrowseHome: () -> Unit,
) {
    val history = remember { DesktopSearchHistory() }
    val viewModel = remember {
        DesktopSearchViewModels.live
            ?: SearchViewModel(source = DesktopSearchSource(), history = history, scope = scope)
                .also { DesktopSearchViewModels.live = it }
    }
    val state by viewModel.state.collectAsState()
    // The count in the chrome is read when the ViewModel is made, and extensions can be allowed or
    // stopped after that. This is the re-read; it starts no search.
    LaunchedEffect(viewModel, providerChanges) { viewModel.onAction(SearchAction.SourcesChanged) }
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

/**
 * A title's page.
 *
 * @param resume where the library left this title, or null. Non-null asks for that episode's sources as
 *   soon as the page has loaded — which is what Resume means here: the right episode, starting where it
 *   was left. Asking plays the best link found, as Play does (`KMP-39`), unless a row is chosen first.
 * @param onPlay given what the library needs to record a position, as well as what the player needs.
 *   The episode is the `data` of the last [DetailAction.PlayRequested] to pass through this host, which
 *   is the only place that knows it: `DetailState` does not carry it.
 */
@Composable
private fun DetailHost(
    id: String,
    scope: CoroutineScope,
    escape: EscapeHandler,
    resume: LibraryResume?,
    onBack: () -> Unit,
    onPlay: (LibraryTitle, String?, String, StreamLink) -> Unit,
) {
    val viewModel = remember(id) { DesktopDetailViewModels.get(id, scope) }
    val state by viewModel.state.collectAsState()
    var playing by remember(id) { mutableStateOf<String?>(null) }
    val act: (DetailAction) -> Unit = { action ->
        if (action is DetailAction.PlayRequested) playing = action.data
        viewModel.onAction(action)
    }
    SideEffect {
        // The same two steps as the visible Back button: the source list first, then the page.
        escape.onEscape = {
            if (viewModel.state.value.links != null) viewModel.onAction(DetailAction.LinksDismissed) else onBack()
        }
    }
    val loaded = (state.content as? DetailLoad.Loaded)?.value
    LaunchedEffect(viewModel, resume, loaded) {
        // Only while nothing has been asked for yet, so going back to the source list does not re-ask.
        if (resume != null && loaded != null && playing == null && state.links == null) {
            (resume.episodeData ?: loaded.primaryData)?.let { act(DetailAction.PlayRequested(it)) }
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            val detail = (viewModel.state.value.content as? DetailLoad.Loaded)?.value
            val kept = LibraryTitle(
                id = LibraryTitleId(id),
                title = detail?.title ?: effect.let { if (it is DetailEffect.Play) it.title else "" },
                bucket = null,
                keptAt = 0L,
                posterUrl = detail?.posterUrl,
                providerName = detail?.providerName,
            )
            when (effect) {
                is DetailEffect.Play -> onPlay(kept, playing, effect.title, effect.link)
                // The three Android effects. `DetailScreenDesktop` sends none of the actions that
                // produce them — it plays by picking a stream from the source list, which is
                // `PlayRequested` and then `Play` above, and it draws no trailer button and no
                // recommendations rail. They are named rather than swept up by an `else` so that a
                // fourth effect still fails this build instead of being silently dropped here.
                is DetailEffect.OpenPlayer, DetailEffect.PlayTrailer, is DetailEffect.OpenTitle -> Unit
            }
        }
    }
    DetailScreenDesktop(
        state = state,
        onAction = act,
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
private const val SUBTITLE_RENDER_CHECK_ARGUMENT = "--check-subtitle-render"

private val WINDOW_WIDTH = 1280.dp
private val WINDOW_HEIGHT = 800.dp
private const val MIN_WINDOW_WIDTH = 800
private const val MIN_WINDOW_HEIGHT = 520

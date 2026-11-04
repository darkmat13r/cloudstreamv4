package com.lagradost.cloudstream4.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.primitive.ProgressRule
import com.lagradost.cloudstream4.core.designsystem.state.StateAction
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.designsystem.state.StateView
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButtonTone
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopIconButton
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopMotion
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.error_loading_links_toast
import com.lagradost.cloudstream4.core.strings.generated.resources.go_back
import com.lagradost.cloudstream4.core.strings.generated.resources.loading
import com.lagradost.cloudstream4.core.strings.generated.resources.no_subtitles
import com.lagradost.cloudstream4.core.strings.generated.resources.pause
import com.lagradost.cloudstream4.core.strings.generated.resources.player_enter_fullscreen
import com.lagradost.cloudstream4.core.strings.generated.resources.player_exit_fullscreen
import com.lagradost.cloudstream4.core.strings.generated.resources.player_fit_title
import com.lagradost.cloudstream4.core.strings.generated.resources.player_mute
import com.lagradost.cloudstream4.core.strings.generated.resources.player_subtitles_settings
import com.lagradost.cloudstream4.core.strings.generated.resources.player_unmute
import com.lagradost.cloudstream4.core.strings.generated.resources.resume
import com.lagradost.cloudstream4.core.strings.generated.resources.seek_back_10_seconds
import com.lagradost.cloudstream4.core.strings.generated.resources.seek_forward_10_seconds
import com.lagradost.cloudstream4.core.strings.generated.resources.source_error
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.media.model.SubtitleCue
import com.lagradost.cloudstream4.media.model.SubtitleTrack
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.jetbrains.compose.resources.stringResource

/**
 * The desktop player (`docs/plans/desktop-design.md`): full-bleed video, one seek track, transport, and
 * volume and subtitles. Space pauses, left and right skip, up and down change the volume, M mutes, C opens
 * the subtitle list, R how the picture fits the window, F or Cmd+F toggles full screen; Escape is the window's.
 *
 * The source and audio selectors are not built. Lives in `:desktopApp` rather than a shared module because
 * it is bound to [VlcPlayer].
 *
 * @param player started and released by this screen, so leaving it stops the audio.
 * @param onBack leaves the screen, which is also what the end of the stream does.
 * @param setEscape told what Escape should do now, as `HomeHost` is.
 * @param startAtMillis where to start, from the library (`KMP-26`). Zero plays from the beginning.
 *   The seek waits for a reported length, because [VlcPlayer.seekTo] takes a fraction and there is
 *   nothing to take a fraction of until then, and it happens **once** — a second one would fight the
 *   person's own seeking.
 * @param onProgress told the position as the player reports it. Storage is the host's: this screen
 *   writes nothing, and the host decides which of these reports is worth keeping.
 * @param clock,session passed in so a check can read what this screen is showing.
 * @param arrivingSubtitles the tracks the search behind [link] has found so far, each time it finds more.
 * @param fullscreen the window's full screen, left when this screen closes. Null with no window to fill, as
 *   in a check, and then there is no control for it.
 */
@Composable
internal fun PlayerScreen(
    player: VlcPlayer,
    title: String,
    link: StreamLink,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    setEscape: (() -> Unit) -> Unit = {},
    startAtMillis: Long = 0L,
    onProgress: (positionMillis: Long, durationMillis: Long) -> Unit = { _, _ -> },
    clock: PlaybackClock = remember { PlaybackClock() },
    session: SubtitleSession? = null,
    arrivingSubtitles: Flow<List<SubtitleTrack>> = emptyFlow(),
    fullscreen: PlayerFullscreen? = null,
) {
    val frame by player.frame.collectAsState()
    val pictureSize by player.pictureSize.collectAsState()
    val status by player.status.collectAsState()
    var started by remember { mutableStateOf(true) }
    DisposableEffect(player, link) {
        started = player.play(link.url, link.referer, link.userAgent, link.audioUrl)
        // Off the UI thread: stopping libVLC blocks, and can take seconds on a stalled stream.
        onDispose { thread(isDaemon = true, name = "vlc-release") { player.release() } }
    }
    DisposableEffect(fullscreen) { onDispose { fullscreen?.leave() } }
    LaunchedEffect(status.finished) { if (status.finished) onBack() }
    LaunchedEffect(player, clock) { player.status.collect(clock::onStatus) }
    val progress by rememberUpdatedState(onProgress)
    LaunchedEffect(player, link) {
        var sought = startAtMillis <= 0L
        player.status.collect { reported ->
            if (!sought && reported.lengthMillis > 0L) {
                sought = true
                player.seekTo(startAtMillis.toFloat() / reported.lengthMillis)
            }
            progress(reported.positionMillis, reported.lengthMillis)
        }
    }
    val scope = rememberCoroutineScope()
    val subtitles = session ?: remember(link) {
        SubtitleSession(link.subtitles, HttpSubtitleLoader(), scope, desktopSubtitlePreference)
    }
    LaunchedEffect(subtitles, arrivingSubtitles) { arrivingSubtitles.collect(subtitles::offer) }
    PlayerContent(
        frame = frame,
        pictureSize = pictureSize,
        status = status.copy(failed = !started || status.failed),
        caption = listOfNotNull(title, link.qualityLabel, link.label).joinToString(SEPARATOR),
        controls = player,
        subtitles = subtitles,
        clock = clock,
        onBack = onBack,
        setEscape = setEscape,
        modifier = modifier,
        fullscreen = fullscreen,
        volume = desktopPlayerVolume,
        fit = desktopPictureFit,
    )
}

/** The screen itself, over whatever supplies frames and status, so a test can press its keys. */
@OptIn(ExperimentalComposeUiApi::class) // onPointerEvent: the only way to see a mouse move, for the HUD.
@Composable
internal fun PlayerContent(
    frame: ImageBitmap?,
    status: PlayerStatus,
    caption: String,
    controls: PlayerControls,
    subtitles: SubtitleSession,
    clock: PlaybackClock,
    onBack: () -> Unit,
    setEscape: (() -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    pictureSize: IntSize? = null,
    fullscreen: PlayerFullscreen? = null,
    volume: PlayerVolume = remember { PlayerVolume() },
    fit: PictureFitChoice = remember { PictureFitChoice() },
) {
    LaunchedEffect(controls, volume.audible) { controls.setVolume(volume.audible) }
    val tracks by subtitles.tracks.collectAsState()
    val selection by subtitles.selection.collectAsState()
    val failures by subtitles.failures.collectAsState()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var lastActivity by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var hudVisible by remember { mutableStateOf(true) }
    var pickerOpen by remember { mutableStateOf(false) }
    var fitOpen by remember { mutableStateOf(false) }
    val listOpen = pickerOpen || fitOpen
    val failed = status.failed
    val fullscreenOn = fullscreen?.on == true
    SideEffect {
        setEscape {
            when {
                pickerOpen -> pickerOpen = false
                fitOpen -> fitOpen = false
                fullscreenOn -> fullscreen?.toggle()
                else -> onBack()
            }
        }
    }
    // Focus goes back to the root before a focused button leaves the composition, including the pause
    // button a failure removes. Otherwise it is cleared rather than moved, and the root's key handler
    // stops seeing any key, so Space and the arrows go dead. The list takes focus while it is open.
    LaunchedEffect(focus, failed, listOpen) { if (!listOpen) runCatching { focus.requestFocus() } }
    LaunchedEffect(lastActivity, status.playing, listOpen) {
        hudVisible = true
        if (status.playing && !listOpen) {
            delay(PlayerScreenDefaults.HudTimeoutMillis)
            runCatching { focus.requestFocus() }
            hudVisible = false
        }
    }
    val hudShown = hudVisible || failed || listOpen
    // Held while the list is open. It grows upward from its foot, so a track arriving would slide the row under the
    // pointer just as it is clicked. An empty list has no rows to slide, so the first tracks do appear in it.
    val listed = remember(pickerOpen, tracks.isEmpty()) { tracks }
    val seekBy: (Long) -> Unit = { delta ->
        clock.onSeekBy(delta)
        controls.seekBy(delta)
    }
    val seekTo: (Float) -> Unit = { fraction ->
        clock.onSeekTo(fraction)
        controls.seekTo(fraction)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CloudStreamTheme.colors.bg)
            .focusRequester(focus)
            .focusable()
            .onPointerEvent(PointerEventType.Move) { lastActivity = System.currentTimeMillis() }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                lastActivity = System.currentTimeMillis()
                if (listOpen) {
                    // The list has the keys now. Space and Enter are its rows', the arrows move between them.
                    return@onPreviewKeyEvent when (event.key) {
                        Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                        Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                        Key.C -> {
                            pickerOpen = false
                            true
                        }
                        Key.R -> {
                            fitOpen = false
                            true
                        }
                        else -> false
                    }
                }
                when (event.key) {
                    Key.Spacebar -> controls.togglePause()
                    Key.DirectionLeft -> seekBy(-PlayerScreenDefaults.SkipMillis)
                    Key.DirectionRight -> seekBy(PlayerScreenDefaults.SkipMillis)
                    Key.DirectionUp -> volume.step(PlayerVolume.STEP)
                    Key.DirectionDown -> volume.step(-PlayerVolume.STEP)
                    Key.M -> volume.toggleMute()
                    Key.C -> if (!failed) pickerOpen = true
                    Key.R -> fitOpen = true
                    // F alone or with Cmd: the modifier is not read, so both come here.
                    Key.F -> fullscreen?.toggle() ?: return@onPreviewKeyEvent false
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        frame?.let { image ->
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = fit.current.contentScale(pictureSize),
                filterQuality = FilterQuality.Low,
                modifier = Modifier.fillMaxSize(),
            )
        }
        val timeline = (selection as? SubtitleSelection.Showing)?.timeline
        val cues by produceState(emptyList<SubtitleCue>(), timeline, clock) {
            value = emptyList()
            if (timeline != null) {
                while (true) {
                    value = timeline.activeAt(clock.positionMillis())
                    delay(PlayerScreenDefaults.CueTickMillis)
                }
            }
        }
        val topInset by animateDpAsState(if (hudShown) PlayerScreenDefaults.HudTopInset else 0.dp, DesktopMotion.spec())
        val bottomInset by animateDpAsState(if (hudShown) PlayerScreenDefaults.HudBottomInset else 0.dp, DesktopMotion.spec())
        SubtitleOverlay(
            cues = cues,
            pictureSize = pictureSize ?: frame?.let { IntSize(it.width, it.height) },
            topInset = topInset,
            bottomInset = bottomInset,
            fit = fit.current,
        )
        // Not over the list: the row it came from is saying the same thing.
        if (!listOpen) SubtitleNotice(selection, bottomInset)
        AnimatedVisibility(
            visible = hudShown,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Hud(
                caption = caption,
                status = status,
                onTogglePause = controls::togglePause,
                onSeekTo = seekTo,
                onSeekBy = seekBy,
                onBack = onBack,
                volumeControl = { VolumeControl(volume) },
                selectors = {
                    if (!failed) {
                        PlayerSelector(
                            label = stringResource(Res.string.player_subtitles_settings),
                            value = selection.track()?.label ?: stringResource(Res.string.no_subtitles),
                            onClick = {
                                fitOpen = false
                                pickerOpen = !pickerOpen
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    PlayerSelector(
                        label = stringResource(Res.string.player_fit_title),
                        value = stringResource(fit.current.words()),
                        onClick = {
                            pickerOpen = false
                            fitOpen = !fitOpen
                        },
                        modifier = Modifier.weight(1f),
                    )
                },
                fullscreenControl = {
                    if (fullscreen != null) {
                        DesktopIconButton(
                            icon = if (fullscreenOn) CloudStreamIcons.ExitFullscreen else CloudStreamIcons.EnterFullscreen,
                            contentDescription = stringResource(
                                if (fullscreenOn) Res.string.player_exit_fullscreen else Res.string.player_enter_fullscreen,
                            ),
                            onClick = fullscreen::toggle,
                            tone = DesktopButtonTone.Quiet,
                        )
                    }
                },
            )
        }
        if (fitOpen) {
            PictureFitPicker(
                current = fit.current,
                onSelect = { chosen ->
                    fit.choose(chosen)
                    fitOpen = false
                },
                onDismiss = { fitOpen = false },
            )
        }
        if (pickerOpen) {
            SubtitlePicker(
                tracks = listed,
                selection = selection,
                failures = failures,
                onSelect = { track ->
                    subtitles.select(track)
                    pickerOpen = false
                },
                onDismiss = { pickerOpen = false },
            )
        }
    }
}

/**
 * Says what became of a track, then gets out of the way. It lasts a few seconds because the video goes on
 * underneath, and the track's row keeps saying it for as long as anyone looks.
 */
@Composable
private fun BoxScope.SubtitleNotice(selection: SubtitleSelection, bottomInset: Dp) {
    val failure = selection as? SubtitleSelection.Failed
    var shown by remember { mutableStateOf<SubtitleFailure?>(null) }
    LaunchedEffect(failure) {
        shown = failure?.reason
        if (failure != null) {
            delay(PlayerScreenDefaults.NoticeMillis)
            shown = null
        }
    }
    val reason = shown ?: return
    BasicText(
        text = stringResource(failureWords(reason)),
        style = CloudStreamTheme.typography.body.copy(color = CloudStreamTheme.colors.ink),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = bottomInset + DesktopDimens.WindowMargin)
            .background(
                CloudStreamTheme.colors.surface.copy(alpha = PlayerScreenDefaults.ControlsAlpha),
                CloudStreamTheme.shapes.medium,
            )
            .padding(horizontal = DesktopDimens.GroupGap, vertical = DesktopDimens.RelatedGap),
    )
}

/**
 * Back and the title at the top, transport floating at the bottom, the picture between them. Under the transport,
 * the selectors say what is playing (artboard 7d). They stay through loading and failure, when they are what is left to do.
 *
 * The controls float on their own surface, so they read over any frame without a heavy scrim across the
 * picture, and they stop growing at [PlayerScreenDefaults.ControlsMaxWidth] so a wide window keeps the
 * play button near the timeline it belongs to.
 */
@Composable
internal fun Hud(
    caption: String,
    status: PlayerStatus,
    onTogglePause: () -> Unit,
    onSeekTo: (Float) -> Unit,
    onSeekBy: (Long) -> Unit,
    onBack: () -> Unit,
    volumeControl: @Composable () -> Unit = {},
    selectors: @Composable RowScope.() -> Unit = {},
    fullscreenControl: @Composable () -> Unit = {},
) {
    val colors = CloudStreamTheme.colors
    Box(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap),
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to colors.bg, 1f to Color.Transparent))
                .padding(DesktopDimens.WindowMargin),
        ) {
            DesktopIconButton(CloudStreamIcons.Back, stringResource(Res.string.go_back), onBack)
            BasicText(
                text = caption,
                style = CloudStreamTheme.typography.label.copy(color = colors.ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(DesktopDimens.WindowMargin)
                .widthIn(max = PlayerScreenDefaults.ControlsMaxWidth)
                .fillMaxWidth()
                .background(
                    colors.surface.copy(alpha = PlayerScreenDefaults.ControlsAlpha),
                    CloudStreamTheme.shapes.extraLarge,
                )
                .padding(horizontal = DesktopDimens.GroupGap, vertical = DesktopDimens.RelatedGap),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap),
                modifier = Modifier.fillMaxWidth().heightIn(min = PlayerScreenDefaults.TransportHeight),
            ) {
                when {
                    status.failed -> BasicText(
                        text = stringResource(Res.string.source_error),
                        style = CloudStreamTheme.typography.body.copy(color = colors.ink),
                    )
                    status.buffering && status.lengthMillis == 0L -> BasicText(
                        text = stringResource(Res.string.loading),
                        style = CloudStreamTheme.typography.body.copy(color = colors.ink2),
                    )
                    else -> {
                        DesktopIconButton(
                            icon = CloudStreamIcons.SeekBackward,
                            contentDescription = stringResource(Res.string.seek_back_10_seconds),
                            onClick = { onSeekBy(-PlayerScreenDefaults.SkipMillis) },
                            tone = DesktopButtonTone.Quiet,
                        )
                        DesktopIconButton(
                            icon = if (status.playing) CloudStreamIcons.Pause else CloudStreamIcons.Play,
                            contentDescription = stringResource(if (status.playing) Res.string.pause else Res.string.resume),
                            onClick = onTogglePause,
                            tone = DesktopButtonTone.Primary,
                        )
                        DesktopIconButton(
                            icon = CloudStreamIcons.SeekForward,
                            contentDescription = stringResource(Res.string.seek_forward_10_seconds),
                            onClick = { onSeekBy(PlayerScreenDefaults.SkipMillis) },
                            tone = DesktopButtonTone.Quiet,
                        )
                        SeekTrack(status, onSeekTo, Modifier.weight(1f))
                        volumeControl()
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
                modifier = Modifier.fillMaxWidth(),
            ) {
                selectors()
                fullscreenControl()
            }
        }
    }
}

/** The speaker mutes and the rule beside it sets the level, following the pointer 1:1 because nothing here waits on a network. */
@Composable
private fun VolumeControl(volume: PlayerVolume) {
    var width by remember { mutableStateOf(0) }
    val silent = volume.audible == 0
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
    ) {
        DesktopIconButton(
            icon = if (silent) CloudStreamIcons.Muted else CloudStreamIcons.Volume,
            contentDescription = stringResource(if (volume.muted) Res.string.player_unmute else Res.string.player_mute),
            onClick = volume::toggleMute,
            tone = DesktopButtonTone.Quiet,
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(PlayerScreenDefaults.VolumeTrackWidth)
                .height(PlayerScreenDefaults.SeekTargetHeight)
                .onSizeChanged { width = it.width }
                .pointerInput(Unit) {
                    fun levelAt(x: Float) = (x / width * PlayerVolume.MAX).roundToInt()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (width <= 0) return@awaitEachGesture
                        volume.set(levelAt(down.position.x))
                        down.consume()
                        while (true) {
                            val change = awaitPointerEvent().changes.first()
                            if (!change.pressed) break
                            volume.set(levelAt(change.position.x))
                            change.consume()
                        }
                    }
                },
        ) {
            // Ink and not the default: `live` is playback state only, and a level is not playback.
            ProgressRule(
                progress = volume.audible.toFloat() / PlayerVolume.MAX,
                color = CloudStreamTheme.colors.ink,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The picture follows the pointer only on release, because a seek is a network request, but the track
 * and the time follow it 1:1 from the moment it goes down, wherever it wanders while held.
 */
@Composable
internal fun SeekTrack(status: PlayerStatus, onSeekTo: (Float) -> Unit, modifier: Modifier = Modifier) {
    var width by remember { mutableStateOf(0) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    val length = status.lengthMillis
    val progress = scrub ?: if (length > 0) status.positionMillis.toFloat() / length else 0f
    val shownPosition = scrub?.let { (it * length).toLong() } ?: status.positionMillis
    val time = CloudStreamTheme.typography.captionMono.copy(color = CloudStreamTheme.colors.ink)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RowPadding),
    ) {
        BasicText(text = clock(shownPosition), style = time, maxLines = 1)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .height(PlayerScreenDefaults.SeekTargetHeight)
                .onSizeChanged { width = it.width }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (width <= 0) return@awaitEachGesture
                        scrub = (down.position.x / width).coerceIn(0f, 1f)
                        down.consume()
                        while (true) {
                            val change = awaitPointerEvent().changes.first()
                            if (!change.pressed) break
                            scrub = (change.position.x / width).coerceIn(0f, 1f)
                            change.consume()
                        }
                        scrub?.let(onSeekTo)
                        scrub = null
                    }
                },
        ) {
            ProgressRule(progress = progress, modifier = Modifier.fillMaxWidth())
        }
        BasicText(text = clock(length), style = time, maxLines = 1)
    }
}

/** `1:02:03`, or `2:03` under an hour. Digits and colons are not translated copy here. */
private fun clock(millis: Long): String {
    val total = (millis / MILLIS_PER_SECOND).coerceAtLeast(0)
    val hours = total / SECONDS_PER_HOUR
    val minutes = (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = (total % SECONDS_PER_MINUTE).toString().padStart(CLOCK_DIGITS, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(CLOCK_DIGITS, '0')}:$seconds" else "$minutes:$seconds"
}

private const val SEPARATOR = " · "
private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_HOUR = 3600L
private const val SECONDS_PER_MINUTE = 60L
private const val CLOCK_DIGITS = 2

internal object PlayerScreenDefaults {
    val ControlsMaxWidth: Dp = 720.dp
    const val ControlsAlpha = 0.88f
    /** Far taller than the rule it holds, so a pointer does not have to aim at 4dp. */
    val SeekTargetHeight: Dp = 32.dp
    val VolumeTrackWidth: Dp = 80.dp
    /** `seek_back_10_seconds` and `seek_forward_10_seconds` say this number: change them together. */
    const val SkipMillis = 10_000L
    const val HudTimeoutMillis = 3_000L
    /** What the top row covers: its 36 button and a 24 margin on each side. Mirrors `DesktopDimens`. */
    val HudTopInset: Dp = 84.dp
    /** The controls' 8 + 36 + 8 + 40 + 8, their 24 margin, and 8 clear of them. */
    val HudBottomInset: Dp = 132.dp
    val TransportHeight: Dp = 36.dp
    /** Two lines, on the 4dp step. */
    val SelectorHeight: Dp = 40.dp
    val PickerWidth: Dp = 280.dp
    val PickerMaxHeight: Dp = 400.dp
    val PickerMarkSize: Dp = 16.dp
    /** `FocusRingStroke`, the ring's width, and what it needs clear around a row. */
    val PickerRingRoom: Dp = 4.dp
    const val PickerAlpha = 0.96f
    /**
     * How often the shown cue is checked against the clock: the most a cue can be late by, on top of one
     * frame. 25 times a second, far under the 100 ms that `KMP-25` asks of it, and a check is a binary search.
     */
    const val CueTickMillis = 40L
    const val NoticeMillis = 5_000L
    /** Line height to font size, from the Manrope line boxes measured in `design/`. */
    const val CaptionLineRatio = 1.375f
    /** A caption is about this share of the picture's height: 32 on 1280 x 720, 20 on 800 x 450. */
    const val CaptionHeightShare = 0.044f
    const val CaptionMinSize = 16f
    const val CaptionMaxSize = 48f
}

/** No VLC where vlcj can find it. The app is otherwise fine, so this is a state and not a crash. */
@Composable
internal fun PlayerUnavailable(onBack: () -> Unit) {
    StateView(
        prompt = StatePrompt(
            headline = stringResource(Res.string.source_error),
            body = stringResource(Res.string.error_loading_links_toast),
            action = StateAction(stringResource(Res.string.go_back), onBack),
        ),
        detail = VLC_MISSING_DETAIL,
        modifier = Modifier.fillMaxSize(),
    )
}

/** Machine-readable, like `NO_SOURCES · 0 INSTALLED`. A sentence would be a new string to translate. */
private const val VLC_MISSING_DETAIL = "VLC · NOT INSTALLED"

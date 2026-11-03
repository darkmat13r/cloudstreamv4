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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
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
import com.lagradost.cloudstream4.core.strings.generated.resources.pause
import com.lagradost.cloudstream4.core.strings.generated.resources.player_subtitles_settings
import com.lagradost.cloudstream4.core.strings.generated.resources.resume
import com.lagradost.cloudstream4.core.strings.generated.resources.seek_back_10_seconds
import com.lagradost.cloudstream4.core.strings.generated.resources.seek_forward_10_seconds
import com.lagradost.cloudstream4.core.strings.generated.resources.source_error
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.media.model.SubtitleCue
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * The desktop player (`docs/plans/desktop-design.md`): full-bleed video, one seek track, transport, and
 * subtitles. Space pauses, left and right skip, C opens the subtitle list; Escape is the window's.
 *
 * The source and audio selectors are not built. Lives in `:desktopApp` rather than a shared module because
 * it is bound to [VlcPlayer].
 *
 * @param player started and released by this screen, so leaving it stops the audio.
 * @param onBack leaves the screen, which is also what the end of the stream does.
 * @param setEscape told what Escape should do now, as `HomeHost` is.
 * @param clock,session passed in so a check can read what this screen is showing.
 */
@Composable
internal fun PlayerScreen(
    player: VlcPlayer,
    title: String,
    link: StreamLink,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    setEscape: (() -> Unit) -> Unit = {},
    clock: PlaybackClock = remember { PlaybackClock() },
    session: SubtitleSession? = null,
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
    LaunchedEffect(status.finished) { if (status.finished) onBack() }
    LaunchedEffect(player, clock) { player.status.collect(clock::onStatus) }
    val scope = rememberCoroutineScope()
    val subtitles = session ?: remember(link) { SubtitleSession(link.subtitles, HttpSubtitleLoader(), scope) }
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
) {
    val selection by subtitles.selection.collectAsState()
    val failures by subtitles.failures.collectAsState()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var lastActivity by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var hudVisible by remember { mutableStateOf(true) }
    var pickerOpen by remember { mutableStateOf(false) }
    val failed = status.failed
    SideEffect { setEscape(if (pickerOpen) ({ pickerOpen = false }) else onBack) }
    // Focus goes back to the root before a focused button leaves the composition, including the pause
    // button a failure removes. Otherwise it is cleared rather than moved, and the root's key handler
    // stops seeing any key, so Space and the arrows go dead. The list takes focus while it is open.
    LaunchedEffect(focus, failed, pickerOpen) { if (!pickerOpen) runCatching { focus.requestFocus() } }
    LaunchedEffect(lastActivity, status.playing, pickerOpen) {
        hudVisible = true
        if (status.playing && !pickerOpen) {
            delay(PlayerScreenDefaults.HudTimeoutMillis)
            runCatching { focus.requestFocus() }
            hudVisible = false
        }
    }
    val hudShown = hudVisible || failed || pickerOpen
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
                if (pickerOpen) {
                    // The list has the keys now. Space and Enter are its rows', the arrows move between them.
                    return@onPreviewKeyEvent when (event.key) {
                        Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                        Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                        Key.C -> {
                            pickerOpen = false
                            true
                        }
                        else -> false
                    }
                }
                when (event.key) {
                    Key.Spacebar -> controls.togglePause()
                    Key.DirectionLeft -> seekBy(-PlayerScreenDefaults.SkipMillis)
                    Key.DirectionRight -> seekBy(PlayerScreenDefaults.SkipMillis)
                    Key.C -> if (!failed) pickerOpen = true
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        frame?.let { image ->
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = FitAs(pictureSize),
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
        )
        // Not over the list: the row it came from is saying the same thing.
        if (!pickerOpen) SubtitleNotice(selection, bottomInset)
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
                subtitleControl = {
                    DesktopIconButton(
                        icon = CloudStreamIcons.Subtitles,
                        contentDescription = stringResource(Res.string.player_subtitles_settings),
                        onClick = { pickerOpen = !pickerOpen },
                        tone = if (selection is SubtitleSelection.Showing || selection is SubtitleSelection.Loading) {
                            DesktopButtonTone.Secondary
                        } else {
                            DesktopButtonTone.Quiet
                        },
                    )
                },
            )
        }
        if (pickerOpen) {
            SubtitlePicker(
                tracks = subtitles.tracks,
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
 * Back and the title at the top, transport floating at the bottom, the picture between them.
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
    subtitleControl: @Composable () -> Unit = {},
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap),
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
                    subtitleControl()
                }
            }
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
/**
 * [ContentScale.Fit] for a frame whose size is not its picture's shape: fits [picture], so the two axes scale differently.
 * That is a frame libVLC stretched into a decoder's buffer, or one with non-square pixels. See `BUG-15`.
 */
private class FitAs(private val picture: IntSize?) : ContentScale {
    override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor {
        if (picture == null) return ContentScale.Fit.computeScaleFactor(srcSize, dstSize)
        val scale = minOf(dstSize.width / picture.width, dstSize.height / picture.height)
        return ScaleFactor(scale * picture.width / srcSize.width, scale * picture.height / srcSize.height)
    }
}

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
    /** `seek_back_10_seconds` and `seek_forward_10_seconds` say this number: change them together. */
    const val SkipMillis = 10_000L
    const val HudTimeoutMillis = 3_000L
    /** What the top row covers: its 36 button and a 24 margin on each side. Mirrors `DesktopDimens`. */
    val HudTopInset: Dp = 84.dp
    /** The controls' 52, their 24 margin, and 8 clear of them. */
    val HudBottomInset: Dp = 84.dp
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

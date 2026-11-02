package com.lagradost.cloudstream4.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.primitive.ProgressRule
import com.lagradost.cloudstream4.core.designsystem.state.StateAction
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.designsystem.state.StateView
import com.lagradost.cloudstream4.core.designsystem.target.GhostTextButton
import com.lagradost.cloudstream4.core.designsystem.target.PrimaryTextButton
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.error_loading_links_toast
import com.lagradost.cloudstream4.core.strings.generated.resources.go_back
import com.lagradost.cloudstream4.core.strings.generated.resources.loading
import com.lagradost.cloudstream4.core.strings.generated.resources.pause
import com.lagradost.cloudstream4.core.strings.generated.resources.resume
import com.lagradost.cloudstream4.core.strings.generated.resources.source_error
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.detail.StreamLink
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * Artboard 7d of `design/TV Detail Player.dc.html`, reduced to what works today: full-bleed video, the
 * times, one seek track, and transport. Space pauses, left and right skip; Escape is the window's.
 *
 * Text buttons stand in for the drawn transport icons, which are blocked on the icon pack, and the
 * source, subtitle and audio selectors are not built (`KMP-15`). Lives in `:desktopApp` rather than a
 * shared module because it is bound to [VlcPlayer].
 *
 * @param player started and released by this screen, so leaving it stops the audio.
 * @param onBack leaves the screen, which is also what the end of the stream does.
 */
@OptIn(ExperimentalComposeUiApi::class) // onPointerEvent: the only way to see a mouse move, for the HUD.
@Composable
internal fun PlayerScreen(
    player: VlcPlayer,
    title: String,
    link: StreamLink,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val frame by player.frame.collectAsState()
    val status by player.status.collectAsState()
    var started by remember { mutableStateOf(true) }
    DisposableEffect(player, link) {
        started = player.play(link.url, link.referer, link.userAgent, link.audioUrl)
        // Off the UI thread: stopping libVLC blocks, and can take seconds on a stalled stream.
        onDispose { thread(isDaemon = true, name = "vlc-release") { player.release() } }
    }
    LaunchedEffect(status.finished) { if (status.finished) onBack() }

    val focus = remember { FocusRequester() }
    var lastActivity by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var hudVisible by remember { mutableStateOf(true) }
    val failed = !started || status.failed
    // Focus goes back to the root before a focused button leaves the composition, including the pause
    // button a failure removes. Otherwise it is cleared rather than moved, and the root's key handler
    // stops seeing any key, so Space and the arrows go dead.
    LaunchedEffect(focus, failed) { runCatching { focus.requestFocus() } }
    LaunchedEffect(lastActivity, status.playing) {
        hudVisible = true
        if (status.playing) {
            delay(PlayerScreenDefaults.HudTimeoutMillis)
            runCatching { focus.requestFocus() }
            hudVisible = false
        }
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
                when (event.key) {
                    Key.Spacebar -> player.togglePause()
                    Key.DirectionLeft -> player.seekBy(-PlayerScreenDefaults.SkipMillis)
                    Key.DirectionRight -> player.seekBy(PlayerScreenDefaults.SkipMillis)
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        frame?.let { image ->
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.Low,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (hudVisible || failed) {
            Hud(
                caption = listOfNotNull(title, link.qualityLabel, link.label).joinToString(SEPARATOR),
                status = status.copy(failed = failed),
                onTogglePause = player::togglePause,
                onSeekTo = player::seekTo,
                onBack = onBack,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun Hud(
    caption: String,
    status: PlayerStatus,
    onTogglePause: () -> Unit,
    onSeekTo: (Float) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CloudStreamTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 1f to colors.bg))
            .padding(PlayerScreenDefaults.HudPadding),
        verticalArrangement = Arrangement.spacedBy(PlayerScreenDefaults.HudGap),
    ) {
        BasicText(
            text = caption,
            style = CloudStreamTheme.typography.label.copy(color = colors.ink2),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when {
            status.failed -> BasicText(
                text = stringResource(Res.string.source_error),
                style = CloudStreamTheme.typography.body.copy(color = colors.live),
            )
            status.buffering && status.lengthMillis == 0L -> BasicText(
                text = stringResource(Res.string.loading),
                style = CloudStreamTheme.typography.captionMono.copy(color = colors.ink),
            )
            else -> {
                BasicText(
                    text = "${clock(status.positionMillis)} / ${clock(status.lengthMillis)}",
                    style = CloudStreamTheme.typography.captionMono.copy(color = colors.ink),
                )
                SeekTrack(status, onSeekTo)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(PlayerScreenDefaults.HudGap)) {
            if (!status.failed) {
                PrimaryTextButton(
                    label = stringResource(if (status.playing) Res.string.pause else Res.string.resume),
                    onClick = onTogglePause,
                )
            }
            GhostTextButton(label = stringResource(Res.string.go_back), onClick = onBack)
        }
    }
}

/** A click anywhere along it seeks there. The rule itself is thinner than the target it sits in. */
@Composable
private fun SeekTrack(status: PlayerStatus, onSeekTo: (Float) -> Unit) {
    var width by remember { mutableStateOf(0) }
    val progress = if (status.lengthMillis > 0) status.positionMillis.toFloat() / status.lengthMillis else 0f
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(PlayerScreenDefaults.SeekTargetHeight)
            .onSizeChanged { width = it.width }
            .pointerInput(Unit) {
                detectTapGestures { offset -> if (width > 0) onSeekTo(offset.x / width) }
            },
    ) {
        ProgressRule(progress = progress, modifier = Modifier.fillMaxWidth())
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
    val HudPadding: Dp = CloudStreamDimens.ChromeRowTop
    val HudGap: Dp = 12.dp
    /** The artboard's 96px seek cell at px/2. */
    val SeekTargetHeight: Dp = 48.dp
    const val SkipMillis = 10_000L
    const val HudTimeoutMillis = 3_000L
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

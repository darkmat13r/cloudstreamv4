package com.lagradost.cloudstream4.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.lagradost.cloudstream4.feature.player.HudSkip
import kotlinx.coroutines.flow.first

/** Skip as the HUD draws it, and what pressing it does. [hud] is null while there is nothing to skip. */
internal class SkipButton(val hud: HudSkip?, val onSkip: () -> Unit)

/**
 * Skip for the stream playing (`KMP-86`): [source]'s stamps, asked for once the length is known, and the one under the
 * playhead offered while it is inside the stream. Pressing it seeks [controls] just past the stamp, or calls
 * [onPlayNext] when the stamp runs to the end and [hasNext], as Android's does.
 *
 * The stamps are the episode's, not the stream's: a source switch plays the same episode, so they stay, and each use
 * holds them to the length of the stream now playing.
 */
@Composable
internal fun rememberSkip(
    source: SkipStampSource?,
    status: PlayerStatus,
    hasNext: Boolean,
    controls: PlayerControls,
    onPlayNext: () -> Unit,
): SkipButton {
    // Whichever stream is playing: one that failed before saying its length gives way to the next.
    val lengthNow by rememberUpdatedState(status.lengthMillis)
    val stamps by produceState(emptyList<SkipStamp>(), source) {
        val asked = source ?: return@produceState
        value = asked.stamps(snapshotFlow { lengthNow }.first { it > 0 })
    }
    val length = status.lengthMillis
    val stamp = stamps.at(status.positionMillis, length)
    val toNext = stamp?.skipsToNext(length, hasNext) == true
    return SkipButton(
        hud = stamp?.let { HudSkip(skipLabel(it.kind, toNext)) },
        onSkip = {
            when {
                stamp == null -> Unit
                toNext -> onPlayNext()
                else -> controls.seekTo(skipTarget(stamp, length))
            }
        },
    )
}

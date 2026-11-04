package com.lagradost.cloudstream4.core.designsystem.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.designsystem.primitive.Badge
import com.lagradost.cloudstream4.core.designsystem.target.TargetLabel
import com.lagradost.cloudstream4.core.theme.CardSize
import com.lagradost.cloudstream4.core.theme.CloudStreamCardSizes
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * An episode still with its text below the art (`Components` §02), on the [CloudStreamCardSizes.LandscapeMedium]
 * rung. The block is 112dp: art 72, gap 8, then the 32dp text stack.
 *
 * Unlike `BackdropCard`, the title is layout rather than a reveal on focus, and the card does not lift: the board
 * draws no lift here, and lifting the art alone would close the gap to its own title. What it shares with
 * `BackdropCard` is the rung, the ring on the art, the badge inset and the progress rule.
 *
 * Like every media card it hoists [focused] and is not itself focusable. A rail decides.
 *
 * @param title the episode's name.
 * @param meta the mono line, such as `48M · 28M LEFT`. Its line box stays when null, so a rail keeps one baseline.
 * @param focused whether the ring is drawn, around the art.
 * @param number the badge, such as `E4`, or null for none.
 * @param progress how much is watched, coerced into `0f..1f`. The track always draws, at 0 for an unwatched
 *   episode, as the board specifies.
 * @param image the artwork; it receives the art's full size as a minimum constraint.
 */
@Composable
fun EpisodeCard(
    title: String,
    meta: String?,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    number: String? = null,
    progress: Float = NOTHING_WATCHED,
    image: @Composable () -> Unit,
) {
    val rung = CloudStreamCardSizes.LandscapeMedium
    Column(modifier.width(rung.width)) {
        BelowArtArtwork(rung = rung, focused = focused, image = image) {
            if (number != null) {
                Badge(
                    text = number,
                    modifier = Modifier.align(Alignment.TopStart).padding(MediaCardDefaults.ContentPadding),
                )
            }
            MediaCardProgressRule(
                progress = progress.coercedProgress(),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        Spacer(Modifier.height(BelowArtCardDefaults.EpisodeTextGap))
        BelowArtText(title = title, subtitle = meta)
    }
}

/**
 * A title to resume, with its rule and text below the art (`TV Home` 3c), on the
 * [CloudStreamCardSizes.LandscapeLarge] rung. The block is 152dp: art 108, 4, rule 4, 4, then the 32dp text stack.
 *
 * Differs from [EpisodeCard] in where the rule sits. Here it is its own row under the art, which is what keeps
 * everything below it on the step. Focus is hoisted, and there is no lift, as on [EpisodeCard].
 *
 * @param title the title.
 * @param subtitle the mono line: episode, time left, the source and quality it resumes from. Its line box stays
 *   when null.
 * @param progress how much is watched, coerced into `0f..1f`.
 * @param focused whether the ring is drawn, around the art.
 * @param image the artwork; it receives the art's full size as a minimum constraint.
 */
@Composable
fun ContinueCard(
    title: String,
    subtitle: String?,
    progress: Float,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    image: @Composable () -> Unit,
) {
    val rung = CloudStreamCardSizes.LandscapeLarge
    Column(modifier.width(rung.width)) {
        BelowArtArtwork(rung = rung, focused = focused, image = image)
        Spacer(Modifier.height(BelowArtCardDefaults.ContinueRuleGap))
        MediaCardProgressRule(progress = progress.coercedProgress())
        Spacer(Modifier.height(BelowArtCardDefaults.ContinueRuleGap))
        BelowArtText(title = title, subtitle = subtitle)
    }
}

/** The art box. The ring is outside its clip, for the reason on `MediaCard`. */
@Composable
private fun BelowArtArtwork(
    rung: CardSize,
    focused: Boolean,
    image: @Composable () -> Unit,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val shape = CloudStreamTheme.shapes.none
    Box(Modifier.size(rung.asDpSize).tvFocusRing(focused = focused, shape = shape)) {
        Box(Modifier.fillMaxSize().clip(shape).background(CloudStreamTheme.colors.raised)) {
            Box(Modifier.fillMaxSize(), propagateMinConstraints = true) { image() }
            overlay()
        }
    }
}

/** The stack from the tokens, 16 + 4 + 12 = 32dp. Both line boxes are always laid out. */
@Composable
private fun BelowArtText(title: String, subtitle: String?) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    BelowArtLine(title, CloudStreamDimens.StackTitleLineBox, typography.label, colors.ink)
    Spacer(Modifier.height(CloudStreamDimens.StackGap))
    BelowArtLine(subtitle, CloudStreamDimens.StackSubtitleLineBox, typography.captionMono, colors.muted)
}

@Composable
private fun BelowArtLine(text: String?, lineBox: Dp, style: TextStyle, color: Color) {
    Box(Modifier.fillMaxWidth().height(lineBox), contentAlignment = Alignment.CenterStart) {
        if (text != null) TargetLabel(text = text, style = style, color = color)
    }
}

private fun Float.coercedProgress(): Float =
    if (isNaN()) NOTHING_WATCHED else coerceIn(MediaCardState.PROGRESS_EMPTY, MediaCardState.PROGRESS_FULL)

private const val NOTHING_WATCHED = MediaCardState.PROGRESS_EMPTY

/** Values the below-art cards need that `:core:theme` does not name yet. */
@Immutable
object BelowArtCardDefaults {

    /** Art to title on [EpisodeCard], 16px on the artboard. Equal to `CloudStreamDimens.ListRowPadding` in value only. */
    val EpisodeTextGap: Dp = CloudStreamDimens.ListRowPadding

    /** Art to rule and rule to title on [ContinueCard], 8px on the artboard. Mirrors `CloudStreamDimens.StackGap`. */
    val ContinueRuleGap: Dp = CloudStreamDimens.StackGap
}

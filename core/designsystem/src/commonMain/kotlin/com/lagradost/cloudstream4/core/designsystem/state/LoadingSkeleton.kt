package com.lagradost.cloudstream4.core.designsystem.state

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.theme.CardSize
import com.lagradost.cloudstream4.core.theme.CloudStreamCardSizes
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

private const val DEFAULT_CARD_COUNT = 6
private const val DEFAULT_ROW_COUNT = 8

/** Literals the skeletons own, and what they should become in `:core:theme`. */
@Immutable
object SkeletonDefaults {
    /**
     * Horizontal gap between cards in a rail. Mirrors `dimens.ChipRowSpacing` (24dp), which is
     * the same 48px on the artboard but means chips. Should be promoted to
     * `dimens.RailCardSpacing` — every rail in the app needs it, not just this skeleton.
     */
    val CardSpacing: Dp = 24.dp

    /**
     * The rail-title placeholder bar. The height mirrors the `railTitle` ramp's 20sp size (its
     * line box is 28sp; a placeholder bar stands for the glyphs, not the box). Should be
     * promoted to `dimens.RailTitlePlaceholderHeight` / `Width`.
     */
    val TitleBarHeight: Dp = 20.dp

    /** See [TitleBarHeight]. */
    val TitleBarWidth: Dp = 160.dp

    /**
     * Width of the primary text bar in a list-row skeleton. Its *height* is the real token,
     * `dimens.StackTitleLineBox`. Should be promoted to `dimens.PlaceholderPrimaryWidth`.
     */
    val PrimaryBarWidth: Dp = 160.dp

    /** Width of the secondary text bar. See [PrimaryBarWidth]. */
    val SecondaryBarWidth: Dp = 96.dp
}

/**
 * A card-shaped placeholder at one rung of the media ladder.
 *
 * **The whole point is that it is the same size as the thing it replaces.** [CardSize] has no
 * public constructor, so a skeleton cannot be built at a size the ladder does not contain, and
 * therefore cannot be built at a size the real card will not be — which is what makes the layout
 * hold still when content arrives.
 *
 * @param rung the same rung the real card will use.
 * @param modifier applied to the placeholder.
 */
@Composable
fun SkeletonCard(rung: CardSize, modifier: Modifier = Modifier) {
    SkeletonCardBlock(rung = rung, alpha = rememberSkeletonAlpha(), modifier = modifier)
}

/** Shares one pulse with its siblings; see [rememberSkeletonAlpha]. */
@Composable
private fun SkeletonCardBlock(rung: CardSize, alpha: Float, modifier: Modifier = Modifier) {
    SkeletonBlock(
        alpha = alpha,
        shape = CloudStreamTheme.shapes.none,
        modifier = modifier.size(rung.asDpSize),
    )
}

/**
 * A rail: its title, then a row of cards, at the exact geometry the loaded rail will have.
 *
 * The gap under the title is [CloudStreamDimens.LiftedRowClearance] (16dp) rather than a plain
 * gutter, because a rail's cards lift 4dp on focus and the row must already own the space they
 * rise into — 12dp of clearance plus the 4dp of lift. Using a smaller gap here and a larger one
 * in the loaded rail is precisely the jump this component exists to prevent.
 *
 * @param rung the rung the real cards use. Required, and deliberately so: a skeleton whose rung
 *   defaulted would silently stop matching the rail it stands in.
 * @param modifier applied to the rail.
 * @param cardCount how many placeholders to draw. Enough to overflow the content band is right;
 *   the default is six.
 */
@Composable
fun SkeletonRail(
    rung: CardSize,
    modifier: Modifier = Modifier,
    cardCount: Int = DEFAULT_CARD_COUNT,
) {
    val alpha = rememberSkeletonAlpha()
    Column(modifier = modifier) {
        SkeletonBlock(
            alpha = alpha,
            shape = CloudStreamTheme.shapes.small,
            modifier = Modifier
                .width(SkeletonDefaults.TitleBarWidth)
                .height(SkeletonDefaults.TitleBarHeight),
        )
        Spacer(Modifier.height(CloudStreamDimens.LiftedRowClearance))
        Row(horizontalArrangement = Arrangement.spacedBy(SkeletonDefaults.CardSpacing)) {
            repeat(cardCount) {
                SkeletonCardBlock(rung = rung, alpha = alpha)
            }
        }
    }
}

/**
 * One list-row placeholder at [CloudStreamDimens.ListRowHeight] (48dp), laid out exactly as a
 * real row: the 4dp selection-marker cell, the optional 24dp disc, and the two-line text stack
 * whose 16 + 4 + 12 dp total centres in the row at +8dp.
 *
 * @param modifier applied to the row.
 */
@Composable
fun SkeletonListRow(modifier: Modifier = Modifier) {
    SkeletonListRowBlock(alpha = rememberSkeletonAlpha(), modifier = modifier)
}

/** Shares one pulse with its siblings; see [rememberSkeletonAlpha]. */
@Composable
private fun SkeletonListRowBlock(alpha: Float, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(CloudStreamDimens.ListRowHeight)
            .padding(horizontal = CloudStreamDimens.ListRowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(CloudStreamDimens.ListRowMarkerWidth))
        Spacer(Modifier.width(CloudStreamDimens.ListRowPadding))
        SkeletonBlock(
            alpha = alpha,
            shape = CloudStreamTheme.shapes.pill,
            modifier = Modifier.size(CloudStreamDimens.ListRowDiscSize),
        )
        Spacer(Modifier.width(CloudStreamDimens.ListRowPadding))
        SkeletonTextStack(alpha = alpha)
    }
}

/** The 16 + 4 + 12 dp stack from `dimens.StackTitleLineBox` and friends. */
@Composable
private fun SkeletonTextStack(alpha: Float) {
    Column {
        SkeletonBlock(
            alpha = alpha,
            shape = CloudStreamTheme.shapes.small,
            modifier = Modifier
                .width(SkeletonDefaults.PrimaryBarWidth)
                .height(CloudStreamDimens.StackTitleLineBox),
        )
        Spacer(Modifier.height(CloudStreamDimens.StackGap))
        SkeletonBlock(
            alpha = alpha,
            shape = CloudStreamTheme.shapes.small,
            modifier = Modifier
                .width(SkeletonDefaults.SecondaryBarWidth)
                .height(CloudStreamDimens.StackSubtitleLineBox),
        )
    }
}

/**
 * A column of [SkeletonListRow]s sharing one pulse, for a settings list, a source list, an
 * episode list — anything built from the 48dp row.
 *
 * @param modifier applied to the column.
 * @param rowCount how many rows to draw.
 */
@Composable
fun SkeletonList(
    modifier: Modifier = Modifier,
    rowCount: Int = DEFAULT_ROW_COUNT,
) {
    val alpha = rememberSkeletonAlpha()
    Column(modifier = modifier) {
        repeat(rowCount) {
            SkeletonListRowBlock(alpha = alpha)
        }
    }
}

@Preview
@Composable
private fun SkeletonRailPreview() {
    StatePreviewSurface {
        SkeletonRail(rung = CloudStreamCardSizes.LandscapeMedium, cardCount = 3)
    }
}

@Preview
@Composable
private fun SkeletonListPreview() {
    StatePreviewSurface {
        SkeletonList(rowCount = 4)
    }
}

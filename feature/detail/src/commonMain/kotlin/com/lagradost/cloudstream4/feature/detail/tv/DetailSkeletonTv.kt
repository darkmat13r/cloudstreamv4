package com.lagradost.cloudstream4.feature.detail.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.rows.RailHeaderDefaults
import com.lagradost.cloudstream4.core.designsystem.state.SkeletonBlock
import com.lagradost.cloudstream4.core.designsystem.state.rememberSkeletonAlpha
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * The loading state of 7a and 7b, which is §2.8's LOADING note drawn in the boxes the real page
 * resolves to.
 *
 * ## Why this exists rather than the host's generic rail
 *
 * `UiStateHost`'s default skeleton is a rail of landscape cards, and its own KDoc says to **pass one
 * shaped like your content** because "a screen that is not a rail of landscape cards will visibly
 * jump when its content lands". This page is not one: it is a title block over a hero. The generic
 * rail also began at x0, outside the band, with the back cell drawn over its first card.
 *
 * ## Every box is the real box
 *
 * Not one spacer here is an absolute y. The ladder is `HeadBlock`'s own, token for token, and the
 * cast strip is `DetailCastRowTv`'s, so the board's LOADING coordinates fall out rather than being
 * transcribed — and a change to the real ladder moves the skeleton with it. The board's numbers, at
 * px/2, and where each comes from:
 *
 * | Board | dp | From |
 * |---|---|---|
 * | title 200,176,720x88 | x0 of the band, y88, 360x44 | `ChromeTop` + `ChromeRowHeight` + `ChromeToTitle` |
 * | meta 200,272,560x32 | y136, 280x16 | + title + `TitleToMeta` |
 * | plot bars 328, 368 | y164, y184 | + meta + `MetaToPlot`, then the 20dp line pitch |
 * | action bar 512, 1568 wide | y256, 784x32 | + `PlotToCaption` + `CaptionHeight` + `CaptionToActions` |
 * | avatars 728, 160 round, 216 pitch | y364, 80 round, 108 pitch | + `SectionGap` + the rail header + `LiftedRowClearance` |
 * | name 904, role 944 | y452, y472 | + `CastAvatarToName`, then `StackGap` |
 *
 * The 108dp pitch is not a number either: it is `CastBlockWidth` (96) plus `MinFocusGap` (12), which
 * is what the real row spaces its blocks by.
 *
 * The board gives two plot bars and no second width, so both are the plot's own width rather than a
 * shortened second line, which would be an invention.
 *
 * @param modifier the host's, which carries the band inset. Blocks are sized here.
 */
@Composable
internal fun DetailSkeletonTv(modifier: Modifier = Modifier) {
    val alpha = rememberSkeletonAlpha()
    val shape = CloudStreamTheme.shapes.small
    Column(modifier) {
        Spacer(Modifier.height(DetailScreenTvDefaults.ChromeTop))
        Spacer(Modifier.height(CloudStreamDimens.ChromeRowHeight))
        Spacer(Modifier.height(DetailScreenTvDefaults.ChromeToTitle))
        SkeletonBlock(
            alpha = alpha,
            shape = shape,
            modifier = Modifier
                .width(DetailSkeletonDefaults.TitleWidth)
                .height(DetailSkeletonDefaults.TitleHeight),
        )
        Spacer(Modifier.height(DetailScreenTvDefaults.TitleToMeta))
        SkeletonBlock(
            alpha = alpha,
            shape = shape,
            modifier = Modifier
                .width(DetailSkeletonDefaults.MetaWidth)
                .height(DetailSkeletonDefaults.LineHeight),
        )
        Spacer(Modifier.height(DetailScreenTvDefaults.MetaToPlot))
        repeat(DetailScreenTvDefaults.PlotLines) { line ->
            if (line > FIRST_LINE) Spacer(Modifier.height(DetailSkeletonDefaults.LineGap))
            SkeletonBlock(
                alpha = alpha,
                shape = shape,
                modifier = Modifier
                    .width(DetailScreenTvDefaults.PlotMaxWidth)
                    .height(DetailSkeletonDefaults.LineHeight),
            )
        }
        Spacer(Modifier.height(DetailSkeletonDefaults.LineGap))
        Spacer(Modifier.height(DetailScreenTvDefaults.PlotToCaption))
        Spacer(Modifier.height(DetailScreenTvDefaults.CaptionHeight))
        Spacer(Modifier.height(DetailScreenTvDefaults.CaptionToActions))
        SkeletonBlock(
            alpha = alpha,
            shape = CloudStreamTheme.shapes.medium,
            modifier = Modifier
                .width(DetailSkeletonDefaults.ActionBarWidth)
                .height(CloudStreamDimens.TextButtonHeight),
        )
        Spacer(Modifier.height(DetailScreenTvDefaults.SectionGap))
        Spacer(Modifier.height(RailHeaderDefaults.Height))
        Spacer(Modifier.height(CloudStreamDimens.LiftedRowClearance))
        Row(horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap)) {
            repeat(DetailSkeletonDefaults.CastCount) {
                Column(
                    modifier = Modifier.width(DetailScreenTvDefaults.CastBlockWidth),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SkeletonBlock(
                        alpha = alpha,
                        shape = CircleShape,
                        modifier = Modifier.size(DetailSkeletonDefaults.AvatarSize),
                    )
                    Spacer(Modifier.height(DetailScreenTvDefaults.CastAvatarToName))
                    SkeletonBlock(
                        alpha = alpha,
                        shape = shape,
                        modifier = Modifier
                            .width(DetailSkeletonDefaults.CastNameWidth)
                            .height(DetailSkeletonDefaults.LineHeight),
                    )
                    Spacer(Modifier.height(CloudStreamDimens.StackGap))
                    SkeletonBlock(
                        alpha = alpha,
                        shape = shape,
                        modifier = Modifier
                            .width(DetailSkeletonDefaults.CastRoleWidth)
                            .height(DetailSkeletonDefaults.LineHeight),
                    )
                }
            }
        }
    }
}

private const val FIRST_LINE = 0

/** 7a's LOADING boxes that no other token already means. Each is the board's px, halved. */
@Immutable
internal object DetailSkeletonDefaults {

    /** 720x88 on the artboard. */
    val TitleWidth: Dp = 360.dp
    val TitleHeight: Dp = 44.dp

    /** 560 on the artboard. */
    val MetaWidth: Dp = 280.dp

    /**
     * One text line's bar. Mirrors `CloudStreamDimens.StackTitleLineBox` (16dp), which is the line
     * box the meta, the plot and the cast's two lines are all set in.
     */
    val LineHeight: Dp = CloudStreamDimens.StackTitleLineBox

    /**
     * Between two bars of one paragraph: the plot's 20dp line pitch less the 16dp bar. Mirrors
     * `CloudStreamDimens.StackGap`.
     */
    val LineGap: Dp = CloudStreamDimens.StackGap

    /**
     * One bar standing for the action row, and **the shorter of the two rows the page can show**.
     *
     * The skeleton cannot know the title's type before it resolves, so the bar either stops short of
     * the row it becomes or overhangs it. The board set takes the first: a bar that grows on arrival
     * reads as the page filling in, where one that shrinks reads as a mistake. So this is the film
     * row — primary, Start over, Trailer, the watchlist, Full synopsis — and a series' row, which
     * adds Season and Episodes, grows past it.
     *
     * Re-derived for the icon-only row (`DEC-17`, boards 7f and 7g). The old 784 came from the
     * artboard's x200..x1768 and stood for a row of five text buttons; measured against what the page
     * actually drew it overhung by 46px at 1280 wide. Three 48dp cells in place of three 120dp ghosts
     * is what moves the number.
     *
     * `ResumeWidth` + 3 × (`MinFocusGap` + `IconTargetCell`) + `MinFocusGap` + `TextButtonMinWidth`
     * = 220 + 180 + 12 + 120 = 532.
     */
    val ActionBarWidth: Dp = DetailScreenTvDefaults.ResumeWidth +
        (CloudStreamDimens.MinFocusGap + CloudStreamDimens.IconTargetCell) * ICON_SECONDARIES +
        CloudStreamDimens.MinFocusGap + CloudStreamDimens.TextButtonMinWidth

    /** Start over, Trailer and the watchlist: the icon-only cells a film's row carries. */
    private const val ICON_SECONDARIES = 3

    /** Seven, as the board draws, which is also what fills the band at the 108dp pitch. */
    const val CastCount = 7

    /** 160 round on the artboard, centred in the 96dp block the real row uses. */
    val AvatarSize: Dp = 80.dp

    /** The two lines under a headshot, inside `CastBlockWidth`. */
    val CastNameWidth: Dp = 80.dp
    val CastRoleWidth: Dp = 56.dp
}

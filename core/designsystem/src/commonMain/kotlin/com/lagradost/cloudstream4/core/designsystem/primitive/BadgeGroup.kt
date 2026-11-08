package com.lagradost.cloudstream4.core.designsystem.primitive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * The [Badge]es on **one edge** of a card: side by side while they fit the width the caller
 * allows, stacked when they do not.
 *
 * ## Why this is not a `Row`
 *
 * `TV Search` 4a draws two badges at a card's leading corner — the quality, then `DUB` or `SUB` —
 * in a `.badges` row with an 8px gap. A `Row` reproduces that drawing and nothing else: it
 * measures its children in order, so the first badge is offered the whole box and the second is
 * offered whatever is left. In English that is fine (`1080P` and `DUB` together are about 55dp of
 * an 72dp box). In Catalan `quality_sdr` alone measures 154dp and in Tamil `app_dubbed_text`
 * measures 141dp (`BUG-62`, measured on `Television_720p` in all 61 locales), so the first badge
 * takes the box, the second is measured at zero and **disappears** — the card loses the fact that
 * it is dubbed rather than losing a few letters of it.
 *
 * Stacking is the one answer that keeps both facts: each badge gets the full box on its own line,
 * ellipsises inside it, and the pair is two [BadgeDefaults.Height] rows instead of one. A card is
 * 120dp tall at its smallest portrait rung, so a second 16dp row costs it nothing.
 *
 * ```
 * fits:            does not fit:
 * ┌────┐ ┌─────┐   ┌──────────────┐
 * │1080P│ │ DUB │  │ RANG DINAM…  │
 * └────┘ └─────┘   └──────────────┘
 *                  ┌──────────────┐
 *                  │ மொழிபெயர்க்க… │
 *                  └──────────────┘
 * ```
 *
 * ## The cap comes from the constraints, not from a parameter
 *
 * Each badge is measured at the group's own `maxWidth`, so a single badge too wide for the box
 * ellipsises rather than overflowing, and the row/stack decision is made against that same
 * number. `MediaCard` already hands its badge slots the card's width less the content inset, which
 * is where the 72dp of an 80dp poster comes from; a group laid out somewhere unbounded always
 * stays on one line, which is what an unbounded caller asked for.
 *
 * The gap is [BadgeDefaults.Gap] in whichever axis the group ends up using. There is no parameter
 * for it: the design has one badge gap.
 *
 * @param modifier applied to the group.
 * @param content the badges, normally two [Badge]es. One badge is a row of one; none lays out
 *   nothing at all.
 */
@Composable
fun BadgeGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = BadgeDefaults.Gap.roundToPx()
        val cap = constraints.maxWidth
        val badges = measurables.map { it.measure(Constraints(maxWidth = cap)) }
        if (badges.isEmpty()) return@Layout layout(0, 0) {}
        val gaps = gap * (badges.size - 1)
        val sideBySide = badges.sumOf { it.width } + gaps
        if (sideBySide <= cap) {
            layout(sideBySide, badges.maxOf { it.height }) {
                var x = 0
                badges.forEach { badge ->
                    badge.placeRelative(x, 0)
                    x += badge.width + gap
                }
            }
        } else {
            layout(badges.maxOf { it.width }, badges.sumOf { it.height } + gaps) {
                var y = 0
                badges.forEach { badge ->
                    badge.placeRelative(0, y)
                    y += badge.height + gap
                }
            }
        }
    }
}

@Preview
@Composable
private fun BadgeGroupPreview() {
    CloudStreamTheme {
        Column(
            modifier = Modifier
                .background(CloudStreamTheme.colors.raised)
                .padding(CloudStreamDimens.SidePanelContentInset),
        ) {
            // The box a badge group gets on the medium portrait rung: 80dp of card less its inset.
            PreviewBadgeBox {
                BadgeGroup {
                    Badge(text = "1080P")
                    Badge(text = "DUB")
                }
            }
            PreviewBadgeBox {
                BadgeGroup {
                    Badge(text = "RANG DINAMIC SIMPLE (SDR)")
                    Badge(text = "DUB")
                }
            }
        }
    }
}

/** The 72dp a card's badge slot allows, drawn so the preview shows what the group is fitting into. */
@Composable
private fun PreviewBadgeBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .width(PREVIEW_BADGE_BOX)
            .padding(vertical = CloudStreamDimens.StackGap)
            .background(CloudStreamTheme.colors.surface),
        content = { content() },
    )
}

/** `CloudStreamCardSizes.PortraitMedium`'s 80dp less `MediaCardDefaults.ContentPadding` on both sides. */
private val PREVIEW_BADGE_BOX: Dp = 72.dp

package com.lagradost.cloudstream4.feature.detail.tv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusLift
import com.lagradost.cloudstream4.core.designsystem.primitive.Avatar
import com.lagradost.cloudstream4.core.designsystem.rows.RailHeader
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.cast
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.detail.DetailAction
import com.lagradost.cloudstream4.feature.detail.DetailCastMember
import com.lagradost.cloudstream4.feature.detail.initialsOf
import com.lagradost.cloudstream4.feature.detail.roleLabel
import org.jetbrains.compose.resources.stringResource

/**
 * 7a's cast rail: 96x120 blocks on a 108dp pitch, each lifting as one unit.
 *
 * No header. `:core:strings` has no word for "Cast" and the shipping TV page has no header over its
 * cast either, so the rail is the row alone — and the section's own gap above it then puts it where the
 * board's header would have been. No "Full cast" card either: the side panel it opens is "not yet
 * drawn" by the design's own words, there is no actor id to open a person page with, and `CardSize`'s
 * constructor is `internal` to `:core:theme`, so a feature module cannot build the board's 76x120 card.
 *
 * The rail carries its inset as [LazyRow]'s content padding at the ring's bleed and the rest as
 * padding, so the first block's left edge still lands on x100 and its ring is not cut at the viewport.
 * A lazy row clips only along its scroll axis, which is why the 4dp lift and the ring above the blocks
 * survive.
 *
 * @param swappedRoles the positions showing a voice actor instead of a character.
 * @param artwork draws a headshot the host has loaded. A person with none gets initials, decided **per
 *   person**: the old screen read the first actor's image and dropped the whole rail to a text line
 *   when it was missing.
 */
@Composable
internal fun DetailCastRowTv(
    cast: List<DetailCastMember>,
    swappedRoles: Set<Int>,
    onAction: (DetailAction) -> Unit,
    artwork: @Composable (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Spacer(Modifier.height(DetailScreenTvDefaults.SectionGap))
        // The board's "Cast 24". Its own count, and no provenance: the board's "· TMDB" names the
        // metadata provider, and nothing in the tree records which one supplied the cast.
        RailHeader(
            title = stringResource(Res.string.cast),
            count = cast.size.toString(),
            modifier = Modifier.contentInset(),
        )
        Spacer(Modifier.height(CloudStreamDimens.LiftedRowClearance))
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = DetailScreenTvDefaults.ContentStart - CloudStreamDimens.FocusRingBleed,
                    end = DetailScreenTvDefaults.ContentEnd - CloudStreamDimens.FocusRingBleed,
                ),
            horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap),
            contentPadding = PaddingValues(CloudStreamDimens.FocusRingBleed),
        ) {
            // The index is in the key because two people can share a name, and a duplicate key crashes.
            items(cast.size, key = { index -> "$index:${cast[index].name}" }) { index ->
                val member = cast[index]
                CastItem(
                    member = member,
                    showVoiceActor = index in swappedRoles,
                    onClick = {
                        // The only press action a cast item has: there is no actor id, so there is
                        // nowhere to open. A member with no voice actor does nothing on press and stays
                        // a focus stop, so the row scrolls and every name is reachable.
                        if (member.voiceActorName != null) {
                            onAction(DetailAction.CastMemberActivated(index))
                        }
                    },
                    artwork = artwork,
                )
            }
        }
    }
}

/**
 * One person: an 80dp disc, 8dp, the name's 16dp line box, 4dp, the role's 12dp line box — 120dp, which
 * is the block's whole height.
 *
 * The block lifts; the **ring goes on the disc**, which `Avatar` draws itself. The role slot stays empty
 * rather than re-centring the stack when the provider sent no role, because the board draws no
 * reduced-height variant.
 *
 * ## `tvFocusLift` must come after the focus target, or DOWN never leaves this rail
 *
 * `boundsInRoot()` walks from a node's own coordinator upwards, so a `graphicsLayer` **above** the focus
 * target is applied to the focus rect and one **below** it is not. With the lift above it, a focused
 * block reported a rect 4dp higher than its unlifted neighbours — and Compose's two-dimensional search
 * admits a `Down` candidate when `focusedRect.bottom < candidate.bottom || focusedRect.top <
 * candidate.top`, which 4dp of lift makes true for every sibling on the same row. Their 4dp of
 * major-axis distance then beat the 88dp to the rail below, so DOWN walked sideways along the cast row
 * for ever: measured on `Television_720p` as `Tobias Kerr: {Down=Ines Marlowe, Left=Ines Marlowe}`,
 * which stranded five device tests on `Ines Marlowe`.
 *
 * Unlifted, two blocks on one row share a top and a bottom, neither clause holds, no sibling is a
 * candidate, and the search leaves the rail as it should. `MediaCard` has always had this order — it
 * appends the lift after the caller's `clickable` — which is why `PosterCard` rails never showed it.
 * Keep the lift last, and keep the ring inside `Avatar` where it rises with the drawn content anyway.
 *
 * Note that on an AniList title the name is the *character* and the voice actor arrives separately, so
 * the first line can read a character and the second "Main". That is correct, not a binding defect.
 */
@Composable
private fun CastItem(
    member: DetailCastMember,
    showVoiceActor: Boolean,
    onClick: () -> Unit,
    artwork: @Composable (String?) -> Unit,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    var focused by remember { mutableStateOf(false) }
    val second = if (showVoiceActor) member.voiceActorName else member.role
    Column(
        modifier = Modifier
            .size(DetailScreenTvDefaults.CastBlockWidth, DetailScreenTvDefaults.CastBlockHeight)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            // The lift goes **after** the focus target, and that order is load-bearing — see the KDoc.
            .tvFocusLift(lifted = focused),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(
            initials = initialsOf(member.name),
            // The initials are decoration, and without this they are the *first* text on this item's
            // merged node: a screen reader says "RT" instead of "Ren Takeda", and two people with the
            // same initials read as one stop. The item is named by the name and the role below it.
            modifier = Modifier.clearAndSetSemantics { },
            focused = focused,
            image = member.imageUrl?.let { url -> { artwork(url) } },
        )
        Spacer(Modifier.height(DetailScreenTvDefaults.CastAvatarToName))
        BasicText(
            text = member.name,
            style = remember(typography, colors) {
                typography.label.copy(color = colors.ink, textAlign = TextAlign.Center)
            },
            maxLines = SINGLE_LINE,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().height(CloudStreamDimens.StackTitleLineBox),
        )
        Spacer(Modifier.height(CloudStreamDimens.StackGap))
        if (second != null) {
            BasicText(
                text = roleLabel(second),
                style = remember(typography, colors) {
                    typography.captionMono.copy(color = colors.muted, textAlign = TextAlign.Center)
                },
                maxLines = SINGLE_LINE,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().height(CloudStreamDimens.StackSubtitleLineBox),
            )
        }
    }
}

private const val SINGLE_LINE = 1

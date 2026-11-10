package com.lagradost.cloudstream4.feature.detail.tv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.lagradost.cloudstream4.core.designsystem.card.MediaCardState
import com.lagradost.cloudstream4.core.designsystem.card.PosterCard
import com.lagradost.cloudstream4.core.designsystem.card.PosterRung
import com.lagradost.cloudstream4.core.designsystem.rows.RailHeader
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.more_like_this
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.feature.detail.DetailAction
import com.lagradost.cloudstream4.feature.detail.META_SEPARATOR
import com.lagradost.cloudstream4.feature.detail.DetailRecommendation
import org.jetbrains.compose.resources.stringResource

/**
 * 7a-ii's "More like this" rail: [PosterRung.Large] (96x144), the rung the board draws, on the same
 * 108dp pitch as the cast row.
 *
 * The header reads `recommended` ("Recommended") because `:core:strings` has no "More like this", and
 * its count line is the count alone: the board writes `<PROVIDER> · 24`, but the provider name is already
 * in the chrome row and the board's own uppercase there is mockup copy rather than a style rule.
 *
 * There is no "See all" card — no key for the words, no destination drawn anywhere, and `CardSize`'s
 * constructor is `internal` to `:core:theme`, so a feature module cannot build the board's 76x144 card.
 *
 * `PosterCard` already matches the board exactly: the focus-gated title reveal, the 4dp lift and the
 * ring at offset 0 are all its own.
 *
 * @param takeFocus whether the first card takes focus now, which is what coming back from another title
 *   asks for.
 */
@Composable
internal fun DetailRelatedRowTv(
    recommendations: List<DetailRecommendation>,
    providerName: String?,
    onAction: (DetailAction) -> Unit,
    artwork: @Composable (String?) -> Unit,
    takeFocus: Boolean,
    onFocusTaken: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Spacer(Modifier.height(DetailScreenTvDefaults.SectionGap))
        // 7a-ii's "More like this", with its count and the provider that supplied them. The board
        // writes "38 · TMDB", naming the metadata provider; nothing in the tree records that, so this
        // names the extension the title came from, which is the provider this page actually has.
        RailHeader(
            title = stringResource(Res.string.more_like_this),
            count = listOfNotNull(recommendations.size.toString(), providerName)
                .joinToString(META_SEPARATOR),
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
            // The index is in the key because a provider can repeat an id, and a duplicate key crashes.
            items(recommendations.size, key = { index -> "$index:${recommendations[index].id}" }) { index ->
                val recommendation = recommendations[index]
                RelatedCard(
                    recommendation = recommendation,
                    onClick = { onAction(DetailAction.RecommendationActivated(recommendation.id)) },
                    artwork = artwork,
                    takeFocus = takeFocus && index == 0,
                    onFocusTaken = onFocusTaken,
                )
            }
        }
    }
}

/**
 * One recommendation.
 *
 * Every card keeps the same modifier chain, [FocusRequester] included. Attaching one only to the card
 * being returned to rebuilds its focus node when the flag goes false and drops the focus it has just
 * taken; on the real device focus went to the rail (`KMP-34`, measured on `Television_720p`).
 */
@Composable
private fun RelatedCard(
    recommendation: DetailRecommendation,
    onClick: () -> Unit,
    artwork: @Composable (String?) -> Unit,
    takeFocus: Boolean,
    onFocusTaken: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(takeFocus) {
        if (!takeFocus) return@LaunchedEffect
        // After a frame: a card composed in this one has not been placed.
        withFrameNanos { }
        focusRequester.requestFocusIfAttached()
        onFocusTaken()
    }
    PosterCard(
        rung = PosterRung.Large,
        state = MediaCardState(
            focused = focused,
            title = recommendation.title,
            meta = recommendation.year?.toString(),
        ),
        modifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = null, indication = null, onClick = onClick),
    ) {
        Box(Modifier.fillMaxSize()) { artwork(recommendation.posterUrl) }
    }
}

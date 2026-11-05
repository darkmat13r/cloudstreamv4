package com.lagradost.cloudstream4.feature.home.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.primitive.Badge
import com.lagradost.cloudstream4.core.designsystem.target.PrimaryTextButton
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.duration_format
import com.lagradost.cloudstream4.core.strings.generated.resources.home_more_info
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.home.HomeFeatured
import com.lagradost.cloudstream4.feature.home.HomeHero
import com.lagradost.cloudstream4.feature.home.HomeItem
import com.lagradost.cloudstream4.feature.home.HomeRowKey
import org.jetbrains.compose.resources.stringResource

/**
 * The art behind the top of the page, `TV Home` 3c's `.art` and `.scrim`: [HomeScreenTvDefaults.HeroArtHeight] tall from the
 * top of the screen, under the chrome.
 *
 * It sits in the page's first item, so it scrolls away with the hero. That item starts below the list's top padding, so the
 * art is placed that far above it, and it reports no height of its own so the item measures only what is laid out in it.
 */
@Composable
internal fun HomeHeroBackdrop(featured: HomeFeatured, artwork: @Composable (HomeItem) -> Unit) {
    val colors = CloudStreamTheme.colors
    val art = remember(featured) {
        HomeItem(id = featured.id, title = featured.title, posterUrl = featured.backdropUrl, posterHeaders = featured.backdropHeaders)
    }
    // 3c's two scrims, from the left and from the foot, so the words over the art stay legible on any picture.
    val across = remember(colors) {
        Brush.horizontalGradient(
            0.04f to colors.bg,
            0.26f to colors.bg.copy(alpha = 0.9f),
            0.52f to colors.bg.copy(alpha = 0.46f),
            0.84f to colors.bg.copy(alpha = 0f),
        )
    }
    val up = remember(colors) {
        Brush.verticalGradient(
            0.36f to colors.bg.copy(alpha = 0f),
            0.76f to colors.bg.copy(alpha = 0.7f),
            0.98f to colors.bg,
        )
    }
    Box(Modifier.heroBehindChrome()) {
        Box(Modifier.fillMaxSize(), propagateMinConstraints = true) { artwork(art) }
        Box(Modifier.fillMaxSize().background(across))
        Box(Modifier.fillMaxSize().background(up))
    }
}

/** Full width, [HomeScreenTvDefaults.HeroArtHeight] tall, raised to the top of the screen, and taking no room. */
private fun Modifier.heroBehindChrome(): Modifier = layout { measurable, constraints ->
    val width = constraints.maxWidth
    val placeable = measurable.measure(Constraints.fixed(width, HomeScreenTvDefaults.HeroArtHeight.roundToPx()))
    layout(width, 0) { placeable.place(0, -CloudStreamDimens.ChromeRowTop.roundToPx()) }
}

/**
 * 3c's hero words and its one button. Every band sits at a fixed distance from the last, as the board places them
 * absolutely, so a fact that is missing leaves its band empty rather than moving the button.
 *
 * **One button, `More info`, which opens the title's page.** That is what the old TV hero's only button does, and what
 * pressing a card does. 3c's `Resume S2 E4`, `Watch trailer`, `+ Library` and the square cell are not built: `KMP-40`
 * lists why for each.
 */
@Composable
internal fun HomeHeroTv(
    hero: HomeHero.Shown,
    takeFocus: Boolean,
    onFocusTaken: () -> Unit,
    onOpen: () -> Unit,
    stops: HomeStops,
    modifier: Modifier = Modifier,
) {
    val featured = hero.featured
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val requester = remember { FocusRequester() }
    LaunchedEffect(takeFocus) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        requester.requestFocusIfAttached()
        onFocusTaken()
    }
    val label = stringResource(Res.string.home_more_info)
    Column(modifier) {
        Spacer(Modifier.height(HomeScreenTvDefaults.HeroAboveEyebrow))
        HeroLine(hero.eyebrow, typography.labelMono.copy(color = colors.ink2), HomeScreenTvDefaults.HeroEyebrowWidth)
        Spacer(Modifier.height(HomeScreenTvDefaults.HeroBandGap))
        Box(Modifier.height(HomeScreenTvDefaults.HeroTitleLineBox), contentAlignment = Alignment.CenterStart) {
            BasicText(
                text = featured.title,
                style = typography.display.copy(color = colors.ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = HomeScreenTvDefaults.HeroTitleWidth),
            )
        }
        Spacer(Modifier.height(HomeScreenTvDefaults.HeroBandGap))
        HeroMeta(featured)
        Spacer(Modifier.height(HomeScreenTvDefaults.HeroBandGap))
        Box(Modifier.height(HomeScreenTvDefaults.HeroSynopsisHeight)) {
            featured.synopsis?.let { synopsis ->
                BasicText(
                    text = synopsis,
                    style = typography.body.copy(color = colors.ink2),
                    maxLines = HomeScreenTvDefaults.HERO_SYNOPSIS_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = HomeScreenTvDefaults.HeroSynopsisWidth),
                )
            }
        }
        Spacer(Modifier.height(HomeScreenTvDefaults.HeroBandGap))
        PrimaryTextButton(
            label = label,
            onClick = onOpen,
            modifier = Modifier
                .focusRequester(requester)
                .onFocusChanged { if (it.isFocused) stops.last = requester }
                // The title, because `More info` alone does not say about what.
                .semantics { contentDescription = "$label · ${featured.title}" },
        )
        Spacer(Modifier.height(HomeScreenTvDefaults.HeroBelowButton))
    }
}

/** The page's key for the hero's button, through the same focus target the cards use. */
internal fun heroTarget(featured: HomeFeatured) = HomeFocusTarget(HomeRowKey.Hero, featured.id)

@Composable
private fun HeroLine(text: String?, style: androidx.compose.ui.text.TextStyle, width: Dp) {
    Box(Modifier.height(CloudStreamDimens.StackTitleLineBox), contentAlignment = Alignment.CenterStart) {
        if (text != null) {
            BasicText(
                text = text,
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = width),
            )
        }
    }
}

/**
 * 3c's `.meta`: the certificate, then the facts in one mono line, then the score. Only those the source gave. 3c's season
 * and episode counts are not here: the old hero does not show them, and no word exists for either with its number.
 */
@Composable
private fun HeroMeta(featured: HomeFeatured) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val duration = featured.durationMinutes?.takeIf { it > 0 }?.let {
        UiString.Resource(Res.string.duration_format, listOf(it)).resolve()
    }
    val facts = listOfNotNull(featured.year?.toString(), duration) + featured.genres
    Row(
        modifier = Modifier.height(CloudStreamDimens.StackTitleLineBox).width(HomeScreenTvDefaults.HeroMetaWidth),
        horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.ListRowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        featured.contentRating?.let { Badge(text = it) }
        if (facts.isNotEmpty()) {
            BasicText(
                text = facts.joinToString(HomeScreenTvDefaults.META_SEPARATOR),
                style = typography.labelMono.copy(color = colors.ink2),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        featured.score?.let { Badge(text = it) }
    }
}

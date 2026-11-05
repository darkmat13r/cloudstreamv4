package com.lagradost.cloudstream4.feature.library.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.library.LibraryBucket
import com.lagradost.cloudstream4.feature.library.LibraryCard
import com.lagradost.cloudstream4.feature.library.LibraryState
import com.lagradost.cloudstream4.feature.library.LibraryStatus
import com.lagradost.cloudstream4.feature.library.LibraryTab
import com.lagradost.cloudstream4.feature.library.LibraryTitleId

/**
 * One preview per state, at the artboard's size. Development previews and not screenshot tests, for the
 * reason `SearchScreenTvPreviews` records: the screenshot plugin renders nothing and cannot see a KMP module.
 */
private const val TV_WIDTH_DP = 960
private const val TV_HEIGHT_DP = 540

private val PreviewCards = List(21) { index ->
    LibraryCard(
        id = LibraryTitleId("preview/$index"),
        title = "Title $index",
        providerName = "Filmxy",
        typeLabel = "MOVIE",
        progress = if (index % 3 == 0) 0.4f else null,
    )
}

private val PreviewCounts = mapOf(
    LibraryBucket.Watching to 21,
    LibraryBucket.Completed to 8,
    LibraryBucket.Dropped to 1,
)

@Composable
private fun PreviewGround(state: LibraryState) {
    CloudStreamTheme {
        Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
            LibraryScreenTv(state = state, onAction = {})
        }
    }
}

@Preview(name = "Library grid", widthDp = TV_WIDTH_DP, heightDp = TV_HEIGHT_DP)
@Composable
private fun LibraryGrid() = PreviewGround(
    LibraryState(
        tab = LibraryTvTabs.first(),
        status = LibraryStatus.Showing(PreviewCards),
        counts = PreviewCounts,
        totalTitles = 30,
    ),
)

@Preview(name = "Library empty page", widthDp = TV_WIDTH_DP, heightDp = TV_HEIGHT_DP)
@Composable
private fun LibraryEmptyPage() = PreviewGround(
    LibraryState(
        tab = LibraryTab.Bucket(LibraryBucket.OnHold),
        status = LibraryStatus.Empty,
        counts = PreviewCounts,
        totalTitles = 30,
    ),
)

@Preview(name = "Library loading", widthDp = TV_WIDTH_DP, heightDp = TV_HEIGHT_DP)
@Composable
private fun LibraryLoading() = PreviewGround(LibraryState(tab = LibraryTvTabs.first()))

@Preview(name = "Library failed", widthDp = TV_WIDTH_DP, heightDp = TV_HEIGHT_DP)
@Composable
private fun LibraryFailed() = PreviewGround(
    LibraryState(tab = LibraryTvTabs.first(), status = LibraryStatus.Failed("IOException")),
)

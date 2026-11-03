package com.lagradost.cloudstream4.home

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopFeedback
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.library
import com.lagradost.cloudstream4.core.strings.generated.resources.title_home
import com.lagradost.cloudstream4.core.strings.generated.resources.title_search
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.home.HomeAction
import com.lagradost.cloudstream4.feature.home.HomeEffect
import com.lagradost.cloudstream4.feature.home.HomeViewModel
import com.lagradost.cloudstream4.feature.home.desktop.HomeScreenDesktop
import com.lagradost.cloudstream4.search.PosterImage
import org.jetbrains.compose.resources.stringResource

/**
 * @param setEscape what Escape does while this is on screen. Only the picker needs it: at the top level
 *   there is nothing to go back to, so the window keeps its default.
 */
@Composable
internal fun HomeHost(
    viewModel: HomeViewModel,
    setEscape: ((() -> Unit)?) -> Unit,
    onOpen: (String) -> Unit,
    onSearch: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    SideEffect {
        setEscape(if (state.pickerOpen) ({ viewModel.onAction(HomeAction.PickerDismissed) }) else null)
    }
    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is HomeEffect.OpenTitle -> onOpen(effect.id)
                HomeEffect.OpenSearch -> onSearch()
            }
        }
    }
    HomeScreenDesktop(
        state = state,
        onAction = viewModel::onAction,
        // Only public http(s) addresses get this far. See `posterHttpClient`.
        artwork = { item -> PosterImage(url = item.posterUrl, modifier = Modifier.fillMaxSize()) },
    )
}

/**
 * A screen with nothing under it. The tab bar is these in this order, and `Main.kt`'s router maps each
 * to the screen it shows.
 *
 * It was a `Boolean` while there were two of them (`KMP-20`). `KMP-26` added the third, and a third
 * thing a boolean has to express is where that stops working.
 */
internal enum class TopLevelDestination {
    Home,
    Search,
    Library,
}

/** The screens that have nothing under them, and the control that moves between them. */
@Composable
internal fun TopLevelScreen(
    selected: TopLevelDestination,
    onSelect: (TopLevelDestination) -> Unit,
    trailing: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = DesktopDimens.WindowMargin, end = DesktopDimens.WindowMargin, top = DesktopDimens.GroupGap),
        ) {
            TopLevelDestination.entries.forEach { destination ->
                TopLevelTab(destination.label(), destination == selected) { onSelect(destination) }
            }
            Spacer(Modifier.weight(1f))
            trailing()
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun TopLevelDestination.label(): String = when (this) {
    TopLevelDestination.Home -> stringResource(Res.string.title_home)
    TopLevelDestination.Search -> stringResource(Res.string.title_search)
    TopLevelDestination.Library -> stringResource(Res.string.library)
}

/** Text only: the selected one is filled and in full ink, and there is no icon pack yet (`DEC-11`). */
@Composable
internal fun TopLevelTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = CloudStreamTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.medium
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .desktopFeedback(source, lift = false)
            .tvFocusRing(focused = focused, shape = shape)
            .background(if (selected || hovered) colors.raised else Color.Transparent, shape)
            .selectable(
                selected = selected,
                interactionSource = source,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .heightIn(min = DesktopDimens.ButtonHeight)
            .padding(horizontal = DesktopDimens.ButtonPadding),
    ) {
        BasicText(
            text = label,
            style = CloudStreamTheme.typography.label.copy(color = if (selected) colors.ink else colors.muted),
            maxLines = 1,
        )
    }
}

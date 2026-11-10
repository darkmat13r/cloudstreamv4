package com.lagradost.cloudstream4.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopFeedback
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.player_panel_on
import com.lagradost.cloudstream4.core.strings.generated.resources.player_panel_open
import com.lagradost.cloudstream4.core.strings.generated.resources.player_panel_use
import com.lagradost.cloudstream4.core.strings.generated.resources.player_source_playing
import com.lagradost.cloudstream4.core.strings.generated.resources.player_source_retry
import com.lagradost.cloudstream4.core.strings.generated.resources.player_source_switch
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.player.RowVerb
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * `DS-23` M4 to M7: one row of a player panel. 56 tall, the 4×24 marker at its left edge when chosen, the name over a
 * mono second line, and at the right the verb that says what pressing it does. Pressing the row is the commit.
 *
 * Chosen is the raised fill and the muted marker; white is focus only (Design System v2), so the board's white marker
 * is muted here, as every Mac list's mark already was. A failed row dims its name and its verb.
 *
 * @param detail the second line, only from what the player knows. Null leaves the row one line, still 56 tall.
 * @param fromFile the words are the file's, not the app's: quieter unless chosen, and left to right whatever they hold.
 */
@Composable
internal fun PlayerPanelRow(
    title: String,
    detail: String?,
    verb: RowVerb,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    failed: Boolean = false,
    fromFile: Boolean = false,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.medium
    val direction = if (fromFile) TextDirection.Ltr else TextDirection.Unspecified
    val titleColor = when {
        failed -> colors.muted
        fromFile && !selected -> colors.ink2
        else -> colors.ink
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .desktopFeedback(source, lift = false)
            .tvFocusRing(focused = focused, shape = shape)
            .background(if (hovered || selected) colors.raised else Color.Transparent, shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .heightIn(min = PlayerScreenDefaults.PanelRowHeight),
    ) {
        // Decoration: a row's state is also in the selection the screen reads back.
        Box(
            Modifier
                .size(PlayerScreenDefaults.PanelMarkerWidth, PlayerScreenDefaults.PanelMarkerHeight)
                .background(if (selected) colors.muted else Color.Transparent, CloudStreamTheme.shapes.small),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(
                    start = PlayerScreenDefaults.PanelRowTextStart - PlayerScreenDefaults.PanelMarkerWidth,
                    top = DesktopDimens.RelatedGap,
                    bottom = DesktopDimens.RelatedGap,
                ),
        ) {
            BasicText(
                text = title,
                style = typography.label.copy(color = titleColor, textDirection = direction),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                BasicText(
                    text = detail,
                    style = typography.labelMono.copy(color = colors.muted, textDirection = direction),
                    // Two, for a failure's sentence in a longer language; the row grows rather than cut it.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        BasicText(
            text = stringResource(verb.words()),
            style = typography.label.copy(color = if (failed) colors.muted else colors.ink),
            maxLines = 1,
            modifier = Modifier.padding(start = DesktopDimens.RelatedGap, end = PlayerScreenDefaults.PanelRowVerbEnd),
        )
    }
}

/** The same keys TV's 7e rows read (`KMP-66`). */
internal fun RowVerb.words(): StringResource = when (this) {
    RowVerb.Playing -> Res.string.player_source_playing
    RowVerb.Switch -> Res.string.player_source_switch
    RowVerb.Retry -> Res.string.player_source_retry
    RowVerb.On -> Res.string.player_panel_on
    RowVerb.Use -> Res.string.player_panel_use
    RowVerb.Open -> Res.string.player_panel_open
}

/** On for the one in use, Use for the rest. */
internal fun useVerb(selected: Boolean): RowVerb = if (selected) RowVerb.On else RowVerb.Use

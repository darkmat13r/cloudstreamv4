package com.lagradost.cloudstream4.core.designsystem.target

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.tooling.preview.Preview
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * Previews of the three target classes.
 *
 * **A static preview cannot show the focus ring**, because focus is real input state that a
 * focusable only acquires when something gives it focus — which is why [FocusedChipPreview]
 * requests it explicitly. Treat the unfocused previews as the resting appearance and that one as
 * the proof that the ring sits [CloudStreamDimens.FocusRingBleed] (8dp) outside the target without
 * moving it. Every preview is padded by [CloudStreamDimens.FocusBandInset] (28dp) for the same
 * reason the artboard margin is 56px and not 48px: the bleed has to land somewhere unclipped.
 */
@Composable
private fun PreviewChrome(content: @Composable () -> Unit) {
    CloudStreamTheme {
        Box(
            modifier = Modifier
                .background(CloudStreamTheme.colors.bg)
                .padding(CloudStreamDimens.FocusBandInset),
            contentAlignment = Alignment.Center,
            content = { content() },
        )
    }
}

/** A stand-in for a real glyph: this module ships no icon assets. */
@Composable
private fun PreviewGlyph(focused: Boolean) {
    val colors = CloudStreamTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                color = if (focused) colors.ink else colors.muted,
                shape = CloudStreamTheme.shapes.small,
            ),
    )
}

@Preview
@Composable
private fun IconCellPreview() {
    PreviewChrome {
        val arrangement = remember { Arrangement.spacedBy(CloudStreamDimens.MinFocusGap) }
        Row(horizontalArrangement = arrangement, verticalAlignment = Alignment.CenterVertically) {
            IconCell(onClick = {}, glyph = { focused -> PreviewGlyph(focused) })
            IconCell(onClick = {}, glyph = { focused -> PreviewGlyph(focused) })
            IconCell(onClick = {}, enabled = false, glyph = { PreviewGlyph(focused = false) })
        }
    }
}

@Preview
@Composable
private fun TextButtonPreview() {
    PreviewChrome {
        val arrangement = remember { Arrangement.spacedBy(CloudStreamDimens.MinFocusGap) }
        Column(verticalArrangement = arrangement, horizontalAlignment = Alignment.Start) {
            PrimaryTextButton(label = "Play", onClick = {})
            PrimaryTextButton(label = "Play", onClick = {}, enabled = false)
            GhostTextButton(label = "More info", onClick = {})
            GhostTextButton(label = "More info", onClick = {}, enabled = false)
        }
    }
}

@Preview
@Composable
private fun ChipRowPreview() {
    PreviewChrome {
        ChipRow {
            Chip(label = "All", selected = true, onClick = {})
            Chip(label = "Movies", selected = false, onClick = {}, count = "128")
            Chip(label = "Series", selected = false, onClick = {}, count = "42")
        }
    }
}

/** The one preview that shows a ring: it takes focus on composition. */
@Preview
@Composable
private fun FocusedChipPreview() {
    PreviewChrome {
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
        Chip(
            label = "Movies",
            selected = false,
            onClick = {},
            modifier = Modifier.focusRequester(focusRequester),
            count = "128",
        )
    }
}

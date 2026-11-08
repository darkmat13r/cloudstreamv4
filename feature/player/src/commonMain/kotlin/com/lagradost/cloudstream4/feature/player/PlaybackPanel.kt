package com.lagradost.cloudstream4.feature.player

import androidx.compose.runtime.Immutable

/** Which of artboard 7e's panels is open: what SOURCE, SUBTITLES or AUDIO opened. */
enum class PanelKind { Source, Subtitles, Audio }

/**
 * A row's trailing verb, 7e's `.tag`: what pressing the row does, or that it is what plays. There is no Apply: pressing
 * the row is the commit.
 */
enum class RowVerb { Playing, Switch, Retry, On, Use, Open }

/**
 * One row of a 7e panel.
 *
 * Labels only, resolved by the host, as [PlayerHudState] is: a source's row is its display name and quality, never its
 * address.
 *
 * @property key what the host acts on when the row is pressed. Opaque.
 * @property active what plays now, or the track in use: 7e marks it three ways, marker, fill and its verb.
 * @property failed this source failed; its row stays, dimmed with a failed marker, and pressing it retries.
 */
@Immutable
data class PanelRow(
    val key: String,
    val title: String,
    val detail: String? = null,
    val verb: RowVerb,
    val active: Boolean = false,
    val failed: Boolean = false,
)

/**
 * One open 7e panel.
 *
 * @property title `Source`, `Subtitles`, `Audio`, or a second level's name.
 * @property meta the header's lines under the title: `2 hidden links`, `Switching keeps your position…`.
 * @property nested a second level (a subtitle's variants, its settings, the video tracks): Back goes up, not out.
 * @property offline 7e's offline state: a banner takes the meta block; no row moves.
 */
@Immutable
data class PlaybackPanelState(
    val kind: PanelKind,
    val title: String,
    val meta: List<String> = emptyList(),
    val rows: List<PanelRow> = emptyList(),
    val nested: Boolean = false,
    val offline: Boolean = false,
)

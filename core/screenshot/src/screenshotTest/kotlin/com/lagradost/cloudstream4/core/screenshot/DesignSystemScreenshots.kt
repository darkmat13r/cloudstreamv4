package com.lagradost.cloudstream4.core.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButton
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButtonTone
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopIconButton
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.primitive.Badge
import com.lagradost.cloudstream4.core.designsystem.primitive.BadgeGroup
import com.lagradost.cloudstream4.core.designsystem.primitive.BadgeTone
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanel
import com.lagradost.cloudstream4.core.designsystem.rows.ListRow
import com.lagradost.cloudstream4.core.designsystem.rows.ListRowState
import com.lagradost.cloudstream4.core.designsystem.state.StateAction
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.designsystem.state.StateView
import com.lagradost.cloudstream4.core.designsystem.target.Chip
import com.lagradost.cloudstream4.core.designsystem.target.GhostTextButton
import com.lagradost.cloudstream4.core.designsystem.target.PrimaryTextButton
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * The screenshot suite for the design system.
 *
 * These are a *curated* set of states, not a mirror of every `@Preview` in
 * `:core:designsystem`. A preview written for development convenience can be changed freely; a
 * preview in this file is a state someone decided to hold still, and changing its rendering
 * should fail the build until a human looks at the diff and re-records it.
 *
 * Add a case here when a state is load-bearing: a failure that must stay legible, a disabled
 * control that must not read as enabled, a token whose contrast matters. Do not add a case for
 * every permutation — a suite nobody reads the diffs of is worse than no suite.
 *
 * Record:  `./gradlew :core:screenshot:updateDebugScreenshotTest`
 * Verify:  `./gradlew :core:screenshot:validateDebugScreenshotTest`
 */
private const val SURFACE_PADDING_DP = 16

@Composable
private fun Surface(content: @Composable () -> Unit) {
    CloudStreamTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CloudStreamTheme.colors.bg)
                .padding(SURFACE_PADDING_DP.dp),
            verticalArrangement = Arrangement.spacedBy(SURFACE_PADDING_DP.dp),
        ) {
            content()
        }
    }
}

/**
 * Primary and ghost, enabled and disabled.
 *
 * The disabled primary is the case worth holding: it drops to `raised`/`muted` rather than
 * dimming the ink fill, so it cannot be mistaken for an enabled control at three metres.
 */
@Preview(name = "text buttons", widthDp = 360, heightDp = 260)
@Composable
fun TextButtons() {
    Surface {
        PrimaryTextButton(label = "Resume", onClick = {})
        PrimaryTextButton(label = "Resume", onClick = {}, enabled = false)
        GhostTextButton(label = "Trailer", onClick = {})
        GhostTextButton(label = "Trailer", onClick = {}, enabled = false)
    }
}

/**
 * Selected and unselected, with and without a trailing count.
 *
 * The count is the part that regressed before: a 20sp mono line centred in a 24dp chip lands off
 * the 4dp step, so it is set in the 12sp ramp whose line box divides cleanly.
 */
@Preview(name = "chips", widthDp = 360, heightDp = 220)
@Composable
fun Chips() {
    Surface {
        Chip(label = "Movies", selected = true, onClick = {})
        Chip(label = "TV series", selected = false, onClick = {})
        Chip(label = "Watching", selected = true, onClick = {}, count = "142")
        Chip(label = "Dropped", selected = false, onClick = {}, count = "11")
    }
}

/**
 * The 48dp row in its four states.
 *
 * The two-line variant is the one under watch: title plus subtitle must total a multiple of 16dp
 * or the stack does not centre on the step, which is invisible in isolation and obvious in a
 * column of twelve.
 */
@Preview(name = "list rows", widthDp = 420, heightDp = 300)
@Composable
fun ListRows() {
    Surface {
        ListRow(state = ListRowState(title = "Vidsrc"), onClick = {})
        ListRow(
            state = ListRowState(title = "Gogoanime", subtitle = "EN · ANIME"),
            onClick = {},
        )
        ListRow(
            state = ListRowState(
                title = "Filmxy",
                subtitle = "EN · MOVIES",
                selected = true,
            ),
            onClick = {},
        )
        ListRow(
            state = ListRowState(
                title = "Dramacool",
                subtitle = "KO · UNAVAILABLE",
                enabled = false,
            ),
            onClick = {},
        )
    }
}

/**
 * Both badge tones side by side.
 *
 * `Live` is the only sanctioned non-playback use of the live token in this component, so the two
 * tones appearing together is exactly the comparison worth freezing.
 */
@Preview(name = "badges", widthDp = 240, heightDp = 140)
@Composable
fun Badges() {
    Surface {
        Badge(text = "4K")
        Badge(text = "CACHED")
        Badge(text = "LIVE", tone = BadgeTone.Live)
    }
}


/**
 * `BUG-62`: what the design system does when a translation is far longer than the English it was drawn for.
 *
 * Two states, both of which were a silent cut before: a pair of badges that cannot share a card's edge, and a side
 * panel's row title that cannot fit one line. Each is in the real worst translation the sweep found, on the surface
 * that caps it — a 72dp badge box (a `PortraitMedium` card less its inset), and a real `SidePanel`, which is what
 * tells a row it may take a second line at all.
 *
 * This is the kind of state the rule asks for: the geometry is easy to break silently, and the thing being held is
 * that **nothing is cut without saying so** — the badges stack, the title takes a second line and its row grows one
 * whole 16dp step.
 */
@Preview(name = "long labels", widthDp = 360, heightDp = 540)
@Composable
fun LongLabels() {
    Surface {
        Box(Modifier.width(BADGE_BOX_DP.dp)) {
            BadgeGroup {
                Badge(text = SDR_CA)
                Badge(text = DUB_TA)
            }
        }
        SidePanel(header = { Badge(text = SORT_BY_EN) }) {
            ListRow(state = ListRowState(title = RELEASE_TA, selected = true), onClick = {})
            ListRow(
                state = ListRowState(title = UPDATED_TA, subtitle = NOT_KEPT_TA, enabled = false),
                onClick = {},
            )
            ListRow(state = ListRowState(title = "Rating"), onClick = {})
        }
    }
}

/** `CloudStreamCardSizes.PortraitMedium` (80dp) less `MediaCardDefaults.ContentPadding` on both sides. */
private const val BADGE_BOX_DP = 72

/** The panel's own header, which is a `RailHeader` on a real screen; this case is about its rows. */
private const val SORT_BY_EN = "SORT BY"

/** `quality_sdr` in `ca`, upper-cased as a badge draws it: 154.0dp against English's 21.0dp. */
private const val SDR_CA = "RANG DINAMIC SIMPLE (SDR)"

/** `app_dubbed_text` in `ta`: 141.2dp against English's 21.8dp. */
private const val DUB_TA = "மொழிபெயர்க்கப்பட்டது"

/** `sort_release_date_old` in `ta`, in a single-line row's `body`: 309.5dp against English's 157.0dp. */
private const val RELEASE_TA = "வெளியீட்டு தேதி (பழையதிலிருந்து புதியது)"

/** `sort_updated_new` in `ta`, in a two-line row's `label`: 216.3dp against English's 107.4dp. */
private const val UPDATED_TA = "புதுப்பிக்கப்பட்டது (பழையது புதியது)"

/** `library_sort_not_kept` in `ta`, the reason `TV Library` 5d puts under a criterion it cannot sort by. */
private const val NOT_KEPT_TA = "Simkl இதை வைத்திருக்காது"

/**
 * A full-surface state with its single required action.
 *
 * `StatePrompt` cannot be constructed without an action, which is the constraint this case
 * exists to demonstrate: the button in this image is not optional decoration, it is the reason
 * the type compiles.
 */
@Preview(name = "state view", widthDp = 640, heightDp = 400)
@Composable
fun StateViewEmpty() {
    CloudStreamTheme {
        Column(modifier = Modifier.background(CloudStreamTheme.colors.bg)) {
            StateView(
                prompt = StatePrompt(
                    headline = "Nothing on hold",
                    body = "Titles you pause land here. Four are in Watching that you " +
                        "haven't opened in a month.",
                    action = StateAction(label = "Go to Watching", onActivate = {}),
                ),
                requestInitialFocus = false,
            )
        }
    }
}

/**
 * The same surface carrying a machine-readable detail line.
 *
 * `detail` is the field that replaces the app's `// No user indication it failed :(` branch, so
 * it is worth a case of its own — it is the part a user can paste into a bug report.
 */
@Preview(name = "state view with detail", widthDp = 640, heightDp = 400)
@Composable
fun StateViewError() {
    CloudStreamTheme {
        Column(modifier = Modifier.background(CloudStreamTheme.colors.bg)) {
            StateView(
                prompt = StatePrompt(
                    headline = "Couldn't reach AniList",
                    body = "Your library is showing the copy saved on this device.",
                    action = StateAction(label = "Retry sync", onActivate = {}),
                ),
                detail = "HTTP 503 · anilist.co · 2h ago",
                requestInitialFocus = false,
            )
        }
    }
}

/**
 * A glyph has to stay centred in its button, and a primary button's glyph has to flip to the dark role with its
 * fill: a white glyph on the white fill would simply vanish.
 */
@Preview(name = "desktop icon buttons", widthDp = 320, heightDp = 80)
@Composable
fun DesktopIconButtons() {
    CloudStreamTheme {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.background(CloudStreamTheme.colors.bg).padding(16.dp),
        ) {
            DesktopIconButton(CloudStreamIcons.Back, "Go back", onClick = {})
            DesktopIconButton(CloudStreamIcons.Play, "Play", onClick = {}, tone = DesktopButtonTone.Primary)
            DesktopIconButton(CloudStreamIcons.Close, "Close", onClick = {}, tone = DesktopButtonTone.Quiet)
            DesktopButton("Play", onClick = {}, primary = true, icon = CloudStreamIcons.Play)
        }
    }
}

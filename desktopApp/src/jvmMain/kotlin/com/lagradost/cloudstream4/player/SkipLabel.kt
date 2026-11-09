package com.lagradost.cloudstream4.player

import androidx.compose.runtime.Composable
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.next_episode
import com.lagradost.cloudstream4.core.strings.generated.resources.skip_type_credits
import com.lagradost.cloudstream4.core.strings.generated.resources.skip_type_ed
import com.lagradost.cloudstream4.core.strings.generated.resources.skip_type_format
import com.lagradost.cloudstream4.core.strings.generated.resources.skip_type_intro
import com.lagradost.cloudstream4.core.strings.generated.resources.skip_type_mixed_ed
import com.lagradost.cloudstream4.core.strings.generated.resources.skip_type_mixed_op
import com.lagradost.cloudstream4.core.strings.generated.resources.skip_type_op
import com.lagradost.cloudstream4.core.strings.generated.resources.skip_type_preview
import com.lagradost.cloudstream4.core.strings.generated.resources.skip_type_recap
import org.jetbrains.compose.resources.stringResource

/** The Skip button's words (`KMP-86`), Android's (`VideoSkipStamp.uiText`): `Skip Opening`, or `Next episode`. */
@Composable
internal fun skipLabel(kind: SkipKind, toNextEpisode: Boolean): String =
    if (toNextEpisode) {
        stringResource(Res.string.next_episode)
    } else {
        stringResource(Res.string.skip_type_format, stringResource(kindName(kind)))
    }

private fun kindName(kind: SkipKind) = when (kind) {
    SkipKind.Opening -> Res.string.skip_type_op
    SkipKind.Ending -> Res.string.skip_type_ed
    SkipKind.Recap -> Res.string.skip_type_recap
    SkipKind.MixedOpening -> Res.string.skip_type_mixed_op
    SkipKind.MixedEnding -> Res.string.skip_type_mixed_ed
    SkipKind.Credits -> Res.string.skip_type_credits
    SkipKind.Intro -> Res.string.skip_type_intro
    SkipKind.Preview -> Res.string.skip_type_preview
}

package com.lagradost.cloudstream4.feature.account

import androidx.compose.runtime.Composable
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.account_profile_current
import com.lagradost.cloudstream4.core.strings.generated.resources.account_profile_locked
import com.lagradost.cloudstream4.core.strings.generated.resources.account_profile_no_sync
import org.jetbrains.compose.resources.stringResource

/**
 * A profile card's second line in words: `Locked · PIN`, `Current · AniList`, or `No sync`. TV's cards (8d) and the
 * Mac's (`DS-25` A1, A2) say the same thing, so both read it from here (`KMP-95`).
 */
@Composable
internal fun profileLineText(card: ProfileCard): String = when (val line = card.line()) {
    ProfileLine.Locked -> stringResource(Res.string.account_profile_locked)
    is ProfileLine.Status -> {
        val services = if (line.services.isEmpty()) stringResource(Res.string.account_profile_no_sync)
        else line.services.joinToString(", ")
        if (line.current) "${stringResource(Res.string.account_profile_current)} · $services" else services
    }
}

/**
 * Up to two initials, for a disc with no picture: `Alex` is `A`, `Ana Lima` is `AL`. Each is a word's first code point,
 * so a letter outside the BMP is never cut to half a surrogate pair.
 */
internal fun profileInitials(name: String): String =
    name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.firstCodePoint().uppercase() }

private fun String.firstCodePoint(): String =
    if (length >= 2 && this[0].isHighSurrogate() && this[1].isLowSurrogate()) substring(0, 2) else substring(0, 1)

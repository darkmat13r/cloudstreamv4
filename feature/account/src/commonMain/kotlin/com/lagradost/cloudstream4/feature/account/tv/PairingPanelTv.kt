package com.lagradost.cloudstream4.feature.account.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanel
import com.lagradost.cloudstream4.core.designsystem.rows.ListRow
import com.lagradost.cloudstream4.core.designsystem.rows.ListRowState
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.account_pair_code
import com.lagradost.cloudstream4.core.strings.generated.resources.account_pair_instructions
import com.lagradost.cloudstream4.core.strings.generated.resources.account_pair_new_code
import com.lagradost.cloudstream4.core.strings.generated.resources.account_pair_title
import com.lagradost.cloudstream4.core.strings.generated.resources.cancel
import com.lagradost.cloudstream4.core.strings.generated.resources.device_pin_counter_text
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.account.AccountAction
import com.lagradost.cloudstream4.feature.account.Pairing
import org.jetbrains.compose.resources.stringResource

/**
 * 8d's **Pair Simkl** panel: a device code to enter on a phone, with a QR code of the address.
 *
 * Focus starts on *Get a new code* and is trapped in the panel (the host's); *Cancel* is pinned at the foot where 7e
 * pins Done. Leaving the panel by any route asks the host to stop polling (`BUG-49`'s `DevicePinPoll`).
 *
 * The code is shown because the person has to read it. It is a short-lived credential: it is never logged, and the
 * screenshots of this panel use a fixture.
 */
@Composable
fun PairingPanelTv(pairing: Pairing, onAction: (AccountAction) -> Unit, modifier: Modifier = Modifier) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val entry = remember { FocusRequester() }
    // The host moves focus into the panel on the frame it opens; ask again once the rows exist (as 7e's panels do).
    LaunchedEffect(pairing.serviceName) {
        withFrameNanos { }
        runCatching { entry.requestFocus() }
    }
    SidePanel(
        modifier = modifier,
        header = {
            Column(verticalArrangement = Arrangement.spacedBy(PairingPanelDefaults.HeaderGap)) {
                Text(
                    UiString.Resource(Res.string.account_pair_title, listOf(pairing.serviceName)).resolve(),
                    style = typography.railTitle,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(Res.string.account_pair_instructions),
                    style = typography.label,
                    color = colors.ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        commit = {
            ListRow(
                state = ListRowState(title = stringResource(Res.string.cancel)),
                onClick = { onAction(AccountAction.CancelPairing) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PairingPanelDefaults.BlockGap),
        ) {
            pairing.qr?.let { qr ->
                Box(
                    Modifier
                        .size(PairingPanelDefaults.QrSize)
                        // `.qr` is white whatever the theme: a code has to scan.
                        .background(Color.White, RoundedCornerShape(PairingPanelDefaults.QrRadius))
                        .padding(PairingPanelDefaults.QrQuietZone),
                ) {
                    Image(bitmap = qr, contentDescription = null, modifier = Modifier.fillMaxSize())
                }
            }
            Text(pairing.verificationUrl, style = typography.labelMono, color = colors.ink2, textAlign = TextAlign.Center, maxLines = 1)
            Text(
                stringResource(Res.string.account_pair_code).uppercase(),
                style = typography.captionMono.copy(fontWeight = FontWeight.Bold, letterSpacing = PairingPanelDefaults.LabelTracking),
                color = colors.muted,
                textAlign = TextAlign.Center,
            )
            Text(
                pairing.userCode,
                style = typography.h1.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = PairingPanelDefaults.CodeTracking),
                color = colors.ink,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Text(
                UiString.Resource(Res.string.device_pin_counter_text, listOf(pairing.secondsLeft / 60, pairing.secondsLeft % 60)).resolve(),
                style = typography.captionMono,
                color = colors.muted,
                textAlign = TextAlign.Center,
            )
        }
        ListRow(
            state = ListRowState(title = stringResource(Res.string.account_pair_new_code)),
            onClick = { onAction(AccountAction.NewCode) },
            modifier = Modifier.fillMaxWidth().focusRequester(entry),
        )
    }
}

/** Literals the pairing panel owns. Artboard px / 2 = dp. */
internal object PairingPanelDefaults {
    /** The title at y96 over the instructions at y176. */
    val HeaderGap: Dp = 12.dp

    /** Between the QR, the address, the label, the code and the countdown. */
    val BlockGap: Dp = 8.dp

    /** `.qr`, 232 px. */
    val QrSize: Dp = 116.dp

    /** `.qr`'s 8 px radius. */
    val QrRadius: Dp = 4.dp

    /** `.qrp`'s 16 px inset: the white margin a scanner needs. */
    val QrQuietZone: Dp = 8.dp

    /** `.lbl`'s `letter-spacing:0.14em`. */
    val LabelTracking = 0.14.em

    /** `.code`'s `letter-spacing:0.18em`, so a code reads as separate characters across a room. */
    val CodeTracking = 0.18.em
}

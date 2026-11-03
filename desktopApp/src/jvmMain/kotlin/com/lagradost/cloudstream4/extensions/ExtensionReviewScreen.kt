package com.lagradost.cloudstream4.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButton
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopIconButton
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.primitive.HairlineSide
import com.lagradost.cloudstream4.core.designsystem.primitive.hairline
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_allow
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_decision_not_saved
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_decisions_unreadable
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_keep_off
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_review_clean_note
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_review_empty
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_review_file_note
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_review_intro
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_review_title
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_status_allowed
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_status_blocked
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_status_hash_mismatch
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_status_held
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_status_kept_off
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_status_start_failed
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_warning_classloader_access
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_warning_dynamic_script
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_warning_environment
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_warning_exit
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_warning_filesystem
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_warning_other
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_warning_raw_socket
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_warning_reflection
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_warning_suspicious_string
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_warning_telemetry
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_warning_webview
import com.lagradost.cloudstream4.core.strings.generated.resources.extensions_waiting_count
import com.lagradost.cloudstream4.core.strings.generated.resources.go_back
import com.lagradost.cloudstream4.core.strings.generated.resources.loading
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The sentence for one scan rule. A rule this does not know gets a sentence saying so, so a new rule cannot be
 * shown as nothing. `ExtensionReviewWordsTest` fails when one of the scanner's rules reaches that fallback.
 */
internal fun warningSentence(rule: String): StringResource = when (rule) {
    "reflection" -> Res.string.extension_warning_reflection
    "classloader-access" -> Res.string.extension_warning_classloader_access
    "filesystem" -> Res.string.extension_warning_filesystem
    "raw-socket" -> Res.string.extension_warning_raw_socket
    "dynamic-script" -> Res.string.extension_warning_dynamic_script
    "webview" -> Res.string.extension_warning_webview
    "environment" -> Res.string.extension_warning_environment
    "exit" -> Res.string.extension_warning_exit
    "suspicious-string" -> Res.string.extension_warning_suspicious_string
    "telemetry" -> Res.string.extension_warning_telemetry
    else -> Res.string.extension_warning_other
}

/** The words for a status. A failed start's raw reason is shown apart from them, as untrusted text. */
internal fun statusWords(status: ReviewStatus): StringResource = when (status) {
    ReviewStatus.Held -> Res.string.extension_status_held
    ReviewStatus.KeptOff -> Res.string.extension_status_kept_off
    ReviewStatus.Allowed -> Res.string.extension_status_allowed
    ReviewStatus.Starting -> Res.string.loading
    is ReviewStatus.StartFailed -> Res.string.extension_status_start_failed
    ReviewStatus.Blocked -> Res.string.extension_status_blocked
    ReviewStatus.HashMismatch -> Res.string.extension_status_hash_mismatch
}

/** How many are waiting, on the screens the person is actually looking at. Words here for that reason. */
@Composable
internal fun ExtensionsNotice(state: ExtensionReviewState, onOpen: () -> Unit, quiet: @Composable (String, () -> Unit) -> Unit) {
    when {
        state.waiting > 0 -> DesktopButton(
            // UiString: Compose's own formatting leaves a plain `%d` in the text.
            label = UiString.Resource(Res.string.extensions_waiting_count, listOf(state.waiting)).resolve(),
            onClick = onOpen,
            icon = CloudStreamIcons.Warning,
        )
        // Nothing to ask, and still the way to change your mind.
        state.reviews.isNotEmpty() -> quiet(stringResource(Res.string.extension_review_title), onOpen)
    }
}

/**
 * Every extension the scan had something to say about, and the two choices about each.
 *
 * **Neither choice is the primary button.** The default is already off, and this screen has no business nudging
 * a decision about somebody else's code in either direction. A plugin's own text goes in text of its own: never
 * into a format string, and never as markup.
 */
@Composable
internal fun ExtensionReviewScreen(
    state: ExtensionReviewState,
    onAction: (ReviewAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    Box(modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(
            verticalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap),
            modifier = Modifier
                .widthIn(max = DesktopDimens.ReadingWidth + DesktopDimens.WindowMargin + DesktopDimens.WindowMargin)
                .fillMaxWidth()
                .padding(DesktopDimens.WindowMargin),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap),
            ) {
                DesktopIconButton(CloudStreamIcons.Back, stringResource(Res.string.go_back), onBack)
                BasicText(
                    text = stringResource(Res.string.extension_review_title),
                    style = typography.railTitle.copy(color = colors.ink),
                )
            }
            state.notSaved?.let { name ->
                Notice(stringResource(Res.string.extension_decision_not_saved), name)
            }
            if (state.decisionsUnreadable) Notice(stringResource(Res.string.extension_decisions_unreadable), null)
            if (state.reviews.isEmpty()) {
                BasicText(
                    text = stringResource(Res.string.extension_review_empty),
                    style = typography.label.copy(color = colors.ink),
                )
            } else {
                BasicText(
                    text = stringResource(Res.string.extension_review_intro),
                    style = typography.body.copy(color = colors.ink2),
                )
                // Drawn on the row and not between rows, so no row is pushed off the step by it.
                val ruled = Modifier.hairline(HairlineSide.Top, colors.line).padding(top = DesktopDimens.GroupGap)
                state.reviews.forEach { review -> ReviewRow(review, onAction, ruled) }
                BasicText(
                    text = stringResource(Res.string.extension_review_file_note),
                    style = typography.caption.copy(color = colors.muted),
                    modifier = ruled.fillMaxWidth(),
                )
            }
            BasicText(
                text = stringResource(Res.string.extension_review_clean_note),
                style = typography.caption.copy(color = colors.muted),
            )
        }
    }
}

/** Something went wrong with the choices themselves. Stays on the page, because nothing else will say so. */
@Composable
private fun Notice(words: String, untrusted: String?) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    Row(horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap)) {
        Box(Modifier.height(typography.body.lineHeightDp()), contentAlignment = Alignment.Center) {
            CloudStreamIcon(CloudStreamIcons.Warning, null, size = CloudStreamIconSize.Small, tint = colors.ink)
        }
        Column {
            BasicText(text = words, style = typography.body.copy(color = colors.ink))
            if (untrusted != null) UntrustedLine(untrusted)
        }
    }
}

@Composable
private fun ReviewRow(review: ExtensionReview, onAction: (ReviewAction) -> Unit, modifier: Modifier) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap),
        ) {
            Column(Modifier.weight(1f)) {
                BasicText(
                    text = review.displayName,
                    style = typography.label.copy(color = colors.ink, textDirection = TextDirection.Ltr),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BasicText(
                    text = stringResource(statusWords(review.status)),
                    style = typography.caption.copy(color = colors.muted),
                )
            }
            Choices(review, onAction)
        }
        (review.status as? ReviewStatus.StartFailed)?.let { failed -> UntrustedLine(failed.detail, maxLines = 3) }
        review.findings.forEach { finding ->
            FindingRow(finding, sentence = review.status != ReviewStatus.Blocked)
        }
    }
}

@Composable
private fun Choices(review: ExtensionReview, onAction: (ReviewAction) -> Unit) {
    val allow = review.status in setOf(ReviewStatus.Held, ReviewStatus.KeptOff) || review.status is ReviewStatus.StartFailed
    val keepOff = review.status in setOf(ReviewStatus.Held, ReviewStatus.Allowed) || review.status is ReviewStatus.StartFailed
    Row(horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap)) {
        if (keepOff) {
            DesktopButton(stringResource(Res.string.extension_keep_off), { onAction(ReviewAction.KeepOff(review.fileName)) })
        }
        if (allow) {
            DesktopButton(stringResource(Res.string.extension_allow), { onAction(ReviewAction.Allow(review.fileName)) })
        }
    }
}

/** @param sentence false for a blocked extension: nobody is being asked anything, so the raw reason is enough. */
@Composable
private fun FindingRow(finding: ReviewFinding, sentence: Boolean) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    Row(horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap)) {
        Box(Modifier.height(typography.body.lineHeightDp()), contentAlignment = Alignment.Center) {
            CloudStreamIcon(CloudStreamIcons.Warning, null, size = CloudStreamIconSize.Small, tint = colors.muted)
        }
        Column(Modifier.weight(1f)) {
            if (sentence) {
                BasicText(
                    text = stringResource(warningSentence(finding.rule)),
                    style = typography.body.copy(color = colors.ink2),
                )
            }
            finding.details.forEach { detail -> UntrustedLine(detail) }
            if (finding.hidden > 0) UntrustedLine("+${finding.hidden}")
        }
    }
}

/** What a plugin wrote or caused, cleaned already: small, muted, and in no danger of reading as the app's voice. */
@Composable
private fun UntrustedLine(text: String, maxLines: Int = 1) {
    BasicText(
        text = text,
        // Class names and file names read left to right in every language, and a paragraph that runs the other
        // way moves their punctuation to the wrong end.
        style = CloudStreamTheme.typography.captionMono.copy(
            color = CloudStreamTheme.colors.muted,
            textDirection = TextDirection.Ltr,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun TextStyle.lineHeightDp() = with(LocalDensity.current) { lineHeight.toDp() }

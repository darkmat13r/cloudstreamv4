package com.lagradost.cloudstream4.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream4.feature.account.desktop.PairCodeSheet
import com.lagradost.cloudstream4.feature.account.desktop.PasswordSignInSheet
import com.lagradost.cloudstream4.feature.account.desktop.RemoveSignInSheet
import com.lagradost.cloudstream4.feature.account.desktop.SignInFailedSheet
import com.lagradost.cloudstream4.feature.account.desktop.SyncRowState
import com.lagradost.cloudstream4.feature.account.desktop.SyncServicesSheet
import com.lagradost.cloudstream4.feature.account.desktop.SyncStatus
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.awt.Desktop
import java.net.URI

/** Where the sync sheets are (`KMP-95d`). Every step but [List] goes back to [List] on Cancel, focused on its row. */
internal sealed interface SyncStep {
    /** A9. [focusOn] is the row a sign-in came back from (A12). */
    data class List(val focusOn: DesktopService? = null) : SyncStep

    /** A11, A11b: Simkl's code. A new [attempt] is a new code. */
    data class Pair(val attempt: Int = 0) : SyncStep

    /** A16, A16b. [email] is kept after a refusal, the password never. */
    data class Password(
        val service: DesktopService,
        val attempt: Int = 0,
        val mismatch: Boolean = false,
        val email: String = "",
    ) : SyncStep {
        /** Never the email: a state logged by accident must not carry it. */
        override fun toString(): String = "Password(service=$service, attempt=$attempt, mismatch=$mismatch)"
    }

    /** A13, A13b. */
    data class Failed(val service: DesktopService, val offline: Boolean) : SyncStep

    /** A14. */
    data class Remove(val service: DesktopService) : SyncStep

    /** The step Cancel goes back to. */
    fun back(): SyncStep? = when (this) {
        is List -> null
        is Pair -> List(DesktopService.Simkl)
        is Password -> List(service)
        is Failed -> List(service)
        is Remove -> List(service)
    }
}

/**
 * The sync sheets for [profile] (`KMP-95d`; `DS-25` A9, A11 to A14, A16): sign-ins to Simkl with a code and to the two
 * subtitle services with a password, held in [store] for this profile only.
 *
 * - **Every way out of a sign-in ends it at once** (A12, `BUG-49`): leaving a step leaves its composition, which cancels
 *   the poll or the request in flight. An answer that comes after is never stored: the store is written only from a
 *   coroutine still running, checked just before.
 * - Nothing about a sign-in is logged or written to a file here: the store is the only place one goes.
 *
 * @param tick waits between Simkl's countdown seconds; a test passes one on a fake clock.
 * @param openUrl opens Simkl's page in the browser. Only an https address on simkl.com reaches it.
 */
@Composable
internal fun SyncSheets(
    step: SyncStep,
    onStep: (SyncStep?) -> Unit,
    profile: Int,
    profileName: String,
    store: SignInStore,
    client: SignInClient,
    tick: suspend (Long) -> Unit = { delay(it) },
    openUrl: (String) -> Unit = ::browse,
) {
    when (step) {
        is SyncStep.List -> {
            val row = { service: DesktopService ->
                val status = when {
                    store.get(profile, service) != null -> SyncStatus.Connected
                    service == DesktopService.Simkl && !client.simklAvailable -> SyncStatus.Unavailable
                    else -> SyncStatus.NotConnected
                }
                SyncRowState(
                    id = service.name,
                    name = service.displayName,
                    status = status,
                    onSignIn = {
                        onStep(if (service.kind == SignInKind.Code) SyncStep.Pair() else SyncStep.Password(service))
                    },
                    onRemove = { onStep(SyncStep.Remove(service)) },
                )
            }
            SyncServicesSheet(
                profileName = profileName,
                syncRows = DesktopService.entries.filter { !it.subtitles }.map(row),
                subtitleRows = DesktopService.entries.filter { it.subtitles }.map(row),
                onDone = { onStep(null) },
                focusOn = step.focusOn?.name,
            )
        }
        is SyncStep.Pair -> PairStep(step, onStep, profile, store, client, tick, openUrl)
        is SyncStep.Password -> PasswordStep(step, onStep, profile, store, client)
        is SyncStep.Failed -> SignInFailedSheet(
            serviceName = step.service.displayName,
            offline = step.offline,
            // A13: Try again starts the sign-in afresh.
            onRetry = {
                onStep(if (step.service.kind == SignInKind.Code) SyncStep.Pair() else SyncStep.Password(step.service))
            },
            onCancel = { onStep(step.back()) },
        )
        is SyncStep.Remove -> RemoveSignInSheet(
            profileName = profileName,
            serviceName = step.service.displayName,
            onRemove = {
                store.remove(profile, step.service)
                onStep(SyncStep.List(step.service))
            },
            onCancel = { onStep(step.back()) },
        )
    }
}

private sealed interface PairState {
    data object Asking : PairState
    class Showing(val code: PairCode, val secondsLeft: Int) : PairState
    class Expired(val code: PairCode) : PairState
}

@Composable
private fun PairStep(
    step: SyncStep.Pair,
    onStep: (SyncStep?) -> Unit,
    profile: Int,
    store: SignInStore,
    client: SignInClient,
    tick: suspend (Long) -> Unit,
    openUrl: (String) -> Unit,
) {
    var state by remember(step.attempt) { mutableStateOf<PairState>(PairState.Asking) }
    LaunchedEffect(step.attempt) {
        val code = when (val answer = client.simklCode()) {
            is SignInResult.Done -> answer.value
            SignInResult.Offline -> return@LaunchedEffect onStep(SyncStep.Failed(DesktopService.Simkl, offline = true))
            else -> return@LaunchedEffect onStep(SyncStep.Failed(DesktopService.Simkl, offline = false))
        }
        var left = code.expiresInSeconds
        var sincePoll = 0
        state = PairState.Showing(code, left)
        while (left > 0) {
            tick(MILLIS_PER_SECOND)
            left--
            sincePoll++
            state = PairState.Showing(code, left)
            if (sincePoll < code.intervalSeconds) continue
            sincePoll = 0
            when (val answer = client.simklPoll(code)) {
                is SignInResult.Done -> {
                    // Cancel leaves this composition and cancels this coroutine: a late answer stops here.
                    currentCoroutineContext().ensureActive()
                    store.put(profile, DesktopService.Simkl, answer.value)
                    return@LaunchedEffect onStep(SyncStep.List(DesktopService.Simkl))
                }
                SignInResult.Pending -> Unit
                SignInResult.Offline -> return@LaunchedEffect onStep(SyncStep.Failed(DesktopService.Simkl, offline = true))
                SignInResult.Refused, SignInResult.Failed ->
                    return@LaunchedEffect onStep(SyncStep.Failed(DesktopService.Simkl, offline = false))
            }
        }
        // A11b: nothing polls once the code has expired.
        state = PairState.Expired(code)
    }
    val shown = state
    val code = when (shown) {
        PairState.Asking -> null
        is PairState.Showing -> shown.code
        is PairState.Expired -> shown.code
    }
    val url = code?.verificationUrl ?: HttpSignInClient.SIMKL_PIN_PAGE
    val qr = remember(code?.verificationUrl) { code?.let { pairQr(it.verificationUrl) } }
    PairCodeSheet(
        serviceName = DesktopService.Simkl.displayName,
        code = code?.userCode.orEmpty(),
        address = url.removePrefix("https://").removeSuffix("/"),
        secondsLeft = (shown as? PairState.Showing)?.secondsLeft,
        expired = shown is PairState.Expired,
        onOpen = { openUrl(url) },
        onNewCode = { onStep(SyncStep.Pair(step.attempt + 1)) },
        onCancel = { onStep(step.back()) },
        qr = qr,
    )
}

@Composable
private fun PasswordStep(
    step: SyncStep.Password,
    onStep: (SyncStep?) -> Unit,
    profile: Int,
    store: SignInStore,
    client: SignInClient,
) {
    // One sign-in at a time; a new number starts it. The two fields go to it and nowhere else.
    var submitted by remember(step) { mutableStateOf<Pair<String, String>?>(null) }
    var submissions by remember(step) { mutableIntStateOf(0) }
    val sent = submitted
    if (sent != null) {
        LaunchedEffect(submissions) {
            val (email, password) = sent
            val next = when (val answer = client.passwordSignIn(step.service, email, password)) {
                is SignInResult.Done -> {
                    currentCoroutineContext().ensureActive()
                    store.put(profile, step.service, answer.value)
                    SyncStep.List(step.service)
                }
                SignInResult.Refused, SignInResult.Pending ->
                    SyncStep.Password(step.service, attempt = step.attempt + 1, mismatch = true, email = email)
                SignInResult.Offline -> SyncStep.Failed(step.service, offline = true)
                SignInResult.Failed -> SyncStep.Failed(step.service, offline = false)
            }
            submitted = null
            onStep(next)
        }
    }
    PasswordSignInSheet(
        serviceName = step.service.displayName,
        mismatch = step.mismatch,
        busy = sent != null,
        attempt = step.attempt,
        email = step.email,
        onSignIn = { email, password ->
            submitted = email to password
            submissions++
        },
        onCancel = { onStep(step.back()) },
    )
}

/** Opens [url] in the person's browser. Nothing is logged if it cannot. */
private fun browse(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}

private const val MILLIS_PER_SECOND = 1_000L

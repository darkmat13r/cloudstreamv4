package com.lagradost.cloudstream4.account

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AuthRepo
import com.lagradost.cloudstream3.syncproviders.CredentialStore
import com.lagradost.cloudstream3.syncproviders.DevicePinPoll
import com.lagradost.cloudstream3.ui.account.AccountHelper.showAccountEditDialog
import com.lagradost.cloudstream3.ui.account.AccountViewModel
import com.lagradost.cloudstream3.ui.account.ProfileSignIns
import com.lagradost.cloudstream3.ui.settings.SettingsAccount
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.account.AccountAction
import com.lagradost.cloudstream4.feature.account.AccountSelectState
import com.lagradost.cloudstream4.feature.account.Pairing
import com.lagradost.cloudstream4.feature.account.ProfileCard
import com.lagradost.cloudstream4.feature.account.SignIn
import com.lagradost.cloudstream4.feature.account.SyncService
import com.lagradost.cloudstream4.feature.account.tv.AccountSelectScreenTv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import qrcode.QRCode

/**
 * **The switch.** `false` keeps the old account grid on Android TV. Phone and the emulator layout never get the Compose
 * screen whatever this says.
 */
object TvAccountSwitch {
    @JvmField
    var useComposeAccountSelect: Boolean = true
}

/**
 * `KMP-73` (`Q16`): artboard 8d hosted in `AccountSelectActivity` on TV, over the same [AccountViewModel] the old grid
 * used, so selecting, the PIN check and the hand-off to `MainActivity` are unchanged.
 *
 * What 8d does not draw stays the old dialogs: the profile editor (add, rename, picture, PIN, delete), the PIN prompt,
 * Kitsu's sign-in form and a browser sign-in.
 *
 * **Security.** Sign-in state is read by key presence through [CredentialStore], never parsing a token; the pairing
 * code is shown on screen and never logged; a pairing ends on every way out (`BUG-49`'s [DevicePinPoll]).
 */
internal class AccountSelectTv(
    private val activity: FragmentActivity,
    private val viewModel: AccountViewModel,
    /** Opened from `MainActivity` to manage profiles: Done goes back there, as the old grid's close button did. */
    private val onManageDone: (() -> Unit)? = null,
) {
    private val state = MutableStateFlow(AccountSelectState(profiles = emptyList(), services = emptyList()))
    private var poll: DevicePinPoll? = null

    /** The sync services 8d lists, in its order. */
    private val repos: List<AuthRepo>
        get() = listOf(AccountManager.simklApi, AccountManager.malApi, AccountManager.aniListApi, AccountManager.kitsuApi)
            .mapNotNull { api -> AccountManager.syncApis.firstOrNull { it.idPrefix == api.idPrefix } }

    fun attach() {
        activity.setContent {
            CloudStreamTheme {
                val current by state.collectAsState()
                AccountSelectScreenTv(current, ::onAction, avatar = { Picture(it) })
            }
        }
        activity.observe(viewModel.accounts) { refresh() }
        // SidePanelHost handles BACK as a key, which API 36 sends to the back dispatcher instead; without this BACK on the
        // pairing panel would close the whole screen. Added again on each open, so it is asked before the activity's own.
        val closesPairing = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = endPairing()
        }
        activity.lifecycleScope.launch {
            state.map { it.pairing != null }.distinctUntilChanged().collect { open ->
                closesPairing.remove()
                closesPairing.isEnabled = open
                if (open) activity.onBackPressedDispatcher.addCallback(activity, closesPairing)
            }
        }
        activity.observe(viewModel.isEditing) { editing -> state.update { it.copy(managing = editing) } }
    }

    private fun refresh() {
        val profiles = DataStoreHelper.getAccounts(activity)
        val selected = DataStoreHelper.selectedKeyIndex
        state.update { old ->
            old.copy(
                profiles = profiles.map { account ->
                    ProfileCard(
                        id = account.keyIndex,
                        name = account.name,
                        current = account.keyIndex == selected,
                        locked = account.lockPin != null,
                        syncedWith = repos.filter { isSignedIn(it, account.keyIndex) }.map { it.name },
                    )
                },
                services = repos.map { repo ->
                    SyncService(repo.idPrefix, repo.name, connected = isSignedIn(repo, selected), signIn = signInFor(repo))
                },
            )
        }
    }

    /** A profile's picture when it is one of the bundled ones; a picture from an address shows its initials. */
    @androidx.compose.runtime.Composable
    private fun Picture(card: ProfileCard) {
        val account = DataStoreHelper.getAccounts(activity).firstOrNull { it.keyIndex == card.id } ?: return
        if (account.customImage != null) return
        val drawable = DataStoreHelper.profileImages.getOrNull(account.defaultImageIndex) ?: return
        Image(painterResource(drawable), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }

    private fun signInFor(repo: AuthRepo): SignIn = when {
        repo.hasPin -> SignIn.PairWithCode
        repo.hasInApp -> SignIn.InApp
        repo.hasOAuth2 && hasBrowser() -> SignIn.Browser
        else -> SignIn.Unavailable
    }

    /** Whether anything can open a web page. A TV without a browser cannot finish an OAuth redirect. */
    private fun hasBrowser(): Boolean =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).resolveActivity(activity.packageManager) != null

    private fun onAction(action: AccountAction) {
        when (action) {
            is AccountAction.ProfilePressed -> {
                val account = DataStoreHelper.getAccounts(activity).firstOrNull { it.keyIndex == action.id } ?: return
                if (state.value.managing) {
                    showAccountEditDialog(
                        activity, account, isNewAccount = false,
                        accountEditCallback = { viewModel.handleAccountUpdate(it, activity) },
                        accountDeleteCallback = { viewModel.handleAccountDelete(it, activity) },
                    )
                } else {
                    viewModel.handleAccountSelect(account, activity)
                }
            }

            AccountAction.AddProfile -> addProfile()
            AccountAction.ToggleManage ->
                if (state.value.managing && onManageDone != null) onManageDone.invoke() else viewModel.toggleIsEditing()
            is AccountAction.ServicePressed -> {
                val repo = repos.firstOrNull { it.idPrefix == action.id } ?: return
                val service = state.value.services.firstOrNull { it.id == action.id } ?: return
                when {
                    service.connected -> confirmRemove(repo)
                    service.signIn == SignIn.PairWithCode -> startPairing(repo)
                    service.signIn == SignIn.InApp -> SettingsAccount.showAppLogin(activity, repo)
                    service.signIn == SignIn.Browser -> repo.openOAuth2PageWithToast()
                    else -> Unit
                }
            }

            AccountAction.NewCode -> repos.firstOrNull { it.hasPin }?.let { startPairing(it) }
            AccountAction.CancelPairing -> endPairing()
        }
    }

    /** As the old grid's add card: the next free index (`BUG-48`), an unused bundled picture, the default name. */
    private fun addProfile() {
        val accounts = DataStoreHelper.getAccounts(activity)
        val used = accounts.filter { it.customImage == null }.map { it.defaultImageIndex }.toSet()
        val image = DataStoreHelper.profileImages.indices.firstOrNull { it !in used } ?: 0
        showAccountEditDialog(
            activity,
            DataStoreHelper.Account(
                keyIndex = ProfileSignIns.nextKeyIndex(accounts),
                name = activity.getString(R.string.account),
                defaultImageIndex = image,
            ),
            isNewAccount = true,
            accountEditCallback = { viewModel.handleAccountUpdate(it, activity) },
            accountDeleteCallback = { viewModel.handleAccountDelete(it, activity) },
        )
    }

    /** 8d draws "Disconnect…": one OK on a focused row must not clear a sign-in without asking. */
    private fun confirmRemove(repo: AuthRepo) {
        AlertDialog.Builder(activity, R.style.AlertDialogCustom)
            .setTitle(repo.name)
            .setMessage(R.string.account_sync_remove)
            .setPositiveButton(R.string.account_sync_remove) { _, _ ->
                activity.lifecycleScope.launch {
                    runCatching { withContext(Dispatchers.IO) { repo.authUser()?.let { repo.logout(it) } } }
                        .onFailure { logError(it) }
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startPairing(repo: AuthRepo) {
        endPairing()
        activity.lifecycleScope.launch {
            val pin = withContext(Dispatchers.IO) { runCatching { repo.pinRequest() }.onFailure { logError(it) }.getOrNull() }
            if (pin == null) {
                showToast(activity, R.string.device_pin_error_message)
                return@launch
            }
            val qr = withContext(Dispatchers.Default) {
                runCatching {
                    (QRCode.ofSquares().build(pin.verificationUrl).render().nativeImage() as Bitmap).asImageBitmap()
                }.getOrNull()
            }
            state.update {
                it.copy(
                    pairing = Pairing(
                        serviceName = repo.name,
                        verificationUrl = pin.verificationUrl.removePrefix("https://").removePrefix("http://"),
                        userCode = pin.userCode,
                        secondsLeft = pin.expiresIn,
                        qr = qr,
                    ),
                )
            }
            val next = DevicePinPoll(pin, { payload, stillWanted -> repo.login(payload, stillWanted) })
            poll = next
            next.start(
                scope = activity.lifecycleScope,
                ui = Dispatchers.Main,
                io = Dispatchers.IO,
                onTick = { secondsLeft -> state.update { s -> s.copy(pairing = s.pairing?.copy(secondsLeft = secondsLeft)) } },
                onSignedIn = {
                    showToast(activity, activity.getString(R.string.authenticated_user, repo.name))
                    endPairing()
                    refresh()
                },
                onExpired = { state.update { s -> s.copy(pairing = s.pairing?.copy(secondsLeft = 0)) } },
                onError = { logError(it) },
            )
        }
    }

    private fun endPairing() {
        poll?.cancel()
        poll = null
        state.update { it.copy(pairing = null) }
    }

    companion object {
        /**
         * Whether [repo] holds a sign-in for profile [keyIndex], by key presence. A sign-out leaves the key holding an
         * empty array rather than removing it, so an empty array counts as signed out. The value is compared, never
         * parsed or logged.
         */
        internal fun isSignedIn(repo: AuthRepo, keyIndex: Int): Boolean {
            val raw = CredentialStore.readRaw("${AccountManager.ACCOUNT_TOKEN}/${repo.idPrefix}/$keyIndex") ?: return false
            return raw.isNotBlank() && raw.filterNot { it.isWhitespace() } != "[]"
        }
    }
}

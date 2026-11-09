package com.lagradost.cloudstream4.account

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.lagradost.cloudstream4.EscapeHandler
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.account_pin_confirm
import com.lagradost.cloudstream4.core.strings.generated.resources.account_pin_current
import com.lagradost.cloudstream4.core.strings.generated.resources.account_pin_new
import com.lagradost.cloudstream4.core.strings.generated.resources.account_pin_not_saved
import com.lagradost.cloudstream4.core.strings.generated.resources.account_pin_unreadable
import com.lagradost.cloudstream4.core.strings.generated.resources.account_pin_prompt
import com.lagradost.cloudstream4.core.strings.generated.resources.account_pin_set_title
import com.lagradost.cloudstream4.core.strings.generated.resources.account_profile_locked
import com.lagradost.cloudstream4.core.strings.generated.resources.account_profile_not_added
import com.lagradost.cloudstream4.core.strings.generated.resources.default_account
import com.lagradost.cloudstream4.core.strings.generated.resources.enter_current_pin
import com.lagradost.cloudstream4.core.strings.generated.resources.enter_pin_with_name
import com.lagradost.cloudstream4.core.strings.generated.resources.pin_error_incorrect
import com.lagradost.cloudstream4.core.strings.generated.resources.pin_error_wait
import com.lagradost.cloudstream4.core.strings.generated.resources.profile_number
import com.lagradost.cloudstream4.feature.account.ProfileCard
import com.lagradost.cloudstream4.feature.account.desktop.DeleteProfileSheet
import com.lagradost.cloudstream4.feature.account.desktop.PinRow
import com.lagradost.cloudstream4.feature.account.desktop.PinSheet
import com.lagradost.cloudstream4.feature.account.desktop.ProfileButtonDesktop
import com.lagradost.cloudstream4.feature.account.desktop.ProfileEditorSheet
import com.lagradost.cloudstream4.feature.account.desktop.ProfilePickerDesktop
import com.lagradost.cloudstream4.feature.library.LibraryStore
import com.lagradost.cloudstream4.forgetWindowViewModels
import org.jetbrains.compose.resources.stringResource

/** What fills the window. */
private sealed interface ProfilesScreen {
    /** A1: who is watching, at launch. */
    data object Picking : ProfilesScreen

    /** A5: Manage profiles. Done goes back to [returnTo]. */
    data class Managing(val returnTo: ProfilesScreen) : ProfilesScreen

    /** The window, over the current profile's library. */
    data object App : ProfilesScreen
}

/** What is open over it. */
private sealed interface ProfilesSheet {
    /** [pinNotSaved]: a PIN change just failed to be written, and nothing changed (the reviewer of `#286`). */
    data class Edit(val id: Int, val pinNotSaved: Boolean = false) : ProfilesSheet
    /** [notAdded]: an Add just failed, as the PINs file could not be read, and nothing changed (the reviewer of `#286`). */
    data class Add(val notAdded: Boolean = false) : ProfilesSheet
    data class Delete(val id: Int) : ProfilesSheet

    /** A3 to A4b: [id]'s PIN, under `BUG-55`'s limit, before [then]. */
    data class Unlock(val id: Int, val then: AfterUnlock) : ProfilesSheet

    /** A7c: the PIN in use, under the same limit, before [then]. */
    data class CurrentPin(val id: Int, val then: AfterCurrent) : ProfilesSheet

    /** A7, then A7b with [first] typed. A mismatch starts again from A7. */
    data class SetPin(val id: Int, val first: String? = null) : ProfilesSheet {
        /** Never the PIN: a state logged by accident must not carry it. */
        override fun toString(): String = "SetPin(id=$id, confirming=${first != null})"
    }

    /** `KMP-95d`: the current profile's sync sheets, at [step]. */
    data class Sync(val step: SyncStep) : ProfilesSheet
}

private enum class AfterUnlock { Open, Edit }

private enum class AfterCurrent { Change, Remove }

/**
 * Who is watching, around the window (`KMP-95a`, `95b`; `DS-25` A1, A2, A5, A6, A6b, A8).
 *
 * - With two profiles or more the window opens on the picker. After a choice, [app] is the window over that profile's
 *   library, with the profile button for its sidebar's foot.
 * - Choosing another profile drops the window's kept view models first ([forgetWindowViewModels]), then makes [app]
 *   again under the new profile's key: the stack goes back to Home and every view model is made again, so nothing of
 *   the last profile shows.
 * - Manage profiles, from the picker or the sidebar's list, fills the window with A5; Done goes back where it came
 *   from. A card there opens its editor, and Add profile the same sheet for a new one. Delete in the editor asks first
 *   (A8). Deleting the profile watching makes the first profile the one watching, through the same switch.
 * - **A locked profile** (`KMP-95c`) asks its PIN before it opens, from the picker or the sidebar's list, and before
 *   its editor opens from Manage (A5). Its editor's PIN row sets a PIN twice over (A7, A7b), and changes or removes
 *   one only after the PIN in use (A7c). Every prompt for a profile's own PIN is under `BUG-55`'s limit ([LimitedPin]).
 * - **Sync services** (`KMP-95d`), from the sidebar's list, opens the current profile's sign-ins ([SyncSheets]). They
 *   are kept in [DesktopProfiles.signIns], in memory only until `KMP-95f`, and the sheets say they last until you quit.
 * - The window's Escape is Cancel on what is on top: a sheet, then the profile list, then Manage.
 *
 * @param app the window, given the profile's library and the sidebar's foot.
 * @param signInClient the services' network half; a test passes a fake.
 * @param syncTick and [openUrl] are [SyncSheets]'s, for a test.
 * @param redirects the browser's `cloudstreamapp://` redirects, which `main` hands over (`KMP-95e`).
 */
@Composable
internal fun ProfilesHost(
    profiles: DesktopProfiles,
    escape: EscapeHandler,
    signInClient: SignInClient = remember { HttpSignInClient() },
    syncTick: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    openUrl: ((String) -> Unit)? = null,
    redirects: BrowserRedirects = remember { BrowserRedirects() },
    app: @Composable (library: LibraryStore, foot: @Composable () -> Unit) -> Unit,
) {
    var screen by remember { mutableStateOf(if (profiles.picksAtLaunch) ProfilesScreen.Picking else ProfilesScreen.App) }
    var sheet by remember { mutableStateOf<ProfilesSheet?>(null) }
    var listOpen by remember { mutableStateOf(false) }
    val defaultName = stringResource(Res.string.default_account)
    // `Profile %d` is an Android-style pattern, which `stringResource` would leave unfilled; `UiString` fills it.
    val numbered = UiString.Resource(Res.string.profile_number, listOf(profiles.stored.profiles.size + 1)).resolve()
    val cards = profiles.cards(defaultName)
    // Before the profile changes, not only when the old window goes: nothing kept for one profile may be found by the next.
    val switchTo: (Int) -> Unit = { id ->
        if (id != profiles.stored.current) forgetWindowViewModels()
        profiles.choose(id)
    }
    val manageFrom: (ProfilesScreen) -> Unit = { from -> screen = ProfilesScreen.Managing(from) }

    // Read here, so this recomposes when any of them changes and the window's Escape follows the top one.
    val openSheet = sheet
    val open = listOpen
    val current = screen
    // Cancel on a sheet: the PIN steps of an editor go back to it, everything else closes.
    val cancelSheet: () -> Unit = {
        sheet = when (val now = sheet) {
            is ProfilesSheet.SetPin -> ProfilesSheet.Edit(now.id)
            is ProfilesSheet.CurrentPin -> ProfilesSheet.Edit(now.id)
            is ProfilesSheet.Delete -> ProfilesSheet.Edit(now.id)
            is ProfilesSheet.Sync -> now.step.back()?.let { ProfilesSheet.Sync(it) }
            else -> null
        }
    }
    val locked: (Int) -> Boolean = { id -> cards.firstOrNull { it.id == id }?.locked == true }
    SideEffect {
        escape.overlay = when {
            openSheet != null -> cancelSheet
            open -> ({ listOpen = false })
            current is ProfilesScreen.Managing -> ({ screen = current.returnTo })
            else -> null
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (current) {
            ProfilesScreen.Picking -> {
                SideEffect { escape.onEscape = null }
                ProfilePickerDesktop(
                    cards,
                    onChoose = { id ->
                        if (locked(id)) {
                            sheet = ProfilesSheet.Unlock(id, AfterUnlock.Open)
                        } else {
                            switchTo(id)
                            screen = ProfilesScreen.App
                        }
                    },
                    onManage = { manageFrom(ProfilesScreen.Picking) },
                    onAdd = if (profiles.canAdd) ({ sheet = ProfilesSheet.Add() }) else null,
                )
            }
            is ProfilesScreen.Managing -> {
                SideEffect { escape.onEscape = null }
                ProfilePickerDesktop(
                    cards,
                    // A5: a locked profile's PIN before its editor.
                    onChoose = { id -> sheet = if (locked(id)) ProfilesSheet.Unlock(id, AfterUnlock.Edit) else ProfilesSheet.Edit(id) },
                    managing = true,
                    onManage = { screen = current.returnTo },
                    onAdd = if (profiles.canAdd) ({ sheet = ProfilesSheet.Add() }) else null,
                )
            }
            ProfilesScreen.App -> key(profiles.stored.current) {
                val profile = profiles.stored.current
                val library = remember { profiles.libraryFor(profile) }
                app(library) {
                    ProfileButtonDesktop(
                        cards,
                        open = listOpen,
                        onOpenChange = { listOpen = it },
                        onChoose = { id -> if (locked(id)) sheet = ProfilesSheet.Unlock(id, AfterUnlock.Open) else switchTo(id) },
                        onManage = { manageFrom(ProfilesScreen.App) },
                        onSync = { sheet = ProfilesSheet.Sync(SyncStep.List()) },
                    )
                }
            }
        }

        when (openSheet) {
            null -> Unit
            is ProfilesSheet.Sync -> {
                val card = cards.firstOrNull { it.current }
                if (card == null) {
                    SideEffect { sheet = null }
                } else {
                    // Keyed by the profile: a switch can never carry a sign-in in progress to another profile.
                    key(card.id) {
                        val onStep: (SyncStep?) -> Unit = { next -> sheet = next?.let { ProfilesSheet.Sync(it) } }
                        if (openUrl != null) {
                            SyncSheets(openSheet.step, onStep, card.id, card.name, profiles.signIns, signInClient, redirects, syncTick, openUrl)
                        } else {
                            SyncSheets(openSheet.step, onStep, card.id, card.name, profiles.signIns, signInClient, redirects, syncTick)
                        }
                    }
                }
            }
            is ProfilesSheet.Add -> ProfileEditorSheet(
                name = numbered,
                adding = true,
                onSave = { name ->
                    // Add is offered only while there is room, so null here is a PIN that could not be forgotten.
                    sheet = if (profiles.add(name, fallback = numbered) == null) ProfilesSheet.Add(notAdded = true) else null
                },
                onCancel = { sheet = null },
                onDelete = null,
                pinMessage = if (openSheet.notAdded) stringResource(Res.string.account_profile_not_added) else null,
            )
            is ProfilesSheet.Edit -> {
                val card = cards.firstOrNull { it.id == openSheet.id }
                if (card == null) {
                    SideEffect { sheet = null }
                } else {
                    ProfileEditorSheet(
                        name = card.name,
                        adding = false,
                        onSave = { name ->
                            profiles.rename(card.id, name)
                            sheet = null
                        },
                        onCancel = { sheet = null },
                        // The first profile is never deleted (A6c): it is the way back in.
                        onDelete = if (profiles.canDelete(card.id)) ({ sheet = ProfilesSheet.Delete(card.id) }) else null,
                        pinMessage = if (openSheet.pinNotSaved) stringResource(Res.string.account_pin_not_saved) else null,
                        pin = when {
                            card.id == StoredProfiles.FIRST -> PinRow.FirstProfile
                            card.locked -> PinRow.On(
                                onChange = { sheet = ProfilesSheet.CurrentPin(card.id, AfterCurrent.Change) },
                                onRemove = { sheet = ProfilesSheet.CurrentPin(card.id, AfterCurrent.Remove) },
                            )
                            else -> PinRow.Off(onSet = { sheet = ProfilesSheet.SetPin(card.id) })
                        },
                    )
                }
            }
            is ProfilesSheet.Delete -> {
                val card = cards.firstOrNull { it.id == openSheet.id }
                if (card == null) {
                    SideEffect { sheet = null }
                } else {
                    DeleteProfileSheet(
                        name = card.name,
                        onDelete = {
                            if (card.id == profiles.stored.current) forgetWindowViewModels()
                            profiles.delete(card.id)
                            sheet = null
                        },
                        onCancel = { sheet = ProfilesSheet.Edit(card.id) },
                    )
                }
            }
            is ProfilesSheet.Unlock -> {
                val card = cards.firstOrNull { it.id == openSheet.id }
                if (card == null) {
                    SideEffect { sheet = null }
                } else {
                    LimitedPin(
                        profiles = profiles,
                        card = card,
                        title = UiString.Resource(Res.string.enter_pin_with_name, listOf(card.name)).resolve(),
                        subtitle = stringResource(Res.string.account_profile_locked),
                        prompt = stringResource(Res.string.account_pin_prompt),
                        onRight = {
                            when (openSheet.then) {
                                AfterUnlock.Open -> {
                                    sheet = null
                                    switchTo(card.id)
                                    screen = ProfilesScreen.App
                                }
                                AfterUnlock.Edit -> sheet = ProfilesSheet.Edit(card.id)
                            }
                        },
                        onCancel = cancelSheet,
                    )
                }
            }
            is ProfilesSheet.CurrentPin -> {
                val card = cards.firstOrNull { it.id == openSheet.id }
                if (card == null) {
                    SideEffect { sheet = null }
                } else {
                    LimitedPin(
                        profiles = profiles,
                        card = card,
                        title = stringResource(Res.string.enter_current_pin),
                        subtitle = card.name,
                        prompt = stringResource(Res.string.account_pin_current),
                        onRight = {
                            sheet = when (openSheet.then) {
                                AfterCurrent.Change -> ProfilesSheet.SetPin(card.id)
                                AfterCurrent.Remove -> ProfilesSheet.Edit(card.id, pinNotSaved = !profiles.removePin(card.id))
                            }
                        },
                        onCancel = cancelSheet,
                    )
                }
            }
            is ProfilesSheet.SetPin -> {
                val card = cards.firstOrNull { it.id == openSheet.id }
                if (card == null) {
                    SideEffect { sheet = null }
                } else {
                    // A new PIN is not under the limit: there is nothing to guess yet.
                    PinSheet(
                        name = card.name,
                        title = stringResource(Res.string.account_pin_set_title),
                        subtitle = card.name,
                        prompt = stringResource(if (openSheet.first == null) Res.string.account_pin_new else Res.string.account_pin_confirm),
                        message = null,
                        enabled = true,
                        attempt = if (openSheet.first == null) 0 else 1,
                        onComplete = { pin ->
                            val first = openSheet.first
                            sheet = when {
                                first == null -> ProfilesSheet.SetPin(card.id, pin)
                                first == pin -> ProfilesSheet.Edit(card.id, pinNotSaved = !profiles.setPin(card.id, pin))
                                // A7: a mismatch clears both and starts over.
                                else -> ProfilesSheet.SetPin(card.id)
                            }
                        },
                        onCancel = cancelSheet,
                    )
                }
            }
        }
    }
}

/**
 * A prompt for [card]'s own PIN under `BUG-55`'s limit (A3, A3b, A4, A4b, A7c). A wrong PIN clears the cells and says
 * so, with no count of tries left (A3b). While a wait runs the cells are off, nothing typed reaches them, and the line
 * counts down each frame from the boot clock; Cancel stays live (A4, A4b).
 */
@Composable
private fun LimitedPin(
    profiles: DesktopProfiles,
    card: ProfileCard,
    title: String,
    subtitle: String,
    prompt: String,
    onRight: () -> Unit,
    onCancel: () -> Unit,
) {
    var attempt by remember(card.id) { mutableIntStateOf(0) }
    var wrong by remember(card.id) { mutableStateOf(false) }
    var unreadable by remember(card.id) { mutableStateOf(false) }
    var waitMs by remember(card.id) { mutableLongStateOf(profiles.pins.waitMs(card.id)) }
    if (waitMs > 0L) {
        LaunchedEffect(card.id) {
            while (waitMs > 0L) {
                withFrameMillis { }
                waitMs = profiles.pins.waitMs(card.id)
            }
        }
    }
    val message = when {
        waitMs > 0L -> UiString.Resource(Res.string.pin_error_wait, listOf(waitClock(waitMs))).resolve()
        unreadable -> stringResource(Res.string.account_pin_unreadable)
        wrong -> stringResource(Res.string.pin_error_incorrect)
        else -> null
    }
    PinSheet(
        name = card.name,
        title = title,
        subtitle = subtitle,
        prompt = prompt,
        message = message,
        enabled = waitMs == 0L,
        attempt = attempt,
        onComplete = { pin ->
            when (val result = profiles.pins.check(card.id, pin)) {
                PinCheck.Right -> onRight()
                is PinCheck.Wrong -> {
                    unreadable = false
                    wrong = true
                    waitMs = result.waitMs
                    attempt++
                }
                is PinCheck.Waiting -> {
                    waitMs = result.waitMs
                    attempt++
                }
                // Nothing compared and nothing counted: the profile stays locked, and the person is told why.
                PinCheck.Unreadable -> {
                    unreadable = true
                    wrong = false
                    attempt++
                }
            }
        },
        onCancel = onCancel,
    )
}

/** A wait as A4 shows it, `00:30` or `59:12`: whole seconds, rounded up, so it never reads 00:00 while it runs. */
internal fun waitClock(waitMs: Long): String {
    val seconds = (waitMs + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND
    val minutes = seconds / SECONDS_PER_MINUTE
    val rest = seconds % SECONDS_PER_MINUTE
    return "${minutes.toString().padStart(2, '0')}:${rest.toString().padStart(2, '0')}"
}

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L

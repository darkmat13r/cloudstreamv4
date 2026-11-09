package com.lagradost.cloudstream4.account

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.lagradost.cloudstream4.EscapeHandler
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.default_account
import com.lagradost.cloudstream4.core.strings.generated.resources.profile_number
import com.lagradost.cloudstream4.feature.account.desktop.DeleteProfileSheet
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
    data class Edit(val id: Int) : ProfilesSheet
    data object Add : ProfilesSheet
    data class Delete(val id: Int) : ProfilesSheet
}

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
 * - The window's Escape closes what is on top first: a sheet, then the profile list, then Manage.
 *
 * @param app the window, given the profile's library and the sidebar's foot.
 */
@Composable
internal fun ProfilesHost(
    profiles: DesktopProfiles,
    escape: EscapeHandler,
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
    SideEffect {
        escape.overlay = when {
            openSheet != null -> ({ sheet = null })
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
                        switchTo(id)
                        screen = ProfilesScreen.App
                    },
                    onManage = { manageFrom(ProfilesScreen.Picking) },
                    onAdd = if (profiles.canAdd) ({ sheet = ProfilesSheet.Add }) else null,
                )
            }
            is ProfilesScreen.Managing -> {
                SideEffect { escape.onEscape = null }
                ProfilePickerDesktop(
                    cards,
                    onChoose = { id -> sheet = ProfilesSheet.Edit(id) },
                    managing = true,
                    onManage = { screen = current.returnTo },
                    onAdd = if (profiles.canAdd) ({ sheet = ProfilesSheet.Add }) else null,
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
                        onChoose = switchTo,
                        onManage = { manageFrom(ProfilesScreen.App) },
                    )
                }
            }
        }

        when (openSheet) {
            null -> Unit
            ProfilesSheet.Add -> ProfileEditorSheet(
                name = numbered,
                adding = true,
                onSave = { name ->
                    profiles.add(name, fallback = numbered)
                    sheet = null
                },
                onCancel = { sheet = null },
                onDelete = null,
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
        }
    }
}

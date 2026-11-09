package com.lagradost.cloudstream4.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream4.EscapeHandler
import com.lagradost.cloudstream4.forgetWindowViewModels
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.default_account
import com.lagradost.cloudstream4.feature.account.desktop.ProfileButtonDesktop
import com.lagradost.cloudstream4.feature.account.desktop.ProfilePickerDesktop
import com.lagradost.cloudstream4.feature.library.LibraryStore
import org.jetbrains.compose.resources.stringResource

/**
 * Who is watching, around the window (`KMP-95a`, `DS-25` A1 and A2).
 *
 * With two profiles or more the window opens on the picker. After a choice, [app] is the window over that profile's
 * library, with the profile button for its sidebar's foot. Choosing another profile drops the window's kept view
 * models first ([forgetWindowViewModels]), then makes [app] again under the new profile's key: the stack goes back to
 * Home and every view model is made again, so nothing of the last profile shows. While the profile list is open, the window's Escape closes it before the screen under it hears.
 *
 * @param app the window, given the profile's library and the sidebar's foot.
 */
@Composable
internal fun ProfilesHost(
    profiles: DesktopProfiles,
    escape: EscapeHandler,
    app: @Composable (library: LibraryStore, foot: @Composable () -> Unit) -> Unit,
) {
    var picking by remember { mutableStateOf(profiles.picksAtLaunch) }
    var listOpen by remember { mutableStateOf(false) }
    // Before the profile changes, not only when the old window goes: nothing kept for one profile may be found by the next.
    val switchTo: (Int) -> Unit = { id ->
        if (id != profiles.stored.current) forgetWindowViewModels()
        profiles.choose(id)
    }
    val cards = profiles.cards(stringResource(Res.string.default_account))
    if (picking) {
        SideEffect { escape.onEscape = null }
        ProfilePickerDesktop(cards, onChoose = { id ->
            switchTo(id)
            picking = false
        })
        return
    }
    // Read here, so this recomposes when the list opens or closes and the window's Escape follows it.
    val open = listOpen
    SideEffect { escape.overlay = if (open) ({ listOpen = false }) else null }
    key(profiles.stored.current) {
        val profile = profiles.stored.current
        val library = remember { profiles.libraryFor(profile) }
        app(library) {
            ProfileButtonDesktop(cards, open = listOpen, onOpenChange = { listOpen = it }, onChoose = switchTo)
        }
    }
}

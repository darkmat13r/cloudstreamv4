package com.lagradost.cloudstream4.feature.account

import androidx.compose.ui.graphics.ImageBitmap

/**
 * A local profile: a separate watch history, not a login (8d's own words).
 *
 * [syncedWith] holds the display names of the services this profile is signed in to, read by key presence only. The
 * screen never sees a token, an account name or an id.
 */
data class ProfileCard(
    val id: Int,
    val name: String,
    val current: Boolean,
    val locked: Boolean,
    val syncedWith: List<String> = emptyList(),
)

/** What the second line of a profile card says. */
sealed interface ProfileLine {
    /** A locked profile says only that it is locked: what it syncs with is its own business until the PIN. */
    data object Locked : ProfileLine

    /** `Current · Kitsu`, `Kitsu, Simkl`, or `Current · No sync`. */
    data class Status(val current: Boolean, val services: List<String>) : ProfileLine
}

fun ProfileCard.line(): ProfileLine = if (locked) ProfileLine.Locked else ProfileLine.Status(current, syncedWith)

/** How a service can be signed in to on this device. */
enum class SignIn {
    /** A code shown here and entered on a phone (Simkl's device code). */
    PairWithCode,

    /** A form on the device itself (Kitsu's email and password). */
    InApp,

    /** Only a web browser's redirect, and the device has a browser. */
    Browser,

    /** Only a web browser's redirect, and the device has none: 8d's honest "Not available here". */
    Unavailable,
}

data class SyncService(
    val id: String,
    val name: String,
    val connected: Boolean,
    val signIn: SignIn,
)

/**
 * A device-code pairing in progress. [userCode] is a short-lived credential: it is shown, because the person has to
 * read it, and is never logged.
 */
data class Pairing(
    val serviceName: String,
    val verificationUrl: String,
    val userCode: String,
    val secondsLeft: Int,
    val qr: ImageBitmap?,
)

data class AccountSelectState(
    val profiles: List<ProfileCard>,
    val services: List<SyncService>,
    val managing: Boolean = false,
    val pairing: Pairing? = null,
)

sealed interface AccountAction {
    data class ProfilePressed(val id: Int) : AccountAction
    data object AddProfile : AccountAction
    data object ToggleManage : AccountAction
    data class ServicePressed(val id: String) : AccountAction
    data object NewCode : AccountAction
    data object CancelPairing : AccountAction
}

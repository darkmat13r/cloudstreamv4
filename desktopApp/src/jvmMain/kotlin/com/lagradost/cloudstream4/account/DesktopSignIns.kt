package com.lagradost.cloudstream4.account

import androidx.compose.runtime.mutableIntStateOf

/** How a service signs in on the Mac (`DS-25`). */
internal enum class SignInKind {
    /** A11: a code shown here and approved in any browser. */
    Code,

    /** A16: an email and password typed here, the owner's exception for the two subtitle services. */
    Password,

    /** A10: the browser, and back through a `cloudstreamapp://` redirect (`KMP-95e`). */
    Browser,
}

/**
 * The services the Mac signs in to, in A9's order. AniList and MyAnimeList sign in through the browser (`KMP-95e`).
 * Kitsu is never on the Mac: it signs in only with a password.
 */
internal enum class DesktopService(val displayName: String, val kind: SignInKind, val subtitles: Boolean) {
    AniList("AniList", SignInKind.Browser, subtitles = false),
    MyAnimeList("MyAnimeList", SignInKind.Browser, subtitles = false),
    Simkl("Simkl", SignInKind.Code, subtitles = false),
    OpenSubtitles("OpenSubtitles", SignInKind.Password, subtitles = true),
    SubDl("SubDL", SignInKind.Password, subtitles = true),
}

/**
 * What a sign-in leaves behind. Every field is a secret: [toString] names none of them, so a sign-in logged by
 * accident carries nothing.
 *
 * - Simkl: [token] only. Its token has no expiry.
 * - SubDL: [token] is the API key its login trades the password for; the password is not kept (`DS-25`).
 * - OpenSubtitles: [token] is a JWT said to last 24 hours, with no refresh token, so [email] and [password] are kept
 *   beside it to sign in again (the owner's ruling, 2026-11-08).
 * - MyAnimeList: [token] and the [refreshToken] it is renewed with, as Android keeps them.
 * - AniList: [token] only. It lasts a year, and AniList has no refresh token.
 */
internal class Credential(
    val token: String,
    val email: String? = null,
    val password: String? = null,
    val refreshToken: String? = null,
) {
    override fun toString(): String = "Credential(<hidden>)"
}

/**
 * Where sign-ins are kept: one [Credential] per profile and service.
 *
 * An unsigned build has [InMemorySignInStore] (`KMP-95d`): sign-ins last until the app quits, and the window says so.
 * A signed build has [KeychainSignInStore] (`KMP-95f`) and nothing else (`AccountStorage.forBuild`).
 *
 * Every change says whether it was kept. **False means nothing changed**: a sign-in that could not be kept is not
 * signed in, and one that could not be removed is still there.
 */
internal interface SignInStore {
    /** Whether a sign-in outlives a quit; when it does not, the window says so (`account_signins_until_quit`). */
    val lastsAfterQuit: Boolean

    fun get(profile: Int, service: DesktopService): Credential?
    fun put(profile: Int, service: DesktopService, credential: Credential): Boolean
    fun remove(profile: Int, service: DesktopService): Boolean

    /** [profile] was deleted, or its id is about to be reused: every sign-in of its goes with it. */
    fun forget(profile: Int): Boolean

    /** Drops the sign-ins of every profile not in [profiles], as the launch sweep does libraries and PINs. */
    fun retainOnly(profiles: Set<Int>): Boolean
}

/**
 * Sign-ins in this process's memory only (D3, the PM, 2026-11-08): nothing is written to a file, a preference or a
 * log, and quitting the app forgets them all. Not thread-safe: the window calls it from its one UI thread.
 */
internal class InMemorySignInStore : SignInStore {
    private val held = HashMap<Pair<Int, DesktopService>, Credential>()

    override val lastsAfterQuit: Boolean get() = false

    /** Bumped on every change, so a screen reading [get] recomposes. */
    private val changes = mutableIntStateOf(0)

    override fun get(profile: Int, service: DesktopService): Credential? {
        changes.intValue
        return held[profile to service]
    }

    override fun put(profile: Int, service: DesktopService, credential: Credential): Boolean {
        held[profile to service] = credential
        changes.intValue++
        return true
    }

    override fun remove(profile: Int, service: DesktopService): Boolean {
        if (held.remove(profile to service) != null) changes.intValue++
        return true
    }

    override fun forget(profile: Int): Boolean {
        if (held.keys.removeAll { it.first == profile }) changes.intValue++
        return true
    }

    override fun retainOnly(profiles: Set<Int>): Boolean {
        if (held.keys.removeAll { it.first !in profiles }) changes.intValue++
        return true
    }

    override fun toString(): String = "InMemorySignInStore(<hidden>)"
}

/** A Simkl code to show (A11). [userCode] is a short-lived credential: shown, because it must be read, never logged. */
internal class PairCode(
    val userCode: String,
    val verificationUrl: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
) {
    override fun toString(): String = "PairCode(expiresInSeconds=$expiresInSeconds, intervalSeconds=$intervalSeconds, userCode=<hidden>)"
}

/** What one call to a service came to. Nothing the service said is carried: the screens show only our own words. */
internal sealed interface SignInResult<out T> {
    data class Done<T>(val value: T) : SignInResult<T>

    /** Not yet: Simkl's code is not approved yet. */
    data object Pending : SignInResult<Nothing>

    /** The service said no: a wrong email or password (A16b), or a code it does not know. */
    data object Refused : SignInResult<Nothing>

    /** No connection (A13b): nothing reached the service. */
    data object Offline : SignInResult<Nothing>

    /** Anything else: an answer that could not be read, a server error (A13). */
    data object Failed : SignInResult<Nothing>
}

/**
 * The network half of a sign-in, apart so the screens are tested over a fake. Every call is HTTPS to the service's
 * own host and logs nothing.
 */
internal interface SignInClient {
    /** Simkl's client id is in this build; without it Simkl is "Not available here". */
    val simklAvailable: Boolean

    /** A11: a new code. */
    suspend fun simklCode(): SignInResult<PairCode>

    /** One poll of [code]: [SignInResult.Pending] until it is approved, then the token. */
    suspend fun simklPoll(code: PairCode): SignInResult<Credential>

    /** A16: [service]'s own login. */
    suspend fun passwordSignIn(service: DesktopService, email: String, password: String): SignInResult<Credential>

    /** MyAnimeList's client id is in this build. */
    val malAvailable: Boolean

    /** A10: MyAnimeList's page to sign in on, carrying [state] and the PKCE [verifier] (`plain`, as MAL allows only). */
    fun malSignInPage(state: String, verifier: String): String?

    /** The code the redirect brought, traded for the token with the same [verifier]. */
    suspend fun malExchange(code: String, verifier: String): SignInResult<Credential>

    /** AniList's client id is in this build. */
    val aniListAvailable: Boolean

    /** A10: AniList's page to sign in on (its implicit grant), carrying [state], which must come back with the token. */
    fun aniListSignInPage(state: String): String?
}

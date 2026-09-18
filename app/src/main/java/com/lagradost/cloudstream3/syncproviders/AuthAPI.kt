package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.splitUrlParameters
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.APP_STRING
import java.security.SecureRandom

data class AuthLoginPage(
    /** The website to open to authenticate */
    val url: String,
    /**
     * State/control code to verify against the redirectUrl to make sure the request is valid.
     * This parameter will be saved, and then used in AuthAPI::login.
     */
    val payload: String? = null,
)

data class AuthPinData(
    val deviceCode: String,
    val userCode: String,
    /** QR Code url */
    val verificationUrl: String,
    /** In seconds */
    val expiresIn: Int,
    /** Check if the code has been verified interval */
    val interval: Int,
)

/** The login field requirements to display to the user */
data class AuthLoginRequirement(
    val password: Boolean = false,
    val username: Boolean = false,
    val email: Boolean = false,
    val server: Boolean = false,
)

/** Stateless Authentication class used for all personalized content */
abstract class AuthAPI {
    open val name: String = "NONE"
    open val idPrefix: String = "NONE"

    /** Drawable icon of the service */
    open val icon: Int? = null

    /** If this service requires an account to use */
    open val requiresLogin: Boolean = true

    /** Link to a website for creating a new account */
    open val createAccountUrl: String? = null

    /** The sensitive redirect URL from OAuth should contain "/redirectUrlIdentifier" to trigger the login */
    open val redirectUrlIdentifier: String? = null

    /** Has OAuth2 login support, including login, loginRequest and refreshToken */
    open val hasOAuth2: Boolean = false

    /** Has on device pin support, aka login with a QR code */
    open val hasPin: Boolean = false

    /** Has in app login support, aka login with a dialog */
    open val hasInApp: Boolean = false

    /** The requirements to login in app */
    open val inAppLoginRequirement: AuthLoginRequirement? = null

    companion object {
        @Deprecated(
            message = "Use APIHolder.unixTime instead",
            replaceWith = ReplaceWith(
                expression = "APIHolder.unixTime",
                imports = ["com.lagradost.cloudstream3.APIHolder"]
            ),
            level = DeprecationLevel.WARNING,
        )
        val unixTime: Long
            get() = APIHolder.unixTime

        @Deprecated(
            message = "Use APIHolder.unixTimeMS instead",
            replaceWith = ReplaceWith(
                expression = "unixTimeMS",
                imports = ["com.lagradost.cloudstream3.APIHolder.unixTimeMS"]
            ),
            level = DeprecationLevel.WARNING,
        )
        val unixTimeMs: Long
            get() = unixTimeMS

        fun splitRedirectUrl(redirectUrl: String): Map<String, String> {
            return splitUrlParameters(
                redirectUrl.replace(APP_STRING, "https").replace("/#", "?")
            )
        }

        fun generateCodeVerifier(): String {
            // It is recommended to use a URL-safe string as code_verifier.
            // See section 4 of RFC 7636 for more details.
            val secureRandom = SecureRandom()
            val codeVerifierBytes = ByteArray(96) // base64 has 6bit per char; (8/6)*96 = 128
            secureRandom.nextBytes(codeVerifierBytes)
            return base64Encode(codeVerifierBytes).trimEnd('=')
                .replace("+", "-").replace("/", "_").replace("\n", "")
        }
    }

    /** Is this url a valid redirect url for this service? */
    @Throws
    open fun isValidRedirectUrl(url: String): Boolean =
        redirectUrlIdentifier != null && url.contains("/$redirectUrlIdentifier")

    /** OAuth2 login from a valid redirectUrl, and payload given in loginRequest */
    @Throws
    open suspend fun login(redirectUrl: String, payload: String?): AuthToken? =
        throw NotImplementedError()

    /** OAuth2 login request, asking the service to provide a url to open in the browser */
    @Throws
    open fun loginRequest(): AuthLoginPage? = throw NotImplementedError()

    /** Pin login request, asking the service to provide an verificationUrl to display with a QR code */
    @Throws
    open suspend fun pinRequest(): AuthPinData? = throw NotImplementedError()

    /** OAuth2 token refresh, this ensures that all token passed to other functions will be valid */
    @Throws
    open suspend fun refreshToken(token: AuthToken): AuthToken? = throw NotImplementedError()

    /** Pin login, this will be called periodically while logging in to check if the pin has been verified by the user */
    @Throws
    open suspend fun login(payload: AuthPinData): AuthToken? = throw NotImplementedError()

    /** In app login */
    @Throws
    open suspend fun login(form: AuthLoginResponse): AuthToken? = throw NotImplementedError()

    /** Get the visible user account */
    @Throws
    open suspend fun user(token: AuthToken?): AuthUser? = throw NotImplementedError()

    /**
     * An optional security measure to make sure that even if an attacker gets ahold of the token, it will be invalid.
     *
     * Note that this will currently only be called *once* on logout,
     * and as such any network issues it will fail silently, and the token will not be revoked.
     */
    @Throws
    open suspend fun invalidateToken(token: AuthToken): Nothing = throw NotImplementedError()

    @Throws
    @Deprecated("Please use the new API for AuthAPI", level = DeprecationLevel.ERROR)
    fun toRepo(): AuthRepo = when (this) {
        is SubtitleAPI -> SubtitleRepo(this)
        is SyncAPI -> SyncRepo(this)
        else -> throw NotImplementedError("Unknown inheritance from AuthAPI")
    }

    @Suppress("DEPRECATION_ERROR")
    @Deprecated("Please use the new API for AuthAPI", level = DeprecationLevel.ERROR)
    fun loginInfo(): LoginInfo? {
        return this.toRepo().authUser()?.let { user ->
            LoginInfo(
                profilePicture = user.profilePicture,
                name = user.name,
                accountIndex = -1,
            )
        }
    }

    @Deprecated("Please use the new API for AuthAPI", level = DeprecationLevel.ERROR)
    suspend fun getPersonalLibrary(): SyncAPI.LibraryMetadata? {
        @Suppress("DEPRECATION_ERROR")
        return (this.toRepo() as? SyncRepo)?.library()?.getOrThrow()
    }

    @Deprecated("Please use the new API for AuthAPI", level = DeprecationLevel.ERROR)
    class LoginInfo(
        val profilePicture: String? = null,
        val name: String?,
        val accountIndex: Int,
    )
}

package com.lagradost.cloudstream4.account

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * The Mac's sign-ins over HTTPS (`KMP-95d`), each the same exchange as the Android provider's:
 * - Simkl: `SimklApi.pinRequest` and `login(AuthPinData)`, the device code;
 * - OpenSubtitles: `OpenSubtitlesApi.login`, a JWT for a username and password;
 * - SubDL: `Subdl.login`, a token for an email and password, traded at once for the API key it keeps.
 *
 * **Nothing is logged**: no interceptor, no request or response printed, and no exception message passed on, since one
 * can carry a URL with a code in it. A failure is only a [SignInResult] kind. Redirects are not followed, so every
 * request reaches only the host named here. Cancelling the coroutine cancels the call in flight, so Cancel ends a
 * sign-in at once (`BUG-49`).
 *
 * @param simklClientId this build's, from `generateDesktopSecrets`; null when the build had none.
 */
internal class HttpSignInClient(
    private val simklClientId: String? = DesktopSecrets.simklClientId,
    private val malClientId: String? = DesktopSecrets.malClientId,
    private val aniListClientId: String? = DesktopSecrets.aniListClientId,
    private val malSite: HttpUrl = MAL_SITE.toHttpUrl(),
    private val http: OkHttpClient = defaultClient(),
    private val simklApi: HttpUrl = SIMKL_API.toHttpUrl(),
    private val openSubtitlesApi: HttpUrl = OPEN_SUBTITLES_API.toHttpUrl(),
    private val subDlApi: HttpUrl = SUBDL_API.toHttpUrl(),
) : SignInClient {

    override val simklAvailable: Boolean get() = simklClientId != null

    override val malAvailable: Boolean get() = malClientId != null

    override val aniListAvailable: Boolean get() = aniListClientId != null

    /**
     * `AniListApi.loginRequest`'s page, the implicit grant (no secret, no PKCE: AniList offers neither to an app that
     * cannot keep one), with a random [state] Android does not send (the owner, 2026-11-09).
     */
    override fun aniListSignInPage(state: String): String? {
        val id = aniListClientId ?: return null
        return ANILIST_AUTHORIZE.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", id)
            .addQueryParameter("response_type", "token")
            .addQueryParameter("state", state)
            .build()
            .toString()
    }

    /** `MALApi.loginRequest`'s page, with a random [state] in place of its counter (the owner, 2026-11-08). */
    override fun malSignInPage(state: String, verifier: String): String? {
        val id = malClientId ?: return null
        return malSite.newBuilder().addPathSegments("v1/oauth2/authorize")
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", id)
            .addQueryParameter("code_challenge", verifier)
            .addQueryParameter("code_challenge_method", "plain")
            .addQueryParameter("state", state)
            .build()
            .toString()
    }

    /** `MALApi.login`'s exchange. */
    override suspend fun malExchange(code: String, verifier: String): SignInResult<Credential> {
        val id = malClientId ?: return SignInResult.Failed
        // The code came in a URL from outside: only a token's characters go on to MAL.
        if (!isToken(code)) return SignInResult.Failed
        val body = okhttp3.FormBody.Builder()
            .add("client_id", id)
            .add("code", code)
            .add("code_verifier", verifier)
            .add("grant_type", "authorization_code")
            .build()
        val request = Request.Builder().url(malSite.newBuilder().addPathSegments("v1/oauth2/token").build()).post(body).build()
        return exchange(request) { status, text ->
            when (status) {
                in 200..299 -> {
                    val answer = json.decodeFromString(MalToken.serializer(), text)
                    val token = answer.accessToken
                    val refresh = answer.refreshToken
                    if (token == null || !isToken(token) || (refresh != null && !isToken(refresh))) SignInResult.Failed
                    else SignInResult.Done(Credential(token = token, refreshToken = refresh))
                }
                400, 401, 403 -> SignInResult.Refused
                else -> SignInResult.Failed
            }
        }
    }

    override suspend fun simklCode(): SignInResult<PairCode> {
        val id = simklClientId ?: return SignInResult.Failed
        val url = simklApi.newBuilder().addPathSegments("oauth/pin")
            .addQueryParameter("client_id", id)
            .addQueryParameter("redirect_uri", SIMKL_REDIRECT)
            .build()
        return exchange(Request.Builder().url(url).get().build()) { code, body ->
            if (code !in 200..299) return@exchange SignInResult.Failed
            val answer = json.decodeFromString(SimklPin.serializer(), body)
            val userCode = answer.userCode?.takeIf { it.matches(USER_CODE) } ?: return@exchange SignInResult.Failed
            SignInResult.Done(
                PairCode(
                    userCode = userCode,
                    verificationUrl = simklVerificationUrl(answer.verificationUrl),
                    expiresInSeconds = (answer.expiresIn ?: DEFAULT_EXPIRES_S).coerceIn(1, MAX_EXPIRES_S),
                    intervalSeconds = (answer.interval ?: DEFAULT_INTERVAL_S).coerceIn(1, MAX_INTERVAL_S),
                )
            )
        }
    }

    override suspend fun simklPoll(code: PairCode): SignInResult<Credential> {
        val id = simklClientId ?: return SignInResult.Failed
        val url = simklApi.newBuilder().addPathSegments("oauth/pin").addPathSegment(code.userCode)
            .addQueryParameter("client_id", id)
            .build()
        return exchange(Request.Builder().url(url).get().build()) { status, body ->
            if (status !in 200..299) return@exchange SignInResult.Failed
            val answer = json.decodeFromString(SimklExchange.serializer(), body)
            // Until the person approves the code, Simkl answers with no token.
            when (val token = answer.accessToken) {
                null, "" -> SignInResult.Pending
                // A token that could break a header later is never stored (the reviewer of #290).
                else -> if (isToken(token)) SignInResult.Done(Credential(token = token)) else SignInResult.Failed
            }
        }
    }

    override suspend fun passwordSignIn(service: DesktopService, email: String, password: String): SignInResult<Credential> =
        when (service) {
            DesktopService.OpenSubtitles -> openSubtitles(email, password)
            DesktopService.SubDl -> subDl(email, password)
            DesktopService.Simkl, DesktopService.MyAnimeList, DesktopService.AniList -> SignInResult.Failed
        }

    private suspend fun openSubtitles(username: String, password: String): SignInResult<Credential> {
        val body = buildJsonObject {
            put("username", username)
            put("password", password)
        }.toString().toRequestBody(JSON)
        val request = Request.Builder()
            .url(openSubtitlesApi.newBuilder().addPathSegment("login").build())
            .header("User-Agent", OPEN_SUBTITLES_AGENT)
            .header("Api-Key", OPEN_SUBTITLES_KEY)
            .post(body)
            .build()
        return exchange(request) { status, text ->
            when (status) {
                in 200..299 -> when (val token = json.decodeFromString(OpenSubtitlesLogin.serializer(), text).token) {
                    null, "" -> SignInResult.Refused
                    else -> if (isToken(token)) SignInResult.Done(Credential(token = token, email = username, password = password)) else SignInResult.Failed
                }
                400, 401, 403 -> SignInResult.Refused
                else -> SignInResult.Failed
            }
        }
    }

    private suspend fun subDl(email: String, password: String): SignInResult<Credential> {
        val body = buildJsonObject {
            put("email", email)
            put("password", password)
        }.toString().toRequestBody(JSON)
        val login = Request.Builder().url(subDlApi.newBuilder().addPathSegment("login").build()).post(body).build()
        val token = when (val result = exchange(login) { status, text ->
            when (status) {
                in 200..299 -> when (val token = json.decodeFromString(SubDlLogin.serializer(), text).token) {
                    null, "" -> SignInResult.Refused
                    // It goes into a header next: a CR, LF or anything else OkHttp refuses would throw outside [exchange].
                    else -> if (isToken(token)) SignInResult.Done(token) else SignInResult.Failed
                }
                400, 401, 403 -> SignInResult.Refused
                else -> SignInResult.Failed
            }
        }) {
            is SignInResult.Done -> result.value
            else -> return result.narrow()
        }
        val keyRequest = Request.Builder()
            .url(subDlApi.newBuilder().addPathSegments("user/userApi").build())
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        // SubDL keeps the API key and the email, never the password (`DS-25`).
        return exchange(keyRequest) { status, text ->
            if (status !in 200..299) return@exchange SignInResult.Failed
            json.decodeFromString(SubDlKey.serializer(), text).apiKey
                ?.takeIf(::isToken)
                ?.let { SignInResult.Done(Credential(token = it, email = email)) }
                ?: SignInResult.Failed
        }
    }

    /**
     * One request, its answer read by [read]. No connection is [SignInResult.Offline]; anything else that goes wrong,
     * an unreadable answer included, is [SignInResult.Failed]. No exception's message goes further.
     */
    private suspend fun <T> exchange(request: Request, read: (status: Int, body: String) -> SignInResult<T>): SignInResult<T> {
        val response = try {
            http.newCall(request).await()
        } catch (e: UnknownHostException) {
            return SignInResult.Offline
        } catch (e: ConnectException) {
            return SignInResult.Offline
        } catch (e: NoRouteToHostException) {
            return SignInResult.Offline
        } catch (e: IOException) {
            return SignInResult.Failed
        }
        return response.use {
            runCatching {
                val source = it.body?.source() ?: return@use SignInResult.Failed
                val bytes = source.apply { request(MAX_BODY_BYTES + 1) }.buffer
                if (bytes.size > MAX_BODY_BYTES) SignInResult.Failed else read(it.code, bytes.readUtf8())
            }.getOrElse { SignInResult.Failed }
        }
    }

    @Serializable
    private data class SimklPin(
        @SerialName("user_code") val userCode: String? = null,
        @SerialName("verification_url") val verificationUrl: String? = null,
        @SerialName("expires_in") val expiresIn: Int? = null,
        @SerialName("interval") val interval: Int? = null,
    )

    @Serializable
    private data class MalToken(
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
    )

    @Serializable
    private data class SimklExchange(@SerialName("access_token") val accessToken: String? = null)

    @Serializable
    private data class OpenSubtitlesLogin(val token: String? = null)

    @Serializable
    private data class SubDlLogin(val token: String? = null)

    @Serializable
    private data class SubDlKey(@SerialName("api_key") val apiKey: String? = null)

    companion object {
        const val SIMKL_API = "https://api.simkl.com/"
        const val OPEN_SUBTITLES_API = "https://api.opensubtitles.com/api/v1/"
        const val SUBDL_API = "https://api.subdl.com/"
        const val MAL_SITE = "https://myanimelist.net/"
        const val ANILIST_AUTHORIZE = "https://anilist.co/api/v2/oauth/authorize"

        /** Where a person approves a Simkl code, when Simkl's own answer names none we trust. */
        const val SIMKL_PIN_PAGE = "https://simkl.com/pin"

        /** `SimklApi`'s, which Simkl's code exchange is registered with. */
        private const val SIMKL_REDIRECT = "cloudstreamapp://simkl"

        /** `OpenSubtitlesApi.API_KEY` and its user agent: the app's own, public in the Android source already. */
        private const val OPEN_SUBTITLES_KEY = "uyBLgFD17MgrYmA0gSXoKllMJBelOYj2"
        private const val OPEN_SUBTITLES_AGENT = "Cloudstream3 v0.2"

        private const val DEFAULT_EXPIRES_S = 900
        private const val MAX_EXPIRES_S = 3_600
        private const val DEFAULT_INTERVAL_S = 5
        private const val MAX_INTERVAL_S = 60
        private const val MAX_BODY_BYTES = 64L * 1024
        private const val TIMEOUT_S = 20L

        /**
         * What a service's token may be before it is kept or put in a header (the reviewer of #290): the characters of
         * a bearer token, so no CR, LF or non-ASCII ever reaches OkHttp, which would throw.
         */
        private val TOKEN = Regex("[A-Za-z0-9._~+/=-]{1,4096}")

        internal fun isToken(value: String): Boolean = TOKEN.matches(value)

        /** A code is shown on screen, so only plain letters and digits get there. */
        private val USER_CODE = Regex("[A-Za-z0-9]{4,16}")
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        /** Simkl's answer is untrusted: only an https address on simkl.com is shown and opened, else its own page. */
        internal fun simklVerificationUrl(answer: String?): String {
            val url = answer?.toHttpUrlOrNull() ?: return SIMKL_PIN_PAGE
            val simkl = url.host == "simkl.com" || url.host.endsWith(".simkl.com")
            return if (url.isHttps && simkl && url.username.isEmpty() && url.password.isEmpty()) url.toString() else SIMKL_PIN_PAGE
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_S * 2, TimeUnit.SECONDS)
            .build()
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T> SignInResult<*>.narrow(): SignInResult<T> = this as SignInResult<T>

/** The call, cancelled when the coroutine is. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }
        }
    )
}

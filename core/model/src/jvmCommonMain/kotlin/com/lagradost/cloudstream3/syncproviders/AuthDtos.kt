package com.lagradost.cloudstream3.syncproviders

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder.unixTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class AuthToken(
    /**
     * This is the general access tokens/api token representing a logged in user.
     * Access tokens are the thing that applications use to make API requests on behalf of a user.
     */
    @JsonProperty("accessToken") @SerialName("accessToken")
    val accessToken: String? = null,
    /** For OAuth a special refresh token is issues to refresh the access token. */
    @JsonProperty("refreshToken") @SerialName("refreshToken")
    val refreshToken: String? = null,
    /** In UnixTime (sec) when it expires */
    @JsonProperty("accessTokenLifetime") @SerialName("accessTokenLifetime")
    val accessTokenLifetime: Long? = null,
    /** In UnixTime (sec) when it expires */
    @JsonProperty("refreshTokenLifetime") @SerialName("refreshTokenLifetime")
    val refreshTokenLifetime: Long? = null,
    /**
     * Sometimes AuthToken needs to be customized to store e.g. username/password,
     * this acts as a catch all to store text or JSON data.
     */
    @JsonProperty("payload") @SerialName("payload")
    val payload: String? = null,
) {
    fun isAccessTokenExpired(marginSec: Long = 10L) =
        accessTokenLifetime != null && unixTime + marginSec >= accessTokenLifetime

    fun isRefreshTokenExpired(marginSec: Long = 10L) =
        refreshTokenLifetime != null && unixTime + marginSec >= refreshTokenLifetime
}

@OptIn(ExperimentalSerializationApi::class) // JsonNames is an experimental annotation for now
@Serializable
data class AuthUser(
    /** Account display-name, can also be email if name does not exist */
    @JsonProperty("name") @SerialName("name")
    val name: String?,
    /**
     * Unique account identifier. If a subsequent login is done then it
     * will be refused if another account with the same id exists.
     */
    @JsonProperty("id") @SerialName("id")
    val id: Int,
    /** Profile picture URL */
    @JsonProperty("profilePicture") @SerialName("profilePicture")
    val profilePicture: String? = null,
    /** Profile picture Headers of the URL */
    @JsonProperty("profilePictureHeaders") @JsonAlias("profilePictureHeader")
    @SerialName("profilePictureHeaders") @JsonNames("profilePictureHeader")
    val profilePictureHeaders: Map<String, String>? = null,
)

/**
 * Stores all information that should be used to authorize access.
 * Be aware that token and user may change independently when a refresh is needed,
 * and as such there should be no strong pairing between the two.
 *
 * Any local set/get key should use user.id.toString(),
 * as token.accessToken (even hashed) is unsecure, and will rotate.
 */
@Serializable
data class AuthData(
    @JsonProperty("user") @SerialName("user") val user: AuthUser,
    @JsonProperty("token") @SerialName("token") val token: AuthToken,
)

/** What the user responds to the AuthLoginRequirement */
@Serializable
data class AuthLoginResponse(
    @JsonProperty("password") @SerialName("password") val password: String?,
    @JsonProperty("username") @SerialName("username") val username: String?,
    @JsonProperty("email") @SerialName("email") val email: String?,
    @JsonProperty("server") @SerialName("server") val server: String?,
)

package com.lagradost.cloudstream3.actions.temp.fcast

import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The one FCast packet that stays in `:app` (B7, plan §5.2).
 *
 * `Opcode` and the seven other message classes moved to `:core:common` `commonMain`. This one cannot
 * follow: `@Serializable` makes the compiler emit `write$Self$app`, whose `$app` suffix is this
 * module's Kotlin module name. It is published — `app/api/classes-jar.api` lists it, and that dump's
 * own KDoc names `write$Self$app` as something it deliberately guards — so moving the class renames a
 * member third-party extensions link against. Moving it needs the same kind of accepted ABI delta as
 * `DEC-08`, not a file move.
 *
 * See https://gitlab.com/futo-org/fcast/-/wikis/Protocol-version-1
 */
@Serializable
data class PlayMessage(
    @JsonProperty("container") @SerialName("container") val container: String,
    @JsonProperty("url") @SerialName("url") val url: String? = null,
    @JsonProperty("content") @SerialName("content") val content: String? = null,
    @JsonProperty("time") @SerialName("time") val time: Double? = null,
    @JsonProperty("speed") @SerialName("speed") val speed: Double? = null,
    @JsonProperty("headers") @SerialName("headers") val headers: Map<String, String>? = null,
)

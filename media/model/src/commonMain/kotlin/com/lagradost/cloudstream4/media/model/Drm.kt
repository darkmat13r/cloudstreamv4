package com.lagradost.cloudstream4.media.model

/**
 * The DRM system a stream is protected with.
 *
 * **This enum does not exist anywhere in the codebase today, and that is the point.** `:library`
 * declares three `kotlin.uuid.Uuid` constants (`CLEARKEY_DRM_UUID`, `WIDEVINE_DRM_UUID`,
 * `PLAYREADY_DRM_UUID`) and `:app` dispatches on them with a `when` over `uuid.toJavaUuid()`
 * (`CS3IPlayer.kt:1282`, `:1304-1305`) that falls through to a logged error. A UUID is the right
 * wire format and the wrong thing to branch on: it cannot be exhaustively matched, so "this engine
 * cannot play this scheme" is a runtime log rather than a compile-time case.
 *
 * The UUIDs stay where they are. `:app` maps between them and this enum at the boundary, which is
 * also where `kotlin.uuid.Uuid` ↔ `java.util.UUID` conversion already happens — `:library`'s
 * public `newDrmExtractorLink`, `setUuid` and `getUuid` all take or return `java.util.UUID`
 * (`ExtractorApi.kt:531`, `:679`, `:684`), which is one reason `:library` itself does not compile
 * for Kotlin/Native today.
 *
 * [UNKNOWN] is not a failure: an extension may legitimately supply a scheme this build has never
 * heard of, and an engine should report it unsupported rather than crash.
 */
enum class DrmScheme {
    CLEAR_KEY,
    WIDEVINE,
    PLAYREADY,
    UNKNOWN,
}

/**
 * What an engine needs in order to decrypt a protected stream.
 *
 * Restates the DRM half of `:library`'s `DrmExtractorLink`. Two shape changes from it, both
 * deliberate:
 *
 * - [scheme] replaces the raw `uuid`, for the reason in [DrmScheme].
 * - [keyRequestParameters] is a `Map`, not the concrete `HashMap` that `DrmExtractorLink` declares.
 *   A contract has no business naming an implementation, and `java.util.HashMap` is not portable.
 */
data class DrmConfiguration(
    val scheme: DrmScheme,
    /** Key id, for schemes that carry the key in the link rather than fetching it. */
    val keyId: String? = null,
    /** The key itself, for ClearKey. */
    val key: String? = null,
    /** JWK key type, where the scheme uses one. */
    val keyType: String? = null,
    val licenseUrl: String? = null,
    val keyRequestParameters: Map<String, String> = emptyMap(),
)

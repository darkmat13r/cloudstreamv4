/**
 * `:core:model` — plain data models shared by the player, subtitle and sync-provider code.
 *
 * ## FQN freeze: these classes are published extension API
 *
 * Every class here keeps the `com.lagradost.cloudstream3.*` fully-qualified name it had in
 * `:app`, nested classes stay nested (`AbstractSubtitleEntities$SubtitleEntity`,
 * `SyncSearchViewModel$SyncSearchResultSearchResponse`), and every file keeps the name that decides
 * its facade. `:app:makeJar` merges this module's `jvm` jar into `app/build/classes.jar`, so a
 * package rename, a nested class pulled to top level or a renamed file breaks every published
 * extension. Move files; never rename them or their package. When you move an extension-facing
 * class here, add a sentinel to `cloudstream.extension-api-jar` in the same change.
 *
 * - `ui/player/IGenerator.kt` must keep that name: it compiles to the facade `IGeneratorKt`, which
 *   extensions read the four `LOADTYPE_*` sets from. `:app`'s old `IGenerator.kt`, which kept
 *   `VideoGenerator` and `NoVideoGenerator`, was renamed to `VideoGenerator.kt` in the same change.
 *   Had it kept the name, the first top-level member added to it would emit a second
 *   `IGeneratorKt` and `makeJar` would fail on the duplicate (`DuplicatesStrategy.FAIL`).
 * - **`AuthLoginPage`, `AuthPinData` and `AuthLoginRequirement` stay in `:app` for now**, although
 *   they are pure. `AuthAPI` (still in `:app`, Compose-compiled) has an `AuthLoginRequirement`
 *   field. While both live in one module the Compose compiler folds that into the constant
 *   `AuthAPI.$stable = 8`. Across modules it emits `getstatic AuthLoginRequirement.$stable`, which is
 *   `0`, so `AuthAPI`, `SubtitleAPI`, `BackupAPI`, `AuthRepo` and `PlainAuthRepo` would silently
 *   become *stable* at runtime. They are passed to composables (`SettingsAccountScreen`). No
 *   signature changes, so neither `abiValidation` nor the jar dump sees it. Move them together with
 *   `AuthAPI`, or with an explicit decision to accept that stability change.
 *
 * ## Source sets and dependencies
 *
 * Set up by `cloudstream.kmp.extension-api`, which applies the Compose compiler so moved classes
 * keep the `$stable` field `:app` emitted. See that plugin's KDoc.
 *
 * - `commonMain`: pure Kotlin. Nothing here uses `java.*`, Jackson or `synchronized`.
 * - `api(project(":library"))`: `TvType`, `SearchResponse`, `SearchQuality`, `Score` and
 *   `ExtractorLinkType` are in these classes' public signatures, so callers need `:library` on
 *   their compile classpath.
 *
 * ## iOS canary: exempt until Track L
 *
 * `commonMain` depends on `:library`, which does not compile for Kotlin/Native yet, so this module
 * is on `IOS_CANARY_EXEMPT` in `cloudstream.kmp.library` (migration plan §4.1 rule 4) and gets no
 * iOS targets. Nothing in this module's own sources blocks iOS. The exemption lifts when Track L
 * makes the `:library` declarations above iOS-compilable. Remove the entry then, and do not add
 * JVM-only code to `commonMain` in the meantime.
 */
plugins {
    id("cloudstream.kmp.extension-api")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":library"))
        }
    }
}

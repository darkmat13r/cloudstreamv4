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
 * - `utils/TestingUtils.kt` holds `TestingUtils` and nine nested binary names — eight declared
 *   (`$Logger`, `$Logger$LogLevel`, `$Logger$Message`, `$TestResult`, `$TestResult$Companion`,
 *   `$TestResultList`, `$TestResultLoad`, `$TestResultProvider`) plus the compiler-synthesised
 *   `$Logger$Message$WhenMappings`, a switch map emitted for `when` over an enum. Every one of
 *   those binary names is in `app/api/classes-jar.api`, so a nested class pulled to top level
 *   breaks published extensions. **The file declares no top-level
 *   members, so it emits no `TestingUtilsKt` facade, and it must not acquire one**: `:app` has no
 *   `TestingUtils.kt` any more, but adding a top-level member here would create a facade class that
 *   is not in the committed dump. This is the `IGenerator.kt` hazard above in reverse — there the
 *   facade must keep existing, here it must keep not existing.
 * - **`AuthToken`, `AuthUser`, `AuthData` and `AuthLoginResponse` did move** (`AuthDtos.kt`, plan
 *   §5.1), because the hazard above does not reach them: no class in `classes.jar` reads any
 *   of their `$stable` fields with `getstatic`, before or after, so a whole-jar scan of all 910
 *   `$stable` initialisers shows zero changes. `AuthAPI` stays folded to the constant `8`. The move
 *   was taken with **one accepted ABI change**, recorded in `app/api/classes-jar.api`: the four
 *   `internal` synthetic `write$Self$app` methods are renamed to `write$Self$CloudStream_core_model`,
 *   because Kotlin mangles `internal` members with the JVM module name and this module's is
 *   `CloudStream.core:model`. They are `ACC_SYNTHETIC` and their only callers are the co-located
 *   `$$serializer.serialize` bodies, which moved with them; the same mangling is already visible in
 *   the committed dump as 122 `write$Self$CloudStream_library` entries. Do **not** try to suppress
 *   the rename by forcing the module name to `app` — `makeJar` then fails on a duplicate
 *   `META-INF/app.kotlin_module` — and do **not** add `@Immutable`/`@Stable` to these DTOs: it
 *   removes the `$stable` initialiser and silently flips `AuthUser` and `AuthData` from `8` to `0`,
 *   which is exactly the hazard described above and is invisible to both checkers.
 *
 * ## Source sets and dependencies
 *
 * Set up by `cloudstream.kmp.extension-api`, which applies the Compose compiler so moved classes
 * keep the `$stable` field `:app` emitted. See that plugin's KDoc.
 *
 * - `commonMain`: pure Kotlin. Nothing here uses `java.*`, Jackson or `synchronized`. Checked by
 *   reading, not by a build: this module has no iOS target (see below), so its `commonMain`
 *   metadata compilation resolves against a JVM platform and would accept `java.*` silently.
 * - `jvmCommonMain`: the Auth DTOs, which carry Jackson annotations. Jackson is JVM-only, so they
 *   cannot sit in `commonMain`. `kotlinx-serialization-json` is `api` because `Companion.serializer()`
 *   is public ABI; `jackson-module-kotlin` is `implementation` because only the annotations are used
 *   and they are `CLASS`-retained. `:library` declares both the same way, so this module must
 *   declare them itself rather than inherit them.
 * - `api(project(":library"))`: `TvType`, `SearchResponse`, `SearchQuality`, `Score` and
 *   `ExtractorLinkType` are in these classes' public signatures, so callers need `:library` on
 *   their compile classpath.
 * - The Kotlin serialization plugin is applied for the `@Serializable` DTOs. Without it the
 *   `$$serializer` and `$Companion` classes are simply not generated — no error, just a jar that
 *   silently loses them.
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
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":library"))
        }

        named("jvmCommonMain") {
            dependencies {
                api(libs.kotlinx.serialization.json)
                implementation(libs.jackson.module.kotlin)
            }
        }
    }
}

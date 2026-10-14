import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink

/**
 * Base convention for every Kotlin Multiplatform library module in CloudStream:
 * [cloudstream.kmp.base] (SDK levels, `jvmTarget`, opt-ins) plus the `jvm()` canary and the
 * opt-in iOS canary.
 *
 * ## The `jvm()` target is a purity canary, not a shipping target
 *
 * Nothing ships from the `jvm` target of a `:core:*` module today. It is declared so that the
 * *build fails* the moment an `android.*` import reaches `commonMain`: `commonMain` must compile
 * against the intersection of `android` and `jvm`, and the JVM target has no `android.*` on its
 * classpath. Removing `jvm()` silently turns every `commonMain` source set back into an
 * Android-only source set and the migration becomes cosmetic. Do not remove it.
 *
 * A module whose code is irreducibly Android uses `cloudstream.kmp.android-only` instead, which
 * says so in its build file rather than hiding it behind a canary that never compiles anything.
 *
 * ## The iOS canary: `-Pcloudstream.iosCanary=true`
 *
 * `jvm()` only proves `commonMain` is Android-free. Android and JVM are both JVMs, so the shared
 * `commonMain` compilation still sees the JDK: `java.*` imports, `synchronized`, `String.format`,
 * OkHttp and Jackson all compile in `commonMain` today. The canary adds `iosArm64()` +
 * `iosSimulatorArm64()` so `commonMain` must also compile for Kotlin/Native, where none of that
 * exists.
 *
 * ```
 * ./gradlew iosCanary -Pcloudstream.iosCanary=true
 * ```
 *
 * - **Off by default.** With the property absent or `false` this plugin declares exactly the
 *   targets and tasks it always did: no iOS target, no `iosCanary` task, no Kotlin/Native
 *   download into `~/.konan`, and Android artifacts and classes.jar are unchanged.
 * - **Klibs only.** `iosCanary` depends on the two main `compileKotlinIos*` klib compilations.
 *   Every `KotlinNativeLink` and `KotlinNativeSimulatorTest` task is disabled, so nothing links,
 *   nothing runs and nothing ships. There is no `iosX64`: Compose Multiplatform 1.12 publishes none.
 * - **`jvmCommonMain` is invisible to it.** Code that `cloudstream.kmp.extension-api` puts in
 *   `jvmCommonMain` is never compiled for iOS; the canary guards `commonMain` only. A module with
 *   an empty `commonMain` (`:core:network`) passes trivially.
 * - **Linux runners.** Kotlin/Native cross-compiles Apple klibs on Linux only while no dependency
 *   uses cinterop or CocoaPods. Compose UI's iOS klibs are cinterop-backed, so any module that
 *   resolves Compose UI, directly or transitively (`cloudstream.kmp.compose`, Compose resources),
 *   needs a macOS host; modules without it may run on Linux.
 * - **Exemptions.** A module whose `commonMain` depends on `:library` cannot pass until Track L
 *   makes `:library` iOS-compilable (migration plan §4.1 rule 4). It goes in
 *   [IOS_CANARY_EXEMPT], which gets no iOS targets and no `iosCanary` task. That list may only
 *   shrink. A module depending on an exempt module is necessarily exempt too.
 */

plugins {
    id("cloudstream.kmp.base")
    id("org.jetbrains.kotlin.multiplatform")
}

/**
 * Modules the iOS canary skips because their `commonMain` depends on `:library` (plan §4.1
 * rule 4). This list may only shrink.
 */
val IOS_CANARY_EXEMPT: Set<String> = setOf(
    ":core:model", // commonMain api(project(":library")); lifts when Track L makes :library iOS-compilable
    // commonMain implementation(project(":library")): AppSettings's preference defaults and value
    // types are TvType / DubStatus / SearchQuality / AllLanguagesName. Not a new dependency —
    // `:shared` declared the same edge (KMP-06d moved the code here) and had no iOS target at all,
    // so iOS readiness is unchanged. Lifts with :core:model's, on the same Track L work.
    ":core:settings",
)

val iosCanaryEnabled = providers.gradleProperty("cloudstream.iosCanary").orNull?.toBoolean() == true &&
    project.path !in IOS_CANARY_EXEMPT

kotlin {
    // The purity canary. See the KDoc above before touching this line.
    jvm()

    // Idempotent: KGP applies it implicitly when nothing calls dependsOn, and ignores a re-apply
    // (cloudstream.kmp.extension-api calls it too). Explicit so a module adding a manual dependsOn
    // keeps iosMain / appleMain / nativeMain.
    applyDefaultHierarchyTemplate()

    if (iosCanaryEnabled) {
        // Klibs only; see "The iOS canary" in the KDoc above.
        val iosCompilations = listOf(iosArm64(), iosSimulatorArm64()).map { target ->
            target.compilations.getByName("main").compileTaskProvider
        }
        tasks.register("iosCanary") {
            group = "verification"
            description = "Compiles commonMain as iOS klibs (iosArm64, iosSimulatorArm64). Links nothing."
            dependsOn(iosCompilations)
        }
    }
}

if (iosCanaryEnabled) {
    // The canary never links or runs, even when `check`/`allTests` pull these in.
    tasks.withType<KotlinNativeLink>().configureEach { enabled = false }
    tasks.withType<KotlinNativeSimulatorTest>().configureEach { enabled = false }
}

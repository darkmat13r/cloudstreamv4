import org.gradle.api.artifacts.VersionCatalogsExtension

/**
 * Convention for a KMP module whose code is **irreducibly Android** — Media3, Cast,
 * NotificationCompat, SAF, the TorrServer AAR — and which may receive extension-facing classes
 * moved out of `:app` (`:core:notification`, `:media:*`).
 *
 * ## When to use it, and when not
 *
 * Use it only when the module's reason to exist is an Android API. Anything that compiles without
 * `android.*` belongs in [cloudstream.kmp.library] or `cloudstream.kmp.extension-api`, where the
 * `jvm()` canary keeps it that way. An iOS implementation of the same capability is a sibling
 * module over a common model module, never an `actual` inside this one — which is why this module
 * gets **no `jvm()` canary and never the iOS canary**. Put the code in `androidMain`.
 *
 * Settings shared with every KMP module (SDK levels, `jvmTarget`, opt-ins) come from
 * [cloudstream.kmp.base].
 *
 * ## Why the Compose compiler, in a module with no Compose code
 *
 * For the reason `cloudstream.kmp.extension-api` gives: `:app` is Compose-compiled, so every class
 * it produces carries `public static final int $stable` and `@StabilityInferred`, and both are
 * binary API. A class moved here keeps them only if this module is Compose-compiled too. Only
 * `compose-runtime` is added (where `@StabilityInferred` lives), not the UI bundle.
 *
 * ## How extensions see it
 *
 * If the module has a `src/<sourceSet>/{kotlin,java}/com/lagradost/cloudstream3` directory,
 * `cloudstream.extension-api-jar` merges its compiled **Android** classes into `classes.jar` —
 * there is no `jvm` jar to merge — so every class in the module becomes de facto extension API.
 * New first-party code goes in a non-merged sibling module.
 *
 * ## No `abiValidation`, and what guards the ABI instead
 *
 * Kotlin 2.4.0's built-in ABI validation does not see the `android` target of
 * `com.android.kotlin.multiplatform.library`: with it enabled here, `updateKotlinAbi` writes no dump
 * (`api/` stays empty) although the module has a public class, and `internalDumpKotlinAbi` does not
 * depend on the Android compilation, so `checkKotlinAbi` has nothing to compare. Enabling it would
 * look like a guard and be none. Until it covers this target, a moved class is guarded by a
 * `makeJar` sentinel added in the same change (presence) and by the planned javap descriptor diff of
 * `classes.jar` (signatures, `$stable`, §6.2 of the migration plan). Until that diff exists, list the
 * ABI delta in the PR by hand, as `javap` shows it.
 */

plugins {
    id("cloudstream.kmp.base")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
}

val androidOnlyCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    android {
        // With no jvm() test compilation, `commonTest` would belong to no compilation and KGP warns
        // "Unused Kotlin Source Sets" on every configuration. Host tests give it one (androidHostTest).
        withHostTest { }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(androidOnlyCatalog.findLibrary("compose-runtime").get())
        }
    }
}

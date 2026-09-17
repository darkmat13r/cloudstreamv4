import org.gradle.api.artifacts.VersionCatalogsExtension

/**
 * Convention for a KMP module that receives classes moved out of `:app` — classes third-party
 * extensions compile against through `:app:makeJar`.
 *
 * ## Why the Compose compiler, in a module with no Compose code
 *
 * `:app` is Compose-compiled, and the Compose compiler emits `public static final int $stable` and
 * `@StabilityInferred` on **every** class, composable or not. Both are binary API: an extension that
 * was itself Compose-compiled reads `$stable` with `getstatic`, and throws `NoSuchFieldError` if the
 * field disappears. Moving a class into a module without the Compose compiler silently removes it.
 * With the compiler applied, the moved classes are byte-identical to the ones `:app` produced
 * (verified with `javap -c` on `Event`, `EmptyEvent`, `Vector2`, `IDisposableHelper`).
 *
 * Only `compose-runtime` is added — `@StabilityInferred` lives there. Not the UI bundle
 * `cloudstream.kmp.compose` brings: a utility module must not drag material3 and coil into every
 * module that depends on it.
 *
 * ## jvmCommonMain
 *
 * Most of `:app` is JVM-bound (java.*, `synchronized`, OkHttp, Jackson) without being Android-bound.
 * Such code lives in `jvmCommonMain`, shared by `androidMain` and `jvmMain` — the layout `:library`
 * uses. The `jvm()` canary still compiles it, so an `android.*` import fails the build.
 *
 * ## ABI validation
 *
 * The public ABI, `$stable` fields included, is checked against the committed `api/` dump (same
 * `@Prerelease` / `@InternalAPI` excludes as `:library`); `check` runs `checkKotlinAbi`, so a
 * dropped member or `$stable` fails the build. Record a deliberate change with
 * `./gradlew :<module>:updateKotlinAbi` and review the `api/` diff alongside it.
 */

plugins {
    id("cloudstream.kmp.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

val extensionApiCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(extensionApiCatalog.findLibrary("compose-runtime").get())
        }

        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
        }

        androidMain { dependsOn(jvmCommonMain) }
        jvmMain { dependsOn(jvmCommonMain) }
    }

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        filters {
            exclude {
                annotatedWith.add("com.lagradost.cloudstream3.Prerelease")
                annotatedWith.add("com.lagradost.cloudstream3.InternalAPI")
            }
        }
    }
}

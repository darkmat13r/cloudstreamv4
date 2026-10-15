/**
 * `:feature:extensions` — the extension catalogue, install state and load seam, as contracts.
 *
 * ## Contracts only, like `:media:model` at `KMP-07`
 *
 * No implementation, no wiring, and **nothing in `:app` is changed by this module existing**. Plan
 * row 17 is the `ExtensionRuntime` seam plus the repository model in commonMain; the rest of that
 * row — making `:app`'s `PluginManager` implement the seam — edits published, extension-facing code
 * and is not done here.
 *
 * ## Not extension API
 *
 * Package `com.lagradost.cloudstream4.feature.extensions`, no `com/lagradost/cloudstream3`
 * directory, so `:app:makeJar` never merges it (plan §4.2: `:feature:*` is not merged, and the old
 * classes stay in `:app`). Nothing here is on the published contract and no sentinel is needed.
 * `:app`'s `plugins.Repository`, `SitePlugin`, `PluginData` and `PluginWrapper` are all in
 * `app/api/classes-jar.api` and stay exactly where they are; these types restate the portable half.
 *
 * ## `cloudstream.kmp.library`, not a new `cloudstream.kmp.feature`
 *
 * Plan §4.2 pencils in a `kmp.feature` plugin for `:feature:*`. It would have the same contents as
 * `cloudstream.kmp.library` — android + the jvm purity canary, no jar merge — so this module applies
 * that instead of adding a second name for one behaviour. When a feature module needs the Compose
 * compiler for its screens (Phase 7's recipe step 1), `cloudstream.kmp.compose` already exists and
 * is the right plugin. If `kmp.feature` ever earns distinct behaviour, that is when to add it.
 *
 * ## No dependencies, and that is load-bearing
 *
 * commonMain depends on nothing — not `:library`, not `:core:strings`, not a serialization runtime.
 * That is why this module is **not** in `IOS_CANARY_EXEMPT` and takes part in the 12-module canary:
 * plan §4.1 rule 4 would exempt it the moment its commonMain reached `:library`, and that list "may
 * only shrink" (`cloudstream.kmp.library`'s KDoc). `ExtensionListing.tvTypes` stays a list of wire
 * strings rather than `TvType` for exactly this reason — see `ExtensionIndex.kt`.
 *
 * ## `jvmMain` holds the one working runtime, and the source set is what keeps the canary green
 *
 * `KMP-13`: `JvmExtensionRuntime` needs `:library` — `BasePlugin`, `APIHolder`, `extractorApis` —
 * and `:library` is not iOS-compilable until Track L. Putting it in **`jvmMain`** rather than
 * `commonMain` keeps that edge off the shared compilation, so this module stays out of
 * `IOS_CANARY_EXEMPT` and the canary stays 14 of 14 — `jvmMain` is never compiled for Native, and
 * that list may only shrink. Fourth time the source set has avoided an exemption: `KMP-05`,
 * `KMP-11`, `KMP-12`, here.
 *
 * The module's `commonMain` is still dependency-free, so none of this reaches the contracts.
 */
plugins {
    id("cloudstream.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // `ExtensionRuntime`'s members are `suspend`: the seam is an I/O boundary on every
            // platform, and a blocking signature would force a thread choice into the contract.
            implementation(libs.kotlinx.coroutines.core)
        }

        jvmMain.dependencies {
            // `BasePlugin` (the plugin supertype and the `filename` -> `sourcePlugin` stamp),
            // `APIHolder.apis` / `allProviders` and `extractorApis` (the registries an unload has
            // to clear), and `@CloudstreamPlugin` (the RUNTIME-retained marker discovery reads).
            // `implementation`, not `api`: no `:library` type appears in a public signature here —
            // `ExtensionJar`, which returns `Class<out BasePlugin>`, is `internal`.
            implementation(project(":library"))
            // Already on `commonMain`; restated because `Mutex` and `Dispatchers.IO` are used
            // directly in this source set rather than inherited by accident.
            implementation(libs.kotlinx.coroutines.core)
            // Nothing else, and no new catalogue entry: `java.util.zip`, `java.net.URLClassLoader`,
            // `java.nio.file` and `java.security.MessageDigest` are the JDK.
        }

        // `commonTest`, not `jvmTest` (INF-04): `ExtensionContractsTest` is pure Kotlin, so moving
        // it gives the `-Pcloudstream.iosTest=true` gate a suite to run in *this* module, which had
        // none. It is the second module to do that, not the first — `:feature:search` already moved
        // `SearchContentTest` there (`feature/search/build.gradle.kts:48-51`), which is also why the
        // convention plugin's "no module on this plugin has a `commonTest` directory" KDoc is
        // already stale. `commonTest` is a parent of `jvmTest`, so the JVM-only suites beside it
        // (`ExtensionHashTest`, `JvmExtensionRuntimeTest`) inherit these two and need no block of
        // their own.
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

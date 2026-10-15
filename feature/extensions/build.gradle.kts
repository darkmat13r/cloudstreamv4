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

        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
